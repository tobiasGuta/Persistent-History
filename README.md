# Persistent HTTP History for Burp Suite Community

Persistent HTTP History keeps a durable, searchable copy of Burp HTTP request/response traffic in SQLite so Community Edition users do not lose their research history when Burp closes.

Current version: **v2.0.0**

## V2: isolated workspaces

V2 adds separate workspaces so unrelated targets do not need to share one history database.

Each managed workspace has:

- its own SQLite database;
- a human-readable program/workspace name;
- optional target-root rules;
- an independently clearable history;
- the same Burp-native request/response editors and Tool provenance from v1.

Example:

```text
Workspace: Example A
Targets: example-a.com
Database: ~/.burp-persistent-history/workspaces/example-a-<id>/history.sqlite3

Workspace: Example B
Targets: example-b.com
Database: ~/.burp-persistent-history/workspaces/example-b-<id>/history.sqlite3
```

A target root such as `example.com` matches both `example.com` and its subdomains such as `api.example.com`. Multiple roots can be entered for programs that use unrelated domains.

Traffic outside a scoped workspace's configured targets is **not persisted into that workspace**. The UI shows a skipped-outside-targets counter so accidental browsing of another target does not silently mix the histories.

## Existing v1 history

V2 is backward compatible with the existing v1 database:

```text
~/.burp-persistent-history/history.sqlite3
```

If it exists, V2 exposes it as:

```text
Legacy / Unscoped
```

No migration, deletion, or reset is required. The legacy workspace intentionally remains unscoped so the original v1 behavior and data are preserved. Create a new scoped workspace for target isolation.

## Core features

- Captures completed HTTP request/response pairs through Montoya `HttpHandler`.
- Stores complete raw request and response bytes as SQLite BLOBs.
- Persists across Burp restarts.
- Adds a **Persistent History** suite tab.
- Search by URL, method, status, or Burp tool.
- Preserves Burp Tool provenance such as **Proxy** and **Repeater**.
- Uses Burp's native read-only HTTP request/response editors.
- Restores HTTP target/service metadata so **Send to Repeater** knows host, port, and HTTP/HTTPS.
- Pause/resume capture.
- Clear only the currently selected workspace.
- SQLite WAL mode and busy timeout.
- Duplicate-callback protection using Montoya message IDs.
- Large/binary response byte-fidelity coverage.
- Metadata-only loading for the newest 5,000 table rows.
- Database work runs off Swing's event-dispatch thread.

## Workspace UI

The top of the **Persistent History** tab now contains:

```text
Workspace: [ Example A v ] [ New workspace ] [ Targets... ] Targets: example-a.com
Search:    [ ............................................................. ]
```

### Create a workspace

Click **New workspace** and enter:

```text
Workspace / program name:
Example A

Target roots:
example-a.com
api.partner-example.net
```

Target input accepts one item per line or comma-separated values. `*.example.com` is normalized to `example.com`, and an HTTP/HTTPS URL is normalized to its hostname.

Leaving target roots empty creates an intentionally unscoped workspace that captures all HTTP targets.

### Switch workspaces

Selecting another workspace changes both the history being displayed and the database receiving new captured traffic.

The selected workspace is remembered across extension/Burp restarts.

### Edit target roots

Click **Targets...** to modify the active managed workspace's target roots. The v1 **Legacy / Unscoped** database cannot be scoped in place; create a managed workspace instead.

## Storage layout

Default root:

- Windows: `%USERPROFILE%\.burp-persistent-history\`
- Linux/macOS: `~/.burp-persistent-history/`

Layout after creating workspaces:

```text
.burp-persistent-history/
├── history.sqlite3                  # existing v1 history, if present
├── active-workspace.txt
└── workspaces/
    ├── example-a-a1b2c3d4/
    │   ├── workspace.properties
    │   └── history.sqlite3
    └── example-b-e5f6a7b8/
        ├── workspace.properties
        └── history.sqlite3
```

Treat these databases as sensitive. They may contain cookies, authorization headers, bearer tokens, personal data, and request/response bodies from testing sessions.

## Requirements

- Current Burp Suite Community or Professional
- JDK 21
- Maven 3.9+

The extension targets Montoya API **2026.7**.

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

Windows:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

Or:

```powershell
mvn clean verify
```

Loadable JAR:

```text
target/burp-persistent-history-2.0.0.jar
```

## Load into Burp

1. Remove/disable an older Persistent HTTP History build.
2. Go to **Extensions -> Add**.
3. Choose **Java**.
4. Select `target/burp-persistent-history-2.0.0.jar`.
5. Open **Persistent History**.

Expected output includes:

```text
Persistent HTTP History v2.0.0 loaded
Storage root: C:\Users\<you>\.burp-persistent-history
Active workspace: Legacy / Unscoped
```

The active workspace can differ if a managed workspace was selected previously.

## Automated coverage

`mvn verify` covers the v1 persistence core plus V2 isolation behavior, including:

- same Montoya message ID stored once;
- legitimate repeated requests kept separately;
- Repeater Tool provenance preserved;
- paused capture writes nothing;
- scoped workspace rejects unrelated targets;
- root-domain rules permit subdomains but not lookalike domains;
- two program workspaces persist into different SQLite databases;
- active workspace selection survives restart;
- the existing v1 database appears as **Legacy / Unscoped** without migration;
- binary and large responses round-trip byte-for-byte;
- metadata queries avoid materializing message BLOBs.

## Manual V2 acceptance test

1. Load v2.0.0 and confirm your old v1 rows are visible under **Legacy / Unscoped**.
2. Create workspace `Example A` with target `example-a.test` or another authorized test target.
3. Send normal Proxy traffic to that target and confirm it appears.
4. Send a request to an unrelated test hostname and confirm it does **not** appear; the skipped counter should increment after refresh.
5. Create `Example B` with its own target root.
6. Switch to Example B and generate traffic there.
7. Switch between Example A and Example B and confirm each history remains isolated.
8. Send an old persisted request to Repeater and confirm Burp does not ask for target details.
9. Restart Burp, reload the extension, and confirm the previously selected workspace and both databases remain intact.

## Version notes

### v2.0.0

Adds persistent per-program workspaces, separate SQLite databases, target-root scoping, workspace switching, target editing, skipped-outside-target visibility, current-workspace clearing, active-workspace persistence, and backward-compatible access to the v1 database as **Legacy / Unscoped**.

### v1.0.3

Restores Burp `HttpService` metadata from persisted absolute URLs so native **Send to Repeater** actions retain host, port, and HTTP/HTTPS target details.

### v1.0.2

Hardens duplicate handling, Repeater provenance, large/binary response storage, and the 5,000-row UI data path. Replaces plain text viewers with Burp's native Montoya HTTP request/response editors.

### v1.0.1

Fixes SQLite initialization under Burp's isolated extension class loader by explicitly instantiating the Xerial SQLite JDBC driver.

### v1.0.0

Initial persistent SQLite-backed HTTP history implementation.
