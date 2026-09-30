# Scheduled Route Playback Plan

Archive-provided design for scheduled route playback. This integration implements phases 1 through 3; later phases remain pending. GPX import is authorized for this fork by the user.

## Goal

Let Kestrel accept a source location, a route (GPX, GeoJSON, JSON, CSV, or a coordinate list, as produced by OsmAnd, Organic Maps, GraphHopper, Valhalla, BRouter), a speed, a start time and an update interval. At the start time, Kestrel sends mock GPS fixes along the route at that speed until it reaches the destination.

Non-goals for the first version: cloud/web/remote-control support, Room or Prisma schema changes, Loop and PingPong with a start time, cold start from a killed app (decided: not needed).

### Decisions (settled)

1. **Fork-only.** GPX import is in scope. Upstream's `AGENTS.md` ban on GPX/KML does not apply to this fork, and it is not intended as an upstream PR.
2. **No cold start.** The user opens the app, schedules the plan and leaves it running. Phase 6 (exact alarms) is dropped.
3. **License.** The repo is AGPL-3.0. The fork stays AGPL.

## Plan

### Phase 0: Fork and environment

- `gh repo fork narumiruna/kestrel --clone --remote`, then `git checkout -b feature/scheduled-route-playback`. Work from the fork, not the zip (no history).
- Toolchain: JDK 26, AGP 9.4.1, Kotlin 2.4.20, compileSdk 37, minSdk 29. Set `JAVA_HOME` and `ANDROID_HOME` (the `justfile` defaults are macOS paths).
- Run `just android-build`, `just android-test`, `just android-check` on the untouched fork for a green baseline.
- Add `applicationIdSuffix = ".fork"` to the debug build type in `app/build.gradle.kts` so it installs beside upstream. Keep cloud features off (OIDC deep link and `assetlinks.json` are tied to upstream's domain and signing key).
- Conventions: Conventional Commits, stage files explicitly (no `git add -A`), never `--no-verify`, never commit image binaries.

### Phase 1: Core engine (pure Kotlin, `core/routeplan/`)

New files:

- `TrackPoint.kt`: `TrackPoint(point: LatLng, timeMs: Long?, speedMps: Double?)`.
- `PlaybackPlan.kt`: `PlaybackPlan(name, source: LatLng?, points, startAtEpochMs, speed: SpeedSource, updateIntervalMs)`. `SpeedSource` is `Constant(kmh)`, `FromTimestamps`, or `TimestampsScaled(factor)`. Interval is clamped to 200..10,000 ms, default 1000.
- `RouteTimeline.kt`: cumulative distance and time arrays. `positionAt(elapsedSeconds): MockSample` uses binary search and interpolation. Returns the source before start and the last point after the end. If the source differs from the first point, prepend it as a lead-in leg.
- `PlaybackClock.kt`: injectable clock. Anchor at arming: `anchor = elapsedRealtime + (startAt - now)`. `elapsed = elapsedRealtime - anchor - pausedTotal`.

Modify:

- `core/location/Geo.kt`: add great-circle interpolation for long segments. `lerpLatLng` stays for short ones.
- Do not modify `MovementEngine.kt`. It is tested and used by remote commands.

Rules: start times are stored as UTC epoch. A start time in the past prompts "Start now" or "Join at current position". Scheduled plans support `Once` only.

### Phase 2: Importers (`core/routeplan/import/`)

- `RouteImporter.kt`: interface, format sniffing, shared validation (reuse `validateRouteRequest`, add point cap, monotonic timestamps, drop consecutive duplicates).
- `GpxParser.kt`: SAX (`javax.xml.parsers`), `trkpt`, `rtept`, `wpt`, `<time>` via `java.time.Instant`. Disable DOCTYPE (XXE).
- `GeoJsonParser.kt`: `kotlinx.serialization.json`. `LineString`, `MultiLineString`, `Feature`, `FeatureCollection`. Coordinates are `[lng, lat]`. Optional `coordinateProperties.times`.
- `JsonRouteParser.kt`: Kestrel schema plus GraphHopper (`paths[0].points`), OSRM (`routes[0].geometry`), Valhalla (`trip.legs[].shape`, polyline6).
- `PolylineDecoder.kt`: precision 5 and 6.
- `CsvRouteParser.kt`: header and delimiter sniffing (`lat`, `lon`/`lng`, optional `time`, `speed`).
- Coordinate list: reuse `parseCoordInput` per line.

### Phase 3: Service and persistence

- New `core/routeplan/ScheduledPlaybackRunner.kt` with phases `Armed` (hold source, sleep until start), `Moving` (tick at `updateIntervalMs`, call `timeline.positionAt(clock.elapsed())`), `Arrived`.
- New `core/routeplan/LocationSink.kt`: interface wrapping `MockProviderManager` so the runner is testable without Android.
- `LocationService.kt`: add `ACTION_START_SCHEDULED` and companion `startScheduled(...)`. Replace the running mock atomically (do not call `stop()` then start). Keep new logic out of this file (Detekt limits, already 816 lines).
- `RuntimeState.kt`: add `Scheduled(plan, phase)`. Emit only on phase transitions.
- `core/data/Preferences.kt`: add nullable defaulted fields to `RouteState`: `startAtEpochMs`, `updateIntervalMs`, `timesMs`, `sourceLat`, `sourceLng`, `speedSource`, `speedFactor`, `leadInSpeedKmh`, `pausedTotalMs`, `name`. Do not add a `MockState.Mode`. Write through `encodePreservingUnknown`.
- `core/location/RouteProgressWriteCadence.kt`: ordinary routes retain their one-second tick; scheduled routes use the clock and plan interval directly, with persistence on state transitions rather than per-tick progress writes.
- Restore: recompute position from the clock, not saved progress.

### Phase 4: UI and input

- `MainActivity.kt` and `AndroidManifest.xml`: `VIEW`/`SEND` filters for `application/gpx+xml`, `application/gpx`, `application/geo+json`, `text/csv`, plus `application/xml`, `text/xml` and `application/octet-stream`, because apps often label GPX that way; the importer sniffs the content and a non-route file only shows an import error. Read `content://` URIs with `ContentResolver`. No blanket `application/json` filter.
- SAF picker via `ActivityResultContracts.OpenDocument`, plus a paste box.
- New `feature/map/SchedulePlanSheet.kt`: import, Material 3 date/time pickers, "Start now", speed source, interval, summary. Implemented as a modal bottom sheet opened from a button in the map sheet (`MapSheet`), not as a collapsed section, so `MapWorkspace.kt` and `RouteSettingsControls.kt` stay unchanged. A start time in the past shows the "Start now" / "Join at current position" choice (join keeps the original start, so the clock is already mid-route). The summary warns when one update moves more than about 50 m. Plan and timeline building runs off the main thread. An imported preview is not drawn over a route that is playing or scheduled. The draft lives in a ViewModel: it survives rotation but not process death (a route can exceed the saved-state size limit, so the user re-imports).
- Modify `MapScreen.kt`, `MapWorkflowPresentation.kt` (`currentMockSummary`, `runtimeMatchesDraft`), `ui/components/PlaybackStatusBar.kt`, and `LocationService.buildNotification` (countdown "Starts in 12:04", progress, "Arrived"; already present in the service). `MapWorkspace.kt` and `RouteSettingsControls.kt` are not needed because the schedule UI is a sheet. New `feature/map/ScheduleUiModel.kt` holds the draft. `scheduledStatusTitle` lives in `core/routeplan/ScheduleFormat.kt` so the sheet and the status bar share one title.
- Add strings to `res/values/strings.xml`. The sheet and the schedule button use string resources. Pure presentation helpers (`scheduledStatusTitle`, `playbackBarPresentation`, `currentMockSummary`) and core validation and import messages stay English literals, like the existing Route strings beside them.
- Imports always land in Preview. Nothing starts without the user confirming.
- Importing, previewing and configuring a plan never touch system location, so the entry button and the sheet do not wait for setup (same split as drafting a route versus starting it). When setup is incomplete the sheet shows the map's setup prompt (`SetupPromptCard`: Allow permissions, Open developer options, Recheck). The final action remains available so an explicit attempt shows the notification- or mock-specific error inline without starting the service. Nothing in the sheet dismisses it when readiness changes.

### Phase 5: Permissions and mock-location checks

- Existing permissions cover it: `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, `ACCESS_MOCK_LOCATION` (user selects Kestrel under Developer options).
- At scheduling time `LocationService.startScheduledAction` runs `schedulePreflight` (new `core/location/SchedulePreflight.kt`): `MockProviderManager.isMockAllowed()`, then `NotificationManagerCompat.areNotificationsEnabled()`. Fail early, not at the start time. The checks run after the foreground start, which Android requires promptly; a failure leaves any running mock untouched and stops the service if idle. The service check is explicit for notifications because a denied `POST_NOTIFICATIONS` does not stop a location foreground service from starting, only hides its notification. The sheet checks known prerequisites on the final action first, so an explicit attempt shows its own inline fix without starting the service. The service check is the fallback for what the UI cannot see: on API 29 to 32 notifications are not a permission, so `ready` can be true while they are off, and prerequisites can also change between the gate and the press. On API 33+ revoking notifications revokes `POST_NOTIFICATIONS`, which the sheet catches first.
- `MockNotAllowedException` maps to an actionable message in `mockOperationErrorMessage` (it used to fall through to the generic "try again" text). A failed schedule keeps the schedule sheet open and shows the reason there.
- Device validation (HONOR X7d, API 35, September 30, 2026): turning off the visible system notification switch restarted the app process (`11410` to `14578`) but the active point mock and `SET_LOCATION` service survived. After re-importing a two-point draft, pressing Start now kept the sheet open, showed the notification-specific inline error, and left the process, service action and start id unchanged. With Kestrel deselected as the mock app, pressing Start now kept the sheet open with the Developer-options error and started no service. UI dumps immediately before both imports confirmed the exact two-line value `25.03658,121.56540` / `25.04658,121.57540`; later garbage in the still-focused field came from ADB gestures being intercepted after import and did not affect the imported route. The field also rendered `Or paste route text` without duplication or overlap. Still to verify: the notification-specific **service fallback** on an API 29 to 32 device or emulator, where notifications can be disabled without a runtime permission change.
- Not covered, by design: mock access or permissions revoked while a plan is armed, or a killed app. `failScheduled` reports those at the start time.
- Arm as a foreground service when the plan is scheduled. Verify background foreground-service start behaviour on real devices.
- Offer a link to battery optimization settings as guidance.
- Keep upstream's boundary: no claims of bypassing detection or Play Integrity, no jitter.

### Phase 6: Tests

JVM unit tests in `app/src/test/`:

- `GpxParserTest`, `GeoJsonParserTest`, `CsvRouteParserTest`, `PolylineDecoderTest`, `RouteImporterSniffTest`. Golden fixtures in `src/test/resources` from OsmAnd, Organic Maps, BRouter, GraphHopper, Valhalla. Include malformed files, swapped coordinate order, XXE.
- `RouteTimelineTest`: before, at, mid, at waypoint, at end, after end. Constant, timestamps, scaled timestamps, lead-in, long great-circle segment.
- `PlaybackClockTest`: pause/resume, wall-clock jump, start in the past.
- Extend `RouteStateSerializationTest` (old payloads, unknown fields, round trip) and `MapWorkflowPresentationTest`.
- `ScheduleDraftTest`: past start with and without Join, Join withdrawn on a new start, Join on a finished route, large-step flag, factor and lead-in edits not blocking the speed hint, ready plan carries its timeline. `SchedulePreflightTest`: order and short-circuit of the checks. `LocationOperationTest`: the `MockNotAllowedException` message.

Simulated route tests: add `kotlinx-coroutines-test`. Run `ScheduledPlaybackRunner` against a fake `LocationSink` in virtual time. Assert nothing moves before the start, sample count equals duration / interval, inter-sample speed matches target, and the last sample equals the destination.

Instrumented tests on an emulator only (connected instrumentation can wipe app data on a real phone): grant `android:mock_location` via `adb shell appops`, register a `LocationManager` listener, assert samples arrive and `Location.isMock` is true.

Manual: `adb shell dumpsys deviceidle force-idle`, screen-off runs of 1 hour or more, kill the process mid-route and confirm resume, change the device clock mid-run.

Gates: `just android-check`, `just android-lint`, `just android-test`.

### Risks

| Risk | Mitigation |
|---|---|
| Doze, OEM task killers | Foreground service armed at scheduling. Position is a function of the clock. Optional partial wake lock during `Moving` only. Test on real devices. |
| Time sync, time zones, DST | UTC epoch plus monotonic anchor. Show local time. |
| Start in the past | Explicit "Start now" or "Join at current position" choice; a Join on an already finished route is rejected. |
| Timestamps vs speed conflict | User picks the source. Show resulting duration. |
| Long pauses in GPX | Optional compress pauses longer than N minutes. Not implemented. |
| Large or hostile files | Size and point caps, DOCTYPE disabled, optional simplification, parse off the main thread. |
| Big jumps at high speed and long interval | Warn when speed x interval exceeds about 50 m per sample (`MAX_COMFORTABLE_STEP_METERS`; average speed stands in for timestamp plans). |
| Fused provider override | Turn off Google Location Accuracy (README) and show a hint when updates do not arrive. |
| `LocationService` growth | Logic in the new runner, timeline and importer classes. |

## Completion Checklist

- [x] Fork created, green baseline, debug app ID suffix (verified in the environment handoff)
- [x] `RouteTimeline`, `PlaybackClock`, great-circle interpolation with tests (integrated; Gradle JVM tests verified September 29, 2026)
- [x] Importers (GeoJSON, CSV, coordinate list, GPX, router JSON, polyline) with fixtures (integrated; Gradle JVM tests verified September 29, 2026)
- [x] `ScheduledPlaybackRunner`, `LocationSink`, `RuntimeState.Scheduled` (integrated; Gradle JVM tests and debug build verified September 29, 2026)
- [x] `RouteState` schedule fields with forward-compatibility tests (legacy decoding and unknown-field preservation tested)
- [x] Simulated-route tests in virtual time (fake monotonic clock with real coroutine Job cancellation)
- [x] Share/open intents, picker, paste box (Phase 4 import integrated September 29, 2026)
- [x] `SchedulePlanSheet`, status bar, notification countdown (Phase 4 UI integrated; notification countdown was already in the service)
- [x] Schedule sheet text in `strings.xml`; draft kept across rotation by `ScheduleUiModel` (process death re-imports by design)
- [x] Past-start Join choice and large-step warning
- [x] Broader share/open MIME types (`application/xml`, `text/xml`, `application/octet-stream`, `application/gpx`)
- [x] Pre-flight mock and notification checks at schedule time (`schedulePreflight`, unit-tested); explicit attempts with notifications disabled or the mock app unselected keep the sheet open with the specific reason; both UI-known paths verified on a device
- [x] Schedule entry and sheet usable before setup is complete; the sheet shows the setup prompt in place
- [x] Running point mock survives the API 35 notification-disabled rejection with the service action/start id unchanged (device)
- [ ] Notification-specific service fallback verified on an API 29 to 32 device or emulator
- [ ] Emulator instrumented test
- [ ] Real-device Doze and process-kill checks
- [x] `just android-check`, `android-lint`, `android-test` pass for phases 1 through 3 (303 tests; debug build also passes)
- [x] `just android-check`, `android-lint`, `android-test` for the Phase 4 review fixes (join, step warning, ViewModel, strings, MIME types, pre-flight, sheet error); debug build also passes
- [ ] Plan moved to `docs/plans/archived/` when done
