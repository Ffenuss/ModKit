package io.github.ffenuss.modkit.patch

data class NativePatchPreset(
    val id: String,
    val label: String,
    val description: String,
    val replacementHex: String,
)

object NativePatchPresetCatalog {
    fun forAbi(abi: String): List<NativePatchPreset> =
        when (abi.lowercase()) {
            "arm64-v8a" -> arm64Presets
            else -> emptyList()
        }

    private val arm64Presets =
        listOf(
            NativePatchPreset(
                id = "arm64-return-void",
                label = "Сразу вернуть (void)",
                description =
                    "ARM64 RET. Используйте только для метода без возвращаемого значения.",
                replacementHex = "C0 03 5F D6",
            ),
            NativePatchPreset(
                id = "arm64-return-zero",
                label = "Вернуть 0",
                description =
                    "ARM64 MOV X0,#0; RET. Для bool/int/pointer-подобного результата, когда 0 корректен.",
                replacementHex = "00 00 80 D2 C0 03 5F D6",
            ),
            NativePatchPreset(
                id = "arm64-return-one",
                label = "Вернуть 1",
                description =
                    "ARM64 MOV X0,#1; RET. Для bool/int-подобного результата, когда 1 корректна.",
                replacementHex = "20 00 80 D2 C0 03 5F D6",
            ),
        )
}
