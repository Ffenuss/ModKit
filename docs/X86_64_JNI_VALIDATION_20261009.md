# Bounded x86_64 JNI discovery

Implementation checkpoint: `9b258720c4c1a4e35ca3d286f91bb24a2de950a1`.
No production APK is issued from this checkpoint.

## Implementation

JNI discovery now separates ARM64, ARM32 and x86_64 exports and image identities. All eight primitive result encodings exist for x86_64: integer/boolean/narrow returns use EAX, long uses RAX, float and double use XMM0 with bit-exact MOVD/MOVQ transfers. The bounded proof accepts immediate moves, these register transfers, LEA EAX,[RDX+disp8], NOP and RET. Calls, stores, arbitrary loads, branches, callee-saved register changes and incomplete instructions are rejected. An exact entry ENDBR64 is preserved.

The current native_v1 contract remains unchanged. Address alignment is four bytes and replacement ranges are padded after RET to a multiple of four bytes. The entire range must fit the exported, file-backed executable symbol and avoid aliases/overlaps. Short stubs with insufficient symbol space and unaligned entries remain unavailable. Padding is not permission to write beyond a function. This implements no IA-32/x87 route, no RegisterNatives discovery and no arbitrary x86 instruction interpreter.

The owned APK has 10 x86_64 recipes, 8 ARM64 recipes and 6 ARM32 recipes, with distinct ABI identities. New x86_64-only boolean and float exports let the real guest menu assert original values, independent ON/OFF effects and close-time restoration. The APK bytes must remain unchanged.

Android ABI reference: https://developer.android.com/ndk/guides/abis#x86_64

## Verification

Android CI: https://github.com/Ffenuss/ModKit/actions/runs/37990542247
Space CI: https://github.com/Ffenuss/ModKit/actions/runs/37990542252
- 452 unit tests passed, no failures/errors/skips; lint and internal debug assembly passed.
- Independent CPU execution passed 12 ARM64, 10 ARM32 and 10 x86_64 vectors. The x86_64 checker verifies full-width long, float/double bit interpretation, callee-saved registers, stack return and unchanged guest memory.
- JVM report ZIP SHA256: `4a055a74264c1345038a4dd98d8c54ba063795e47be1116584a19259940418e9`.
- Android35 split scenario passed on the first attempt, including discovery of 24 ABI-separated JNI recipes and all eight x86_64 primitive return types.
- Space source compilation/contracts and 13 executor tests passed on the first attempt.
- First-attempt guest tests failed before native toggles: accessibility could not find the initial guest text or the replacement-session label; Android logs contain AccessibilityManager window-add timeouts. Failure report SHA256: `5419b8662dfa52d961b60a2e98e88eb4acbfe81ab3ef76495f7a1252d0fcd2dd`.
- First-attempt Android29 DEX scenario failed after reconfiguration because its MK button was absent from accessibility lookup while the captured screenshot showed it. Failure report SHA256: `3c61dd4c2821683ed2705ae890363ed78a9d7b8009918e78495e1bf222273fbf`.
- Fresh-emulator retries passed with unchanged code/assertions: Android29 has 8 tests with zero failures/errors/skips; Space has 13 executor and 3 guest/panel tests with zero failures/errors/skips. The new guest test verifies boolean and float ON/OFF independence and float restoration on close.
- Both Android APIs completed the DEX scenario with 44 events and 11 exact Math prototypes.
- Successful API29 report SHA256: `ef4d53ba4fa1cd9c78e1e941c6a7bdf01bf25c19f51f57619a0b216ca10f56ec`.
- Successful API35 report SHA256: `d55d78356f1c9dfc6b6f699838244bd03e5106335a2fb78bc6d32291b8f5e937`.
- Successful Space report SHA256: `3b24323553a2cd9a1fc59ea3e0b866a660210d0d60d1be6bd42aa30d8c65397b`.
- These initial UI failures are retained here; passing retries alone do not establish release UI reliability. No speculative application-code fix or relaxed test assertion was introduced to hide them. Final production assembly stayed disabled.

## Release boundary

The earlier ARM32/session record remains in SPACE_RUNTIME_VALIDATION_20261009.md. This checkpoint requires the same preserved signing material and authenticated original Space APK for final pair delivery. Tests use owned apps/emulators; physical phone, proprietary Space kernel startup and third-party game effects remain unconfirmed. Full compatibility is not claimed.
