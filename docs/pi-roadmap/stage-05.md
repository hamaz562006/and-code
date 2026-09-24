# stage-05

refactor: align Pi target with existing target architecture

## Implemented

- Kept PiTarget as a RuntimeTarget adapter only; provisioning, process lifecycle, RPC, and session implementation remain outside the target.
- Kept Pi registered through RuntimeRegistry.additionalTargets, the same registry path used by the other local agent targets.
- Reused the shared mergeWorkspaceRefs(...) helper for Pi workspace exposure instead of maintaining a Pi-specific workspace-to-target mapping.
- Kept Pi capabilities, state transitions, connection, session, provider/model, file, event, and abort operations delegated to PiRuntime.
- No Pi-specific bypass of RuntimeRegistry, RuntimeTarget, or shared runtime state was introduced.

## Boundary after Stage 5

- RuntimeRegistry: owns target discovery and selection.
- PiTarget: adapts the Pi runtime to RuntimeTarget.
- PiRuntime: owns Pi RPC/process/session behavior.
- LocalRuntimeInstaller: owns provisioning and installed state.

## Validation

CI must be green before merge. Any CI failure must be diagnosed from the exact failed job log before changing code.
