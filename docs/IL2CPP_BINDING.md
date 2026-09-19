# IL2CPP evidence and executable binding

This document describes the current fail-closed IL2CPP path in ModKit. It is deliberately narrower than “all IL2CPP versions are supported”.

## Fast path

When the shared ArtifactIndex validates both:

- a `global-metadata.dat` candidate with IL2CPP metadata magic; and
- at least one `libil2cpp.so` candidate with valid ELF magic,

the router schedules `il2cpp.fast-dump` as TARGETED work.

The current structured metadata reader uses bounded random access and reconstructs the validated v27-v31 layouts used by the implementation. Unsupported versions keep the valid magic/version evidence but are not forced through a guessed record layout.

The fast dump can publish:

- metadata version/layout;
- images;
- type definitions;
- method definitions;
- field definitions;
- metadata tokens;
- a human-readable `dump.cs`.

This is metadata identity only. It does not invent RVAs or file offsets.

## Binary confirmation

`il2cpp.codegen-bind` is CONFIRMATION work and runs after a usable metadata image map exists.

The strongest current path is:

```text
metadata image
    ↓ exact image/module name
Il2CppCodeGenModule
    ↓ MethodDef token RID
method-pointer slot
    ↓ pointer validation
executable PT_LOAD address
    ↓ VA mapping
ELF file offset
```

A binding is emitted only when the selected slot points into an executable ELF load segment.

### Registration-backed discovery

If an unambiguous defined `g_CodeRegistration` / CodeRegistration-like dynamic symbol is available, ModKit scans bounded count/pointer pairs inside that structure and accepts a CodeGenModule array only after validating its module structures and executable method-pointer samples.

### Stripped fallback

A stripped binary may not expose CodeRegistration in `.dynsym`. In that case ModKit performs a bounded scan of file-backed non-executable PT_LOAD data.

A fallback candidate is accepted only when:

1. its module count equals the reconstructed metadata image count;
2. every module structure is readable;
3. module names are valid managed image names;
4. the complete unique module-name set exactly matches metadata image names;
5. sampled non-null method pointers meet the executable-segment validation threshold; and
6. the resulting structural candidate is unique.

The fallback scan is bounded. Hitting the bound produces an explicit blocker rather than a fabricated result.

## Proof levels

Current IL2CPP proof promotion is:

```text
valid structural metadata
    → EXACT_METADATA

exact metadata image + module + token slot + executable pointer
    → EXACT_BINARY

runtime observation
    → RUNTIME_CONFIRMED (future runtime layer)

validated mutation prepare/preflight + exact executable proof
    → CHANGE_READY
```

`EXACT_BINARY` does not itself mean an automatic modification is permitted. AutoMod/Patch Lab must still run mutation-specific prepare/preflight validation before `CHANGE_READY`.

## Fail-closed blocker examples

Current binary confirmation may return blockers such as:

- `METADATA_IMAGE_MAP_UNAVAILABLE`
- `CODE_REGISTRATION_SYMBOL_UNRESOLVED`
- `CODEGEN_MODULE_ARRAY_UNRESOLVED`
- `AMBIGUOUS_CODEGEN_MODULE_ARRAY`
- `CODEGEN_FALLBACK_SCAN_LIMIT_REACHED`
- `STRIPPED_CODEGEN_MODULE_ARRAY_UNRESOLVED`
- `NO_METHOD_TOKEN_SLOT_BINDINGS`

A successful stripped fallback clears the CodeRegistration-symbol blocker because the structural module-array proof replaced that missing symbol evidence.

## Current limitations

- structured metadata reconstruction is currently limited to the implemented v27-v31 layouts;
- the fallback intentionally scans only a bounded amount of non-executable file-backed data;
- not every protected or transformed IL2CPP build exposes recoverable static structures;
- multi-ABI confirmation currently stops after the first library that yields exact bindings;
- runtime VA rebasing and runtime confirmation are not implemented yet;
- mutation prepare/preflight is not implemented yet.

These limitations are explicit so partial coverage cannot be mistaken for proof of absence.
