# ModKit

ModKit is an Android-first workbench for **authorized analysis, reverse engineering and defensive validation of APK/APK-set targets**.

Current application version: **0.0.16**

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
- dedicated main-menu Root Process Lab that opens directly into installed/running app discovery, launches or attaches the selected target, and hands control to the MK live overlay; static gameplay-method discovery remains an optional expert tool;
- root gameplay discovery reuses the installed APK plus SHA-bound engine cache and targeted confirmation, so full process dumps are not required for ordinary mod discovery;
- root modification profiles can be saved directly to user-selected device storage as `.modkit.json` files or added directly to ModKit Sandbox; dump export also uses Android document storage instead of the share sheet;
- ModKit Sandbox has a root managed-profile backend: it provisions a separate Android profile, installs the already-present package for that profile without replacing user-0, and launches it with separate app-data/saves; profiles are version/SHA validated before launch;
- root sandbox activation resolves the exact sandbox PID and ELF PT_LOAD mapping, verifies original static/runtime bytes, pauses only that process while applying selected native-code patches, verifies read-back and rolls back already-applied patches if any profile item fails;
- full root snapshot mode walks every readable process mapping except unsafe kernel pseudo-mappings, performs disk-space preflight, streams in bounded batches, and keeps the old 256 MiB path only as an explicit quick mode;
- universal runtime artifact inventory inside root dumps detects live ELF, DEX/CompactDEX, IL2CPP metadata, WASM, SQLite, ZIP/APK/JAR and PE/CLI candidates for later runtime-specific parsing;
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


### Root managed-profile sandbox

The managed-profile sandbox remains available as a separate isolation path for already-confirmed static native-code modifications. It creates/uses a secondary Android profile, launches the game with separate app-data/saves, resolves the exact sandbox PID, applies SHA-bound native-code patches and exposes their switches through the `MK` overlay hosted by the already-root-authorized main ModKit process. Overlay switches apply or restore only validated byte ranges with PID revalidation and read-back verification.

The normal root workflow is the live behavioral scanner below; a static sandbox profile is no longer required just to attach, scan live values or train an action. Billing, authentication and anti-cheat-related methods remain discovery-only sensitive surfaces and are not converted into automatic bypass actions.


### Root live behavioral scanner

The primary root flow is now **Root → choose a running process or installed game → attach/launch → play with the `MK` overlay**. If the selected app is not running, ModKit launches it, waits for its exact main-process PID and attaches automatically.

The overlay contains three complementary workflows:

- **Auto scan** continuously compares bounded rolling live-memory baselines, rotates Int32/Float/Int64/Double sweeps, refreshes candidates with batched reads, scores repeatability/direction/stability and suppresses noisy stack/JIT/GPU-like mappings.
- **Train action** captures an explicit context such as movement, attack, taking damage, resource change, item change or another action; the user hides the overlay, performs that action, then reopens `MK` to rank correlated candidates.
- **Manual scan** supports exact values, unknown initial values, changed/unchanged/increased/decreased refinement, ranges (`10..20`), fuzzy values (`1.0~0.05`), grouped values (`10,20,30`), verified writes and Freeze.

High-confidence or explicitly confirmed values can be stabilized through a module-root pointer chain. Learned candidates are stored per package and artifact SHA, then their live addresses are re-resolved after restart/ASLR. After an app update, old pointer chains may be tested as migration candidates, but they remain read-only until explicitly reconfirmed on the new artifact. Every write revalidates the exact PID and writable mapping and requires read-back confirmation.

Static IL2CPP gameplay-method discovery remains available as an expert tool, but it is no longer the primary root workflow. Billing/authentication/anti-cheat surfaces stay analysis-only and are not turned into automatic bypass actions.


Root-mode audit and remaining architectural gaps are tracked in [docs/ROOT_MODE_AUDIT.md](docs/ROOT_MODE_AUDIT.md).


### Root live code-access tracing

On rooted ARM64 targets, a confirmed live value can now be traced with a bounded hardware watchpoint window. ModKit records native PCs that read/write the selected address, validates those PCs against executable mappings, decodes the ARM64 instruction, correlates the site with cached IL2CPP method evidence when possible, and exposes confirmed writer sites in the MK overlay. A selected writer can be temporarily blocked with a verified NOP toggle; the original instruction is restored on disable, overlay shutdown, process loss, or reattach. Saved code-sites are resolved by module + file offset and revalidated before reuse. The overlay also watches for target-process restarts and reattaches to a replacement main PID when the package/user identity remains unambiguous.

This code-access layer is currently implemented for ARM64 hardware watchpoints. Other ABIs keep the live value scanner, training, writes/Freeze, pointer-chain persistence and static analysis, but do not claim hardware watch tracing.


### Root / MK overlay UX v2

The root overlay has been redesigned around user-facing tasks rather than raw scanner controls. The home screen now exposes four primary actions: automatic mod discovery, action training, guided manual search and saved mods. Raw value types, unknown-value refinement controls, full-memory scans and range/fuzzy/group searches are moved into Expert tools.

Guided manual search uses automatic numeric type selection and a native root scanner for the initial exact-value pass. Integer values such as item counts are searched as integer types before Float/Double. The native scanner reads writable private process memory in large blocks, bounds the quick pass, and revalidates the exact target PID after scanning. The older Kotlin/root-shell scanner remains a bounded fallback.

Automatic behavioral discovery now requires repeated, stable evidence before surfacing candidates and suppresses obvious background/noise values. Repeated action training narrows candidates across rounds. Candidate cards open a dedicated page for editing, Freeze, code-access tracing and saving. Saved mods can be renamed, revalidated or deleted when a binding is wrong.


### Behavioral evidence v3

Behavioral discovery now distinguishes **preliminary**, **stable** and **confirmed** evidence. The first training round never presents raw memory changes as a mod. A second round must reproduce the same candidate; if too many survive, ModKit asks for a third round and requires three-way overlap before showing the shortlist. Large pointer-like integer artifacts and duplicate interpretations of the same address are suppressed from the normal UI and remain available only through Expert tools.

Auto discovery reports observation progress and the number of emerging repeated patterns instead of silently showing an empty result. A stable unknown pattern is labeled honestly as a repeated-action candidate; the user can optionally classify it as movement, attack, taking damage, resource change or another action. Semantic names such as movement speed or health are used only after stronger confirmation.

Behavioral findings are automatically added to **My mods** only when a live code trace confirms a writer for the selected value. Editing a value by itself no longer silently persists a binding. Non-confirmed candidates can still be saved explicitly if the user has independently verified their effect, and saved bindings can be renamed, revalidated or removed.
