# ModKit — implementation roadmap and acceptance matrix

Companion to [the 2026-09-25 audit](AUDIT_ENGINE_COVERAGE_AND_INSTALL_PROTECTION_2026-09-25.md). Baseline: PR #10 → #9 → #8 → #7. This is an actionable plan; **none of the listed engineering tasks is claimed implemented by this documentation PR**.

## Release gates (non-negotiable)

- Work from PR #10 and use one branch + draft PR per deliverable; leave `main` and all prior PRs unmerged until owner approval.
- Preserve working DEX mutation, proven ARM64 mutation, expert view, export, installed-app/split support, persistence and existing test APKs. No destructive auto-uninstall, signature-conflict workaround or cache/data clearing.
- Mark every item **candidate / static patch verified / APK install verified / runtime ON verified / runtime OFF restored / actual gameplay observed** independently. Do not conflate a method name or an installable APK with successful modification.
- Simple mode will eventually offer **only switches that can be turned OFF**; the existing static DEX backend moves to an explicitly labelled legacy/expert workflow until a separately proven reversible DEX runner exists.
- Every engineering PR must pass JVM + lint + APK signature/align checks, Android API 29 and API 35 tests relevant to its changes. Native overlay feature additionally requires a physical ARM64 Android device with ON/OFF comparison on a developer-owned fixture. No claim of broad compatibility from the fixture alone.

## Stage 0 — reproducible evidence and typed results (P0; implement first)

**Files:** `analysis/AnalysisManager.kt`, `analysis/RoutedEngineScheduler.kt`, `analysis/FastAnalysisModels.kt`, `patch/PatchLabDiagnosticReportWriter.kt`, `ui/AutoModViewModel.kt`, `ui/screens/SimpleAutoModScreen.kt`.

1. Introduce a persisted per-engine outcome: `NOT_APPLICABLE`, `UNSUPPORTED_RUNTIME`, `INPUT_MISSING`, `VALIDATION_FAILED`, `NO_CANDIDATES`, `UNVERIFIED_CANDIDATES`, `READY`, `STALLED`, `CANCELLED`, `FAILED`. Include stage, input identity, elapsed time, last progress time and bounded error details.
2. Store all warnings by stage instead of taking only `firstOrNull()`; surface a short Russian diagnosis and a "Why no switches?" breakdown. The number of hidden/blocked items must remain visible and grouped by cause.
3. Extend the **local** diagnostics ZIP with runtime fingerprint + status/confidence, indexed source APK/splits and hashes, missing/invalid metadata paths, each stage's start/end/heartbeat, DEX scan totals and framework-exclusion counts, native-binding coverage and blocker histogram, optional redacted crash/ANR context and a structured JSON summary. Do not automatically transmit APKs, user data or full native libraries.
4. Keep the cached artifact-key identity and previous results; allow retrying only a failed engine without redoing successful SHA/DEX steps. Handle `OutOfMemoryError` as a separately reported fatal state, not as "no recipes".

**Acceptance:** six representative synthetic packages produce six distinct truthful diagnostics; an engine exception is never converted to "no changes"; exporting after a failed or cancelled run preserves partial results without stale success.

## Stage 1 — routing and long-running task correctness (P0)

**Files:** `analysis/RuntimeFingerprintProfiler.kt`, `analysis/EngineRoutingPlan.kt`, `analysis/Il2CppFastDumpEngine.kt`, `analysis/UniversalElfInventoryEngine.kt`, `analysis/AnalysisWatchdogPolicy.kt`, `analysis/RoutedEngineScheduler.kt`.

1. Schedule full `il2cpp.fast-dump` **only** when a validated metadata + matching ELF binary pair exists (not a merely LIKELY IL2CPP ID). If binary exists without validated metadata, show `IL2CPP: metadata missing/invalid`, list searched paths/container names, and offer the DEX/native inventory as honest partial analysis.
2. Distinguish missing, invalid magic, unsupported metadata version, inaccessible split and optional runtime-loaded metadata. Do not call absent/encrypted metadata successfully decoded and do not attempt speculative in-memory dumps without appropriate access.
3. Run the ELF inventory as low-priority *background enrichment*. Default scan analyzes the actual APK's DEX and likely game binary first; optional ELF inventory should not block opening the selection screen. Publish true per-file substage progress (copy bytes, parsing validated headers/symbols, finalize), bound work/heap per file, and persist a resumable cursor. Avoid synthetic timer heartbeats while a parser is genuinely hung.
4. Keep the watchdog actionable: it must name the stalled ELF/file and last genuine milestone; retry only that entry or skip it with an explicit incomplete result. Prevent a long single ELF extraction from triggering the 30-second generic TARGETED timeout when it is still reporting real progress.
5. Preserve PR #9's byte-verified terminal extraction progress and verify that a subsequent native scan starts a separately named stage.

**Acceptance tests:** valid and invalid/missing metadata in base and split; `libil2cpp.so` present alone; 16-ELF bundle with one deliberately slow file; disk exhaustion; mid-stream cancellation/restart; no duplicated extraction; zero-result diagnosis identifies exactly which required input was missing.

## Stage 2 — useful cross-engine candidates, not fabricated ones (P0/P1)

**Files:** `patch/DexLocalPatchEngine.kt`, `patch/DexGameplayContext.kt`, `patch/GameplayModificationFinder.kt`, `patch/NativeRecipeCatalog.kt`, `analysis/Il2CppCodeGenScanner.kt`, `patch/SelectedRuntimeMenuBuilder.kt`.

1. Fix DEX framework filtering: exclude `Lkotlinx/` and known library/framework namespaces by **validated package ownership**, with an explicit opt-in override in expert mode. Add a fixture containing `kotlinx.coroutines.sync.SemaphoreSegment.getMaxSlots()`: it must **not** be shown as "game inventory". Retain genuine similarly named methods in the actual game's own package.
2. Move presentation-only candidates (e.g. `UI_ProgressBox`) to **Visual/UI tests** instead of counting them as confirmed gameplay mods. Attach method identity, proof and an explicit "gameplay impact not established" flag even if the code edit itself is supported.
3. Show real per-ABI support. DEX-only, Unity Mono, Unreal/Cocos/Flutter/Godot and UNKNOWN all get truthful **runtime coverage** and diagnostics. A missing backend creates a named engineering task, not guessed health/ammo/XP controls.
4. **IL2CPP**: retain the 30,000-entry low-memory bulk cap, but add on-demand target-specific binding/materialization of promising project-code candidates beyond that cap. Record total, materialized, bound, uniquely owned, return-type-proven, safe-patch, runtime-proven and excluded counts independently. Never raise bulk limits blindly on phones.
5. Group blocker categories: no validated metadata; unknown return width; shared code body; non-read-only body/side effects; missing field/object offset; unsupported ABI; missing source split; overlay backend unavailable. Only upgrade a blocked item when the missing proof is actually supplied. Explore safe runtime field offsets/instances on **owned fixtures**, keeping unsupported arbitrary object writes blocked.
6. Add ranked *research priority* labels for technical investigation (not claims of gameplay effect), based on method context, project ownership and proof completeness. Avoid elevating library or UI methods into game modifiers.

**Acceptance tests:** false `SemaphoreSegment` inventory is absent, owned `Game.Player.getInventorySize` is retained, `UI_ProgressBox` is explicitly presentation-only, an obfuscated DEX with no proof reports no verified recipes, selected proof can exceed the bulk 30k materialization cap on a controlled fixture without OOM.

## Stage 3 — installer/integrity compatibility preflight (P0, independent of recipe coverage)

**Files (new):** `security/InstallProtectionEvidence.kt`, `security/InstallProtectionDetector.kt`, `ui/screens/InstallCompatibilityScreen.kt`; integrate with `analysis/ArtifactIndex.kt`, `build/BuiltPackageVerifier.kt`, `runtime/RepackedRuntimePackageInstaller.kt` and the export report.

1. Run a cheap **preflight before modifying anything**. Record installed source where Android permits: API 30+ `getInstallSourceInfo`, API 29 `getInstallerPackageName`; inspect original and planned signer hashes and existing same-package installation. Never infer future APK installer from APK bytes.
2. For supplied APK/DEX/ELF, identify **signals** of local installer-package checks, local certificate checks, Play Integrity library/API usage, and Play automatic-protection indicators. Record precise evidence (file/method/API reference) and uncertainty: `LOCAL_CHECK_CONFIRMED`, `LOCAL_CHECK_SUSPECTED`, `REMOTE_ATTESTATION_POSSIBLE`, `NO_EVIDENCE`, `UNINSPECTABLE`. Presence of a library alone is not proof of enforcement; absence of signals is not proof of safety.
3. Show a separate Russian **APK install/launch compatibility** card: signer conflict, install source, protection evidence, expected limits, actual verification outcome. Never suppress an Android install error, fabricate a signature match or auto-delete a user's original game.
4. For apps **owned or explicitly authorized for modification**, support a deliberate, auditable test-build policy: developer-provided build variant / source-level local check opt-out / controlled signing key / allowlisted test backend. Check the pre/post bytes, re-sign and verify the produced APK, launch and regression-test. If an original app requires a Play-only server verdict, classify it as unavailable through this offline pipeline. Do not promise universal automatic bypass or silently mutate arbitrary third-party licensing.
5. Capture user-approved launch diagnostics from target builds to distinguish system signature conflict, local runtime check, Play installer prompt and backend rejection. Avoid sending private tokens, keys, receipts or personal data in diagnostics.

**Acceptance:** owned fixtures for no check, local installer check, local signing check, backend-attestation stub and conflicting installed signer are correctly classified; opt-in authorized test configuration works in owned fixtures; install failure never destroys original data; server stub denial is honestly reported, not claimed removable.

Official platform references:
- https://developer.android.com/reference/android/content/pm/PackageManager
- https://developer.android.com/games/playgames/integrity
- https://developer.android.com/google/play/integrity/overview

## Stage 4 — all *simple-mode* mod choices must actually be reversible (P0)

**Files:** `ui/AutoModViewModel.kt`, `ui/screens/SimpleAutoModScreen.kt`, `patch/SelectedRuntimeMenuBuilder.kt`, `runtime/ModKitRuntimeOverlayService.kt`, `runtimeprobe/.../RuntimeModMenu.java`.

1. Default the simple UI to **Overlay-compatible** only. Never silently route any selection to permanent DEX mutation. Put legacy static DEX in a clearly separated expert workflow until reversible DEX instrumentation has been designed and tested.
2. Present `READY_TO_TOGGLE` independently from `CANDIDATE_FOUND` and `GAMEPLAY_OBSERVED`. Show the exact blocker when an item is not reversible; disallow mixed DEX/native choices that will fail only at build time.
3. Run native overlay integration tests against a developer-owned ARM64 fixture with deterministic health/damage getters. Check permission denied/approved/revoked, injection/installed signer, ON changes, OFF restores original bytes, two consecutive cycles, app restart, missing module, collision rejection and saved settings. Test the distinction between application window and `TYPE_APPLICATION_OVERLAY` permission.
4. Only when separately verified, add a reversible **DEX** strategy with equivalent ON/OFF semantics and rollback rather than pre-applying const-return replacements. Ensure a disabled mod never remains patched in a repackaged APK.
5. Where a protected target requires original distribution/signing, mark it incompatible rather than presenting an overlay as a fix for installer/integrity checks.

**Acceptance:** simple UI cannot produce an irreversibly modified game without a clearly separated expert action; 100% selected native ON/OFF operations on the owned fixture are acknowledged and independently observed; APK at rest contains original game-method bytes when switches are OFF.

## Stage 5 — CI, mobile QA and rollout (P0)

| Fixture/scenario | JVM/static | API 29 | API 35 | Physical ARM64 |
|---|---:|---:|---:|---:|
| Full IL2CPP metadata + native ARM64 | ✓ | ✓ | ✓ | ✓ |
| IL2CPP binary missing/invalid metadata | ✓ | ✓ | ✓ | optional |
| 16 ELF + one long extraction | ✓ | ✓ | ✓ | ✓ |
| DEX framework `SemaphoreSegment` false positive | ✓ | ✓ | ✓ | optional |
| DEX real gameplay vs UI-only getter | ✓ | ✓ | ✓ | ✓ |
| Owned app local installer/signature test settings | ✓ | ✓ | ✓ | ✓ |
| Server-attestation **stub** denies an altered build | ✓ | ✓ | ✓ | optional |
| Native overlay grant → ON → OFF → restore | ✓ | ✓ | ✓ | ✓ |
| APK signing conflict leaves original installed/data intact | ✓ | ✓ | ✓ | ✓ |
| Partial analysis, cancellation, recovery and ZIP export | ✓ | ✓ | ✓ | ✓ |

Stop ship if a build success is reported without APK verification; a selected simple-mode mod has no OFF implementation; any automatic uninstall/data loss occurs; an unsupported runtime is reported as no game mods; or an overlay test passes without asserting actual ON/OFF behavior.

## Prioritized PR breakdown and dependencies

1. **P0/A1+A2+A9:** Engine classification + structured no-result reasons (Stage 0 + IL2CPP routing). This fixes misleading zero-result and Aniimo's spurious `fast-dump` exception before adding new engines.
2. **P0/A3:** ELF inventory milestone/timeout/rerun correction. Independent of recipe scanning; keep main selection usable during optional ELF enrichment.
3. **P0/A4+A5:** Framework false-positive tests + separate visual-only vs gameplay lists. A quick accuracy fix before any effort to increase recipe count.
4. **P0/A7:** Origin/signature/integrity diagnostic and safe installation compatibility. First ship *detection*; later ship opt-in local test variants **only** for owned/authorized apps, with independent tests.
5. **P1/A6:** On-demand IL2CPP evidence binding and per-category blocker reduction. Target actual missing proofs rather than making blocked items selectable wholesale.
6. **P0/A8:** Simple-mode reversible-only UI plus real ARM64 fixture overlay ON/OFF E2E. Preserve legacy expert static patches without calling them overlay-compatible.
7. **P1:** Broader managed/non-IL2CPP engine backends, each on its own accuracy/tests-driven PR rather than a universal name heuristic.

### Evidence required from actual games

For each new app, user can export diagnostics locally via **Ещё → Экспорт диагностики**. The minimum helpful set is **Aniimo**, **Minecraft** and **DropTheCat**: one ZIP per app, plus whether installed as base+splits or single APK and the Android version. Ask for logcat/ANR only after the progress report identifies a truly stalled stage. **Never request the user's game account credentials, payment receipts, private certificates or an automatic dump of personal files.**

Reports from **Delta Force, STAR DIVE and TWoM** help classify non-IL2CPP coverage, but screenshots alone do not justify labeling their engines or enabling more mods.


## Independently validated follow-up draft PRs (2026-09-25)

| Change | Draft PR / commit | CI verification | Still unverified |
|---|---|---|---|
| Exclude coroutine framework `Lkotlinx/` from gameplay DEX name suggestions; retain genuine game `getMaxSlots` via synthetic regression. | [#12](https://github.com/Ffenuss/ModKit/pull/12), `56956c5f371de68ac7729aaf69146503b6fffde2` | [run 36180849062](https://github.com/Ffenuss/ModKit/actions/runs/36180849062): build, JVM and lint SUCCESS; Android API 29 **3/3** and API 35 **3/3**, no skipped/failed fixture tests. | A fresh scan of the actual Minecraft/TWoM installed APKs and all other false-positive sources. |
| Gate full IL2CPP fast-dump/codegen stages on a CONFIRMED runtime profile plus both validated ELF and metadata entries; expose missing metadata in the simple UI. | [#13](https://github.com/Ffenuss/ModKit/pull/13), `ab9b055eb68a30f2f526cc11b8fb3b98e3951034` | [run 36181037872](https://github.com/Ffenuss/ModKit/actions/runs/36181037872): build, JVM and lint SUCCESS; Android API 29 **3/3** and API 35 **3/3**, no skipped/failed fixture tests. | Real Aniimo APK classification, obtaining valid metadata when absent/packed, and full gameplay recipe support. |

These two PRs branch **independently** from #10; their downloadable APK artifacts do **not** include both fixes simultaneously. Neither PR has been merged into #10, #11 or main. The existing emulator fixture suites do not demonstrate a real native overlay ON/OFF transition or actual gameplay effects.
