# Migration Cleanup — Official Antigravity CLI Boundary

This revision removes DroidAntigravity-owned Antigravity OAuth/token provisioning and keeps the integration intentionally thin.

## Removed

- Manual OAuth token entry UI.
- App-side token provisioning/copying.
- `ANTIGRAVITY_OAUTH_TOKEN` / `AGY_OAUTH_TOKEN` injection.
- Token-file creation under the Android app data directory.
- Startup token validation.
- `--dangerously-skip-permissions` from the default launch command.
- Automatic onboarding-file mutation.

## Kept

- Persistent Linux/PRoot runtime.
- PTY process spawning and process-group lifecycle.
- Official `agy` installation/version checks.
- Workspace trust compatibility handling where required by the non-interactive Android host.
- Official Remote Control URL parsing and validation.
- Android WebView.
- Standard Android external-browser URL handoff.
- Foreground-service lifecycle and diagnostics.

## Authentication rule

DroidAntigravity never asks the user for an Antigravity OAuth token and never manages the resulting credentials. The official `agy` CLI owns authentication, credential persistence, logout, and session state.

## Validation

The source tree was statically checked after migration for the removed token-provisioning symbols and launch flag. They are absent.

A full Gradle test/build was attempted, but this environment could not download the configured Gradle 9.7.1 distribution because outbound access to `services.gradle.org` was unavailable. The project therefore has not been represented as successfully compiled in this environment.
