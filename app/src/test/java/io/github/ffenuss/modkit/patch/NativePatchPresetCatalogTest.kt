package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePatchPresetCatalogTest {
    @Test
    fun arm64PresetsAreInstructionAlignedAndParseable() {
        val presets =
            NativePatchPresetCatalog.forAbi("arm64-v8a")

        assertEquals(15, presets.size)
        presets.forEach { preset ->
            val bytes =
                Il2CppNativeMutationDraftBuilder.parseHex(
                    preset.replacementHex,
                )
            assertTrue(bytes.isNotEmpty())
            assertEquals(0, bytes.size % 4)
        }
    }

    @Test
    fun arm64ReturnPresetsUseExpectedInstructions() {
        val byId =
            NativePatchPresetCatalog.forAbi("arm64-v8a")
                .associateBy { it.id }

        assertEquals(
            "C0 03 5F D6",
            byId.getValue("arm64-return-void")
                .replacementHex,
        )
        assertEquals(
            "00 00 80 D2 C0 03 5F D6",
            byId.getValue("arm64-return-zero")
                .replacementHex,
        )
        assertEquals(
            "20 00 80 D2 C0 03 5F D6",
            byId.getValue("arm64-return-one")
                .replacementHex,
        )
        assertEquals(
            "00 10 20 1E C0 03 5F D6",
            byId.getValue("arm64-return-f32-two")
                .replacementHex,
        )
        assertEquals(
            "00 10 60 1E C0 03 5F D6",
            byId.getValue("arm64-return-f64-two")
                .replacementHex,
        )
    }

    @Test
    fun semanticPresetsRequireProvenCompatibleReturnKind() {
        assertEquals(
            listOf("arm64-return-void"),
            NativePatchPresetCatalog
                .forProvenReturnKind(
                    "arm64-v8a",
                    Il2CppNativeReturnKind.VOID,
                )
                .map { it.id },
        )
        assertEquals(
            listOf("arm64-return-zero", "arm64-return-one"),
            NativePatchPresetCatalog
                .forProvenReturnKind(
                    "arm64-v8a",
                    Il2CppNativeReturnKind.BOOLEAN,
                )
                .map { it.id },
        )
        assertEquals(
            listOf("arm64-return-zero"),
            NativePatchPresetCatalog
                .forProvenReturnKind(
                    "arm64-v8a",
                    Il2CppNativeReturnKind.POINTER_OR_REFERENCE,
                )
                .map { it.id },
        )
        assertEquals(
            listOf(
                "arm64-return-f32-half",
                "arm64-return-f32-zero",
                "arm64-return-f32-one",
                "arm64-return-f32-two",
                "arm64-return-f32-three",
                "arm64-return-f32-five",
            ),
            NativePatchPresetCatalog
                .forProvenReturnKind(
                    "arm64-v8a",
                    Il2CppNativeReturnKind.FLOAT32,
                )
                .map { it.id },
        )
        assertEquals(
            listOf(
                "arm64-return-f64-half",
                "arm64-return-f64-zero",
                "arm64-return-f64-one",
                "arm64-return-f64-two",
                "arm64-return-f64-three",
                "arm64-return-f64-five",
            ),
            NativePatchPresetCatalog
                .forProvenReturnKind(
                    "arm64-v8a",
                    Il2CppNativeReturnKind.FLOAT64,
                )
                .map { it.id },
        )
        assertTrue(
            NativePatchPresetCatalog
                .forProvenReturnKind(
                    "arm64-v8a",
                    Il2CppNativeReturnKind.FLOATING_POINT,
                )
                .isEmpty(),
        )
        assertTrue(
            NativePatchPresetCatalog
                .forProvenReturnKind(
                    "arm64-v8a",
                    Il2CppNativeReturnKind.UNKNOWN,
                )
                .isEmpty(),
        )
    }

    @Test
    fun unsupportedAbiHasNoAutomaticPreset() {
        assertTrue(
            NativePatchPresetCatalog.forAbi("x86_64")
                .isEmpty(),
        )
    }
}
