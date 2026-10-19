# WED Faces Camera Operator for Android

Native Android source project for photographers who need to browse a supported Sony camera's card and send only chosen image originals to an existing WED Faces album.

## Requirements and build

- Android Studio with Android SDK Platform 35 and Build Tools 35.0.1, or a JDK 17+ installation and Android command-line tools.
- Open this `native-android/` directory in Android Studio, or run:

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Server setup

Open **Settings**, enter the existing WED Faces server base URL, and sign in with the WED Faces account. The app uses the documented login, refresh, album, upload, import, and import-status endpoints. It stores access and refresh tokens encrypted with an Android Keystore AES-GCM key; the password is not persisted.

The implemented routes are:

- `POST /api/auth/login` and `POST /api/auth/refresh`
- `GET /api/albums` (the server album `id` is used for every album route)
- `POST /api/albums/{albumId}/import/upload` as multipart form fields `uploadId` and `file`
- `POST /api/albums/{albumId}/import` with `{"path":".device-uploads/{albumId}"}`
- `GET /api/albums/{albumId}/import/status` for state, counts, and reported failures

Remote servers must use HTTPS. The debug build permits cleartext networking at the Android platform layer so a developer can point to a local server, but the app itself rejects HTTP unless **Allow HTTP for local development** is explicitly enabled and the host is a local/private-network address. Release builds disallow cleartext traffic.

The app does not include a WED Faces server URL, credentials, API secret, or test success response. The exact live album/status response shapes and the deployment's file-size limit must be confirmed against the target server. The documented upload formats are JPG/JPEG, PNG, and WEBP; an HTTP 413 response is shown as a server file-size limit.

Authentication currently uses the documented email/password route (`POST /api/auth/login`) and refresh route. No upload-key sign-in endpoint or request/response contract is present in the supplied API documentation, so the app does not invent one. Add that flow only after the WED Faces backend contract is supplied.

## Camera connection

The initial adapter is Sony Wi-Fi PTP/IP, using the PTP/IP implementation described by SonyPhotoTransfer. On the camera, start **Send to Smartphone**, then use **Connect to camera Wi-Fi** and enter the camera access-point SSID/password shown by the camera. Android presents its Wi-Fi connection dialog. Camera PTP/IP sockets use the selected Android `Network` socket factory; server API sockets remain on the normal internet-capable network. TCP connection attempts time out after 15 seconds.

The app lists PTP object handles, metadata, and small camera thumbnails. It requests an original only after **Send Selected**. It never sends delete, rename, format, or write operations to the camera. Physical camera behavior remains unverified until tested with a real supported camera and firmware.

USB/OTG, Canon, and Nikon are not implemented. Do not treat the Android photo picker as a camera connection: it is available only under the separately labeled Test Mode.

## Phone-folder watch

In **Settings → Phone folder watch**, choose the phone folder that directly contains the images. Android's Storage Access Framework grants read-only folder access; the app does not request broad media-library permission. Enable **Watch this folder** after signing in and selecting the destination album. WorkManager scans about every 15 minutes (Android may defer background work for battery management), copies supported JPG/JPEG/PNG/WEBP files into app-private storage, and queues them through the same real upload/import flow. Original phone files are not modified or deleted.

By default, only files whose modification time is newer than the moment watch was enabled are queued. If a document provider does not return modification times, those files are excluded unless **Include existing photos** is enabled. That option also opts into the next scan of the already-present files. A saved URI/modified-time/size identity prevents the same unchanged file from being queued again, including after clearing imported rows. Select the folder again if Android access is revoked. Folder watch is not a camera adapter and does not replace the Sony connection.

## Transfer queue

Queue records are stored in SQLite and survive navigation and app restarts. Once selected originals are available as app-owned files, WorkManager runs the upload/import batch when an internet connection is available. The original on the camera is never modified or deleted. Temporary copies are removed after successful server import; failed/retryable copies remain available to the queue.

Camera originals are downloaded one at a time through a serialized foreground-service queue. Each completed original is made upload-ready and enqueued immediately; the next camera download does not need to finish first. Interruptions are marked for retry after reconnecting to the camera. PTP sessions themselves are not persisted by Android.

If an upload has already been accepted by the server, canceling local follow-up does not remove that server-side staged upload.

## Test Mode

Enable **Test Mode** in Settings to select real images from the Android photo picker. These are clearly marked as test-gallery files and never appear as real camera objects. Test Mode exercises selection, queue persistence, server upload, import, and status polling against the configured server. It does not fake server responses.

## Diagnostics and privacy

Diagnostics contain app/version and non-secret queue/camera state only. They do not include passwords, access/refresh tokens, authorization headers, or image bytes. The application requests Wi-Fi permissions only for the user-initiated camera connection and uses the Android system photo picker rather than broad media-library access.

## Third-party source and licenses

The app uses the `libptp` PTP/IP Java source carried by Morning-Sea/SonyPhotoTransfer at commit `6d39accd8c0b366be2efb4c72f11234aba015d51`. The 14 source files remain under `app/src/main/java/com/fimagena/libptp/`, retain their copyright/license headers, and are licensed LGPL-2.1. Two files were modified to allow PTP sockets to use an Android `Network`-bound `SocketFactory`; changes are noted in `licenses/SOURCE_ATTRIBUTION.md`. The full LGPL-2.1 text is in `licenses/LGPL-2.1.txt`.

The MIT-licensed SonyPhotoTransfer application UI is not copied. This project uses its repository as a source reference and includes only the separately licensed `libptp` component.

See `licenses/SOURCE_ATTRIBUTION.md` for exact source and modification details.

## Verification status

The project includes mock-server unit tests for the documented API routes, multipart fields, refresh flow, import status parsing, format rejection, and upload-size errors, plus SQLite queue/import-status persistence coverage. Build/test outcomes and any unavailable live-server or physical-camera tests are reported in `TEST_REPORT.md`.

Release APK signing is not configured with a project-owned keystore. Do not ship an unsigned release APK or reuse a debug key as a production signing key. Configure a user-controlled signing key outside the source package before distribution.
