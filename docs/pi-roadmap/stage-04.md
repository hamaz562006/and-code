# stage-04

refactor: align Pi runtime lifecycle with shared runtime

## Implemented

- Kept PiSandboxLauncher as a thin runtime-process primitive; it does not provision, install, or manage metadata.
- Moved Pi process creation into PiSandboxLauncher.start(...) so PiRuntime owns Pi RPC/session semantics rather than raw process construction.
- Moved Pi process termination into PiSandboxLauncher.stop(...).
- Pi termination now also cleans up the managed PRoot process tree using the existing shared runtime process-tree helpers.
- Preserved the shared InstalledRuntime command suite, rootfs, workspace binding, environment, and PRoot model.
- Removed direct ProcessBuilder lifecycle code from PiRuntime.
- PiTarget and setup/provisioning architecture are unchanged in this stage.

## Boundary after Stage 4

- LocalRuntimeInstaller: provisions and atomically activates the shared runtime and Pi distribution.
- PiInstaller: installs/verifies Pi only.
- PiSandboxLauncher: starts/stops an already-installed Pi process inside the shared runtime.
- PiRuntime: owns Pi RPC, sessions, messages, models, and event translation.
- PiTarget: remains the RuntimeTarget adapter.

## Validation

CI must be green before merge. Any CI failure must be diagnosed from the exact failed job log before changing code.
