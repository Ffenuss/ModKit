# Tool research and engineering choices

Checked 24 September 2026. **D** means publisher documentation/source inspected;
**U** means a user report, not independently verified; **I** is a ModKit design
inference. No closed-source tool was copied or executed. No third-party game
was patched as part of this research. This is a capability comparison, not a
measured accuracy/speed contest.

| Tool / primary source | Observed approach and limitations | Implication for ModKit |
|---|---|---|
| [GameGuardian help](https://docs.gameguardian.net/help.html), [API](https://docs.gameguardian.net/classgg.html), [FAQ](https://docs.gameguardian.net/faq.html) | **D:** exact, fuzzy, grouped numeric searches, refinement after actions, saved results, Lua automation. Memory value changes are not semantic proof; displayed and authoritative values can differ. Root or constrained virtualized environment is required for relevant process access. | **I:** borrow explicit repeatable action/refinement UX and explain observations. Do not advertise arbitrary non-root memory access or treat an observed address as a permanent APK patch. |
| GameKiller | A current attributable original source repository or technical manual was not established. Search found a self-described [channel](https://t.me/gamekillerofficial) and unrelated/repackaged app listings. Those do not prove architecture, compatibility or license. | No code/dependency adopted. Memory-scanner similarities remain unverified, and no performance claims are made. |
| Lucky Patcher | Publisher pages at luckypatchers.com were unavailable to the retrieval tools. [Community discussion](https://www.reddit.com/r/luckypatcher/comments/1lajdnr/) is **U**, describing app-specific custom patches; it is not proof of proprietary implementation or universal compatibility. | **I:** version/SHA-bound recipes are a sound independently designed pattern. No billing/server bypass or closed-source transplant is adopted. Publisher architecture remains an explicit research gap. |
| [MT Manager DEX editor](https://mt.cc/guide/reverse/dex.html), [signing](https://mt.cc/guide/reverse/signature.html), [DEX comparison](https://mt.cc/guide/file/file-comparator.html), [APK MCP](https://mt.cc/guide/reverse/apk-mcp.html) | **D:** expert Smali editing, comparisons, APK inspection/signing and supported local analysis/edit operations. A signing key change does not preserve original identity. Some features have a VIP tier; source reuse is not established. | **I:** retain an expert inspector and exact before/after diffs. Do not make paid MT functionality an app dependency. |
| [APKTool M](https://maximoff.su/apktool/?lang=en), [updates](https://github.com/Maximoff/Apktool-M-updates) | **D:** on-device APK decompilation/build/editing workflow and changelog. Its Android UI distribution is separate from upstream Apktool source. | **I:** phone-only result/export/install is practical; ModKit should reuse its own streaming APK writer when resources need no recompilation. |
| [jadx](https://github.com/skylot/jadx) | **D:** DEX-to-Java, resource decoding, deobfuscation, navigation/usage search; explicitly cannot decompile all code correctly. Apache-2.0. | **I:** useful offline review oracle. Build recipes from typed DEX instructions rather than recompiling decompiler text. |
| [Apktool](https://github.com/iBotPeaches/Apktool) | **D:** decoding resources/manifest/Smali and rebuilding packages. Apache-2.0. Does not infer gameplay intent. | **I:** add only if future resource recipes require decode/recompile; current ZIP entry replacement avoids unnecessary reconstruction. |
| [smali/baksmali/dexlib2](https://github.com/google/smali), [original project](https://github.com/JesusFreke/smali) | **D:** assembly/disassembly and typed DEX model/writer. Existing ModKit uses org.smali:dexlib2:2.5.2, BSD-3-Clause. | Keep the existing emitter; add instruction/dataflow evidence, negative fixtures, exact reparse and ART execution. Do not add a competing DEX writer. |
| [apksig](https://android.googlesource.com/platform/tools/apksig/) | **D:** official APK signature library with signing and verification. Apache-2.0. Structural signature validity is separate from installation and behavior. | Keep apksig; exercise AndroidKeyStore signing on Android, retain nested provider errors, verify every split and copied bytes. |
| [Frida Android](https://frida.re/docs/android/), [Gadget](https://frida.re/docs/gadget/) | **D:** runtime instrumentation via server/root or an injected Gadget/debugger in an authorized target. Gadget supports embedded operation; injection alters packaging/signing. | **I:** a future optional test adapter, not a promise that any installed app can be inspected without root. Existing probe should report exact provenance. Distribution requires component-specific license review. |
| [Ghidra](https://github.com/NationalSecurityAgency/ghidra) | **D:** native disassembly, decompilation and analysis framework; Apache-2.0. | **I:** development/reference tool for ARM64 fixtures and decoding. Shipping a desktop analysis framework inside the phone app is not justified. |
| [Il2CppDumper](https://github.com/Perfare/Il2CppDumper), [MIT license](https://github.com/Perfare/Il2CppDumper/blob/master/LICENSE) | **D:** metadata/executable pairing, dummy assemblies and analysis scripts; dummy metadata is not reconstructed original method logic. Protected/unsupported inputs have explicit failures. | **I:** compare registration/layout handling; retain exact token/slot evidence and full function-pointer census. Do not infer return ABI from method names. |
| [Cpp2IL](https://github.com/SamboyCoding/Cpp2IL) | **D:** LibCpp2IL parsing, native-to-ISIL, CFG/dominator analysis, transformations and CIL generation. Described as WIP; generated code can be imperfect. Core MIT, optional plugin licenses differ. | **I:** staged intermediate representation is preferable to names alone. No .NET runtime or unreviewed plugin bundle added to Android. |
| [BepInEx](https://github.com/BepInEx/BepInEx) | **D:** plugin/patch framework and platform compatibility matrix, not universal Android patch inference. Listed Unity IL2CPP matrix does not claim general ARM support. | **I:** recipe/plugin extension points are useful; a desktop loader cannot be presented as Android support. |
| [MelonLoader](https://github.com/LavaGang/MelonLoader) | **D:** Unity Mono/IL2CPP loader; Android/Quest support explicitly marked WIP in reviewed README; Apache-2.0 license file. | **I:** lifecycle/hooks need platform-specific validation. Do not equate the word universal with a validated phone pipeline. |
| [Android PackageInstaller](https://developer.android.com/reference/android/content/pm/PackageInstaller), [sessions](https://developer.android.com/reference/android/content/pm/PackageInstaller.Session) | **D:** session-based package submission, pending user action, status callbacks and failures. | One transaction for the entire split set; preserve confirmation state; actual installer status separate from build verification. |

## Chosen architecture

1. One discovery catalog, paginated in the UI. A presentation limit must not stop
   parsing. Each engine records inspected/skipped/unsupported counts.
2. Independent evidence stages: identity, method body, semantic hypothesis,
   concrete recipe, static checks, verified APK, functional observation.
3. DEX: inspect the value actually returned; recover field-backed getters even if
   the method name is obfuscated. Reject side effects and unsupported control flow
   until a recipe preserving them exists. Exact field identity is an xref, not
   proof that every caller affects gameplay.
4. IL2CPP: compact complete pointer census for alias and boundary checks, separate
   from memory-bounded human-readable metadata targets. Cache version changes
   whenever proof semantics change.
5. Reuse one staging/build/sign/install path. Compose presents grouped choices;
   expert inspection uses the same engines. No cloud analysis is required.
6. Self-authored test programs distinguish name decoys, side effects, field getters,
   actual ART execution and APK/session integration. Measured fixture coverage
   must never be reported as real-game success rate.

## License handling

No implementation from closed tools is imported. Research does not grant copying
rights. Existing dexlib2 and apksig dependencies are retained; any future imported
source must pin a revision and carry the actual source license/notice, including
transitive native dependencies. Frida components and optional Cpp2IL plugins need
their own review before distribution. No paid feature or external service is
required by these changes.
