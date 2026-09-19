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
- [ ] SHA-bound binding invalidation across the full Evidence Graph
- [ ] Runtime-confirmed transition

## Phase 4 — AutoMod / Patch Lab
- [ ] Compact unified screen
- [ ] No Menu / Runtime intermediary
- [ ] Internal Exact prepare / Preflight
- [ ] One-click Prepare changes
- [ ] CHANGE_READY gate for automatic application
- [ ] One-click Build APK

## Phase 5 — Verified build pipeline
- [ ] Apply plan
- [ ] Rebuild
- [ ] Align
- [ ] Sign
- [ ] Verify
- [ ] Mutation diff
- [ ] Re-analyze built APK

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
