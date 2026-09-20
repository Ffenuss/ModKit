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
            Il2CppNativeReturnKind.FLOAT32 ->
                listOf(
                    arm64ReturnF32Half,
                    arm64ReturnF32Zero,
                    arm64ReturnF32One,
                    arm64ReturnF32Two,
                    arm64ReturnF32Three,
                    arm64ReturnF32Five,
                )
            Il2CppNativeReturnKind.FLOAT64 ->
                listOf(
                    arm64ReturnF64Half,
                    arm64ReturnF64Zero,
                    arm64ReturnF64One,
                    arm64ReturnF64Two,
                    arm64ReturnF64Three,
                    arm64ReturnF64Five,
                )
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


    private val arm64ReturnF32Zero =
        NativePatchPreset(
            id = "arm64-return-f32-zero",
            label = "Всегда вернуть 0.0f",
            description =
                "ARM64 FMOV S0,WZR; RET. Только для доказанного IL2CPP float (R4).",
            replacementHex = "E0 03 27 1E C0 03 5F D6",
        )

    private val arm64ReturnF32Half =
        NativePatchPreset(
            id = "arm64-return-f32-half",
            label = "Всегда вернуть 0.5f",
            description =
                "ARM64 FMOV S0,#0.5; RET. Только для доказанного IL2CPP float (R4).",
            replacementHex = "00 10 2C 1E C0 03 5F D6",
        )

    private val arm64ReturnF32One =
        NativePatchPreset(
            id = "arm64-return-f32-one",
            label = "Всегда вернуть 1.0f",
            description =
                "ARM64 FMOV S0,#1.0; RET. Только для доказанного IL2CPP float (R4).",
            replacementHex = "00 10 2E 1E C0 03 5F D6",
        )

    private val arm64ReturnF32Two =
        NativePatchPreset(
            id = "arm64-return-f32-two",
            label = "Всегда вернуть 2.0f",
            description =
                "ARM64 FMOV S0,#2.0; RET. Только для доказанного IL2CPP float (R4).",
            replacementHex = "00 10 20 1E C0 03 5F D6",
        )

    private val arm64ReturnF32Three =
        NativePatchPreset(
            id = "arm64-return-f32-three",
            label = "Всегда вернуть 3.0f",
            description =
                "ARM64 FMOV S0,#3.0; RET. Только для доказанного IL2CPP float (R4).",
            replacementHex = "00 10 21 1E C0 03 5F D6",
        )

    private val arm64ReturnF32Five =
        NativePatchPreset(
            id = "arm64-return-f32-five",
            label = "Всегда вернуть 5.0f",
            description =
                "ARM64 FMOV S0,#5.0; RET. Только для доказанного IL2CPP float (R4).",
            replacementHex = "00 90 22 1E C0 03 5F D6",
        )

    private val arm64ReturnF64Zero =
        NativePatchPreset(
            id = "arm64-return-f64-zero",
            label = "Всегда вернуть 0.0",
            description =
                "ARM64 FMOV D0,XZR; RET. Только для доказанного IL2CPP double (R8).",
            replacementHex = "E0 03 67 9E C0 03 5F D6",
        )

    private val arm64ReturnF64Half =
        NativePatchPreset(
            id = "arm64-return-f64-half",
            label = "Всегда вернуть 0.5",
            description =
                "ARM64 FMOV D0,#0.5; RET. Только для доказанного IL2CPP double (R8).",
            replacementHex = "00 10 6C 1E C0 03 5F D6",
        )

    private val arm64ReturnF64One =
        NativePatchPreset(
            id = "arm64-return-f64-one",
            label = "Всегда вернуть 1.0",
            description =
                "ARM64 FMOV D0,#1.0; RET. Только для доказанного IL2CPP double (R8).",
            replacementHex = "00 10 6E 1E C0 03 5F D6",
        )

    private val arm64ReturnF64Two =
        NativePatchPreset(
            id = "arm64-return-f64-two",
            label = "Всегда вернуть 2.0",
            description =
                "ARM64 FMOV D0,#2.0; RET. Только для доказанного IL2CPP double (R8).",
            replacementHex = "00 10 60 1E C0 03 5F D6",
        )

    private val arm64ReturnF64Three =
        NativePatchPreset(
            id = "arm64-return-f64-three",
            label = "Всегда вернуть 3.0",
            description =
                "ARM64 FMOV D0,#3.0; RET. Только для доказанного IL2CPP double (R8).",
            replacementHex = "00 10 61 1E C0 03 5F D6",
        )

    private val arm64ReturnF64Five =
        NativePatchPreset(
            id = "arm64-return-f64-five",
            label = "Всегда вернуть 5.0",
            description =
                "ARM64 FMOV D0,#5.0; RET. Только для доказанного IL2CPP double (R8).",
            replacementHex = "00 90 62 1E C0 03 5F D6",
        )

    private val arm64Presets =
        listOf(
            arm64ReturnVoid,
            arm64ReturnZero,
            arm64ReturnOne,
            arm64ReturnF32Half,
            arm64ReturnF32Zero,
            arm64ReturnF32One,
            arm64ReturnF32Two,
            arm64ReturnF32Three,
            arm64ReturnF32Five,
            arm64ReturnF64Half,
            arm64ReturnF64Zero,
            arm64ReturnF64One,
            arm64ReturnF64Two,
            arm64ReturnF64Three,
            arm64ReturnF64Five,
        )
}
