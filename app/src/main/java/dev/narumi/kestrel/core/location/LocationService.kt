package dev.narumi.kestrel.core.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.getSystemService
import dev.narumi.kestrel.R
import dev.narumi.kestrel.core.cloud.RemoteControlPoller
import dev.narumi.kestrel.core.data.KestrelPrefs
import dev.narumi.kestrel.core.data.MockState
import dev.narumi.kestrel.core.data.RouteState
import dev.narumi.kestrel.core.routeplan.LocationSink
import dev.narumi.kestrel.core.routeplan.PlaybackPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class LocationService : Service() {
    private lateinit var mockProvider: MockProviderManager
    private lateinit var prefs: KestrelPrefs
    private lateinit var stateWriter: MockStateWriter
    private lateinit var remoteControlPoller: RemoteControlPoller

    @Volatile private var providerStarted = false
    private val providerWriteLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var routeJob: Job? = null
    private val singleKeepAlive by lazy { SinglePointKeepAlive(scope, ::pushLocation) }
    private var restoreJob: Job? = null

    @Volatile private var paused = false
    private var currentMode: MockState.Mode = MockState.Mode.Idle
    private var stateInitialized = false
    private var latestStartId = 0

    // Publish the active engine and serialized route fields together so progress writers cannot
    // combine state from two routes during replacement.
    @Volatile private var activeRoute: ActiveRouteSnapshot? = null

    // Set instead of activeRoute while a scheduled plan is armed or playing. Also read by the
    // notification builder, hence volatile.
    @Volatile private var activeScheduled: ActiveScheduled? = null

    override fun onCreate() {
        super.onCreate()
        mockProvider = MockProviderManager(applicationContext)
        prefs = KestrelPrefs(applicationContext)
        stateWriter = MockStateWriter(snapshot = ::currentStateSnapshot, write = prefs::setMockState)
        remoteControlPoller = RemoteControlPoller.getInstance(applicationContext)
        ensureChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int =
        runCatching {
            latestStartId = startId
            if (intent?.action != null) {
                restoreJob?.cancel()
                restoreJob = null
            }
            when (intent?.action) {
                null -> restoreAfterRestart(startId)
                ACTION_STOP -> stopAction(intent, startId)
                ACTION_SET_LOCATION -> setLocationAction(intent)
                ACTION_START_ROUTE -> startRouteAction(intent)
                ACTION_START_SCHEDULED -> startScheduledAction(intent)
                ACTION_UPDATE_ROUTE_SETTINGS -> updateRouteSettingsAction(intent)
                ACTION_PAUSE -> pauseAction(intent)
                ACTION_RESUME -> resumeAction(intent)
                else -> foregroundOnlyAction()
            }
        }.getOrElse { error ->
            completeOperation(
                intent,
                succeeded = false,
                message = mockOperationErrorMessage(error, previousMockActive = _runtimeState.value != RuntimeState.Idle),
            )
            Log.w(TAG, "location operation failed", error)
            if (_runtimeState.value == RuntimeState.Idle) {
                setRemoteControlServiceLease(false)
                stopForegroundCompat()
                stopSelfResult(startId)
                START_NOT_STICKY
            } else {
                START_STICKY
            }
        }

    private fun restoreAfterRestart(startId: Int): Int {
        // Restarted by the system after being killed (START_STICKY): try to restore.
        if (!tryEnsureForeground()) return START_NOT_STICKY
        restoreJob =
            scope.launch(Dispatchers.Main.immediate) {
                val restored = runCatching { restoreState() }.getOrDefault(false)
                if (!restored && stopSelfResult(startId)) stopForegroundCompat()
                restoreJob = null
            }
        return START_STICKY
    }

    private fun stopAction(
        intent: Intent,
        startId: Int,
    ): Int {
        synchronized(providerWriteLock) {
            stopRoute()
            stopSingleKeepAlive()
            stopMock()
            currentMode = MockState.Mode.Idle
            _currentMock.value = null
            _runtimeState.value = RuntimeState.Idle
            stateInitialized = true
        }
        setRemoteControlServiceLease(false)
        scope.launch { stateWriter.persist() }
        stopForegroundCompat()
        completeOperation(intent, succeeded = true, message = "Mock location stopped.")
        // A new SET_LOCATION / START_ROUTE may already be queued by a UI "replace mock"
        // action. Do not let this older STOP tear down the service before the newer
        // foreground-start command gets its chance to call startForeground().
        stopSelfResult(startId)
        return START_NOT_STICKY
    }

    private fun setLocationAction(intent: Intent): Int {
        if (!tryEnsureForeground()) {
            completeOperation(
                intent,
                succeeded = false,
                message = "Kestrel could not start its location service. Check notification permission and try again.",
            )
            return if (_runtimeState.value == RuntimeState.Idle) START_NOT_STICKY else START_STICKY
        }
        val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
        val lng = intent.getDoubleExtra(EXTRA_LNG, Double.NaN)
        val point = LatLng(lat, lng)
        require(lat.isFinite() && lng.isFinite() && lat in -90.0..90.0 && lng in -180.0..180.0) {
            "Enter valid latitude and longitude values."
        }

        // Prove that the provider accepts the replacement before cancelling the old route.
        synchronized(providerWriteLock) {
            ensureMockStarted()
            mockProvider.setLocation(point)
            _currentMock.value = point
            stopRoute()
            stopSingleKeepAlive()
            startSingleKeepAlive(point)
            currentMode = MockState.Mode.Single
            _runtimeState.value = RuntimeState.Single(point)
            stateInitialized = true
        }
        setRemoteControlServiceLease(true)
        refreshNotification()
        scope.launch { stateWriter.persist() }
        completeOperation(intent, succeeded = true, message = "Point mock started.")
        return START_STICKY
    }

    private fun startRouteAction(intent: Intent): Int {
        if (!tryEnsureForeground()) {
            completeOperation(
                intent,
                succeeded = false,
                message = "Kestrel could not start its location service. Check notification permission and try again.",
            )
            return if (_runtimeState.value == RuntimeState.Idle) START_NOT_STICKY else START_STICKY
        }
        val lats = intent.getDoubleArrayExtra(EXTRA_LATS)
        val lngs = intent.getDoubleArrayExtra(EXTRA_LNGS)
        require(lats != null && lngs != null && lats.size == lngs.size) {
            "The route waypoint payload is incomplete."
        }
        val waypoints = lats.indices.map { LatLng(lats[it], lngs[it]) }
        val speedKmh = intent.getDoubleExtra(EXTRA_SPEED_KMH, Double.NaN)
        validateRouteRequest(waypoints, speedKmh)?.let { throw IllegalArgumentException(it) }
        val modeName = intent.getStringExtra(EXTRA_MODE) ?: MovementEngine.Mode.Once.name
        val mode =
            runCatching { MovementEngine.Mode.valueOf(modeName) }
                .getOrDefault(MovementEngine.Mode.Once)
        val engine = MovementEngine(waypoints, speedKmh / 3.6, mode)

        // Validate the provider and first sample before replacing a running mock. Provider writes
        // share a lock so a cancelled old route cannot publish a stale sample after this one.
        synchronized(providerWriteLock) {
            ensureMockStarted()
            mockProvider.setLocation(waypoints.first())
            _currentMock.value = waypoints.first()
            activateRoute(engine, waypoints, speedKmh, mode)
            currentMode = MockState.Mode.Route
            _runtimeState.value = checkNotNull(activeRoute).toRuntimeState(paused = false)
            stateInitialized = true
        }
        setRemoteControlServiceLease(true)
        refreshNotification()
        scope.launch { stateWriter.persist() }
        completeOperation(intent, succeeded = true, message = "Route playback started.")
        return START_STICKY
    }

    private fun startScheduledAction(intent: Intent): Int {
        val plan =
            ScheduledPlanHandoff.take(intent.getStringExtra(EXTRA_PLAN_TOKEN))
                ?: throw IllegalArgumentException("The schedule did not reach the service. Try again.")
        if (!tryEnsureForeground()) {
            completeOperation(
                intent,
                succeeded = false,
                message = "Kestrel could not start its location service. Check notification permission and try again.",
            )
            return if (_runtimeState.value == RuntimeState.Idle) START_NOT_STICKY else START_STICKY
        }
        val active = newActiveScheduled(plan, intent.getStringExtra(EXTRA_REQUEST_ID), pausedTotalMs = 0L)

        // Fail now rather than at the start time, possibly hours later, if mock location is not allowed.
        if (!mockProvider.isMockAllowed()) {
            throw MockNotAllowedException("Kestrel is not selected as the mock location app in Developer options")
        }
        // Prove that the provider accepts the source point before cancelling any running mock.
        synchronized(providerWriteLock) {
            plan.source?.let { source ->
                ensureMockStarted()
                mockProvider.setLocation(source)
                _currentMock.value = source
            }
            activateScheduled(active)
            currentMode = MockState.Mode.Route
            _runtimeState.value = active.toRuntimeState(paused = false)
            stateInitialized = true
        }
        setRemoteControlServiceLease(true)
        refreshNotification()
        scope.launch { stateWriter.persist() }
        completeOperation(intent, succeeded = true, message = "Route scheduled.")
        return START_STICKY
    }

    private fun newActiveScheduled(
        plan: PlaybackPlan,
        requestId: String?,
        pausedTotalMs: Long,
    ): ActiveScheduled = ActiveScheduled.create(plan, requestId, pausedTotalMs)

    // Called under providerWriteLock.
    private fun activateScheduled(active: ActiveScheduled) {
        stopRoute()
        stopSingleKeepAlive()
        // With no source point the real location stays in charge until the start time.
        if (active.plan.source == null) {
            stopMock()
            _currentMock.value = null
        }
        paused = false
        activeScheduled = active
        routeJob = scope.launch { active.runner(scheduledSink(active), scheduledListener(active)).run() }
    }

    private fun scheduledSink(active: ActiveScheduled) =
        LocationSink { sample ->
            synchronized(providerWriteLock) {
                if (activeScheduled !== active) return@LocationSink
                ensureMockStarted()
                mockProvider.setLocation(
                    point = sample.point,
                    speed = sample.speedMps.toFloat(),
                    bearing = sample.bearingDeg.toFloat(),
                )
                _currentMock.value = sample.point
            }
        }

    private fun scheduledListener(active: ActiveScheduled) =
        ScheduledPlaybackCallbacks(
            lock = providerWriteLock,
            isCurrent = { activeScheduled === active },
            moving = {
                active.phase = SchedulePhase.Moving
                _runtimeState.value = active.toRuntimeState(paused = paused)
                refreshNotification()
            },
            arrived = { destination ->
                finishRoute(destination)
                scope.launch { stateWriter.persist() }
            },
            failed = { error -> scope.launch(Dispatchers.Main.immediate) { failScheduled(active, error) } },
        )

    private fun failScheduled(
        active: ActiveScheduled,
        error: Exception,
    ) {
        synchronized(providerWriteLock) {
            if (activeScheduled !== active) return
            Log.w(TAG, "Scheduled playback failed: ${error.javaClass.simpleName}")
            stopRoute()
            stopSingleKeepAlive()
            stopMock()
            currentMode = MockState.Mode.Idle
            _currentMock.value = null
            _runtimeState.value = RuntimeState.Idle
        }
        setRemoteControlServiceLease(false)
        active.failureResult(error)?.let { _operationResults.tryEmit(it) }
        scope.launch { stateWriter.persist() }
        if (stopSelfResult(latestStartId)) stopForegroundCompat()
    }

    private fun updateRouteSettingsAction(intent: Intent): Int {
        val expectedPlaybackId = intent.getStringExtra(EXTRA_PLAYBACK_ID)
        require(!expectedPlaybackId.isNullOrBlank()) { "The route has changed. Adjust the current route instead." }
        val update =
            parseRouteSettingsUpdate(
                speedKmh = intent.takeIf { it.hasExtra(EXTRA_SPEED_KMH) }?.getDoubleExtra(EXTRA_SPEED_KMH, Double.NaN),
                modeName = intent.getStringExtra(EXTRA_MODE),
            )
        synchronized(providerWriteLock) {
            val runtime = _runtimeState.value as? RuntimeState.Route
            requireNotNull(runtime) { "No active route is available to adjust." }
            val route = requireNotNull(activeRoute) { "No active route is available to adjust." }
            val updated = route.withSettings(expectedPlaybackId, update.speedKmh, update.mode)
            activeRoute = updated
            _runtimeState.value = updated.toRuntimeState(paused = runtime.paused)
        }
        scope.launch {
            try {
                stateWriter.persist()
                completeOperation(intent, succeeded = true, message = "Playback settings updated. Route progress kept.")
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                completeOperation(
                    intent,
                    succeeded = false,
                    message = "Playback settings changed, but could not be saved for recovery. Try the setting again.",
                )
            }
        }
        return START_STICKY
    }

    private fun pauseAction(intent: Intent): Int {
        synchronized(providerWriteLock) {
            val scheduled = activeScheduled
            if (scheduled != null) {
                check(scheduled.phase == SchedulePhase.Moving && scheduled.clock.pause()) {
                    "The route cannot be paused right now. Stop to cancel a route that has not started."
                }
                paused = true
                updateScheduledRuntimePaused(paused = true)
            } else {
                check(_runtimeState.value is RuntimeState.Route) { "No route is playing." }
                paused = true
                updateRouteRuntimePaused(paused = true)
            }
        }
        refreshNotification()
        scope.launch { stateWriter.persist() }
        completeOperation(intent, succeeded = true, message = "Route paused.")
        return START_STICKY
    }

    private fun resumeAction(intent: Intent): Int {
        synchronized(providerWriteLock) {
            val scheduled = activeScheduled
            if (scheduled != null) {
                check(scheduled.clock.isPaused) { "No paused route is available." }
                scheduled.clock.resume()
                paused = false
                updateScheduledRuntimePaused(paused = false)
            } else {
                check(_runtimeState.value is RuntimeState.Route) { "No paused route is available." }
                paused = false
                updateRouteRuntimePaused(paused = false)
            }
        }
        refreshNotification()
        scope.launch { stateWriter.persist() }
        completeOperation(intent, succeeded = true, message = "Route resumed.")
        return START_STICKY
    }

    private fun foregroundOnlyAction(): Int = if (tryEnsureForeground()) START_STICKY else START_NOT_STICKY

    override fun onDestroy() {
        // Best-effort progress flush before the scope is cancelled. onDestroy is not guaranteed to
        // run under sudden kills; the periodic tick writer is what makes overnight kills survivable.
        // Failed startup/restore must not replace a saved route with an uninitialized Idle state.
        if (stateInitialized) {
            runCatching { runBlocking { stateWriter.persist() } }
        }
        synchronized(providerWriteLock) {
            stopRoute()
            stopSingleKeepAlive()
            stopMock()
        }
        setRemoteControlServiceLease(false)
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun restoreState(): Boolean {
        val state = prefs.mockState.first() ?: return false
        return when (state.mode) {
            MockState.Mode.Single ->
                state.single?.let {
                    val point = LatLng(it.lat, it.lng)
                    if (!point.lat.isFinite() || !point.lng.isFinite() || point.lat !in -90.0..90.0 || point.lng !in -180.0..180.0) {
                        return@let false
                    }
                    synchronized(providerWriteLock) {
                        ensureMockStarted()
                        startSingleKeepAlive(point)
                        currentMode = MockState.Mode.Single
                        _runtimeState.value = RuntimeState.Single(point)
                        stateInitialized = true
                    }
                    setRemoteControlServiceLease(true)
                    refreshNotification()
                    true
                } ?: false
            MockState.Mode.Route -> state.route?.let(::restoreRoute) ?: false
            MockState.Mode.Idle -> false
        }
    }

    private fun restoreScheduled(route: RouteState): Boolean {
        val active = ActiveScheduled.restore(route) ?: return false
        synchronized(providerWriteLock) {
            activateScheduled(active)
            currentMode = MockState.Mode.Route
            // Progress is recomputed from the start time; a restored plan gets a fresh playback ID.
            _runtimeState.value = active.toRuntimeState(paused = false)
            stateInitialized = true
        }
        setRemoteControlServiceLease(true)
        refreshNotification()
        return true
    }

    private fun restoreRoute(route: RouteState): Boolean {
        if (route.startAtEpochMs != null) return restoreScheduled(route)
        if (route.lats.size < 2 || route.lats.size != route.lngs.size) return false
        val waypoints = route.lats.indices.map { LatLng(route.lats[it], route.lngs[it]) }
        if (validateRouteRequest(waypoints, route.speedKmh) != null) return false
        val mode = runCatching { MovementEngine.Mode.valueOf(route.mode) }.getOrDefault(MovementEngine.Mode.Once)
        synchronized(providerWriteLock) {
            startRoute(waypoints, route.speedKmh, mode, route.progressMeters, route.forward)
            currentMode = MockState.Mode.Route
            // Paused remains runtime-only; a restored route resumes with a fresh playback ID.
            _runtimeState.value = checkNotNull(activeRoute).toRuntimeState(paused = false)
            stateInitialized = true
        }
        setRemoteControlServiceLease(true)
        refreshNotification()
        return true
    }

    private fun setRemoteControlServiceLease(active: Boolean) {
        if (::remoteControlPoller.isInitialized) {
            remoteControlPoller.setServiceActive(active)
        }
    }

    private fun updateScheduledRuntimePaused(paused: Boolean) {
        val current = _runtimeState.value as? RuntimeState.Scheduled ?: return
        _runtimeState.value = current.copy(paused = paused)
    }

    private fun updateRouteRuntimePaused(paused: Boolean) {
        val current = _runtimeState.value as? RuntimeState.Route ?: return
        _runtimeState.value = current.copy(paused = paused)
    }

    private fun startRoute(
        waypoints: List<LatLng>,
        speedKmh: Double,
        mode: MovementEngine.Mode,
        initialProgressMeters: Double = 0.0,
        initialForward: Boolean = true,
    ) {
        ensureMockStarted()
        val engine =
            MovementEngine(
                waypoints = waypoints,
                speedMps = speedKmh / 3.6,
                mode = mode,
                initialProgressMeters = initialProgressMeters,
                initialForward = initialForward,
            )
        activateRoute(engine, waypoints, speedKmh, mode)
    }

    private fun activateRoute(
        engine: MovementEngine,
        waypoints: List<LatLng>,
        speedKmh: Double,
        mode: MovementEngine.Mode,
    ) {
        stopRoute()
        stopSingleKeepAlive()
        paused = false
        activeRoute = ActiveRouteSnapshot.create(engine, waypoints, speedKmh, mode)
        routeJob =
            scope.launch {
                // Read once per route job so Settings changes apply to the next route start or
                // restore, not mid-flight.
                val progressWriteIntervalTicks =
                    progressWriteIntervalTicksFor(
                        prefs.mockPlaybackSettings.first().progressWriteIntervalSeconds,
                    )
                var tickCounter = 0
                while (isActive) {
                    delay(LOCATION_SERVICE_TICK_MILLIS)
                    val finished =
                        synchronized(providerWriteLock) {
                            if (activeRoute?.engine !== engine) return@launch
                            if (paused) return@synchronized false
                            pushSample(engine.advance(LOCATION_SERVICE_TICK_MILLIS / 1000.0), engine)
                            engine.isFinished().also { if (it) finishRoute(waypoints.last()) }
                        }
                    if (finished) {
                        stateWriter.persist()
                        return@launch
                    }
                    if (paused) continue
                    tickCounter++
                    if (tickCounter >= progressWriteIntervalTicks) {
                        tickCounter = 0
                        stateWriter.persist()
                    }
                }
            }
    }

    private fun stopRoute() {
        routeJob?.cancel()
        routeJob = null
        paused = false
        activeRoute = null
        activeScheduled = null
    }

    // Called under providerWriteLock so a live settings update cannot race route completion.
    private fun finishRoute(last: LatLng) {
        activeRoute = null
        activeScheduled = null
        currentMode = MockState.Mode.Single
        _runtimeState.value = RuntimeState.Single(last)
        startSingleKeepAlive(last)
        setRemoteControlServiceLease(true)
        refreshNotification()
    }

    private fun currentStateSnapshot(): MockState? =
        synchronized(providerWriteLock) {
            mockStateSnapshot(_runtimeState.value, activeRoute, activeScheduled)
        }

    private fun startSingleKeepAlive(point: LatLng) = singleKeepAlive.start(point)

    private fun stopSingleKeepAlive() = singleKeepAlive.stop()

    private fun pushLocation(point: LatLng) {
        synchronized(providerWriteLock) {
            val activePoint = (_runtimeState.value as? RuntimeState.Single)?.point
            if (activePoint != point || !providerStarted) return
            runCatching { mockProvider.setLocation(point) }
                .onSuccess { _currentMock.value = point }
                .onFailure { Log.w(TAG, "setLocation failed", it) }
        }
    }

    private fun pushSample(
        sample: MockSample,
        engine: MovementEngine,
    ) {
        synchronized(providerWriteLock) {
            if (activeRoute?.engine !== engine || !providerStarted) return
            runCatching {
                mockProvider.setLocation(
                    point = sample.point,
                    speed = sample.speedMps.toFloat(),
                    bearing = sample.bearingDeg.toFloat(),
                )
            }.onSuccess { _currentMock.value = sample.point }
                .onFailure { Log.w(TAG, "setLocation failed", it) }
        }
    }

    private fun tryEnsureForeground(): Boolean =
        runCatching {
            ensureForeground()
            true
        }.onFailure {
            Log.w(TAG, "startForeground failed", it)
            if (_runtimeState.value == RuntimeState.Idle) {
                stopSelf()
            }
        }.getOrDefault(false)

    private fun completeOperation(
        intent: Intent?,
        succeeded: Boolean,
        message: String,
    ) {
        val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID) ?: return
        val action = intent.action.toLocationOperationAction() ?: return
        _operationResults.tryEmit(
            LocationOperationResult(
                requestId = requestId,
                action = action,
                succeeded = succeeded,
                message = message,
            ),
        )
    }

    private fun ensureForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun refreshNotification() {
        val nm = getSystemService<NotificationManager>() ?: return
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun ensureMockStarted() {
        if (providerStarted) return
        mockProvider.start()
        providerStarted = true
    }

    private fun stopMock() {
        synchronized(providerWriteLock) {
            if (!providerStarted) return
            runCatching { mockProvider.stop() }
            providerStarted = false
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService<NotificationManager>() ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.location_service_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.location_service_channel_description)
            },
        )
    }

    private fun buildNotification(): Notification = LocationServiceNotification(this, CHANNEL_ID).build(currentMode, paused, activeScheduled)

    companion object {
        const val ACTION_START = "dev.narumi.kestrel.action.START"
        const val ACTION_STOP = "dev.narumi.kestrel.action.STOP"
        const val ACTION_SET_LOCATION = "dev.narumi.kestrel.action.SET_LOCATION"
        const val ACTION_START_ROUTE = "dev.narumi.kestrel.action.START_ROUTE"
        const val ACTION_START_SCHEDULED = "dev.narumi.kestrel.action.START_SCHEDULED"
        const val ACTION_UPDATE_ROUTE_SETTINGS = "dev.narumi.kestrel.action.UPDATE_ROUTE_SETTINGS"
        const val ACTION_PAUSE = "dev.narumi.kestrel.action.PAUSE"
        const val ACTION_RESUME = "dev.narumi.kestrel.action.RESUME"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LNG = "lng"
        const val EXTRA_LATS = "lats"
        const val EXTRA_LNGS = "lngs"
        const val EXTRA_SPEED_KMH = "speed_kmh"
        const val EXTRA_MODE = "route_mode"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_PLAYBACK_ID = "playback_id"
        const val EXTRA_PLAN_TOKEN = "plan_token"
        private const val CHANNEL_ID = "kestrel_location"
        private const val NOTIFICATION_ID = 1001
        private const val TAG = "LocationService"

        private val _currentMock = MutableStateFlow<LatLng?>(null)
        val currentMock: StateFlow<LatLng?> = _currentMock.asStateFlow()

        private val _runtimeState = MutableStateFlow<RuntimeState>(RuntimeState.Idle)
        private val _operationResults =
            MutableSharedFlow<LocationOperationResult>(replay = 1, extraBufferCapacity = 31)
        val operationResults: SharedFlow<LocationOperationResult> = _operationResults.asSharedFlow()

        /**
         * What the service is doing right now (Idle / Single / Route). UI should derive its
         * run-state from this flow instead of from local Compose `remember` values so a
         * MapScreen dispose (tab switch, config change) does not desync from the actual service.
         *
         * Updated only on real transitions, never per-tick.
         */
        val runtimeState: StateFlow<RuntimeState> = _runtimeState.asStateFlow()

        private val commands =
            LocationServiceCommands(
                report = { _operationResults.tryEmit(it) },
                hasActiveMock = { _runtimeState.value != RuntimeState.Idle },
            )

        fun start(context: Context) = commands.start(context)

        fun setLocation(
            context: Context,
            point: LatLng,
        ): String = commands.setLocation(context, point)

        fun startRoute(
            context: Context,
            waypoints: List<LatLng>,
            speedKmh: Double,
            mode: MovementEngine.Mode = MovementEngine.Mode.Once,
        ): String = commands.startRoute(context, waypoints, speedKmh, mode)

        /** Arms a scheduled plan using an in-process handoff for large routes. */
        fun startScheduled(
            context: Context,
            plan: PlaybackPlan,
        ): String = commands.startScheduled(context, plan)

        fun updateRouteSettings(
            context: Context,
            playbackId: String,
            speedKmh: Double? = null,
            mode: MovementEngine.Mode? = null,
        ): String = commands.updateRouteSettings(context, playbackId, speedKmh, mode)

        fun pause(context: Context): String = commands.pause(context)

        fun resume(context: Context): String = commands.resume(context)

        fun stop(context: Context): String = commands.stop(context)
    }
}
