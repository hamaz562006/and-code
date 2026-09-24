# stage-02

## Shared provisioning pipeline

Pi installation is provisioned only by `LocalRuntimeInstaller.install()`.

`PiRuntime` no longer exposes an independent `install()` entry point that could invoke `PiInstaller` outside the shared staging/activation pipeline.

The resulting boundary is:

Shared runtime provisioning → Pi prerequisites → `PiInstaller` → Pi verification → shared metadata → atomic activation.

This stage deliberately does not change Pi runtime/session execution.
