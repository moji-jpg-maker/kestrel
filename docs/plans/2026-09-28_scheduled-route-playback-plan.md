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

- `MainActivity.kt` and `AndroidManifest.xml`: narrow `VIEW`/`SEND` filters for `application/gpx+xml`, `application/geo+json`, `text/csv`. Read `content://` URIs with `ContentResolver`. No blanket `application/json` filter.
- SAF picker via `ActivityResultContracts.OpenDocument`, plus a paste box.
- New `feature/map/SchedulePlanSheet.kt`: import, Material 3 date/time pickers, "Start now", speed source, interval, summary. Collapsed section inside the route panel.
- Modify `MapScreen.kt`, `MapWorkspace.kt`, `RouteSettingsControls.kt`, `MapWorkflowPresentation.kt` (`currentMockSummary`, `runtimeMatchesDraft`), `ui/components/PlaybackStatusBar.kt`, and `LocationService.buildNotification` (countdown "Starts in 12:04", progress, "Arrived").
- Add strings to `res/values/strings.xml`.
- Imports always land in Preview. Nothing starts without the user confirming.

### Phase 5: Permissions and mock-location checks

- Existing permissions cover it: `ACCESS_FINE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`, `POST_NOTIFICATIONS`, `ACCESS_MOCK_LOCATION` (user selects Kestrel under Developer options).
- At scheduling time call `MockProviderManager.isMockAllowed()` and check notification permission. Fail early, not at the start time.
- Arm as a foreground service when the plan is scheduled. Verify background foreground-service start behaviour on real devices.
- Offer a link to battery optimization settings as guidance.
- Keep upstream's boundary: no claims of bypassing detection or Play Integrity, no jitter.

### Phase 6: Tests

JVM unit tests in `app/src/test/`:

- `GpxParserTest`, `GeoJsonParserTest`, `CsvRouteParserTest`, `PolylineDecoderTest`, `RouteImporterSniffTest`. Golden fixtures in `src/test/resources` from OsmAnd, Organic Maps, BRouter, GraphHopper, Valhalla. Include malformed files, swapped coordinate order, XXE.
- `RouteTimelineTest`: before, at, mid, at waypoint, at end, after end. Constant, timestamps, scaled timestamps, lead-in, long great-circle segment.
- `PlaybackClockTest`: pause/resume, wall-clock jump, start in the past.
- Extend `RouteStateSerializationTest` (old payloads, unknown fields, round trip) and `MapWorkflowPresentationTest`.

Simulated route tests: add `kotlinx-coroutines-test`. Run `ScheduledPlaybackRunner` against a fake `LocationSink` in virtual time. Assert nothing moves before the start, sample count equals duration / interval, inter-sample speed matches target, and the last sample equals the destination.

Instrumented tests on an emulator only (connected instrumentation can wipe app data on a real phone): grant `android:mock_location` via `adb shell appops`, register a `LocationManager` listener, assert samples arrive and `Location.isMock` is true.

Manual: `adb shell dumpsys deviceidle force-idle`, screen-off runs of 1 hour or more, kill the process mid-route and confirm resume, change the device clock mid-run.

Gates: `just android-check`, `just android-lint`, `just android-test`.

### Risks

| Risk | Mitigation |
|---|---|
| Doze, OEM task killers | Foreground service armed at scheduling. Position is a function of the clock. Optional partial wake lock during `Moving` only. Test on real devices. |
| Time sync, time zones, DST | UTC epoch plus monotonic anchor. Show local time. |
| Start in the past | Explicit "Start now" or "Join" choice. |
| Timestamps vs speed conflict | User picks the source. Show resulting duration. |
| Long pauses in GPX | Optional compress pauses longer than N minutes. |
| Large or hostile files | Size and point caps, DOCTYPE disabled, optional simplification, parse off the main thread. |
| Big jumps at high speed and long interval | Warn when speed x interval exceeds about 50 m per sample. |
| Fused provider override | Turn off Google Location Accuracy (README) and show a hint when updates do not arrive. |
| `LocationService` growth | Logic in the new runner, timeline and importer classes. |

## Completion Checklist

- [x] Fork created, green baseline, debug app ID suffix (verified in the environment handoff)
- [x] `RouteTimeline`, `PlaybackClock`, great-circle interpolation with tests (integrated; Gradle JVM tests verified September 29, 2026)
- [x] Importers (GeoJSON, CSV, coordinate list, GPX, router JSON, polyline) with fixtures (integrated; Gradle JVM tests verified September 29, 2026)
- [x] `ScheduledPlaybackRunner`, `LocationSink`, `RuntimeState.Scheduled` (integrated; Gradle JVM tests and debug build verified September 29, 2026)
- [x] `RouteState` schedule fields with forward-compatibility tests (legacy decoding and unknown-field preservation tested)
- [x] Simulated-route tests in virtual time (fake monotonic clock with real coroutine Job cancellation)
- [ ] Share/open intents, picker, paste box
- [ ] `SchedulePlanSheet`, countdown, notification
- [ ] Pre-flight mock and notification permission checks
- [ ] Emulator instrumented test
- [ ] Real-device Doze and process-kill checks
- [x] `just android-check`, `android-lint`, `android-test` pass for phases 1 through 3 (303 tests; debug build also passes)
- [ ] Plan moved to `docs/plans/archived/` when done
