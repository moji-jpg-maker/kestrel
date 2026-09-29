# Scheduled route phase 3 archive integration

## Goal

Integrate the supplied cumulative phase 1–3 archive, preserving verified phase 1–2 fixes and adding the scheduled runner, service lifecycle and compatible persistence fields. UI import and scheduling entry points remain outside the supplied phase 3 code.

## Plan

1. Compare cumulative archive files and keep existing style and importer fixes.
2. Integrate service and persistence changes; adapt runtime consumers and check cancellation, replacement, restore and malformed-state handling.
3. Run Android formatting, Detekt, JVM tests and debug build using Java 26. Do not install or run connected instrumentation.
4. After successful checks, delete only the specified outer archive and archive this completed integration plan. Keep the full feature plan active.

## Completion Checklist

- [x] Archive inspected and cumulative changes identified
- [x] Service, runner and persistence integrated
- [x] Existing runtime consumers handle scheduled playback
- [x] Serialization and lifecycle regression tests pass
- [x] Android check, lint, test and build pass
- [x] Specified outer archive deleted

## Verification

On September 29, 2026, Java 26 Gradle runs passed `just android-format`, `just android-check`, `just android-lint`, `just android-test` and `just android-build`. All 303 JVM tests across 51 suites passed with no failures, errors or skipped tests. The debug APK was built at `app/build/outputs/apk/debug/app-debug.apk`.

Archive integration corrections:

- Preserved the earlier importer exception-cause and validation-helper fixes.
- Replaced the archive's unshipped `SimpleJob` shim with the actual coroutine `Job` and cancellation API.
- Rejected invalid persisted speeds, timestamp sequences and incomplete source coordinates without crashing restore.
- Preserved nullable field defaults and verified unknown-field retention through the existing JSON write path.
- Gated scheduled callbacks by session identity; dispatched failure teardown to the service thread and used the latest received start ID so queued replacement commands are protected.
- Cleared the stale mock dot when arming without a source, and released plan handoff references after foreground dispatch/start failures.
- Extracted notification building, command dispatch, state snapshots, scheduled callbacks and the existing point keep-alive loop so Detekt passes without changing its class-size limit or baseline.
- Updated existing map, playback bar and remote status consumers for the new runtime type; armed schedules expose stop without pause, and scheduled routes do not expose ordinary live route settings.

Scheduled timing uses the plan's millisecond interval directly and does not reuse ordinary route progress tick counts. Schedule persistence happens on transitions; position is recomputed from the start time and pause total.

Only the user-specified outer zip was deleted. Extracted review files remain in `/tmp`. No connected instrumentation, installation or physical device changes were performed; notification delivery, provider behavior, Doze and process restart still require device validation. Import/scheduling UI, preflight UI and the rest of the full feature plan remain pending in the active plan.
