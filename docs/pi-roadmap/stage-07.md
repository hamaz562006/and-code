# Stage 07 — Pi metadata and recovery

This stage aligns Pi with the shared runtime metadata and recovery model.

## Changes

- Decode recorded agent IDs through one metadata helper, ignoring unknown future IDs safely.
- Reinstall from the recorded installed-agent set so Pi and other agents are preserved together.
- Keep the last known-good top-level metadata during reinstall; the installer already writes replacement metadata only after staging succeeds and the new environment is activated.
- If a reinstall fails before activation, the existing metadata remains available for normal recovery instead of making a still-valid active runtime appear uninstalled.

## Boundary

This stage does not change Pi session/provider behavior, setup UI flow, or runtime lifecycle. Those remain covered by later roadmap stages.
