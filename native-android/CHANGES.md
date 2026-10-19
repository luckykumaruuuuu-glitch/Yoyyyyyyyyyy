# WED Faces Camera Operator — Change Summary

## Phase 1 — Per-file upload and import reliability

The imported project already contains per-file retry/error handling, up to three parallel uploads, old/new import-status reconciliation, and queue migration coverage. This continuation did not change Phase 1. The imported test report records the earlier checks; they could not be rerun here because the Android SDK is unavailable.

## Phase 2 — Background transfer flow

- Kept camera downloads serialized and made the service process multiple queued batches without stopping after the first batch completes.
- Each completed original is set to `READY_TO_UPLOAD` and enqueued immediately, before the next camera original starts downloading.
- An interrupted camera download now leaves the batch incomplete and marks its remaining camera items retryable instead of scheduling an import for a partial batch.
- Added three tests for immediate enqueue, an individual camera-read failure not blocking later files, and interrupted-download behavior.
- Updated the README to describe the download/upload handoff and retry behavior.

The new tests and Android build were not executed: Gradle could not find an Android SDK in this environment.

## Phase 3 — Upload-key sign-in

Not implemented. The supplied API contract documents email/password login and refresh only; no upload-key endpoint, request body, response shape, or key lifecycle is documented. Implementing a guessed route would violate the API requirements. Add this after the backend contract is provided.

## Phase 4 — Phone-folder watch

- Added an Android Storage Access Framework folder picker with persisted read permission.
- Added a WorkManager scan on enable and periodic scans (15-minute interval, subject to Android scheduling).
- Supported JPG/JPEG/PNG/WEBP files are copied into app-private storage and queued through the existing upload/import worker.
- Existing files are opt-in; the default is to watch only files modified after enabling.
- A persistent seen-identity table prevents unchanged files from being queued again after imported rows are cleared.
- Periodic scans also re-enqueue idle pending folder-watch batches if the app stopped between saving queue rows and scheduling upload work.
- Added policy and persistence tests; they have not been executed in this environment.

## Phase 5 — Sony USB/OTG continuation

- Added a Sony USB/PTP library module backed by the supplied Sony camera-control source, with one foreground-service owner for USB permissions, session lifecycle, connection state, PTP events, card metadata, thumbnails, and streamed originals.
- Integrated USB and Wi-Fi into the existing Camera screen, folder browser, saved transport-aware queue, private original staging, and existing upload/import workers. Added storage selection, clickable breadcrumbs, explicit gallery save, guarded Auto Upload baseline/deduplication, and safe disconnect/transport-switch behavior during active transfers.
- USB discovery now requires a Sony vendor ID and a PTP-compatible interface with both bulk IN and bulk OUT endpoints. The activity forwards USB reattach intents to the single connection service.
- Added pure tests for USB endpoint matching, PTP card parsing, supported formats, Auto Upload baseline/deduplication persistence, and version-7 queue upgrade preservation.
- No physical Android phone, OTG adapter, Sony camera, WED Faces server, or Android SDK was available for this continuation. USB connection, PTP event behavior, physical-shutter detection, Gallery writes, compilation, tests, and lint remain unverified here. The reference project's README reports α6600 testing, but this app has not been physically validated against it.

## Phase 6 — Release signing

Release signing remains unconfigured because there is no user-owned release keystore in the workspace. Do not ship an unsigned release APK or reuse a debug key as a production signing key. See `TEST_REPORT.md`.
