# Codex local runtime

Support for OpenAI's Codex CLI, run inside the same shared Alpine/PRoot sandbox as OpenCode and
Claude Code (`CodexRuntime`, `CodexTarget`, `CodexInstaller`, `CodexSandboxLauncher`,
`CodexJsonRpcClient`, `CodexItemParser`, `CodexModels`).

## Status: backend only, not registered in the running app yet

This adds a real, unit-tested `RuntimeTarget` implementation (`CodexTarget`/`CodexRuntime`) and wires
it through DI, but **deliberately does not add `codexTarget` to `RuntimeRegistry`'s
`additionalTargets`** in either `AndCodeApplication.kt` or `di/AppModule.kt` yet. `RuntimeRegistry.
targets` is the one list every target picker in the app reads from unconditionally - the Workspaces
screen, the chat model/runtime picker sheet, and the drawer's agent switcher all offer whatever it
contains for the user to select, with no per-agent "is this actually usable" gate of their own. An
earlier draft of this change registered the target and then tried to patch each of those call sites
individually to hide it; that approach missed two of the three (the picker sheet and the drawer) and
was abandoned as fragile. Withholding registration at the one shared source fixes all three at once,
and is simpler to reason about than three independent filters that all have to agree.

This also means: no install button, no onboarding step, no settings screen exist for Codex.
`AndroidSetupScreen.kt` and `AgentSettingsScreen.kt` hardcode each agent at many call sites (selection
state, per-agent toggle rows, step tracking, install dispatch, sign-in state), and Claude Code's and
Antigravity's own screens are 300+ line dedicated files each. Building that surface, and then
registering `codexTarget` once it exists, is a second, independently reviewable change, not folded
into this one. `CodexRuntime.install(abi)` has no caller outside tests today.

A couple of `LocalAgent.CODEX` branches remain in `WorkspacesScreen.kt` and
`AndroidSetupScreen.kt` purely because Kotlin requires an exhaustive `when` over every `LocalAgent`
value regardless of what is actually registered; they are unreachable until Codex is registered and
are commented as such at each site.

Follow-up work: register `codexTarget` in both `RuntimeRegistry` construction sites, an install/status
card (`CodexCard.kt`, modelled on `ClaudeCodeCard.kt` but much shorter - no OAuth state machine), a
`CodexAgentSettingsScreen` entry in `AgentSettingsScreen.kt`, and an onboarding toggle in
`AndroidSetupScreen.kt`.

None of this was tested on an Android device or emulator - the environment this was written in has
no Android SDK, no `/dev/kvm`, and no hardware virtualization, so an emulator cannot run there at all.
Everything below marked "verified" was checked by running the real `codex` binary as a plain Linux
process and driving its JSON-RPC protocol directly; everything else is derived from the protocol's
own published JSON Schema and marked as such. Device acceptance (install, sign-in, a real turn,
approval prompts, abort, session switching) is still required before this is trusted, the same bar
`ANTIGRAVITY.md` sets for that integration.

## Why this is more tractable than Antigravity's integration

Antigravity's CLI is a full-screen terminal program with no scriptable protocol, so that integration
drives it through a PTY and scrapes `--output-format stream-json`. Codex is different in two ways
that matter here:

- **libc**: its native binary targets `aarch64-unknown-linux-musl` / `x86_64-unknown-linux-musl` -
  the same musl this app's shared Alpine rootfs is already built on. Unlike Antigravity (glibc,
  needs `gcompat` and its own Debian-based rootfs), Codex runs directly in the rootfs OpenCode and
  Claude Code already share. No second rootfs is provisioned for it.
- **Protocol**: `codex app-server` speaks a genuine JSON-RPC 2.0 protocol over stdio (newline-
  delimited JSON, no `Content-Length` framing) with a documented schema
  (`codex app-server generate-json-schema`) covering session (`thread/*`), turn (`turn/*`) and item
  (`item/*`) lifecycles, plus server-initiated approval requests. This is much closer to how this app
  already talks to a *remote* OpenCode server than to Antigravity's TUI-scraping approach - one
  long-lived process multiplexes every open thread, instead of one process per chat.

## Distribution

Codex has no Alpine package (unlike Claude Code, installed via `apk add`) and no GitHub release
archive (unlike Antigravity). It ships as the npm package `@openai/codex`, whose native binary lives
in a per-platform *optional dependency* published under the same package name at a synthetic version
(`<version>-linux-arm64`, `<version>-linux-x64`) - confirmed by hand:

```
$ npm view @openai/codex versions        # includes 0.155.1-linux-arm64, 0.155.1-linux-x64, ...
$ npm view @openai/codex@0.155.1-linux-x64 dist
{ tarball: "https://registry.npmjs.org/@openai/codex/-/codex-0.155.1-linux-x64.tgz",
  integrity: "sha512-...", shasum: "...", ... }
```

`CodexReleaseClient` resolves `@openai/codex/latest` for the current version, then fetches
`@openai/codex/<version>-<platform>` for that platform's tarball URL and its `integrity` (an SRI
SHA-512 string npm itself records). `CodexInstaller` downloads that tarball, verifies it against the
SHA-512 (the same "trust the official channel's own recorded digest over HTTPS" principle
`AntigravityReleaseClient` uses for GitHub's SHA-256, just a different registry and a stronger hash),
and extracts only `package/vendor/<target-triple>/bin/codex` - confirmed to run correctly fully
isolated from its sibling `codex-resources`/`codex-path` directories (voice runtime, bundled
`bwrap`, bundled `ripgrep`), none of which this app uses. The full tarball is 100+ MB and mostly
those unused resources; only the ~250 MB *uncompressed* binary itself is installed.

## Sandboxing

Codex defaults to running model-issued shell commands inside its own bundled `bwrap` (bubblewrap)
sandbox, layered *inside* this app's own PRoot jail. Bubblewrap needs unprivileged Linux user
namespaces, which is very unlikely to work nested inside PRoot on Android (PRoot does not provide
real namespace isolation, and Android kernels commonly restrict `CLONE_NEWUSER` for unprivileged
processes) - this was not tested on-device, but running the real binary in this session's own
container (which also lacks a working bubblewrap setup) reproduced exactly the failure mode expected:
Codex logs `Codex could not find bubblewrap on PATH` and falls back to a bundled one; that fallback's
actual sandboxing was not verified to succeed under nested confinement.

`CodexSandboxLauncher` passes `-c sandbox_mode="danger-full-access"` **before** the `app-server`
subcommand (verified: after the subcommand, `codex` does not error but the flag's actual effect
there was not re-verified) to disable Codex's own inner sandbox and rely solely on the outer PRoot
jail - the same posture Claude Code and Antigravity already run under in this app. This does not
reduce containment versus the status quo; it removes a redundant, and inside PRoot likely
non-functional, second sandboxing layer.

## Protocol notes (verified against a live, unauthenticated process)

- Framing is one JSON object per line (NDJSON) on stdin/stdout; there is no `initialize` version
  negotiation.
- A line with `method` **and** `id` is a server-initiated request expecting a reply (an approval
  prompt); a line with `method` and no `id` is a notification; a bare `id` is a response to a call
  this app made. Getting this dispatch wrong (treating every `id`-bearing line as "our" response) was
  an actual bug caught while writing `CodexJsonRpcClientTest` - a real approval request would
  otherwise have been silently dropped.
- `thread/start`, `thread/list`, `turn/start`, `turn/interrupt`, `model/list` and `account/read` all
  work without being signed in (`account/read` reports `{"account": null, "requiresOpenaiAuth":
  true}`). A `turn/start` without credentials streams `item/started`/`item/completed` for the user
  message, then repeated `error` notifications shaped like `{"willRetry": true, "error": {"message":
  "Reconnecting... N/5", "codexErrorInfo": {"responseStreamDisconnected": {"httpStatusCode": 401}}}}`
  - `CodexItemParser` treats a `willRetry: true` error as informational only, ending the turn (and
    the retry loop's terminal `willRetry: false` error, if it's ever reached) rather than every
    individual reconnect attempt.
- `codex login --with-api-key` (reading the key from stdin, confirmed by `codex login --help`) is
  what `CodexRuntime.loginWithApiKey` uses. Codex also supports a ChatGPT browser sign-in
  (`account/login/start`), which is **not implemented**: it is a device/browser round trip this
  session had no way to drive end-to-end, and guessing its flow would be worse than not offering it -
  `CodexTarget.authorizeProvider` throws explicitly rather than silently no-op'ing.
- Turn/item shapes not covered by a live, authenticated run (an actual `agentMessage`,
  `commandExecution`, `fileChange`, an approval prompt's exact resolution) are mapped from the
  protocol's own JSON Schema instead and marked so in code comments and test names. `CodexItemParser`
  falls back to a generic "tool" part carrying the raw item JSON for any item type it does not have a
  dedicated mapping for, so an unrecognized shape surfaces in the UI instead of disappearing.
- Attachments (images) are not sent on `turn/start` - the `UserInput` content type for an image was
  not exercised against a live account, and guessing the wrong shape would silently corrupt the
  request. Text-only turns only, for now.

## Testing

`CodexJsonRpcClientTest`, `CodexItemParserTest` and `CodexModelsTest` use JSON fixtures taken
verbatim from a live, unauthenticated `codex app-server` process where possible, and from the
protocol's own JSON Schema (with a note in the test) where a fixture required a signed-in account.
`CodexInstallerTest` builds small synthetic tarballs matching the real npm tarball's directory layout
to exercise extraction and SHA-512 verification without downloading the real ~140 MB archive in CI.
