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
                missingText = "DEX deep inventory has not yet been migrated into the clean repository",
            )
        }
        if ("native_elf" in runtimes) {
            targeted(
                id = "elf.universal-inventory",
                reason = "Validated ELF present",
                missingText = "Universal ELF deep inventory is pending reviewed migration",
            )
        }
        if ("unity_il2cpp" in runtimes) {
            targeted(
                id = "il2cpp.fast-dump",
                reason = "Validated global-metadata.dat + libil2cpp.so pair",
                missingText = "IL2CPP fast dump / CodeGen exact binding is the next top-priority migration",
            )
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
