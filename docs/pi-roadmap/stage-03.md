# Stage 03 — Pi installer boundary

PiInstaller is responsible only for installing and verifying the Pi distribution inside the already-provisioned staged runtime.

## Boundary

- Runtime provisioning and shared Node.js/npm prerequisites stay in LocalRuntimeInstaller.
- PiInstaller installs the pinned `@earendil-works/pi-coding-agent` package from the npm registry.
- PiInstaller validates the required Node.js version and verifies `/usr/local/bin/pi`.
- PiInstaller does not provision the Linux runtime, create or activate runtime metadata, manage Pi sessions, or start the long-lived Pi runtime.
- The installer command uses shell-safe Node.js validation without nested single-quote conflicts.

Stage 03 does not change Pi runtime/session execution.
