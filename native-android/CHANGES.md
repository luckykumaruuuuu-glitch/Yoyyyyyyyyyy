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

## Phases 5–6 — USB/OTG and release signing

USB/OTG camera transport remains unsupported. The Android project has no verified USB PTP adapter, and no physical camera/OTG device is available here. The app and README do not claim USB support. Release signing remains unconfigured because there is no user-owned release keystore in the workspace; the release build and signing path also cannot be verified without an Android SDK. See `TEST_REPORT.md`.
