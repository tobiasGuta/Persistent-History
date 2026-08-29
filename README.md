# Persistent HTTP History for Burp Suite Community

Persistent HTTP History is a small Burp Suite extension that keeps a durable, searchable copy of HTTP request/response history in SQLite so Burp Community users do not lose their research history when Burp closes.

Current version: **v1.0.2**

## What v1 does

- Captures completed HTTP request/response pairs through Montoya `HttpHandler`.
- Stores complete raw request and response bytes as SQLite BLOBs.
- Persists across Burp restarts.
- Adds a **Persistent History** suite tab.
- Search by URL, method, status, or Burp tool.
- Preserves Burp tool provenance such as **Proxy** and **Repeater**.
- Uses Burp's native read-only HTTP request/response editors, including the normal Pretty / Raw / Hex-style message viewing experience.
- Shows the request and response side by side.
- Pause/resume capture.
- Clear stored history with confirmation.
- SQLite WAL mode and a 5-second busy timeout.
- Capture failures never modify or block the original Burp traffic.

## v1.0.2 hardening

Before adding new features, v1.0.2 focuses on the persistence core:

- **Duplicate callback protection:** Montoya response `messageId()` values are tracked for the lifetime of the loaded extension so the same response callback cannot be persisted twice. The set resets when Burp reloads the extension, so a later Burp session is not deduplicated against an earlier one.
- **Repeater provenance coverage:** tests verify Repeater traffic is stored as Repeater traffic rather than losing the source tool.
- **Large response coverage:** tests round-trip an 8 MiB response through SQLite byte-for-byte.
- **Binary response coverage:** tests round-trip arbitrary binary bytes, including NUL and high-byte values.
- **Few-thousand-row UI path:** the history table now loads metadata only. Full request/response BLOBs are fetched only when you select a row.
- **Non-blocking UI database work:** table refreshes and message loads run off the Swing event-dispatch thread.
- **Refresh coalescing:** high traffic does not force a full 5,000-row table reload for every single response.
- **Native Burp editors:** the old plain `JTextArea` request/response viewers were replaced with Montoya `HttpRequestEditor` and `HttpResponseEditor` components in read-only mode.

## Database location

By default:

- Windows: `%USERPROFILE%\.burp-persistent-history\history.sqlite3`
- Linux/macOS: `~/.burp-persistent-history/history.sqlite3`

Treat this database as sensitive. It may contain cookies, authorization headers, bearer tokens, personal data, and request/response bodies from your testing sessions.

## Requirements

- Current Burp Suite Community or Professional
- JDK 21
- Maven 3.9+

The extension currently targets Montoya API **2026.7**.

## Clone / update

First clone:

```powershell
git clone https://github.com/tobiasGuta/Persistent-History.git
cd Persistent-History
```

Later updates:

```powershell
git pull origin main
```

## Build

On Windows:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

Or directly with Maven:

```powershell
mvn clean verify
```

The loadable shaded JAR is:

```text
target/burp-persistent-history-1.0.2.jar
```

## Load into Burp

1. Open Burp Suite.
2. Go to **Extensions**.
3. Remove/disable an older Persistent HTTP History build if one is loaded.
4. Click **Add**.
5. Extension type: **Java**.
6. Select `target/burp-persistent-history-1.0.2.jar`.
7. Open the new **Persistent History** suite tab.

The extension should log:

```text
Persistent HTTP History v1.0.2 loaded
Database: C:\Users\<you>\.burp-persistent-history\history.sqlite3
```

## Search examples

```text
oauth
POST
403
Repeater
api.example.com/v1/users
```

Search remains intentionally simple in v1.

## Automated tests

`mvn verify` covers the persistence cases we want proven before feature work:

- same Montoya message ID is stored once;
- identical traffic with different message IDs is still stored as separate legitimate requests;
- Repeater provenance survives persistence;
- paused capture does not write records;
- binary request/response bytes survive SQLite exactly;
- an 8 MiB response survives SQLite exactly;
- table metadata queries do not materialize large BLOBs;
- the 5,000-row metadata path completes inside a generous CI timeout.

GitHub Actions runs the same test/package workflow on pushes and pull requests and uploads the shaded extension JAR as a workflow artifact.

## Manual Burp acceptance test

Automated tests cannot fully reproduce Burp's own UI runtime, so do this after each candidate build:

1. Load the extension.
2. Browse an authorized test target through Proxy.
3. Confirm one Proxy row appears.
4. Send one request through Repeater and confirm the row shows **Repeater**.
5. Select both rows and confirm the request and response use Burp's native editors.
6. Open **Pretty**, **Raw**, and **Hex** where applicable.
7. Fetch a large response and confirm Burp stays responsive while selecting other history rows.
8. Fetch a binary response and confirm its bytes can be viewed through the native editor/Hex view.
9. Generate repeated legitimate requests and verify each request remains represented.
10. Close Burp completely.
11. Reopen Burp and reload the extension.
12. Confirm the previous records are still present.

## Security model

The extension is passive with respect to target traffic. It observes completed HTTP exchanges and writes copies locally. A storage exception is contained so persistence failures do not prevent Burp from continuing the HTTP flow.

## SQLite schema

```sql
CREATE TABLE http_history (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    captured_at INTEGER NOT NULL,
    tool        TEXT NOT NULL,
    method      TEXT NOT NULL,
    url         TEXT NOT NULL,
    status      INTEGER NOT NULL,
    request     BLOB NOT NULL,
    response    BLOB
);
```

## Not in v1 yet

These are intentionally deferred until the persistence core is trustworthy:

- account-role tagging;
- notes/tags;
- per-program databases;
- retention policies;
- encryption at rest;
- WebSocket persistence;
- send historical request to Repeater;
- export workflows.

## Version notes

### v1.0.2

Hardens duplicate handling, Repeater provenance, large/binary response storage, and the 5,000-row UI data path. Replaces plain text viewers with Burp's native Montoya HTTP request/response editors.

### v1.0.1

Fixes SQLite initialization under Burp's isolated extension class loader by explicitly instantiating the Xerial SQLite JDBC driver. Maven Shade also preserves service-provider metadata.

### v1.0.0

Initial persistent SQLite-backed HTTP history implementation.
