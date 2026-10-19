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

## SonyPhotoTransfer application

- Repository: https://github.com/Morning-Sea/SonyPhotoTransfer
- Inspected revision: `6d39accd8c0b366be2efb4c72f11234aba015d51`
- The upstream repository's app is MIT-licensed, but none of its MIT-covered app/UI source files are copied into this project.
- Its README, AGENTS.md, project structure, PTP/IP implementation and build setup were used as technical references only.
