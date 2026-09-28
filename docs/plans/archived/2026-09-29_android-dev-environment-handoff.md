# Kestrel session handoff

Updated September 29, 2026 at 00:36, Europe/Bucharest. The user initially requested a handoff, then asked to continue in the same session. Environment setup is complete; this handoff records the final state for the next session.

## Goal

Prepare the local Kestrel Android fork and verify its baseline for scheduled route playback development.

## Completed state

- Checkout `/home/mojtaba/kestrel`; branch `feature/scheduled-route-playback`; baseline HEAD `5bac5a3`.
- Fork origin `git@github.com:moji-jpg-maker/kestrel.git`; upstream `git@github.com:narumiruna/kestrel.git`.
- Java Temurin 26.0.2.1 at `/usr/lib/jvm/temurin-26-jdk-amd64`.
- Gradle 9.7.1, AGP 9.4.1, Kotlin 2.4.20, compileSdk 37 and minSdk 29; repo versions unchanged.
- `just` 1.58.0 at `~/.local/bin/just`; distro 1.21.0 cannot parse this repo.
- SDK root `~/android-sdk`; CLI 20.0 under `cmdline-tools/latest` symlink to `latest-14742923`.
- Installed SDK packages: platform-tools 37.0.1, build-tools 36.0.0 and 37.0.0, `platforms;android-37.0` revision 2. Licenses accepted and SDK packages recognized.
- Official published archive checksums verified for Gradle, just and SDK packages.

## Persistent local configuration

- `~/.config/kestrel/android-env.sh`, sourced by `.bashrc`: Java/SDK paths, user-local just and SDK redirector.
- Ignored `local.properties`: `sdk.dir=/home/mojtaba/android-sdk`.
- `~/.gradle/init.d/kestrel-google.gradle`: scoped to canonical `/home/mojtaba/kestrel`, redirects Google Maven to its official redirector. Known Central-hosted groups (including JUnit/Hamcrest) skip Google lookup.
- Direct Google SDK/Maven URLs returned HTTP 404 here; the official redirector works. Cause of direct endpoint failures was not established.
- SDK redirector setting: `SDK_TEST_BASE_URL=https://redirector.gvt1.com/edgedl/android/repository/`.
- AGP selects build-tools 36.0.0 by default; keep both installed. Live SDK manifest uses `platforms;android-37.0`, not `platforms;android-37`.

## Application configuration changes

- `app/build.gradle.kts`: debug suffix `.fork`.
- `justfile`: device recipes target `dev.narumi.kestrel.fork` and the full activity class `dev.narumi.kestrel.MainActivity`.
- No other application source changes, commits, pushes, cloud startup or device modifications.
- aapt2 and APK metadata confirm the fork package and activity. Device run recipe inspected with `just --dry-run run` only.

## Verified gates

- Untouched baseline build, JVM tests, formatting, icons and Detekt all passed.
- 199 JVM tests across 38 suites; zero failures, errors or skipped tests.
- Final fork `just android-build`, `just android-test`, `just android-check` and `just android-lint` all passed. Test and Detekt tasks reused valid up-to-date results.
- APK: `app/build/outputs/apk/debug/app-debug.apk`, version 0.9.0 (19).

## Logs and download recovery

Durable directory: `/home/mojtaba/.local/state/kestrel/dev-environment-handoff/`.

- `kestrel-baseline-build-10.log`, `kestrel-baseline-test-2.log`, `kestrel-baseline-check-3.log`, `kestrel-baseline-lint-2.log`: successful baseline evidence.
- `kestrel-fork-build.log`, `kestrel-fork-test.log`, `kestrel-fork-check.log`, `kestrel-fork-lint.log`: final gates.
- SDK manifest and checksum-verified resumable download helpers retained. Kotlin compiler 2.4.20 completed and cached; no pending artifact recovery is required.
- Keep SDK and Gradle caches. Network sandbox failures require normal escalated network execution when needed. Commands must remain within the repo's 180-second limit.

## Next work

Environment setup is complete. The checkout has no `core/routeplan/` directory; locate the user's drafted route engine/importers before assessing feature progress. Read `AGENTS.md` and `docs/MEMORY.md` before feature work.

User decisions: fork-only AGPL, GPX import allowed in this fork despite upstream restriction, no cold start/exact alarms, no cloud support in version one. Device installs/instrumentation and external writes need explicit consent. No feature implementation was done during setup.

### Resume commands

```bash
source /home/mojtaba/.config/kestrel/android-env.sh
cd /home/mojtaba/kestrel
git status --short
```

Do not repeat environment installation or cold-cache downloads. Existing quality gates are green; rerun relevant gates after new changes. Prefer just recipes and bound each command to at most 180 seconds.

### Uncommitted work to preserve

At handoff, branch is `feature/scheduled-route-playback`, HEAD is `5bac5a3`, and the working tree contains:

```text
 M app/build.gradle.kts
 M justfile
?? docs/plans/archived/2026-09-28_android-dev-environment-plan.md
?? docs/plans/archived/2026-09-29_android-dev-environment-handoff.md
```

Nothing is staged. Keep these edits when resuming. The completed setup plan is archived beside this handoff. Persistent environment files and ignored build outputs are outside the tracked changes above.

### Scheduled route playback scope

The user's original feature plan remains the intended design, with these constraints:

- Source location, imported route, speed, UTC start time and 200–10,000 ms update interval; scheduled playback uses `Once` only.
- Pure Kotlin timeline and monotonic playback clock; preserve `MovementEngine` for existing remote commands.
- GPX, GeoJSON, CSV, coordinate lists and router JSON import; imports land in Preview and require confirmation before starting.
- Runner and testable location sink, atomic service replacement, scheduled runtime phases and forward-compatible preference fields.
- Schedule sheet, share/open input, countdown/status/notification, preflight mock-location and notification checks.
- JVM importer/timeline/clock/serialization tests and simulated runner tests; emulator/device validation remains separate and requires consent where it changes device state.
- No Room/Prisma schema changes, remote/cloud support, jitter or detection-bypass claims.

The user marked engine/importer drafts as written, but they are absent from this checkout. First locate those drafts or ask for their location; do not claim they were tested. Once found, put the active feature plan in `docs/plans/` and reconcile its checklist with actual code. The original plan's process-kill/resume manual test should be reconciled with the settled decision that cold start from a killed app is not needed before implementing that behavior.

Suggested next-session prompt: "Read this handoff, preserve the completed setup changes, locate my drafted route playback files, and continue the scheduled route playback plan."

## Completion Checklist

- [x] Environment and dependencies configured
- [x] Untouched build/test/check/lint baseline passed
- [x] Fork debug identity and device recipe targeting configured
- [x] Final gates and APK metadata verified
- [x] Completed setup plan archived
