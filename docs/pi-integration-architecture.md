# Pi Integration Architecture Audit

## Scope

This document fixes the architectural boundary for the Pi integration before implementation changes continue.

The reference architecture is the original `yuga-hashimoto/and-code` runtime pipeline at the shared baseline commit:

`15b31e416522047aca5b3b010f514c6eab971e37`

Pi must be a selected local agent inside that pipeline. It must not introduce a second provisioning/runtime architecture.

## Required provisioning boundary

The shared provisioning flow remains:

1. Prepare the bundled PRoot.
2. Download and verify the shared Alpine runtime.
3. Extract and stage the runtime.
4. Install shared runtime packages.
5. Install requirements needed by the selected agent.
6. Run the selected agent installer.
7. Verify the installed agent.
8. Add the agent to runtime metadata.
9. Atomically activate the staging environment.

For Pi specifically:

`shared Alpine -> Node.js/npm prerequisites -> PiInstaller -> Pi verification -> metadata`

The Pi installer owns only Pi distribution installation and verification. It must not own creation of a second runtime, staging environment, or independent metadata system.

## Current Pi components and their intended boundaries

### PiInstaller

**Allowed responsibility**

- Verify the Pi prerequisites supplied by the shared runtime pipeline.
- Install the official Pi npm distribution into the staged Alpine rootfs.
- Verify `/usr/local/bin/pi` and `pi --version`.

**Not allowed**

- Creating a separate runtime.
- Creating or replacing shared staging/activation logic.
- Maintaining independent installed-agent metadata.
- Starting long-lived Pi sessions.

### PiRuntime

**Allowed responsibility**

- Run the already-installed Pi binary inside the shared runtime.
- Own Pi's RPC/session protocol adaptation.
- Translate Pi protocol messages/events into And-Code's runtime API.
- Manage Pi process/session lifecycle after provisioning.

**Not allowed**

- Provisioning the runtime independently of `LocalRuntimeInstaller`.
- Duplicating shared runtime installation or activation.
- Maintaining a second sandbox implementation when an existing shared runtime primitive can provide the same behavior.

### PiTarget

**Allowed responsibility**

- Adapt `PiRuntime` to the existing `RuntimeTarget` contract.
- Expose Pi capabilities, sessions, providers/models, files and events through that contract.

**Not allowed**

- Bypassing `RuntimeRegistry` or the existing target architecture.
- Reimplementing shared provisioning/state machinery.

### PiSandboxLauncher

This component is **not yet approved as an independent architecture boundary**.

Before retaining it, the implementation must demonstrate that an existing shared runtime command/process primitive cannot express Pi's required invocation. If it only assembles a PRoot command/environment that is common to the shared runtime, that logic belongs in a shared primitive rather than a Pi-specific launcher.

## State and metadata

Pi installation state must come from the existing `LocalRuntimeInstaller` / `LocalRuntimeMetadata.components` model.

A Pi-only installation must remain a valid shared-runtime installation. It must not require OpenCode to be installed merely to make the runtime metadata valid.

## Setup flow

Pi follows the existing setup flow:

`Step 1 selection -> Step 2 development tools -> one LocalRuntimeInstaller.install() -> Step 3 progress -> Step 4 authentication -> Step 5 completion`

Selecting multiple agents must provision them in one shared staging operation.

## Implementation order

1. Audit and simplify the existing Pi runtime/target/launcher boundaries.
2. Ensure Pi provisioning is entirely inside `LocalRuntimeInstaller`.
3. Reduce `PiInstaller` to distribution installation/verification.
4. Align Pi process execution with shared runtime primitives.
5. Align `PiTarget` with the existing target pattern.
6. Verify setup, metadata and recovery behavior.
7. Complete provider/model/session/event integration.
8. Add regression coverage and perform device-readiness validation.

Each step is implemented in a separate PR and must have green CI before the next step begins.
