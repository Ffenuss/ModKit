# ModKit

ModKit is an Android-first workbench for **authorized analysis, reverse engineering and defensive validation of APK/APK-set targets**.

Current application version: **0.0.16**

## Product flow

```text
choose game / application / APK file
        ↓
installed package list or file picker
        ↓
optional installed-package dump
        ↓
fast static analysis
        ↓
clear findings
        ↓
targeted confirmation only when needed
        ↓
prepare changes
        ↓
build / sign / verify
        ↓
APK
```

Version 0.0.16 removes the root-oriented user flow from ModKit. The main application no longer exposes Root Process Lab or the root sandbox, and the manifest no longer declares the floating root overlay services or their foreground/overlay permissions.

## Installed games and applications

The installed-package picker has two explicit modes:

- **Games** — packages classified by Android as games.
- **Applications** — ordinary non-game packages.

Both modes support search by label/package name and an optional system-package filter.

Each package has two independent actions:

- **Analyze** — starts the normal ModKit static-analysis pipeline.
- **Dump APK** — creates a ZIP containing the installed base APK, all split APKs, SHA-256 for each copied APK and a TSV inventory of every ZIP entry.

On Android 10 and newer, dumps are published to:

```text
Downloads/ModKit/
```

The dumper does not require root access.

## Current implemented baseline

- clean Android/Compose application;
- separate game/application target selection;
- installed-package APK/split dumper;
- file target selection;
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
- streaming staging APK/APK-set mutation with stale-signature removal;
- align → sign → verify → mutation-diff check → post-build re-analysis;
- human-readable build report and scoped APK/APK-set export;
- CI that tests, lints, builds, verifies the APK and publishes a build artifact.

A runtime being detected does **not** mean every deep mutation backend is complete.

## Build

CI uses Java 17, Gradle 9.5 and Android SDK 37.0.

ModKit's in-app verified build path signs generated test outputs with an app-private AndroidKeyStore identity. This is separate from a production signing identity; replacing an already installed production APK requires a compatible owner-provided signing key.

```bash
gradle :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

## Development rules

The canonical specification is [docs/TECHNICAL_SPEC_VNEXT.md](docs/TECHNICAL_SPEC_VNEXT.md).

Legacy UniRevLab components are not bulk-copied. Every migrated subsystem is reviewed using:

> what exists → what is incomplete → what must be added → when it runs → what proves completion.

See [docs/PORTING_MATRIX.md](docs/PORTING_MATRIX.md) and [docs/ROADMAP.md](docs/ROADMAP.md).

## Security / authorization

Use ModKit only for applications and environments you are authorized to assess. Do not publish customer APKs, secrets, access tokens, signing keys or proprietary analysis artifacts in public issues.
