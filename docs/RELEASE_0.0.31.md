# ModKit Test — continuation / 2026-09-26

## Build available to test now

**0.0.30, PR17**, commit `5b2abfe443217e04110236f4c1b2f2fe835b69e5`.

- [Source PR17](https://github.com/Ffenuss/ModKit/pull/17)
- [Exact build/test run 36186557915](https://github.com/Ffenuss/ModKit/actions/runs/36186557915)
- APK artifact 10886995710, verified against its published ZIP digest and APK checksum.
- 383 JVM tests, zero failures/errors/skips; 3/3 API29 single-APK and 3/3 API35 split-APK fixture tests.
- Lint finished without errors, with existing warnings; this is not a warning-free claim.
- Three generated ARM64 vectors independently executed in Unicorn 2.1.4 locally: integer 27→999,
  conditional 1→0, floating 3→5; preserved checked machine state.
- Distributed as `ModKit-Test-0.0.30-PR17.apk`, 14,782,405 bytes, package `io.github.ffenuss.modkit.test`.
- Signed locally with the pre-existing private test key; v2/v3 verified. All 135 ZIP entry payloads
  match the CI APK; stored-entry alignment checked. Signing material remains outside Git.
- APK SHA-256: `a27cfabaa2b40c477dc8577c42e2145a6397c66ccddd7ff30d479dcfb917715e`.
- Signer SHA-256: `c0029212552bb7b9f250c586a5ff7b3670d732e23751f04d3c7251f4ac0f2715`.

This build contains PRs #8–#10 and #13–#17: installation confirmation, IL2CPP extraction,
experimental native overlay, validated-metadata routing, disk bindings beyond the 30k preview,
Unreal/Flutter structural inventories, cache restoration, and ELF progress/watchdog fixes.
**It does not contain PR12's kotlinx exclusion or the new changes described below.**
Version shown inside the application remains 0.0.30; PR17 identifies this later build.

## Prepared locally: 0.0.31, NOT built or published

Branch: `release/0.0.31-integrated-analyzers-20260926`.

| Commit | Change | Verification in this session |
|---|---|---|
| `8406c68` | Bring PR12's kotlinx exclusion into the integrated release | Source review; prior PR12 CI is not verification of this combined branch |
| `d661b42` | Include the corresponding framework-vs-game DEX regression | Not run on this branch |
| `6f08c90` | Real 30,005-slot ELF scanner regression, complete streaming and shared-body census | Added, not executed |
| `988971a` | Current engine/source/coverage diagnostic tables; remove stale report reuse | Regression added, not executed |
| `1871778` | Version 0.0.31/31, IL2CPP coverage UI, release/CI naming | `git diff --check` passed; compilation pending |

Automatic approval review rejected pushing the new branch to the user's public `Ffenuss/ModKit`
repository, stating that explicit authorization to publish modified source was missing.
The exact repository URL, owner and changed-file list were checked; a second attempt was also
rejected. No alternate write channel, source upload or PR creation was used to bypass that decision.
The branch and commits remain local; main and all existing remote branches remain unchanged.

Local Gradle fallback failed before compilation: distribution download from services.gradle.org
returned `Network is unreachable`. No local or new remote test/build success is claimed.
Next required action: user authorization to publish this exact branch and create a draft PR,
then run fresh JVM/lint/APK/native/API29/API35 checks, fix failures, and sign the actual 0.0.31 APK.

## Phone test for the available PR17 APK

1. Install ModKit Test. This uses its own package alongside original ModKit; retain existing app data.
2. Re-analyze DropTheCat and export diagnostics. `README.txt` should distinguish total disk bindings
   from the memory preview, and `il2cpp/bindings.tsv` should contain every proven indexed binding.
   The exact new count on that game has not been measured here.
3. Analyze Aniimo: missing/invalid metadata should be explained instead of starting an impossible dump.
4. Analyze an authorized Unreal/Flutter target: expect actual resource inventory or a specific missing-input
   result. Blueprint/Dart AOT game-logic decoding and gameplay patch generation are not implemented.
5. During large ELF processing, record the library name and byte progress; export the resulting ZIP.

The 30k limit remains only a memory preview, while the separate metadata parser bound is 300k.
Fixture success does not prove effects in the nine submitted applications or physical ARM64 overlay
ON/OFF/restore. Their reports contain no full input APKs, so those device checks remain pending.
