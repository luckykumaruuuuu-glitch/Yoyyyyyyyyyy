# Test Report

Date: 2026-10-19

## 2026-10-20 Sony USB/PTP integration continuation

### Implemented in source

- Added USB/PTP interface matching that requires a Sony vendor ID, a PTP/vendor-specific interface, and both bulk IN and bulk OUT endpoints before the app calls a device compatible.
- Forwarded USB attach intents from `MainActivity` to the existing single USB-session owner so the session manager can recover from a reattached cable.
- Made explicit USB disconnect wait for the Sony session-close and USB resource-release job; reconnect waits for a previous teardown before claiming the interface.
- Added clickable card breadcrumbs and the requested labels for storage, back/up, selected uploads, and explicit photo saving.
- Unified supported-format checks across the UI and queue. The WED Faces upload formats remain JPG/JPEG, PNG, and WEBP.
- Added transport-routing, PTP card-parser, supported-format, USB endpoint-matcher, Auto Upload baseline/deduplication, and version-7 queue-upgrade tests.
- Updated the Sony USB MIT attribution and documented USB, Auto Upload, and hardware-verification limits.

### Exact verification results

| Command | Result |
|---|---|
| `./gradlew :app:assembleDebug` | **Blocked** before compilation: Android SDK location not found (`ANDROID_HOME`/`ANDROID_SDK_ROOT` unset; no `local.properties`). No APK was produced. |
| `./gradlew :app:testDebugUnitTest` | **Blocked** before test compilation/execution by the same missing Android SDK. The new tests have not run. |
| `./gradlew :app:lintDebug` | **Blocked** before lint by the same missing Android SDK. |
| `./gradlew :app:tasks --all` | **Passed** Gradle project configuration/task listing only; this does not compile source. |
| `git diff --check` | **Passed** with no whitespace errors. |

The unit tests and Android source therefore remain uncompiled in this environment. Earlier test/build results recorded below belong to the imported project state and were not reproduced in this continuation.

### Camera compatibility

| Camera | USB/PTP connection | Card/folders | Preview | Physical-shutter detection | Manual upload | Auto Upload | Save to Phone |
|---|---|---|---|---|---|---|---|
| Sony α6600 (ILCE-6600) | Not verified in this integrated app | Not verified | Not verified | Not verified | Not verified against a live WED Faces server | Not verified | Not verified |
| Other Sony bodies | Not verified | Not verified | Not verified | Not verified | Not verified | Not verified | Not verified |

The supplied reference library README reports testing on an α6600. That is upstream evidence only, not a test of this integrated app. No physical Android phone, OTG adapter, camera, or live WED Faces server was available here.

## Current continuation verification

- Added three pipeline tests covering immediate per-original upload enqueue, per-file camera failure isolation, and interrupted-download recovery. They are present in source but were not executed in this continuation.
- Added phone-folder watch policy tests for supported image formats, existing-file inclusion, and changed-file identity. Added SQLite persistence coverage for avoiding duplicate folder-watch queue entries; these tests were not executed.
- Implemented SAF folder selection, persisted read access, immediate and periodic WorkManager scans, app-private file copies, and queue insertion through the existing upload/import flow. Device-side permission behavior and background scheduling were not exercised.
- Attempted `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` from `native-android/`; Gradle stopped before compilation because no Android SDK is configured (`ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset and `local.properties` is absent).
- Android SDK installation was not available through the configured Replit system-package index. No claim is made that the new tests, build, or lint pass.
- `./gradlew :app:tasks --all` passed, confirming Gradle project configuration only; `git diff --check` also passed. Neither check compiles the Android source.
- No emulator, physical Android device, supported Sony camera, or live WED Faces server was available. Device and server compatibility remain unverified.

## Folder-watch continuation verification

- Attempted `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`; Gradle stopped before compilation because no Android SDK is configured (`ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset, `sdkmanager` is absent, and `local.properties` is absent).
- A follow-up `./gradlew :app:tasks --all` attempt timed out during Gradle startup and did not complete. The current source has therefore not been compiled or tested.
- `git diff --check` passed after the changes.

## Earlier imported report (not rerun in this continuation)

- The imported report recorded `./gradlew :app:testDebugUnitTest` — 9 tests passed:
  - 8 WED Faces API contract tests using MockWebServer (login, album IDs, multipart upload, import path, import counters/failures JSON, refresh, unsupported formats, and HTTP 413).
  - 1 SQLite persistence test confirming server counters and all failed filenames survive closing and reopening the database.
- The imported report also recorded `./gradlew :app:assembleDebug` as passed and `./gradlew :app:lintDebug` as passed with 13 warnings and no errors. These results were not reproducible in this continuation because the Android SDK is missing.

## Issues found and resolved

- The login and token-refresh code treated the raw JSON response string as an already-parsed JSON object. Both now parse the response before reading tokens.
- The import-path test now checks the decoded JSON field instead of relying on the serialized JSON string representation.
- Lint's API 27 navigation-bar theme attribute is now limited to API 27+, and the Android 10–12 Wi-Fi permission declaration includes both fine and coarse location permissions.

## Tests not run / features not verified

- No end-to-end run of Test Mode against a live WED Faces server was performed. API behavior was tested against a local mock server only.
- No live WED Faces login, album selection, upload, or server-side import was performed; no server URL or account was configured for this run.
- No Android emulator or physical device was connected, so installation, touch/layout behavior, Android permission prompts, and device-side test-gallery flow were not exercised.
- Sony camera PTP/IP browsing, thumbnail retrieval, and selected-original transfer were not verified with physical camera hardware.
- Phone-folder watch SAF permissions, WorkManager timing, file-provider behavior, and upload/import were not verified on an Android device.
- USB/OTG PTP integration was added in the 2026-10-20 continuation above; it has not been compiled, unit-tested, or verified on physical hardware in this environment.
- Upload-key sign-in is not implemented because the supplied server contract contains no such endpoint. Email/password login remains the only documented authentication path.
- No release keystore is present. Release signing and Play-distribution readiness were not verified; no production-signed APK is claimed.

## Remaining warnings and blockers

- Lint passes with 13 non-blocking warnings: five date-format warnings in the LGPL-2.1 `libptp` source, a version-gated Wi-Fi permission attribute warning, six dependency-update notices, and an Android backup-configuration notice. Third-party PTP source was left unchanged to preserve its licensing and provenance.
- Physical-camera compatibility and live WED Faces API response behavior remain pending real-device/server verification.
- The built APK is a debug-signed development APK, not a release-signed Play Store package.
