# NXSync

NXSync synchronizes Eden emulator save directories and native Nintendo Switch
saves through one Google Drive file namespace. The repository is
split into independently buildable desktop, Android, sysmodule, and overlay
targets.

## Repository layout

```text
NXSync/
├── cmd/nxsync-desktop/          Wails desktop entry point and minimal UI
│   └── frontend/                Login, path, and live-status view
├── desktop/                     Desktop orchestration and title discovery
├── internal/
│   ├── drive/                   OAuth 2.0 + direct Google Drive v3 client
│   ├── edenconfig/              Cross-platform qt-config.ini parser
│   └── syncengine/              ZIP, SHA-256, conflict, pull, and watcher core
├── switch/
│   ├── sys-savesync/            Boot sysmodule (libnx/curl/mbedTLS/minizip)
│   └── overlay/                 Ultrahand/libtesla-compatible overlay
├── android/                     Compose + SAF + WorkManager application
└── docs/architecture.md         Data model, flows, and safety rules
```

## Desktop

The parser checks exactly these locations:

- Windows: `%APPDATA%\eden\config\qt-config.ini`
- macOS: `~/Library/Preferences/eden/qt-config.ini`
- Linux: `~/.config/eden/qt-config.ini` (or `$XDG_CONFIG_HOME`)

It reads `[Data Storage]`, accepts Eden/Qt spellings of `nand_directory` and
common custom save-directory keys, expands home/environment paths, and resolves:

```text
<nand_directory>/user/save/0000000000000000/<TITLE_ID>/
```

Release builds should embed a Google *Desktop app* OAuth client ID. This is a
Google platform requirement; the end user still gets a single Connect button
and never supplies keys:

```powershell
go build -ldflags "-X main.googleClientID=CLIENT_ID.apps.googleusercontent.com" `
  -o bin/nxsync.exe ./cmd/nxsync-desktop
```

For an unbranded local build, set `NXSYNC_GOOGLE_CLIENT_ID` before launching.

For development:

```text
go test ./...
wails dev -d ./cmd/nxsync-desktop
```

The Drive client requests only `drive.file`, stores refresh tokens with
user-only filesystem permissions, and attaches these `appProperties` to every
archive:

- `nxsync_sha256`
- `nxsync_source_modified`
- `nxsync_source_modified_unix`
- `nxsync_title_id`

An existing remote object is updated only when the SHA-256 differs **and** the
local source timestamp is newer.

## Nintendo Switch

Prerequisites are devkitA64/libnx plus Switch portlibs for curl, mbedTLS,
minizip, zlib, and libultrahand. Build each target with `make` from its folder.

Deploy:

```text
SD root/
├── atmosphere/contents/42000000004E5853/
│   ├── exefs.nsp
│   └── flags/boot2.flag
├── config/savesync/config.json
└── switch/.overlays/savesync.ovl
```

Copy `switch/sys-savesync/config.example.json` to
`/config/savesync/config.json`. The desktop token file supplies the refresh
token for the same Google Cloud project. Never commit this file.

Desktop token locations:

- Windows: `%APPDATA%\nxsync\token.json`
- macOS: `~/Library/Application Support/nxsync/token.json`
- Linux: `~/.config/nxsync/token.json`

The sysmodule listens to `pm:shell` process events. It records the application
PID and Title ID at start, detects the matching exit, and queues a read-only
save dump when the foreground Title ID becomes zero. Offline work remains
queued. Overlay restore requests are also queued, but are deliberately deferred
until the game exits so a live save is never mutated. Before a pull is applied,
the current console save is retained as
`/config/savesync/rollback-<TITLE_ID>.zip`; a failed restore is rolled back
automatically.

The NPDM permissions are a development baseline and should be minimized and
tested against every supported Atmosphère/Horizon release before distribution.

## Android

Open `android/` in Android Studio. The app uses:

- Jetpack Compose for the single-screen UI
- Google Identity Services `AuthorizationClient` with `drive.file`
- `ACTION_OPEN_DOCUMENT_TREE` with a persisted URI permission
- a connected-network `WorkManager` job

Configure an Android OAuth client for package `dev.nxsync.android` and the
signing certificate in the same Google Cloud project as the desktop client.

Android 11 and later prevent the Storage Access Framework from granting
`Android/data` access. If Eden keeps saves there, Eden must expose an exported
or user-selected custom save directory; choose that directory in NXSync.

## Current build requirements

- Go 1.22+, Wails 2.9+
- Android Studio with JDK 17+ and Android SDK 36
- devkitA64/libnx 4.12+ and the listed Switch portlibs

Platform SDKs are intentionally not vendored.

## Release bundle

Run `scripts/package-release.ps1` on Windows after building the two Switch
targets. It verifies the expected outputs and creates:

```text
dist/
├── NXSync-1.0.0.zip
├── SHA256SUMS.txt
└── NXSync-1.0.0/
    ├── Windows/NXSync.exe
    ├── Android/NXSync-debug.apk
    └── Switch SD Card/...
```

The checked bundle contains an installable debug-signed Android APK and a
desktop binary that accepts `NXSYNC_GOOGLE_CLIENT_ID`. A public release must
embed its own desktop OAuth client ID and sign the Android release variant with
the publisher's certificate; those credentials cannot be generated from the
source tree.
