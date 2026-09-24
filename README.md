# DroidAntigravity — Antigravity for Android

DroidAntigravity is a native Android host for the official Google Antigravity CLI (`agy`) running inside a persistent Ubuntu ARM64 Linux userspace powered by LinuxDroid PRoot.

The Android app does not recreate the Antigravity desktop UI. It provides the Linux runtime, process lifecycle, a real Linux PTY terminal, and a full-screen WebView for the official Antigravity Remote Control UI.

## Architecture

```text
Android
├── MainActivity
│   ├── Official Antigravity WebView
│   └── Touch-first macOS-inspired Terminal UI
│
├── RuntimeController
│   ├── Ubuntu rootfs
│   ├── PRoot runtime
│   ├── D-Bus + Secret Service readiness
│   └── Antigravity lifecycle
│
└── NativeSpawn
    ├── process groups
    ├── PTY creation
    ├── PTY read/write
    └── PTY resize

Ubuntu ARM64 / PRoot
├── configured Linux user
├── user's default login shell
├── nano / vim / htop / less / ssh / TUI applications
├── D-Bus session + GNOME Keyring Secret Service
└── official agy CLI
      └── official Remote Control reverse tunnel
             └── https://antigravity.google.com/r/...
```

## Real Linux terminal

The terminal is a real PTY-backed Linux terminal, not an Android command console.

- The active Linux user's configured shell is launched from `/etc/passwd`.
- Android does not maintain shell history, cwd, prompt, aliases, environment, pipes, redirection, or job control.
- The shell owns all shell semantics.
- The terminal emulator is libvterm-backed and handles ANSI/VT control sequences, alternate screens, cursor movement, colors, resize, scrolling and selection.
- Interactive programs such as `nano`, `vim`, `less`, `htop`, `ssh` and other TUI applications are intended to run normally.
- Keyboard and paste input are serialized through a PTY input queue so fast typing and multi-line paste retain ordering.
- Paste uses the terminal emulator's bracketed-paste-aware path when supported by the application.
- Progress output using carriage returns/control sequences updates the existing terminal line instead of being converted into repeated Android text lines.
- The terminal remains independently usable when Antigravity fails or its Remote Control connection is unavailable.

The terminal UI is deliberately touch-first and macOS-inspired: compact title-bar treatment, comfortable touch controls, long-press selection, pinch/scroll support, and an accessory row for common terminal keys.

## Antigravity boundary

DroidAntigravity delegates to the official `agy` CLI for:

- authentication
- credential persistence
- conversations
- agent execution
- project state
- Remote Control

The app does not implement a custom OAuth/token store.

The Linux userspace installs and verifies:

- `dbus`
- `dbus-user-session`
- `gnome-keyring`
- `libsecret-1-0`
- `libsecret-tools`

The Antigravity process is started inside a `dbus-run-session` with the Secret Service component of GNOME Keyring so the CLI and its keyring share the same session bus.

The official interactive Remote Control mode (`agy --remote-control`) is the reliable mode for the PRoot userspace. The official background daemon is capability-detected but is not blindly forced because its documented Linux implementation relies on a systemd user service, which is not provided by PRoot.

## Build

Requirements:

- JDK 21
- Android SDK API 36
- Android NDK `29.0.14206865`
- CMake 3.22.1+
- Git with submodule support

Debug/development build:

```bash
./build.sh debug
```

Release builds require explicit signing credentials:

```bash
export KEYSTORE_FILE=/path/to/release.keystore
export KEYSTORE_PASSWORD='...'
export KEY_ALIAS='...'
export KEY_PASSWORD='...'
./build.sh release
```

No default release keystore password is embedded in the source or CI configuration.

## Project structure

```text
android/
├── app/           Android UI, terminal integration, lifecycle
├── core/          App state, paths, common runtime models
├── runtime/       PRoot runtime and native PTY/process layer
├── rootfs/        Ubuntu ARM64 installation/configuration
├── antigravity/   Official agy installation, lifecycle and Remote Control
├── web/           Official Antigravity endpoint/WebView integration
└── diagnostics/   Runtime and failure diagnostics

proot-repo/        LinuxDroid PRoot submodule
docs/              Architecture documentation
build.sh           Local build entry point
```

## License

DroidAntigravity is Apache License 2.0. Third-party dependencies retain their respective licenses.
