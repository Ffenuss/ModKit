# Development roadmap

## Phase 0 — Clean foundation — v0.0.1
Status: **DONE**

- [x] New clean public Android project
- [x] Version 0.0.1
- [x] Canonical specification committed
- [x] Architecture rules
- [x] Engine contracts
- [x] Shared ArtifactIndex contract
- [x] Simple Mode shell
- [x] Expert Lab shell
- [x] Green CI on the new repository
- [x] Verified debug APK artifact

## Phase 1 — Fast analysis execution
Status: **ACTIVE**

- [x] Real single-pass APK/APK-set archive inventory
- [x] SHA-256 target binding and archive-entry inventory
- [x] Validated DEX / ELF / WASM / IL2CPP-metadata probes
- [x] Multi-label runtime fingerprinting
- [x] Demand-driven engine routing plan
- [x] Routed-engine scheduler execution
- [x] Incremental publication of deep-engine results
- [x] Heartbeat / watchdog / STALLED state
- [x] Responsive cancellation for current FAST work
- [x] Interrupted-run detection after process/device restart
- [x] Restart same file/package target from interrupted state
- [x] Persist/reuse completed migrated engine outputs after restart
- [x] Open completed partial results after restart without re-running the APK
- [x] Restore cached partial results off the UI thread
- [x] Content-addressed engine cache keyed by `artifactSHA + engineID + engineVersion`
- [x] Reuse cached ArtifactIndex before repeating archive-entry probes
- [x] Preserve already produced partial results when analysis is cancelled

Current cache coverage: `artifact.fast-index`, `il2cpp.fast-dump`, and `il2cpp.codegen-bind`. Every newly migrated engine must define and bump its own cache version when output semantics change.

## Phase 2 — IL2CPP fast path
- [x] Immediate validated global-metadata.dat + libil2cpp.so pair detection
- [x] Review legacy metadata parser and selectively migrate validated structures
- [x] Replace legacy whole-file assumptions with bounded/random-access reads
- [ ] Broaden metadata layout support beyond current v27-v31 without fake reconstruction
- [x] Human-readable dump before full audit completion
- [x] Initial CodeGenModule discovery via validated registration symbols or unique bounded metadata-image-set fallback
- [x] Exact MethodDef token RID → CodeGenModule slot → executable pointer binding for supported layouts
- [x] Explicit blocker reasons when binding cannot be proven

## Phase 3 — Exact binding / Evidence Graph 2
- [x] Proof-level enum defined
- [ ] Full Evidence Graph state machine across all engines
- [x] IL2CPP EXACT_METADATA → EXACT_BINARY proof transitions
- [x] Initial fail-closed blocker model for CHANGE_READY
- [x] Reject CHANGE_READY when required proof/preflight is missing
- [x] Current IL2CPP non-ready states expose an explicit next-transition blocker
- [x] Confirmation engines are launched only from the ConfirmationQueue
- [x] Exact source SHA is revalidated before Patch preparation
- [ ] SHA-bound binding invalidation across the full Evidence Graph
- [ ] Runtime-confirmed transition

## Phase 4 — AutoMod / Patch Lab
Status: **ACTIVE**

- [x] Compact unified AutoMod / Patch Lab screen
- [x] No Menu / Runtime intermediary
- [x] One-click Prepare changes entry point
- [x] Source SHA revalidation is internal to preparation
- [x] Fail-closed preparation plan with explicit blockers
- [x] CHANGE_READY gate for automatic application
- [x] Automatically rerun any still-needed static confirmation during preparation
- [x] Concrete mutation specification for the first exact IL2CPP native in-place executor
- [x] Internal mutation preflight with SHA/range/conflict/executor gates
- [x] Staging apply for supported mutation executors
- [x] One-click Build APK after verified staging
- [ ] Generic mutation selection/specification across the remaining backends

## Phase 5 — Verified build pipeline
Status: **CORE DONE FOR CURRENT MUTATION EXECUTORS**

- [x] Apply preflight-approved mutation plan to separate staging APK/APK-set
- [x] Rewrite every APK-set member and strip stale signatures before resigning
- [x] Pure-Java APK alignment with 16 KiB uncompressed native-library alignment checks
- [x] Sign with app-private AndroidKeyStore development identity
- [x] Verify APK signatures with Android apksig
- [x] PackageManager installability sanity verification
- [x] Mutation diff verification after signing
- [x] Re-analyze final signed APK/APK-set
- [x] Human-readable verified build report
- [x] Export final APK/APK-set and build report through a scoped FileProvider
- [ ] Customer/release signing identity selection/import

## Phase 6 — Expert Lab
- [ ] Direct backend execution
- [ ] Capability validation
- [ ] APK/APK-set/app/individual-file inputs
- [ ] Raw technical output separated from Simple Mode

## Phase 7 — Runtime escalation
- [ ] Repacked test runtime
- [ ] Non-root runtime where feasible
- [ ] Root only for unresolved runtime evidence
- [ ] RVA → runtime VA
- [ ] Feed runtime proof back into Evidence Graph

## Phase 8 — Non-ARM64 deep completion
- [ ] ARMv7 / Thumb-2
- [ ] x86
- [ ] x86-64
- [ ] Capstone only if Android packaging remains reproducible

## Phase 9 — Remaining deep backends
- [ ] .NET/CIL
- [ ] Unreal
- [ ] Godot
- [ ] Defold
- [ ] QML/JSC
- [ ] WASM instructions/CFG
- [ ] Deobfuscator depth
- [ ] Flutter/Hermes/Cocos depth
