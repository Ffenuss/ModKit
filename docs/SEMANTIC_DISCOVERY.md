# Semantic Mechanic Model + Behavioral Discovery

Stage 1 of the post-audit plan: recognize gameplay mechanics from **code behavior**,
independent of method names and of a specific engine. Name analysis is kept as a
secondary signal, never as the only proof.

## Files

| File | Role |
|---|---|
| `patch/DexMechanicModel.kt` | Engine-agnostic model: `GameMechanic`, `MechanicConfidence` ladder, `MechanicEvidence`, `MechanicHypothesis`. |
| `patch/DexBehavioralAnalyzer.kt` | Method-local structural analysis of DEX bodies: register→field dataflow, field read/write sets, constants, zero-threshold checks, call relations, proven getters (one-level interprocedural). |
| `patch/DexMechanicClassifier.kt` | Merges structural patterns + name signals into hypotheses with an explicit confidence ladder; emits only proven, patchable candidates. |
| `patch/DexLocalPatchEngine.kt` | `scanDex` runs the structural pass after the name-based pass; deduplicates by stable id; `DexLocalScan` reports `structuralMechanicsConfirmed`, `structuralCandidates`, `mechanicHypotheses`. |
| `patch/AutoModRecipe.kt` | Catalog ordering: name-based recipes first, structural-only after. |
| `testgame/.../ObfuscatedStats.java` | Owned fixture: single-letter members, no gameplay names, same resource shapes as `PlayerStats`. |
| `testgame/.../GameActivity.java` | Obfuscated section: `OBF ALIVE | Obf HP: N`, buttons `Obf hit` / `Obf move`. |
| `test/.../ObfuscatedFixtureDex.kt` | Synthetic DEX mirroring the fixture (JVM tests, no device needed). |
| `test/.../DexBehavioralAnalyzerTest.kt` | Pattern recovery: damage write-back, death check vs availability gate, guarded increment, readers/writers, call relations. |
| `test/.../DexMechanicClassifierTest.kt` | Confidence ladder: obfuscated health CONFIRMED by structure alone; stamina stays PROBABLE; no over-claiming. |
| `test/.../DexStructuralDiscoveryTest.kt` | End-to-end `scanDex` → opportunity → `rewriteDex` on synthetic DEX; no duplication with name-based hits. |
| `androidTest/.../AutoModDeviceTest.kt` | `d_structuralDiscoveryPatchesTheObfuscatedHealthAlone`: real APK scan → structural candidate → build → install → running game shows `OBF ALIVE | Obf HP: 9999` and survives hits. |

## How it works

1. **Proven getters (pass 1).** `DexMethodBodyInspector` proves `INSTANCE_FIELD_GETTER` /
   `STATIC_FIELD_GETTER` bodies. These are the only reads the analyzer trusts across
   method boundaries (one level; a call to a proven getter counts as a read of that field).
2. **Method-local dataflow (pass 2).** Linear register→field binding, fail-closed:
   any unmodeled instruction invalidates bindings. Recognized shapes:
   - `field = field - const` (+ optional `Math.max(0, x)` clamp) → `DAMAGE_WRITE_BACK`. Includes D8's equivalent `add-int/lit8` or `add-int/lit16` with a **negative immediate literal**; arbitrary register additions are not treated as damage;
   - boolean `field <= 0` / `field > 0` → `ZERO_THRESHOLD_CHECK` with direction
     (depleted→`FALSE`, available→`TRUE`);
   - `if (field > 0) { field -= const }` in the same method → `GUARDED_CONSUMPTION`;
   - `field = field + const` (+ optional clamp) → `CLAMPED_INCREMENT`.
3. **Classification (pass 3).** Per field, structure + names:
   - `DAMAGE_WRITE_BACK` + depleted check → **HEALTH, CONFIRMED by structure alone**
     (the decrement+death-check signature is distinctive);
   - `DAMAGE_WRITE_BACK` + availability gate / guarded consumption → STAMINA-like;
     a retry counter is structurally identical, so it is **CONFIRMED only with a
     matching name anchor on the same field**, otherwise PROBABLE;
   - a name signal without corroborating structure never reaches CONFIRMED.

## Confidence ladder (static analysis ceiling: CONFIRMED)

- `DISCOVERED` — legacy name-only match.
- `PROBABLE` — at least one structural pattern, no semantic anchor.
- `CONFIRMED` — structure corroborated by a name anchor on the same field, or the
  distinctive health signature.
- `MODIFIABLE` — CONFIRMED + a proven side-effect-free body for an executable recipe.
- `FUNCTIONALLY_VERIFIED` — effect observed in a running app (device test `d_`).

Static code never claims the top two levels without proof.

## Precision rules (fail-closed)

- Candidates are only proven scalar getters or proven zero-threshold checks of the
  confirmed field; anything else stays a hypothesis.
- Structural candidates are deduplicated against name-based hits by stable id.
- Methods with try blocks, >256 instructions, or unmodeled instruction shapes are
  skipped, not guessed.
- PROBABLE hypotheses are reported in `DexLocalScan.mechanicHypotheses` but produce
  no executable recipe.

## Verified build and device evidence (2026-11-02)

- GitHub Actions [run 41390776138](https://github.com/Ffenuss/ModKit/actions/runs/41390776138): JVM tests, lint, APK build, ARM64 vector checks and emulator device suites **passed** on API 29 (single APK) and API 35 (split APK).
- The APK's D8-compiled `ObfuscatedStats.f()` actually uses `invoke-virtual → move-result → add-int/lit8 → Math.max → iput`. An earlier synthetic test encoded only `sub-int`; adding recognition of negative literal additions fixed the discrepancy.
- The owned fixture confirms obfuscated-health discovery and an in-game effect after applying a static DEX recipe. This is **not** proof of general compatibility or server-side modifications.
- An occasional system installer confirmation failure was observed in earlier emulator runs; continued hardening and repeatability testing are still relevant.

## Limitations (honest list)

- **Method-local, linear dataflow.** Loops, complex CFGs, and more than one level of
  interprocedural calls are not resolved; such methods are skipped.
- **Scalar int/float fields only.** Long/double, arrays, and object-state mechanics
  are out of scope for this stage.
- **`Math.max`/`Math.min` clamps** are the only recognized helper calls; other
  sanitizers break the binding (fail-closed, no false pattern).
- **STAMINA vs AMMO ambiguity** is resolved only by names; without names it stays
  PROBABLE by design.
- **HEALTH structural confirmation** can still be a false positive on exotic code
  (e.g. a shield with the same shape); the device test proves the pipeline, not
  universal correctness.
- **No release claim.** This stage proves discovery + patching on the owned fixture;
  it does not claim the mechanic works on any third-party game.

## Unverified hypotheses (next stages)

- Runtime (FUNCTIONALLY_VERIFIED) promotion for the IL2CPP side.
- Multi-level interprocedural dataflow and loop handling.
- CURRENCY/SPEED/COOLDOWN executable recipes (currently hypotheses only).
