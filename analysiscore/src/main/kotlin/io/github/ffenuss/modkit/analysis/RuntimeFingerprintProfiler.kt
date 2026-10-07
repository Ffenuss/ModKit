package io.github.ffenuss.modkit.analysis

object RuntimeFingerprintProfiler {
    fun profile(entries: List<ArtifactEntry>): List<RuntimeProfile> {
        val byTag = entries
            .flatMap { entry -> entry.tags.map { tag -> tag to entry } }
            .groupBy({ it.first }, { it.second })

        fun evidence(vararg tags: String): List<String> =
            tags.flatMap { byTag[it].orEmpty() }
                .map { "${it.container}:${it.path}" }
                .distinct()
                .take(32)

        val out = mutableListOf<RuntimeProfile>()

        val validDex = evidence("dex_valid")
        if (validDex.isNotEmpty()) {
            out += confirmed("android_dex", "Android Java/Kotlin / DEX", validDex)
        }

        val validElf = evidence("elf_valid")
        if (validElf.isNotEmpty()) {
            out += confirmed("native_elf", "Native ELF / NDK", validElf)
        }

        val il2cppBinary = byTag["il2cpp_binary"].orEmpty().filter { "elf_valid" in it.tags }
        val il2cppMetadata = byTag["il2cpp_metadata"].orEmpty().filter { "il2cpp_metadata_valid" in it.tags }
        if (il2cppBinary.isNotEmpty() && il2cppMetadata.isNotEmpty()) {
            out += confirmed(
                "unity_il2cpp",
                "Unity / IL2CPP",
                (il2cppBinary + il2cppMetadata).map { "${it.container}:${it.path}" },
            )
        } else {
            val signals = evidence("il2cpp_binary", "il2cpp_metadata", "unity_native")
            if (signals.isNotEmpty()) out += likely("unity_il2cpp", "Unity / IL2CPP", signals)
        }

        val mono = evidence("managed_layout", "mono_native")
        if (mono.size >= 2) out += likely("unity_mono", "Unity / Mono / managed", mono)

        val flutter = evidence("flutter_engine", "flutter_app", "flutter_asset")
        if (flutter.size >= 2) out += likely("flutter", "Flutter / Dart", flutter)

        val hermes = evidence("hermes_native", "hermes_bytecode_candidate")
        if (hermes.isNotEmpty()) out += likely("react_native_hermes", "React Native / Hermes", hermes)

        val jsc = evidence("jsc_native")
        if (jsc.isNotEmpty()) out += likely("react_native_jsc", "React Native / JavaScriptCore", jsc)

        val managed = evidence("managed_candidate", "mono_native")
        if (managed.size >= 2) out += likely("dotnet_android", ".NET Android / Xamarin / MAUI", managed)

        val unreal = evidence("unreal_native", "unreal_container_candidate", "unreal_asset_candidate")
        if (unreal.isNotEmpty()) out += likely("unreal", "Unreal Engine", unreal)

        val godot = evidence("godot_native", "godot_pck_candidate")
        if (godot.isNotEmpty()) out += likely("godot", "Godot", godot)

        val defold = evidence("defold_native")
        if (defold.isNotEmpty()) out += likely("defold", "Defold", defold)

        val qt = evidence("qt_qml_signal")
        if (qt.isNotEmpty()) out += signal("qt_qml", "Qt / QML", qt)

        val cocos = evidence("cocos_native")
        if (cocos.isNotEmpty()) out += likely("cocos", "Cocos2d-x / Cocos Creator", cocos)

        val lua = evidence("lua_signal")
        if (lua.isNotEmpty()) out += signal("lua_runtime", "Lua runtime", lua)

        val cordova = evidence("cordova_signal")
        if (cordova.isNotEmpty()) out += signal("cordova", "Apache Cordova / Ionic", cordova)

        val capacitor = evidence("capacitor_signal")
        if (capacitor.isNotEmpty()) out += signal("capacitor", "Capacitor", capacitor)

        val wasm = byTag["wasm_valid"].orEmpty().map { "${it.container}:${it.path}" }.distinct()
        if (wasm.isNotEmpty()) out += confirmed("webassembly", "WebAssembly", wasm)

        if (out.isEmpty() && entries.isNotEmpty()) {
            out += RuntimeProfile(
                runtimeId = "unknown",
                title = "Unknown / custom runtime",
                status = DetectionStatus.SIGNAL,
                confidence = DetectionConfidence.LOW,
                evidence = entries.take(8).map { "${it.container}:${it.path}" },
            )
        }
        return out.distinctBy { it.runtimeId }
    }

    private fun confirmed(id: String, title: String, evidence: List<String>) =
        RuntimeProfile(id, title, DetectionStatus.CONFIRMED, DetectionConfidence.HIGH, evidence.take(32))

    private fun likely(id: String, title: String, evidence: List<String>) =
        RuntimeProfile(id, title, DetectionStatus.LIKELY, DetectionConfidence.MEDIUM, evidence.take(32))

    private fun signal(id: String, title: String, evidence: List<String>) =
        RuntimeProfile(id, title, DetectionStatus.SIGNAL, DetectionConfidence.LOW, evidence.take(32))
}
