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
- [x] Bounded DEX header/table inventory without whole-entry extraction

Current cache coverage: `artifact.fast-index`, `dex.inventory`, `elf.universal-inventory`, `il2cpp.fast-dump`, `il2cpp.codegen-bind`, `runtime.evidence`, and `runtime.stage-attempts`. Every newly migrated engine must define and bump its own cache version when output semantics change.

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
- [x] Full-screen IL2CPP method inspector with reconstructed metadata view and ARM64 disassembly
- [x] Bounded AArch64 CFG + direct callee resolution + reverse direct-caller scan for proven IL2CPP method spans
- [x] Proven scalar-return presets and arbitrary Int64/Float/Double ARM64 return-body generation with in-place method-boundary checks
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
- [x] Direct execution coverage for currently migrated non-IL2CPP backends (bounded DEX inventory + universal ELF inventory)
- [x] Search/filtering for large target and backend inventories

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
- [x] Bounded binary AndroidManifest identity parser with exact package/application extraction
- [x] Verified APK-set manifest inventory with one-base/unique-split/package consistency gates
- [x] Repacked build preflight is bound to the verified manifest inventory and source relationships
- [x] Copy-only binary AndroidManifest rewrite executor for the verified base APK
- [x] Manifest probe declaration round-trip preserves package/application identity and split APK bytes
- [x] Executable runtime-probe DEX payload is generated and structurally validated in the ModKit build
- [x] Copy-only probe DEX injector selects a free multidex slot and preserves split APK bytes
- [x] Probe injection is bound to the verified manifest declaration and exact payload SHA
- [x] Repacked instrumentation coordinator connects copy → manifest proof/rewrite → probe injection → build preflight
- [x] Repacked test build tail reuses real signature-strip/alignment/signing/package-verification stages
- [x] Repacked test build report records signer, alignment, signature and final APK SHA
- [x] Signed installed-test identity verification: package/provider/authority/signer before activation
- [x] Provider activation performs test-process launch and bounded `/proc/self/maps` evidence capture
- [x] PackageInstaller session revalidates staged APK SHA/signers immediately before install
- [x] Unknown-source permission and incompatible installed-signature flows stay explicit and user-confirmed
- [x] Repacked capture feeds Evidence Graph and persisted stage-attempt history
- [x] In-app PackageInstaller handoff for signed test APK/APK-set with user confirmation and signature-conflict handling
- [x] Non-root installed-app process discovery with exact `/proc/<pid>/cmdline` identity proof
- [x] PID-reuse guard by rechecking process identity before and after bounded maps capture
- [x] Automatic non-root maps capture/integration when the main process is uniquely confirmed and readable
- [x] SHA-bound bounded runtime-stage attempt ledger restored into Expert Lab sessions
- [x] Non-root success/blocker attempts feed root-last-resort decisions
- [x] Automatic in-process capture from a transparent repacked test runtime after the signed test build is installed
- [x] Bounded runtime memory-ELF validation for special/deleted executable mappings without proof escalation
- [ ] JNI/dlsym runtime confirmation where static evidence is insufficient
  - [x] Bounded trace parser and SHA/process/PID binding contract
  - [x] Executable mapping + independently confirmed ELF module validation
  - [x] JNI/dlsym observations remain proof-neutral and cannot grant method execution or CHANGE_READY
  - [x] Repacked targeted dlsym probe uses signer/PID binding, exact static ELF mapping and RTLD_NOLOAD
  - [x] Active targeted probe is explicitly distinguished from passive application trace capture
  - [x] ABI-aware native helper payload/build/install/launch flow covers arm64-v8a, armeabi-v7a, x86 and x86_64
  - [x] Passive repacked dlsym producer patches/restores app-owned PLT/GOT slots and validates positive observations
  - [x] Passive dlsym session/export is bounded, signer/PID/session/SHA bound and usable from Expert Lab
  - [x] Passive RegisterNatives capture uses the JNI function table, records only successful app-owned registrations, and is signer/PID/session/SHA bound
  - [x] ART JNI_OnLoad symbol lookup, when the runtime exposes a hookable dlsym PLT slot, is captured as proof-neutral DLSYM presence; missing lookup-hook coverage is incomplete diagnostics, not negative proof
  - [x] Passive JNI_OnLoad invocation executor uses bounded per-lookup wrappers and emits JNI_ON_LOAD only after the original JNI_OnLoad actually returns
  - [x] JNI_OnLoad invocation provenance is bound through provider/status/export/session validation and remains proof-neutral with no method-execution escalation
  - [x] Full repacked native trace capability is registered only after passive dlsym, RegisterNatives and JNI_OnLoad invocation executors are concrete
  - [ ] Non-root/root trace capture executors
- [x] Root live-memory value scanner for exact values and disk-backed unknown-initial-value baselines
- [x] Dedicated main-menu root workflow: explicit root probe → running app/game process picker → attach → automatic modification discovery → optional dump/memory tools
- [x] Root modification discovery reuses static/deep cached analysis and confirmation instead of requiring a multi-gigabyte memory dump
- [x] Root modification profile writer records selected exact local gameplay modifications with package/version/SHA/module/file-offset binding
- [x] Runtime dump export uses Android CreateDocument so the user saves directly to device storage
- [x] Full streaming root process snapshot mode without the previous 256 MiB cap, with free-space preflight and explicit quick bounded mode
- [x] Runtime artifact inventory over live memory: ELF, DEX/CompactDEX, IL2CPP metadata, WASM, SQLite, ZIP/APK/JAR and PE/CLI candidates
- [x] Full-mode exact/unknown value scans cover the complete eligible writable-private range set; quick mode retains bounded scan limits
- [ ] Reconstruct/carve runtime artifact candidates into standalone files and route each recovered artifact through its matching deep backend
- [ ] Universal semantic runtime report that merges DEX/ART, ELF/native, IL2CPP, Mono/CLI, script/VM and generic memory evidence without pretending one format can recover original source for every runtime
- [x] Root process discovery fallback for rooted Android emulators without projected `ps -o` support or working `pidof`
- [x] Runtime value refinement: exact new value / changed / unchanged / increased / decreased
- [x] Natural and byte-aligned scans, batched refinement reads and bounded hit/byte limits
- [x] Root pointer scan with exact PID revalidation
- [x] Explicit runtime value write with writable-map proof and read-back verification
- [x] Explicit runtime value freeze loop with process revalidation on every write
- [x] Root live behavioral scanner foundation: bounded rolling baseline, rotating Int32/Float/Int64/Double sweeps, batched candidate refresh, confidence scoring and noise-region suppression
- [x] Root attach launches an already-running process back to foreground with an MK live overlay bound to the exact PID
- [x] Root picker can also launch a non-running installed app/game, wait for its exact main PID and attach the same MK overlay automatically
- [x] Overlay action-training mode for movement, attack, taking damage, resource changes, item changes and arbitrary user-labeled actions
- [x] Overlay manual value scanner: exact/unknown value, changed/unchanged/increased/decreased refinement, range/fuzzy/group search, verified write and Freeze
- [x] Correlate high-confidence or explicitly confirmed behavioral candidates with restart-stable module-root pointer chains
- [x] Persist learned candidates per package/artifact SHA and automatically re-resolve pointer chains after restart/ASLR
- [x] Try old pointer chains after an app update as read-only migration candidates and require explicit reconfirmation before writes
- [ ] Correlate confirmed live values with the code/methods that read or write them for richer semantic controls (for example damage handlers rather than only HP values)
- [ ] Root runtime only for evidence that remains unresolved after non-root stages
  - [x] Root decision engine requires recorded lower-privilege attempts
  - [x] Executor/implementation gaps explicitly cannot justify root escalation
  - [x] Concrete bounded su-based privileged maps capture executor with exact PID/cmdline revalidation
  - [x] Root maps capture is explicit in Expert Lab and never auto-runs
  - [x] Root environment failures are recorded as TARGET_ENVIRONMENT blockers
  - [ ] Root native trace capture executor; ROOT_TRACE_CAPTURE remains false until this exists

## Phase 7B — Runtime profile / sandbox delivery
Status: **PLANNED / ACTIVE FOUNDATION**

- [x] Exportable root modification profile format for confirmed local gameplay targets
- [x] Internal sandbox profile store plus import/parser; validate package/version/artifact SHA before launch
- [x] Root Android managed-profile sandbox backend: separate Android user/profile app-data, install-existing, launcher resolution and launch without uninstalling or clearing the original user-0 package
- [x] Root process attach accepts an exact PID so original and sandbox processes with the same package name remain distinguishable
- [x] Root sandbox native-code activation: exact sandbox PID, SHA/version validation, PT_LOAD/load-bias address resolution, stopped-process write/read-back and verified partial-profile rollback
- [ ] In-process injected runtime executor for non-root/clone-package activation and richer per-control hooks
- [ ] Non-root clone-package sandbox backend that rewrites package identity safely and never replaces the original package
- [x] Overlay menu generated from confirmed sandbox profile controls and bound only to validated targets
- [x] Persist selected mod configuration separately from the original APK
- [x] Re-resolve ASLR/module addresses and activate selected root-sandbox modifications on every launch
- [x] External main-profile MK overlay service for per-mod enable/disable while the sandbox game is foreground; live toggles revalidate PID/bytes and verify read-back
- [x] Keep billing/auth/anti-cheat surfaces visible in discovery while never exposing them as automatic profile/live bypass actions

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
