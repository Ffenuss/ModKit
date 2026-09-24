# ModKit

ModKit is an Android-first workbench for **authorized analysis, reverse engineering and defensive validation of APK/APK-set targets**.

Current application version: **0.0.26**

## Gameplay categories and direct split installation (0.0.26)

Patch Lab groups native method candidates by gameplay mechanic and distinguishes statically patchable methods, research candidates, metadata-only signals and diagnostics. Game-state hints appear before UI-only methods; category navigation retains selections across categories. A static patch template is **not** a guarantee of gameplay impact.

When an authorized test build consists of one APK, the standard export produces one `.apk` file. When the source game has split APKs, ModKit exports **every signed APK** to its own `Загрузки/ModKit/<package>-<build-time>/` directory instead of unexpectedly producing a ZIP. The primary **Установить игру** action in the finished-build panel and fixed bottom bar sends all splits together through Android PackageInstaller, without manually extracting a ZIP. A ZIP remains an optional explicit backup via the system save picker. Android refuses an in-place update if the original app has a different signing certificate; ModKit must not silently remove the original or its saved data.

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

## Local Full / Premium testing

After selecting an installed game and running static analysis, ModKit lists detected changes in Patch Lab. Exact Unity/IL2CPP Boolean methods such as `get_IsFullVersion`, `isPremium` or `hasEntitlement` can be selected as local `true` patches without entering an alias, a keystore password, or passing an APK signer check. The original content must already exist in the package: a local flag cannot download missing characters or skins.

Purchase receipt verification, Google Play Billing APIs, account authentication, anti-cheat and server-backed entitlements remain diagnostics rather than automatic patches.

After selecting supported modifications, ModKit verifies the exact binary target, stages the changes, aligns and signs the output automatically with its local AndroidKeyStore test key, then verifies the resulting APK. This does not preserve the source APK signature; Android may require uninstalling an existing copy signed with a different key. Back up game data first.

## Scan diagnostics and broader gameplay discovery (0.0.20)

If a target yields zero selectable modifications, ModKit now reports how many DEX classes and methods it inspected, how many belonged to excluded libraries, how many have code, which method names resembled gameplay signals but had unsupported signatures, and how many native libraries were present. The result can be exported as a text-only scan diagnostic without sharing the full APK or game save.

Method matching now includes more explicit local names for health/HP, invulnerability, stamina, ammunition, movement, no-clip, cooldowns, XP, level, inventory capacity and debug flags in self-authored apps. Library namespace matching was corrected so game packages containing `/android/` are no longer discarded. This increases coverage for unobfuscated DEX apps; it cannot resolve heavily obfuscated or fully native game logic automatically.

Build retries now use unique output directories and no longer overwrite earlier signed results. The finished-build screen supports saving an APK or split APK ZIP directly to Downloads/ModKit (Android 10+) or through Android's system document picker on older phones.

## Signing recovery and visible build status (0.0.24)

ModKit now displays operation progress and errors in a **persistent bottom status bar**, regardless of where the user has scrolled in the long Patch Lab list. Build failures also open a detail dialog without returning to the top. Nested signing-provider failures are shown with the split APK filename, not only the generic `Failed to sign using signer MODKIT` message.

The phone-side testing output signs using APK Signature Scheme v2/v3 for Android 7+ rather than adding a legacy v1/JAR signature. This avoids v1-specific signing-provider failures, but cannot guarantee the hardware-backed AndroidKeyStore is functional on every phone. A 32-byte key/certificate self-test runs before large-APK signing; insufficient internal storage is reported before alignment. A failed build retains verified staging for retry.

The output certificate is still ModKit's test certificate, not the original game's certificate. Android requires the APK set to use one consistent signer, and an installed original with a different signature cannot be updated in place. Back up saves before considering any replacement.

## Automatic signed APK export (0.0.23)

After a verified Patch Lab build completes, ModKit now saves the signed result to **Downloads/ModKit/** without a separate tap on Android 10 or later. For split-package targets this is a single `*-apk-set.zip` containing every signed split APK, SHA256SUMS.txt, and the build report; a lone base.apk must not be installed as though it were the complete game. The status panel at the top of AutoMod shows whether the APKs are still being signed, being saved, or ready, with the exported filename. Export failure is reported separately without invalidating an already verified build. Android 8/9 continues to use the system save picker.

The original installed app is not silently uninstalled. ModKit's testing certificate may conflict with the original developer's signing certificate; back up local saves before replacing any app. A successfully signed APK does not guarantee the selected code modifications behave as intended.

## One-tap IL2CPP APK builds (0.0.22)

Previously, checking available Native Patch Lab modifications did not enable the bottom Build APK action. Applying a patch required two additional manual staging operations, so tapping Build appeared to do nothing.

Now the Patch Lab action **«Применить и собрать APK»** combines checkbox selections with any existing queued manual drafts, performs one strict preflight, creates and validates the staged APK, and triggers the existing align/sign/verify pipeline. The top-level Build APK button still requires a previously staged result and explains this requirement. Parallel build attempts are blocked, and the UI distinguishes completed staging from ongoing signing. A successful CI build proves implementation checks, not that every detected modification will affect gameplay.

## Dedicated Unity gameplay assemblies (0.0.21)

Version 0.0.20 incorrectly treated Assembly-CSharp as the only gameplay code: dedicated game assemblies (for example FlickEngineAssembly.dll) were grouped with plugins, hidden by default and excluded from automatic gameplay and field suggestions. Version 0.0.21 recognizes project/game assemblies with the narrow GameAssembly/EngineAssembly naming pattern, prioritizes them during bounded IL2CPP binary binding and exposes their exact methods in Patch Lab and AutoMod. The analysis summary now distinguishes runtime evidence and exact method bindings from ready patches.

The binding limit remains 30,000 on normal devices, with a 5,000-method retry on low-memory failure; this is a bounded partial scan, not full reconstruction of every method. Neither a binary binding nor an attractive method name guarantees that a mutation is safe or will affect gameplay. Review diagnostic reports and test the result on an authorized copy, preserving original save data.

## Automatic Android DEX modifications (0.0.19)

ModKit now supports a second executable mutation backend for ordinary Android applications and games with DEX code. After FAST analysis, the AutoMod screen scans `classes.dex`, `classes2.dex` and other DEX files inside the base APK and installed splits without repeating the full APK deep-index pass. It identifies exact class/method signatures, offers supported modifications as independent checkboxes, and explains which method and return value will change.

Current concrete local DEX changes include proven Boolean gameplay toggles (for example invincibility, movement permission, infinite stamina and cooldown state), selected integer health/ammo/stamina getters and float movement-speed getters. Local Full/Premium Boolean getters are only selectable when the developer explicitly enables the own-game test mode. A method-name match is not proof of a gameplay effect: in-app behavior still requires testing.

For a supported selection, ModKit reopens and revalidates the original APK-set, checks the exact method identity and DEX SHA-256, rewrites the selected methods using dexlib2, reparses the resulting DEX and feeds full DEX entry replacements through the same FILE_REPLACE mutation preflight, verified staging, ZIP alignment, APK signing, mutation diff verification, installability checks and post-build reanalysis used by the existing IL2CPP backend. Multiple selected DEX methods across base/split APKs are supported.

The rebuilt single APK or entire split APK-set can now be installed from the finished-build screen using Android PackageInstaller, with explicit Android confirmation. If the installed original has another signing certificate, ModKit explains the conflict and can open the system uninstall page at the user's request; it never uninstalls the original automatically. **Back up local game saves before uninstalling.**

DEX scanning and rewriting are intentionally bounded to 96 MiB per DEX entry and 350,000 scanned methods. Unsupported DEX formats, inconclusive return types, obfuscated methods, missing assets, compiled Mono assemblies, unsupported native ABIs and server-authoritative entitlements are reported as limitations rather than silently advertised as successful modifications. A local APK edit cannot forge a server-verified purchase.

DEX reading and writing uses `org.smali:dexlib2:2.5.2`, published under the BSD 3-Clause License ([Maven Central](https://central.sonatype.com/artifact/org.smali/dexlib2/2.5.2)).

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
- bounded Android DEX method discovery, multi-method exact rewrite and executable output verification;
- test-mode local Full/Premium getter discovery for own games;
- Android PackageInstaller flow for the finished APK and split APK-set;
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
