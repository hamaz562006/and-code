# stage-06

refactor: align Pi setup flow with shared provisioning

This PR is a staged checkpoint in the Pi integration rebuild. Stage 6 aligns the onboarding flow with the shared one-pass LocalRuntimeInstaller provisioning path. Pi no longer starts a second Pi-specific setup pass from Step 3; Step 2 owns the single provisioning request and Step 3 only observes shared installation state and recovery.
