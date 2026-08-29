# Persistent HTTP History for Burp Suite Community

Persistent HTTP History keeps a durable, searchable copy of Burp HTTP request/response traffic in SQLite so Community Edition users do not lose their research history when Burp closes.

Current version: **v2.1.1**

## v2.1: Burp-style workspace scope

V2.1 upgrades each managed workspace from simple target roots to structured scope rules.

Open:

```text
Persistent History -> Scope...
```

The scope editor provides separate **Include in workspace** and **Exclude from workspace** tables with:

- Enabled
- Protocol (`any`, `http`, `https`)
- Host / IP range
- Port
- File / path

Each table supports:

```text
Add
Edit
Remove
```

The scope window also supports:

```text
Import Burp JSON...
```

Use this with a JSON settings file saved from Burp's Target scope settings. The importer recognizes the normal Burp structure:

```json
{
  "target": {
    "scope": {
      "advanced_mode": true,
      "include": [],
      "exclude": []
    }
  }
}
```

It also accepts full Burp settings JSON files when the `target.scope` section is nested inside the document, and it converts normal-scope `prefix` entries into structured rules.

Importing only loads the rules into the editor. Review them and click **Save** before they become active.

### v2.1.1 UI fix

V2.1.1 removes Swing HTML helper-label markup from the workspace and scope dialogs. This prevents Burp themes that disable Swing HTML rendering from displaying literal `<html>` text. Scope rules, workspace databases, and stored history are unchanged.

## Scope behavior

For a managed scoped workspace, a URL is persisted only when:

1. it matches an enabled include rule; and
2. it does not match any enabled exclude rule.

Advanced fields follow Burp's scope model:

- Protocol matches HTTP, HTTPS, or either.
- Host supports regular expressions and common IPv4 CIDR / octet-range forms.
- Port is a regular expression.
- File is a regular expression against the URL path.
- Query strings are ignored for File matching.

An explicit **Unscoped: capture all targets** checkbox preserves the original capture-all workspace behavior. This is separate from a scoped workspace with zero include rules, which captures nothing.

Persistent History workspace scope does **not** modify Burp's own Target scope. This is intentional: Burp scope controls Burp; workspace scope controls what this extension writes to disk.

## Backward compatibility

No history database reset is required.

### v1 history

The original database remains available as:

```text
Legacy / Unscoped
```

at:

```text
~/.burp-persistent-history/history.sqlite3
```

### v2.0 workspaces

Existing v2.0 `targets=` roots are converted into structured include rules on first v2.1 load. Their existing SQLite databases are reused.

Each managed workspace now stores its scope beside its database:

```text
~/.burp-persistent-history/workspaces/<workspace-id>/
├── workspace.properties
├── scope.json
└── history.sqlite3
```

`scope.json` uses a Burp-compatible `target.scope` JSON shape.

## Core features

- Persistent full HTTP request and response bytes.
- Separate SQLite database per managed workspace.
- Search by URL, method, status, or Burp tool.
- Tool provenance such as **Proxy** and **Repeater**.
- Burp-native read-only HTTP request/response editors.
- Historical requests retain target metadata for **Send to Repeater**.
- Capture pause/resume.
- Clear only the selected workspace.
- Duplicate-callback protection using Montoya message IDs.
- Binary and large-response fidelity tests.
- Metadata-only table loading for the newest 5,000 records.
- Database work stays off Swing's event-dispatch thread.
- Existing v1 and v2.0 data remain available.

## Quick workspace setup

For a simple program you can still use target roots when creating the workspace:

```text
Workspace: Example Program

Quick target roots:
example.com
api.example.net
```

A root such as `example.com` includes `example.com` and its subdomains.

For more precise control, create the workspace and then use **Scope...**.

## Importing the same scope you use in Burp

A convenient workflow is:

```text
Burp Target -> Scope -> settings menu -> Save settings
                               |
                               v
                         scope-settings.json
                               |
                               v
Persistent History -> Scope... -> Import Burp JSON...
```

The two scopes remain independent after import. Changing one does not silently modify the other.

## Build

Requirements:

- Current Burp Suite Community or Professional
- JDK 21
- Maven 3.9+

The extension targets Montoya API **2026.7**.

Clone/update:

```powershell
git clone https://github.com/tobiasGuta/Persistent-History.git
cd Persistent-History
```

Later:

```powershell
git pull origin main
```

Build:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
```

or:

```powershell
mvn clean verify
```

Load:

```text
target/burp-persistent-history-2.1.1.jar
```

in:

```text
Burp -> Extensions -> Add -> Java
```

## Suggested v2.1 acceptance test

1. Load v2.1 and confirm your existing v1/v2 history is still present.
2. Create or select a managed workspace.
3. Open **Scope...**.
4. Confirm existing quick target roots appear as include rules.
5. Add an include rule and an exclude rule manually.
6. Generate matching and excluded traffic and confirm only the intended requests are stored.
7. Save a Target scope JSON file from Burp.
8. Use **Import Burp JSON...** in the workspace.
9. Review the imported Include/Exclude tables and click **Save**.
10. Generate one in-scope request and one unrelated request.
11. Confirm the unrelated request is skipped.
12. Restart Burp and confirm both workspace history and scope rules survive.

## Security note

The SQLite databases can contain authentication headers, cookies, tokens, personal data, and complete request/response bodies. Treat the `.burp-persistent-history` directory as sensitive.

Scope JSON is local configuration. Imports are limited to 5 MiB and invalid regexes are rejected before the scope can be saved.
