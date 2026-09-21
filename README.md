# ModKit

ModKit is an Android-first workbench for **authorized analysis, reverse engineering and defensive validation of APK/APK-set targets**.

Current application version: **0.0.5**

## Product flow

```text
select app / APK
        ↓
fast analysis
        ↓
clear findings
        ↓
targeted confirmation only when needed
        ↓
runtime / root only when static evidence is insufficient
        ↓
prepare changes
        ↓
build / sign / verify
        ↓
APK
```

A separate **Expert Lab** will expose compatible engines directly for an APK, APK-set, installed application or individual file.

## Development rules

The canonical specification is [docs/TECHNICAL_SPEC_VNEXT.md](docs/TECHNICAL_SPEC_VNEXT.md).

Legacy UniRevLab components are **not bulk-copied**. Every migrated subsystem is reviewed using the rule:

> what exists → what is incomplete → what must be added → when it runs → what proves completion.

See [docs/PORTING_MATRIX.md](docs/PORTING_MATRIX.md) and [docs/ROADMAP.md](docs/ROADMAP.md).

## Current implemented baseline

- clean Android/Compose application;
- installed-app and file target selection;
- single-pass archive inventory;
- target SHA-256 binding;
- validated DEX / ELF / WASM / IL2CPP metadata probes;
- multi-label runtime fingerprinting;
- demand-driven engine routing plan;
- process-scoped analysis state;
- cancellation;
- heartbeat + stalled-state watchdog;
- interrupted-analysis recovery UI;
- Evidence Graph + ConfirmationQueue for fail-closed IL2CPP proof transitions;
- compact AutoMod / Patch Lab with automatic static confirmation during preparation;
- exact IL2CPP native mutation draft + internal mutation preflight;
- full-screen IL2CPP method inspector with reconstructed C#-like metadata view, ARM64 disassembly, CFG, direct callees and bounded reverse-caller scan;
- proven ARM64 scalar-return presets plus arbitrary Int64/Float/Double return-body generation with in-place boundary checks;
- root live-memory scanner for exact and unknown initial values, natural/byte alignment, changed/increased/decreased refinement, pointer scan, explicit verified writes and freeze;
- dedicated main-menu Root Process Lab: root probe, running app/game process picker, direct attach, bounded live process dump/export and memory tools without a prerequisite APK analysis;
- streaming staging APK/APK-set mutation with stale-signature removal;
- align → sign → verify → mutation-diff check → post-build re-analysis;
- human-readable build report and scoped APK/APK-set export;
- CI that tests, lints, builds, verifies zip container/alignment and publishes an APK artifact.

The deep engines are being migrated one at a time. A runtime being detected does **not** mean its deep backend is already complete.

## Build

CI uses Java 17, Gradle 9.5 and Android SDK 37.0.

ModKit's in-app verified build path currently signs generated test outputs with an app-private AndroidKeyStore identity. This is intentionally separate from a customer's production signing identity; replacing an already installed production APK requires a compatible owner-provided signing key.

```bash
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## Security / authorization

Use ModKit only for applications and environments you are authorized to assess. Do not publish customer APKs, secrets, access tokens, signing keys or proprietary analysis artifacts in public issues.
