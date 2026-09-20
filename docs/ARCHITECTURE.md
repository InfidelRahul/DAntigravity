# DroidAntigravity Architecture

DroidAntigravity is a thin Android host for the official Google Antigravity CLI.

The project does **not** reimplement Antigravity authentication, conversations, agent execution, Remote Control UI, credentials, or session storage. The official `agy` CLI owns those capabilities. DroidAntigravity provides the Linux userspace in which `agy` runs, keeps that process alive with an Android foreground service, and exposes the official Remote Control URL through Android WebView or an external browser.

## Architecture

```text
┌─────────────────────────────────────────────────────────────┐
│                    DroidAntigravity                         │
│                                                             │
│  Android UI                                                 │
│  ├── MainActivity                                           │
│  ├── Android permissions / storage                          │
│  ├── WebView                                                 │
│  └── Browser/share actions                                  │
│                                                             │
│  Android lifecycle                                          │
│  └── LinuxRuntimeService (foreground service)               │
│                                                             │
│  RuntimeController                                          │
│  ├── RootfsInstaller                                        │
│  ├── PRootRuntime                                           │
│  └── AntigravityManager                                     │
│                                                             │
│  Linux / PRoot                                               │
│  └── Ubuntu ARM64 userspace                                  │
│       └── official `agy` CLI                                │
│             ├── Google authentication  ← owned by agy       │
│             ├── credentials/session       ← owned by agy     │
│             ├── conversations/agents     ← owned by agy      │
│             └── Remote Control            ← owned by agy     │
│                         │                                   │
│                         │ official URL                       │
│                         ▼                                   │
│                https://antigravity.google.com/r/...         │
│                         │                                   │
│              ┌──────────┴──────────┐                        │
│              ▼                     ▼                        │
│        Android WebView       External browser               │
└─────────────────────────────────────────────────────────────┘
```

## Responsibilities

### DroidAntigravity owns

- Android application lifecycle.
- Persistent ARM64 Linux/PRoot runtime.
- Rootfs installation and validation.
- Installation of the official `agy` CLI.
- Starting, monitoring, and explicitly stopping the `agy` process.
- PTY/stdout/stderr capture required to observe CLI state.
- Detecting and validating the official Remote Control URL.
- Loading that URL in Android WebView.
- Copying, sharing, or opening the URL in another Android browser.
- Android notifications, foreground-service lifecycle, file chooser and download integration.
- Diagnostics for Android/Linux/PRoot/process/WebView failures.

### Antigravity CLI owns

- Google authentication.
- Credential persistence and secure credential storage.
- `/logout`.
- Conversations and conversation history.
- Agent execution.
- Antigravity projects and settings.
- Agent permissions.
- Remote Control tunnel/session.
- Remote Control web application.

DroidAntigravity must not copy, inject, delete, or otherwise manage Antigravity authentication credentials.

## Startup flow

```text
User starts DroidAntigravity
        │
        ▼
Start foreground runtime service
        │
        ▼
Ensure rootfs
        │
        ▼
Start PRoot Linux userspace
        │
        ▼
Install official agy if missing
        │
        ▼
Start official agy with Remote Control
        │
        ├── existing official session → continue
        │
        └── authentication required → official agy auth flow
                                      (no app token UI)
        │
        ▼
Capture official Remote Control URL
        │
        ├── Android WebView
        └── Copy / Share / Open in browser
```

## Lifecycle

Interactive Remote Control is tied to the lifetime of the `agy` process. Therefore the Android foreground service is responsible for keeping the Linux userspace and CLI process alive while the app is backgrounded.

The Activity/WebView is a presentation layer. Destroying or recreating the Activity must not intentionally stop the Linux runtime.

The user-facing Stop action explicitly stops:

1. Antigravity CLI.
2. Linux/PRoot runtime.
3. Android foreground service.

## Authentication boundary

There is intentionally no DroidAntigravity OAuth implementation.

Do not add:

- OAuth token entry forms.
- App-managed Antigravity token files.
- Environment-variable token injection.
- Token provisioning/copying.
- Credential databases.
- Credential cleanup on startup.
- Custom Google sign-in screens.

If the official CLI changes how it authenticates, DroidAntigravity should continue to delegate authentication to it rather than reproducing the flow.

If Android-specific browser handoff is required by a future CLI authentication flow, implement only the minimum browser/intent bridge required to let the official CLI flow complete. Do not handle the resulting credentials.

## Remote Control URL boundary

The only Antigravity-specific web integration is:

```text
agy stdout/stderr
       │
       ▼
RemoteControlUrlParser
       │
       ▼
validated https://antigravity.google.com/r/...
       │
       ├── WebView
       └── Android browser/share
```

The application does not recreate or proxy the Remote Control web application.

## Security

- Only validated HTTPS Antigravity Remote Control URLs are loaded by the in-app WebView.
- OAuth/token material is not displayed, stored, logged, or exported by DroidAntigravity.
- Diagnostic sanitization remains enabled.
- Release signing credentials are supplied through build environment variables; no default passwords are embedded in Gradle configuration.
- WebView does not allow cleartext network traffic.
- Android's standard browser intents are used for external URLs.

## Foreground service

The runtime uses Android's `specialUse` foreground-service type because the core operation is a user-requested, long-running local Linux/CLI session rather than a bounded data synchronization task.

Android 15+ applies a six-hour background timeout to `dataSync` foreground services, so `dataSync` is not appropriate for an interactive Linux/Antigravity runtime that is intended to remain alive while the user works. The manifest documents the specific local-runtime use case.

## Testing priorities

Before release, verify on the target Android versions/devices:

1. Clean installation.
2. Rootfs creation and validation.
3. `agy --version`.
4. First-run official authentication.
5. Existing authenticated session reuse.
6. Remote Control URL generation.
7. WebView loading.
8. Copy/open/share URL.
9. Home/background/screen-lock lifecycle.
10. Activity recreation without killing `agy`.
11. Explicit Stop.
12. Process crash/recovery.
13. Network loss/reconnect.
14. Diagnostic export contains no credentials.

## Design rule

> If the official Antigravity CLI already provides a capability, DroidAntigravity consumes it instead of reimplementing it.
