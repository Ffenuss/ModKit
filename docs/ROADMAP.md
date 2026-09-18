# Development roadmap

## Phase 0 — Clean foundation — v0.0.1
Status: **IN PROGRESS**

- [x] New clean Android project
- [x] Version 0.0.1
- [x] Canonical specification
- [x] Architecture rules
- [x] Engine contracts
- [x] Shared ArtifactIndex contract
- [x] Simple Mode shell
- [x] Expert Lab shell
- [ ] Green CI on the new public repository

## Phase 1 — Fast analysis execution
- [ ] Real single-pass APK/APK-set ArtifactIndex
- [ ] SHA-256 and archive-entry inventory
- [ ] Runtime fingerprinting
- [ ] FAST/TARGETED/CONFIRMATION/BACKGROUND scheduler
- [ ] Incremental results
- [ ] Heartbeat/watchdog/STALLED
- [ ] Responsive cancellation
- [ ] Reboot recovery

## Phase 2 — IL2CPP fast path
- [ ] Immediate global-metadata.dat + libil2cpp.so pairing
- [ ] Review and selectively port legacy parser
- [ ] Complete missing metadata/codegen coverage before DONE
- [ ] Human-readable dump before full audit completion

## Phase 3 — Exact binding / Evidence Graph 2
- [ ] Formal proof levels
- [ ] CodeGenModule recovery
- [ ] Metadata ↔ binary executable proof
- [ ] Explicit blocker reasons
- [ ] Reject BLOCK + blockers=0
- [ ] SHA-bound binding invalidation

## Phase 4 — AutoMod / Patch Lab
- [ ] Compact unified screen
- [ ] No Menu / Runtime intermediary
- [ ] Internal Exact prepare / Preflight
- [ ] One-click Prepare changes
- [ ] One-click Build APK

## Phase 5 — Verified build pipeline
- [ ] Apply plan
- [ ] Rebuild
- [ ] Align
- [ ] Sign
- [ ] Verify
- [ ] Mutation diff

## Phase 6 — Expert Lab
- [ ] Direct backend execution
- [ ] Capability validation
- [ ] APK/APK-set/app/individual-file inputs

## Phase 7 — Runtime escalation
- [ ] Repacked test runtime
- [ ] Non-root runtime where feasible
- [ ] Root only for unresolved runtime evidence

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
- [ ] WASM
- [ ] Deobfuscator depth
- [ ] Flutter/Hermes/Cocos depth
