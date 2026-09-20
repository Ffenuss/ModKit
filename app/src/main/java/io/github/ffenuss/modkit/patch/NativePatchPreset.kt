package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind

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

    fun find(
        abi: String,
        id: String,
    ): NativePatchPreset? =
        forAbi(abi).firstOrNull { it.id == id }

    fun forProvenReturnKind(
        abi: String,
        returnKind: Il2CppNativeReturnKind,
    ): List<NativePatchPreset> {
        if (!abi.equals("arm64-v8a", ignoreCase = true)) return emptyList()
        return when (returnKind) {
            Il2CppNativeReturnKind.VOID ->
                listOf(arm64ReturnVoid)
            Il2CppNativeReturnKind.BOOLEAN,
            Il2CppNativeReturnKind.INTEGER ->
                listOf(arm64ReturnZero, arm64ReturnOne)
            Il2CppNativeReturnKind.POINTER_OR_REFERENCE ->
                listOf(arm64ReturnZero)
            Il2CppNativeReturnKind.FLOATING_POINT,
            Il2CppNativeReturnKind.VALUE_TYPE,
            Il2CppNativeReturnKind.UNKNOWN ->
                emptyList()
        }
    }

    private val arm64ReturnVoid =
        NativePatchPreset(
            id = "arm64-return-void",
            label = "Сразу завершить метод",
            description =
                "ARM64 RET. Доступно только когда ModKit доказал, что return type = void.",
            replacementHex = "C0 03 5F D6",
        )

    private val arm64ReturnZero =
        NativePatchPreset(
            id = "arm64-return-zero",
            label = "Всегда вернуть 0 / false / null",
            description =
                "ARM64 MOV X0,#0; RET. Показывается только для доказанного совместимого return type.",
            replacementHex = "00 00 80 D2 C0 03 5F D6",
        )

    private val arm64ReturnOne =
        NativePatchPreset(
            id = "arm64-return-one",
            label = "Всегда вернуть 1 / true",
            description =
                "ARM64 MOV X0,#1; RET. Показывается только для доказанного bool/integer return type.",
            replacementHex = "20 00 80 D2 C0 03 5F D6",
        )

    private val arm64Presets =
        listOf(
            arm64ReturnVoid,
            arm64ReturnZero,
            arm64ReturnOne,
        )
}
