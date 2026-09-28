# Android development environment

## Goal

Prepare the local Linux/WSL Kestrel fork for scheduled route playback development and verify the existing Android baseline before changing application code.

## Plan

1. Confirm the fork, feature branch, Java 26, and pinned Gradle/AGP/Kotlin versions.
2. Restore the existing command-line tools path and persist local Java/SDK environment settings.
3. Use the official Google download redirector when direct Google SDK and Maven endpoints return HTTP 404. Keep this network workaround local to this checkout.
4. Install platform-tools, API 37.0, and build-tools 37.0.0 plus AGP's default build-tools 36.0.0 using the live SDK manifest. Verify downloaded archives against published checksums.
5. Install a compatible user-local `just` if the distro version cannot parse `home_directory()`. Run `just android-build`, `just android-test`, `just android-check`, and `just android-lint` on the current checkout.
6. Add the debug `.fork` application ID suffix and recheck the affected build configuration. Do not install on a device or enable cloud services.

## Completion Checklist

- [x] Fork and `feature/scheduled-route-playback` branch exist
- [x] Java 26 installed; build configuration inspected
- [x] Command-line tools available at `cmdline-tools/latest`
- [x] Java/SDK environment persisted and ignored `local.properties` configured
- [x] Compatible user-local `just` installed; official SHA-256 verified; icon check passed
- [x] Official Google SDK manifest and Android Maven artifact accessible through the redirector
- [x] Required SDK packages installed; all archive checksums verified; `sdkmanager --list_installed` recognizes them
- [x] Additional build-tools 36.0.0 installed for the unchanged AGP default
- [x] Pinned Gradle distribution downloaded and SHA-256 verified
- [x] Current-checkout Android build/test/check/lint baseline recorded
- [x] Debug application ID suffix configured and verified

## Baseline

- Source baseline: `5bac5a3`; no application source changes before baseline verification.
- `just icons-check`: passed.
- `just android-check`: passed on the baseline checkout.
- `just android-lint`: passed on the baseline checkout.
- Build dependency resolution works through the local Google Maven redirector. The cold dependency cache and unstable connection require bounded retries; completed downloads are retained.

## Local setup

- Command runner: `~/.local/bin/just` (1.58.0); distro `just` 1.21.0 cannot parse this repo.
- Environment: `~/.config/kestrel/android-env.sh`, sourced by `~/.bashrc`.
- SDK: `~/android-sdk`.
- Google Maven workaround: `~/.gradle/init.d/kestrel-google.gradle`, restricted to this checkout.
- AGP selects build-tools 36.0.0 when `buildToolsVersion` is not set; install it alongside 37.0.0 for this baseline.
- Live SDK manifest names API 37 as `platforms;android-37.0`; `platforms;android-37` does not exist in that manifest.
- The existing fork contains no `core/routeplan/` directory; drafted feature files must be located separately before feature implementation is assessed.

## Completion

Completed September 29, 2026 (Europe/Bucharest).

- Unchanged baseline: APK build succeeded; all 199 JVM tests passed (38 suites, no failures/errors/skips); formatting, icons and Detekt passed.
- Added debug `applicationIdSuffix = ".fork"` and changed just device recipes to `dev.narumi.kestrel.fork/dev.narumi.kestrel.MainActivity`.
- Final `just android-build`, `just android-test`, `just android-check`, and `just android-lint` passed. Test and Detekt outputs reused valid up-to-date baseline results.
- APK metadata and aapt2 confirm package `dev.narumi.kestrel.fork`, launchable activity `dev.narumi.kestrel.MainActivity`, compileSdk 37 and minSdk 29.
- APK: `app/build/outputs/apk/debug/app-debug.apk`. No connected device was modified; cloud services were not started.
- The compiler download was resumed and checksum-verified. Final logs and helpers are preserved in `~/.local/state/kestrel/dev-environment-handoff/`.
- Setup snapshot: [September 29 handoff](2026-09-29_android-dev-environment-handoff.md), updated after the user continued this session.
