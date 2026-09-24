# DroidAntigravity Architecture

DroidAntigravity is a thin Android host for the official Google Antigravity CLI.

## Core boundary

```text
Android UI
   │
   ├── Official Antigravity WebView
   │
   └── Real Linux Terminal UI
          │
          ▼
        PTY
          │
          ▼
Ubuntu ARM64 / PRoot
   │
   ├── user's configured default shell
   ├── D-Bus session
   ├── Secret Service / GNOME Keyring
   └── official agy CLI
          │
          └── official Remote Control reverse tunnel
```

Android provides presentation, lifecycle and transport. Linux owns execution. `agy` owns Antigravity.

## Real terminal architecture

The terminal must never execute commands one-by-one from Android.

```text
Keyboard / IME / paste
          │
          ▼
   Terminal emulator
          │
          ▼
     serialized PTY input
          │
          ▼
 Linux user's configured shell
```

The shell owns:

- current working directory
- history
- prompt
- environment
- aliases/functions
- shell parsing
- pipes/redirection
- job control
- interactive programs

The Android side owns only:

- PTY transport
- VT/xterm terminal emulation
- rendering
- touch selection/scroll/zoom
- clipboard
- mobile accessory controls
- PTY resize

The terminal uses ConnectBot's Apache-2.0 `termlib` component backed by libvterm for terminal emulation. It provides proper alternate-screen handling, ANSI/VT processing, cursor state, selection, IME input and resize behavior.

### Default shell

The Android application does not hardcode Bash or Zsh. PRoot launches a small POSIX bootstrap shell which reads the active `user` account's shell field from `/etc/passwd`, validates it, and `exec`s that shell. The resulting shell is attached directly to the PTY.

### Input ordering

Terminal emulator keyboard/IME callbacks and explicit paste are serialized through a FIFO PTY writer. This prevents concurrent writes from reordering fast typing or multi-line paste.

### Paste

Paste is sent through the terminal emulator's paste API so bracketed-paste mode can be honored by applications that support it. Android never splits pasted text into shell commands.

### Progress output

The emulator receives raw PTY bytes. Carriage returns, erase sequences, cursor movement and alternate-screen operations are interpreted by the terminal emulator. Android does not implement an application-specific percentage parser.

Therefore a process that emits progress updates on one terminal line remains on one line.

## Linux security/session services

The rootfs contains:

```text
dbus
dbus-user-session
gnome-keyring
libsecret-1-0
libsecret-tools
```

`LinuxSecurityServices` verifies both package availability and real Secret Service IPC by starting a temporary `dbus-run-session`, starting `gnome-keyring-daemon --components=secrets`, and calling the `org.freedesktop.secrets` D-Bus interface.

The actual `agy` process runs in its own `dbus-run-session` with the Secret Service daemon started in that same session. Credentials remain owned by the official CLI/keyring stack.

## Antigravity lifecycle

```text
Rootfs
  ↓
PRoot Linux
  ↓
D-Bus + Secret Service
  ↓
agy installed/verified
  ↓
capability detection
  ↓
official authentication
  ↓
agy --remote-control
  ↓
official Remote Control URL
  ↓
WebView
```

The official background daemon capability is detected. It is not automatically selected inside PRoot because the documented Linux daemon registers a systemd user service. The current runtime uses the official interactive Remote Control mode as the PRoot-compatible path.

## Endpoint abstraction

The WebView accepts an `AntigravityEndpoint`:

```text
REMOTE_CONTROL
LOCAL
CODESPACE
```

The primary endpoint is the official HTTPS Remote Control URL. Local HTTP is accepted only for loopback hosts (`localhost` / `127.0.0.1`), allowing a documented future local endpoint without weakening WebView cleartext policy globally. Codespace endpoints can use HTTPS without requiring WebView changes.

## Failure isolation

The Linux terminal is independent of Antigravity:

```text
Linux READY
 ├── Terminal READY
 └── Antigravity STARTING

Antigravity FAILED
 ├── Linux READY
 └── Terminal READY

Remote Control FAILED
 ├── Linux READY
 └── Terminal READY
```

Restarting Antigravity must not unnecessarily destroy the Linux terminal session. Restarting Linux is a separate operation.

## Security boundary

DroidAntigravity does not:

- implement a custom OAuth/token database;
- copy Antigravity credentials into Android storage;
- recreate the Antigravity Remote Control UI;
- silently inject trusted workspace paths;
- use an undocumented localhost port as its primary architecture;
- embed release signing passwords.

## Production signing

The Android source accepts release signing values only from explicit environment variables. `build.sh` refuses a release build when credentials are absent. The existing CI workflow is intentionally unchanged by the migration.
