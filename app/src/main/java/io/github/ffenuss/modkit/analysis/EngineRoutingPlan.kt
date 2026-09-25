package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineScheduleClass

data class PlannedEngine(
    val id: String,
    val scheduleClass: EngineScheduleClass,
    val availableNow: Boolean,
    val reason: String,
)

data class EngineRoutingPlan(
    val engines: List<PlannedEngine>,
    val missingCapabilities: List<String>,
) {
    val fast: List<PlannedEngine> get() = engines.filter { it.scheduleClass == EngineScheduleClass.FAST }
    val targeted: List<PlannedEngine> get() = engines.filter { it.scheduleClass == EngineScheduleClass.TARGETED }
    val confirmation: List<PlannedEngine> get() = engines.filter { it.scheduleClass == EngineScheduleClass.CONFIRMATION }
    val background: List<PlannedEngine> get() = engines.filter { it.scheduleClass == EngineScheduleClass.BACKGROUND }
}

/**
 * Demand-driven router. It deliberately describes unavailable deep engines instead
 * of pretending the backend is fully supported.
 */
object EngineRouter {
    fun plan(index: ArtifactIndex): EngineRoutingPlan {
        val runtimes = index.runtimeProfiles.map { it.runtimeId }.toSet()
        val engines = mutableListOf(
            PlannedEngine(
                id = "artifact.fast-index",
                scheduleClass = EngineScheduleClass.FAST,
                availableNow = true,
                reason = "Single-pass target inventory and validated format probes",
            ),
        )
        val missing = mutableListOf<String>()

        fun targeted(id: String, reason: String, available: Boolean = false, missingText: String? = null) {
            engines += PlannedEngine(id, EngineScheduleClass.TARGETED, available, reason)
            if (!available && missingText != null) missing += missingText
        }

        if ("android_dex" in runtimes) {
            targeted(
                id = "dex.inventory",
                reason = "Validated DEX present",
                available = true,
            )
        }
        if ("native_elf" in runtimes) {
            targeted(
                id = "elf.universal-inventory",
                reason = "Validated ELF present",
                available = true,
            )
        }
        val il2cppProfile = index.runtimeProfiles.firstOrNull {
            it.runtimeId == "unity_il2cpp"
        }
        if (il2cppProfile != null) {
            // A LIKELY runtime ID is not proof that an extractable metadata
            // image exists. Aniimo's partial evidence used to route into
            // fast-dump and fail on its first required input.
            val binaryReady = index.entries.any {
                "il2cpp_binary" in it.tags && "elf_valid" in it.tags
            }
            val metadataReady = index.entries.any {
                "il2cpp_metadata" in it.tags && "il2cpp_metadata_valid" in it.tags
            }
            if (il2cppProfile.status == DetectionStatus.CONFIRMED &&
                binaryReady && metadataReady
            ) {
                targeted(
                    id = "il2cpp.fast-dump",
                    reason = "Validated global-metadata.dat + libil2cpp.so pair",
                    available = true,
                )
                engines += PlannedEngine(
                    id = "il2cpp.codegen-bind",
                    scheduleClass = EngineScheduleClass.CONFIRMATION,
                    availableNow = true,
                    reason = "Run after metadata image reconstruction to prove token-slot executable bindings",
                )
            } else {
                missing += when {
                    !metadataReady && !binaryReady ->
                        "IL2CPP: нет проверенных global-metadata.dat и libil2cpp.so. " +
                            "Доступен частичный анализ DEX и ELF."
                    !metadataReady ->
                        "IL2CPP: global-metadata.dat не найдена или не прошла проверку. " +
                            "Необходимо исследовать упаковку/состав APK; DEX и ELF доступны отдельно."
                    !binaryReady ->
                        "IL2CPP: libil2cpp.so отсутствует или не прошла ELF-проверку. " +
                            "Проверьте исходный APK и все split-файлы."
                    else ->
                        "IL2CPP: признаки движка найдены, но достоверность профиля не подтверждена."
                }
            }
        }
        if ("unity_mono" in runtimes || "dotnet_android" in runtimes) {
            targeted(
                id = "dotnet.metadata",
                reason = "Managed assembly/runtime evidence",
                missingText = ".NET metadata can be migrated, but CIL/CFG/calls remain incomplete in legacy code",
            )
        }
        if ("flutter" in runtimes) targeted("flutter.dart-aot", "Flutter evidence", missingText = "Flutter AOT deep backend pending review")
        if ("react_native_hermes" in runtimes) targeted("hermes.bytecode", "Hermes evidence", missingText = "Hermes deep backend pending review")
        if ("react_native_jsc" in runtimes) targeted("jsc.bytecode", "JavaScriptCore evidence", missingText = "JSC version-specific bytecode decoder remains incomplete")
        if ("unreal" in runtimes) targeted("unreal.deep", "Unreal evidence", missingText = "Unreal PAK/IoStore/object model remains incomplete")
        if ("godot" in runtimes) targeted("godot.deep", "Godot evidence", missingText = "Godot binary PCK/resources remain incomplete")
        if ("defold" in runtimes) targeted("defold.deep", "Defold evidence", missingText = "Defold archive/dependency graph remains incomplete")
        if ("qt_qml" in runtimes) targeted("qt.qml", "Qt/QML evidence", missingText = "Compiled QML cache parser remains incomplete")
        if ("webassembly" in runtimes) targeted("wasm.deep", "Validated WASM present", missingText = "WASM instruction/CFG backend remains incomplete")
        if ("cocos" in runtimes) targeted("cocos.correlation", "Cocos evidence", missingText = "Cocos deep correlation pending review")
        if ("lua_runtime" in runtimes) targeted("lua.inventory", "Lua evidence", missingText = "Lua runtime backend pending review")

        if (runtimes.any { it == "android_dex" || it == "native_elf" }) {
            engines += PlannedEngine(
                id = "security.network-tls-crypto",
                scheduleClass = EngineScheduleClass.BACKGROUND,
                availableNow = false,
                reason = "Heavy security enrichment must never block first useful result",
            )
        }

        return EngineRoutingPlan(
            engines = engines.distinctBy { it.id },
            missingCapabilities = missing.distinct(),
        )
    }
}
