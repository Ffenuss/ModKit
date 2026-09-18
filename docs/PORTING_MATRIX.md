# Porting matrix

Nothing is bulk-copied from UniRevLab Security. Every subsystem is reviewed against the canonical specification before migration.

| Subsystem | Legacy state | New repo state | Required before DONE |
|---|---|---|---|
| Runtime Profiler | Implemented | TO_REVIEW | Preserve multi-label behavior; integrate fast routing |
| Engine Router | Implemented | TO_REVIEW | FAST/TARGETED/CONFIRMATION/BACKGROUND |
| Artifact Inventory | Implemented | TO_REWORK | Shared single-pass ArtifactIndex |
| Deobfuscator | Implemented but incomplete | TO_REVIEW | Reflection/loaders/encrypted strings/R8/native-JNI/resources |
| ARM64 native deep | Strong but incomplete | TO_REVIEW | Exact binary/change-ready proof |
| ARMv7/Thumb | Partial | TO_REWORK | Full CFG/register/stack/PLT/GOT/JNI/dlsym |
| x86 | Partial | TO_REWORK | Decoder/CFG/ModRM/SIB/register/stack/import/indirect |
| x86-64 | Partial | TO_REWORK | RIP-relative/SysV/indirect/dlsym |
| IL2CPP | Substantial but incomplete | TOP PRIORITY | Fast dump, CodeGen, exact metadata↔binary proof, blockers |
| .NET/Mono | Metadata parser | TO_REVIEW | CIL/CFG/calls/reconstruction/NativeAOT/R2R |
| Unreal | Structural | TO_REVIEW | PAK/IoStore/object/FName/Blueprint |
| Godot | Source/scene | TO_REVIEW | Binary PCK/resources/compiled GDScript |
| Defold | Inventory | TO_REVIEW | Archive/manifest/dependency graph |
| Qt/QML | Source parser | TO_REVIEW | Versioned compiled cache |
| JSC | Source + inventory | TO_REVIEW | Bytecode decoder; no fake source |
| WASM | Header/sections/exports | TO_REVIEW | Instructions/CFG/calls/globals/memory |
| Flutter | Backend exists | TO_REVIEW | AOT depth/evidence quality |
| Hermes | Backend exists | TO_REVIEW | HBC depth/evidence quality |
| Cocos | Correlation exists | TO_REVIEW | Deeper generic correlation |
| Network/TLS/Crypto | Exists | RESCHEDULE | Targeted/background only |
| Patch Lab | Exists | TO_REWORK | Compact workflow; internal prepare/preflight |
| AutoMod | Exists in parts | TO_REWORK | CHANGE_READY only |
| Storage | Exists | TO_REWORK | Human-readable primary outputs |
| Simple Mode | Exists | TO_REWORK | Hide pipeline jargon, preserve valid data sources |
