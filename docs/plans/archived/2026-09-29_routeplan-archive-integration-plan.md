# Route plan archive integration

## Goal

Integrate the supplied phase 1 and 2 Android route timeline, clock, interpolation and importers, including user-authorized GPX support. Preserve existing local setup edits. Later service and UI phases are outside this archive integration.

## Plan

1. Inspect the nested archive and compare existing source files.
2. Add the supplied source and JVM tests, merge Geo additions and conform to project formatting and Detekt rules.
3. Run Android formatting, Detekt, JVM tests and debug build.
4. Delete only the user-specified outer zip after all checks pass.

## Completion Checklist

- [x] Archive inspected and GPX exception authorized
- [x] Source and tests integrated
- [x] Android checks, tests and build pass
- [x] Only the specified archive deleted

## Verification

On September 29, 2026, `just android-check`, `just android-lint`, `just android-test` and `just android-build` passed with Java 26. JVM results: 281 tests across 48 suites, including 82 imported tests; no failures, errors or skipped tests. Detekt integration fixes preserve GPX exception causes and split validation helpers. Only the specified outer zip was deleted; extracted review files remain outside the repository. Existing setup edits were preserved.
