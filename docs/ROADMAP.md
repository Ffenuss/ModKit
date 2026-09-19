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

Current cache coverage: `artifact.fast-index`, `elf.universal-inventory`, `il2cpp.fast-dump`, `il2cpp.codegen-bind`, and `runtime.evidence`. Every newly migrated engine must define and bump its own cache version when output semantics change.

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
- [x] All detected runtime profiles enter the central graph at DISCOVERED without fake exact proof
- [x] IL2CPP EXACT_METADATA → EXACT_BINARY proof transitions
- [x] Initial fail-closed blocker model for CHANGE_READY
- [x] Reject CHANGE_READY when required proof/preflight is missing
- [x] Current IL2CPP non-ready states expose an explicit next-transition blocker
- [x] Confirmation engines are launched only from the ConfirmationQueue
- [x] Exact source SHA is revalidated before Patch preparation
- [ ] SHA-bound binding invalidation across the full Evidence Graph
- [x] Runtime-confirmed transition for independently confirmed exact runtime addresses

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
Status: **CORE ACTIVE**

- [x] Direct execution of an individually selected routed backend
- [x] Router/executor capability consistency validation
- [x] APK/APK-set/installed-app/individual-file inputs
- [x] Raw technical output separated from Simple Mode
- [x] Scoped technical report export
- [ ] Direct execution coverage for newly migrated non-IL2CPP backends
- [ ] Search/filtering for large target and backend inventories

## Phase 7 — Runtime escalation
Status: **ACTIVE**

- [x] Runtime escalation planner: static → repacked test → non-root → root-last-resort
- [x] Bounded imported/non-root `/proc/<pid>/maps` capture primitives
- [x] Fail-closed module mapping proof from `PT_LOAD + file offset + device/inode + executable mapping`
- [x] RVA → runtime VA for targets that already have exact binary proof
- [x] Feed confirmed runtime address proof back into Evidence Graph without promoting to CHANGE_READY
- [x] Expert Lab manual process-maps mapping diagnostics and technical-report export
- [x] Typed runtime evidence contract separating process/module/mapping/address/execution/field/JNI-dlsym observations
- [x] SHA-bound runtime evidence persistence and fail-closed snapshot restoration
- [x] Versioned Expert Lab runtime report with explicit capture provenance and unresolved reasons
- [x] Fail-closed repacked-test runtime architecture with explicit executor capability gates
- [x] Verified read-only source-copy workspace with SHA revalidation and scoped cleanup
- [x] Non-root installed-app process discovery with exact `/proc/<pid>/cmdline` identity proof
- [x] PID-reuse guard by rechecking process identity before and after bounded maps capture
- [x] Automatic non-root maps capture/integration when the main process is uniquely confirmed and readable
- [ ] Automatic in-process capture from a transparent repacked test runtime
- [ ] Runtime memory-ELF validation for stripped/relocated modules
- [ ] JNI/dlsym runtime confirmation where static evidence is insufficient
- [ ] Root runtime only for evidence that remains unresolved after non-root stages

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
