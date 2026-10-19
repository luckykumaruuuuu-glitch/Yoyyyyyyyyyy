# Source attribution and modification record

## `libptp` (LGPL-2.1)

- Source repository: https://github.com/Morning-Sea/SonyPhotoTransfer
- Exact source revision: `6d39accd8c0b366be2efb4c72f11234aba015d51`
- Included upstream paths: all 14 files originally located under `app/src/main/java/com/fimagena/libptp/` in that revision.
- Upstream author/license: Fimagena; GNU Lesser General Public License version 2.1. Copyright/license headers are retained in the source files. The full license is in `LGPL-2.1.txt`.
- Purpose: PTP/IP packet/session transport and PTP data types used by the Sony Wi-Fi camera adapter.
- Modifications in this app:
  - `ptpip/TcpConnection.java`: added an overload accepting `javax.net.SocketFactory`, used it to create the transport socket, and capped socket connection attempts at 15 seconds.
  - `ptpip/PtpIpConnection.java`: added a constructor accepting a socket factory and forwarded it to both PTP/IP channels and any secondary session.
- The remaining 12 files are unmodified from the cited commit.

The Gradle application links against this LGPL-2.1 library. The source project includes the library source and license so the library can be modified and rebuilt. This notice is a technical attribution record, not legal advice.

## Sony USB camera-control library (MIT)

- Source project: `sony-camera-android`, supplied as `sony-camera-android-main.zip`.
- Upstream project copyright: © 2026 Andrew Gallo. The source archive does not identify a commit hash or a public repository URL; no revision is inferred here.
- Reused source: Sony USB/PTP transport, Sony PTP operations and card-object parsing, USB connection manager, and service/client lifecycle, integrated under `native-android/sony-usb/`.
- Adaptations include the WED Faces app's transport adapter and queue routing, the standard read-only card-browsing operations and PTP metadata parser, streamed original-file transfer, and endpoint-compatibility checks. The reference Compose demo UI is not copied.
- License: MIT. The complete upstream notice is retained at `SONY_USB_MIT_LICENSE.txt`; the same copyright and license terms apply to the reused source.
- The reference README reports that its engine was tested on the Sony α6600 (ILCE-6600). That is not a physical test of this app's integrated workflow; camera compatibility remains unverified until tested here.
