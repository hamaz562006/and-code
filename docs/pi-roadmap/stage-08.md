# Stage 08 — Pi provider, session, and event integration

Align Pi's RPC adapter with the shared runtime event model.

## Changes

- Emit session-created and session-updated events for Pi session lifecycle operations.
- Map Pi's current RPC streaming events for text, reasoning, tool-call arguments, and tool execution into the existing And-Code event types.
- Treat `agent_settled` as the point at which the shared runtime can mark the session idle.
- Keep Pi provider/model discovery backed by Pi's `get_available_models` RPC response.

## Boundary

No changes to the shared event API or unrelated runtime adapters. Regression coverage is handled by Stage 9.
