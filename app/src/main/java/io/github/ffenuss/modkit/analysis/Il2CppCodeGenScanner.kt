package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.domain.EngineProgress
import io.github.ffenuss.modkit.domain.EngineScheduleClass
import io.github.ffenuss.modkit.domain.RunState
import java.io.File

data class Il2CppCodeGenModuleEvidence(
    val moduleName: String,
    val moduleVirtualAddress: Long,
    val methodPointerCount: Int,
    val methodPointersVirtualAddress: Long,
    val sampledPointers: Int,
    val executablePointers: Int,
) : java.io.Serializable

data class Il2CppMethodBinaryBinding(
    val methodIndex: Int,
    val managedIdentity: String,
    val metadataToken: Long,
    val imageName: String,
    val moduleName: String,
    val slotIndex: Int,
    val functionVirtualAddress: Long,
    val functionFileOffset: Long?,
) : java.io.Serializable

data class Il2CppBinaryEvidence(
    val libraryEntry: String,
    val machine: Int,
    val pointerSize: Int,
    val relativeRelocationCount: Int,
    val codeRegistrationVirtualAddress: Long?,
    val metadataRegistrationVirtualAddress: Long?,
    val codegenRegisterVirtualAddress: Long?,
    val moduleArrayDiscovery: String?,
    val modules: List<Il2CppCodeGenModuleEvidence>,
    val bindings: List<Il2CppMethodBinaryBinding>,
    val blockers: List<String>,
) : java.io.Serializable {
    val exactBindingAvailable: Boolean
        get() = bindings.isNotEmpty()
}

object Il2CppCodeGenScanner {
    private const val MAX_MODULES = 4_096
    private const val MAX_METHOD_POINTERS = 5_000_000
    private const val MAX_SAMPLED_POINTERS = 32
    private const val MAX_FALLBACK_SCAN_BYTES = 64L * 1024L * 1024L
    private const val HEARTBEAT_MS = 1_500L

    private data class ModuleArrayCandidate(
        val modules: List<Il2CppCodeGenModuleEvidence>,
        val discovery: String,
    )

    fun scan(
        file: File,
        libraryEntry: String,
        metadata: Il2CppMetadataModel,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): Il2CppBinaryEvidence {
        ElfImage.open(
            file = file,
            cancellation = cancellation,
            resolveRelativeRelocations = true,
        ).use { image ->
            val blockers = mutableListOf<String>()
            val codeRegistration = uniqueDefinedSymbol(image, "coderegistration")
            val metadataRegistration = uniqueDefinedSymbol(image, "metadataregistration")
            val codegenRegister = uniqueDefinedSymbol(image, "il2cpp_codegen_register")

            if (metadata.images.isEmpty()) {
                blockers += "METADATA_IMAGE_MAP_UNAVAILABLE"
            }

            var candidate: ModuleArrayCandidate? = null
            if (codeRegistration != null) {
                candidate = recoverFromCodeRegistration(
                    image = image,
                    codeRegistrationVa = codeRegistration,
                    metadata = metadata,
                    cancellation = cancellation,
                    progress = progress,
                    libraryEntry = libraryEntry,
                    blockers = blockers,
                )
                if (candidate == null) blockers += "CODEGEN_MODULE_ARRAY_UNRESOLVED"
            } else {
                blockers += "CODE_REGISTRATION_SYMBOL_UNRESOLVED"
            }

            if (candidate == null && metadata.images.isNotEmpty()) {
                candidate = recoverByImageSetScan(
                    image = image,
                    metadata = metadata,
                    cancellation = cancellation,
                    progress = progress,
                    libraryEntry = libraryEntry,
                    blockers = blockers,
                )
                if (candidate != null) {
                    blockers.remove("CODE_REGISTRATION_SYMBOL_UNRESOLVED")
                    blockers.remove("CODEGEN_MODULE_ARRAY_UNRESOLVED")
                }
            }

            val modules = candidate?.modules.orEmpty()
            val bindings = if (modules.isNotEmpty() && metadata.images.isNotEmpty()) {
                bindMethods(
                    image = image,
                    metadata = metadata,
                    modules = modules,
                    cancellation = cancellation,
                    progress = progress,
                    libraryEntry = libraryEntry,
                )
            } else {
                emptyList()
            }

            if (modules.isNotEmpty() && bindings.isEmpty()) {
                blockers += "NO_METHOD_TOKEN_SLOT_BINDINGS"
            }

            return Il2CppBinaryEvidence(
                libraryEntry = libraryEntry,
                machine = image.machine,
                pointerSize = image.pointerSize,
                relativeRelocationCount =
                    image.relativeRelocationCount,
                codeRegistrationVirtualAddress = codeRegistration,
                metadataRegistrationVirtualAddress = metadataRegistration,
                codegenRegisterVirtualAddress = codegenRegister,
                moduleArrayDiscovery = candidate?.discovery,
                modules = modules,
                bindings = bindings,
                blockers = blockers.distinct(),
            )
        }
    }

    private fun uniqueDefinedSymbol(image: ElfImage, needle: String): Long? {
        val normalizedNeedle = needle.lowercase()
        val candidates = image.dynamicSymbols.asSequence()
            .filter { it.defined && it.value > 0L }
            .filter { symbol ->
                val name = symbol.name.lowercase()
                if (normalizedNeedle == "il2cpp_codegen_register") {
                    name == normalizedNeedle
                } else {
                    name.contains(normalizedNeedle)
                }
            }
            .map { it.value }
            .distinct()
            .take(2)
            .toList()
        return candidates.singleOrNull()
    }

    private fun recoverFromCodeRegistration(
        image: ElfImage,
        codeRegistrationVa: Long,
        metadata: Il2CppMetadataModel,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        libraryEntry: String,
        blockers: MutableList<String>,
    ): ModuleArrayCandidate? {
        val pointerSize = image.pointerSize
        val pairStride = pointerSize * 2L
        val expectedNames = metadata.images.map { it.name.lowercase() }.toSet()
        val candidates = mutableListOf<ModuleArrayCandidate>()
        var lastHeartbeat = 0L

        for (pairIndex in 0 until 40) {
            if (cancellation.isCancelled()) throw AnalysisCancelledException()
            val now = System.currentTimeMillis()
            if (now - lastHeartbeat >= HEARTBEAT_MS) {
                lastHeartbeat = now
                progress.publish(
                    EngineProgress(
                        engineId = "il2cpp.codegen-bind",
                        scheduleClass = EngineScheduleClass.CONFIRMATION,
                        state = RunState.RUNNING,
                        currentTask = "IL2CPP: CodeRegistration → CodeGenModule",
                        currentArtifact = libraryEntry,
                        processed = pairIndex.toLong(),
                        total = 40,
                        lastHeartbeatEpochMs = now,
                    ),
                )
            }

            val fieldVa = codeRegistrationVa + pairIndex * pairStride
            val count = image.readU32AtVa(fieldVa)?.toInt() ?: continue
            if (count !in 1..MAX_MODULES) continue
            if (expectedNames.isNotEmpty() && count != expectedNames.size) continue
            val modulesVa = image.readPointerAtVa(fieldVa + pointerSize) ?: continue
            val modules = readModuleArray(image, modulesVa, count, cancellation) ?: continue
            if (!matchesImageSet(modules, expectedNames)) continue
            candidates += ModuleArrayCandidate(
                modules = modules,
                discovery = "CODE_REGISTRATION_PAIR_" + pairIndex,
            )
        }

        return uniqueCandidate(candidates, blockers)
    }

    /**
     * Stripped libil2cpp builds often remove g_CodeRegistration from dynsym.
     * Fallback scans only bounded, file-backed non-executable PT_LOAD data and accepts
     * a candidate only when the entire module-name set exactly matches metadata images
     * and its sampled method pointers resolve to executable segments.
     */
    private fun recoverByImageSetScan(
        image: ElfImage,
        metadata: Il2CppMetadataModel,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        libraryEntry: String,
        blockers: MutableList<String>,
    ): ModuleArrayCandidate? {
        val expectedNames = metadata.images.map { it.name.lowercase() }.toSet()
        if (expectedNames.isEmpty() || expectedNames.size > MAX_MODULES) return null

        val pointerSize = image.pointerSize
        val pairSize = pointerSize * 2L
        val candidates = mutableListOf<ModuleArrayCandidate>()
        var scanned = 0L
        var lastHeartbeat = 0L

        val segments = image.loadSegments
            .filter { !it.executable && it.fileSize >= pairSize }
            .sortedBy { it.virtualAddress }

        outer@ for (segment in segments) {
            var relative = 0L
            while (relative + pairSize <= segment.fileSize) {
                if (scanned >= MAX_FALLBACK_SCAN_BYTES) {
                    blockers += "CODEGEN_FALLBACK_SCAN_LIMIT_REACHED"
                    break@outer
                }
                if ((scanned and 0x3fffL) == 0L && cancellation.isCancelled()) {
                    throw AnalysisCancelledException()
                }

                val fieldVa = segment.virtualAddress + relative
                val count = image.readU32AtVa(fieldVa)?.toInt()
                if (count == expectedNames.size) {
                    val modulesVa = image.readPointerAtVa(fieldVa + pointerSize)
                    if (modulesVa != null && modulesVa > 0L &&
                        image.isFileBackedVa(modulesVa, count.toLong() * pointerSize)
                    ) {
                        val modules = readModuleArray(image, modulesVa, count, cancellation)
                        if (modules != null && matchesImageSet(modules, expectedNames)) {
                            candidates += ModuleArrayCandidate(
                                modules = modules,
                                discovery = "BOUNDED_IMAGE_SET_SCAN@0x" + fieldVa.toString(16),
                            )
                            if (candidates.size > 8) break@outer
                        }
                    }
                }

                relative += pointerSize
                scanned += pointerSize

                val now = System.currentTimeMillis()
                if (now - lastHeartbeat >= HEARTBEAT_MS) {
                    lastHeartbeat = now
                    progress.publish(
                        EngineProgress(
                            engineId = "il2cpp.codegen-bind",
                            scheduleClass = EngineScheduleClass.CONFIRMATION,
                            state = RunState.RUNNING,
                            currentTask = "IL2CPP: stripped CodeGenModule fallback",
                            currentArtifact = libraryEntry,
                            processed = scanned,
                            total = MAX_FALLBACK_SCAN_BYTES,
                            lastHeartbeatEpochMs = now,
                        ),
                    )
                }
            }
        }

        val result = uniqueCandidate(candidates, blockers)
        if (result == null && candidates.isEmpty()) {
            blockers += "STRIPPED_CODEGEN_MODULE_ARRAY_UNRESOLVED"
        }
        return result
    }

    private fun readModuleArray(
        image: ElfImage,
        modulesVa: Long,
        count: Int,
        cancellation: CancellationSignal,
    ): List<Il2CppCodeGenModuleEvidence>? {
        if (count !in 1..MAX_MODULES) return null
        val pointerSize = image.pointerSize
        val methodPointerOffset = if (pointerSize == 8) 16L else 8L
        if (!image.isFileBackedVa(modulesVa, count.toLong() * pointerSize)) return null

        val modules = ArrayList<Il2CppCodeGenModuleEvidence>(count)
        repeat(count) { moduleIndex ->
            if (moduleIndex % 64 == 0 && cancellation.isCancelled()) {
                throw AnalysisCancelledException()
            }

            val moduleVa = image.readPointerAtVa(modulesVa + moduleIndex.toLong() * pointerSize)
                ?: return null
            if (moduleVa <= 0L || !image.isFileBackedVa(moduleVa, methodPointerOffset + pointerSize)) {
                return null
            }

            val nameVa = image.readPointerAtVa(moduleVa) ?: return null
            val moduleName = image.readCStringAtVa(nameVa, 512) ?: return null
            if (!(moduleName.endsWith(".dll", ignoreCase = true) ||
                    moduleName.endsWith(".exe", ignoreCase = true))
            ) {
                return null
            }

            val methodCount = image.readU32AtVa(moduleVa + pointerSize)?.toInt() ?: return null
            if (methodCount !in 0..MAX_METHOD_POINTERS) return null
            val tableVa = image.readPointerAtVa(moduleVa + methodPointerOffset)
            if (methodCount > 0 && (tableVa == null || tableVa <= 0L)) return null

            var sampled = 0
            var executable = 0
            if (methodCount > 0 && tableVa != null) {
                val sampleCount = minOf(methodCount, MAX_SAMPLED_POINTERS)
                if (!image.isFileBackedVa(tableVa, sampleCount.toLong() * pointerSize)) return null
                repeat(sampleCount) { slot ->
                    val functionVa = image.readPointerAtVa(tableVa + slot.toLong() * pointerSize)
                    if (functionVa != null && functionVa > 0L) {
                        sampled++
                        if (image.isExecutableVa(functionVa)) executable++
                    }
                }
                if (sampled == 0) return null
                val ratio = executable.toDouble() / sampled.toDouble()
                val required = if (sampled < 4) 1.0 else 0.75
                if (ratio < required) return null
            }

            modules += Il2CppCodeGenModuleEvidence(
                moduleName = moduleName,
                moduleVirtualAddress = moduleVa,
                methodPointerCount = methodCount,
                methodPointersVirtualAddress = tableVa ?: 0L,
                sampledPointers = sampled,
                executablePointers = executable,
            )
        }
        return modules
    }

    private fun matchesImageSet(
        modules: List<Il2CppCodeGenModuleEvidence>,
        expectedNames: Set<String>,
    ): Boolean {
        if (expectedNames.isEmpty()) return true
        val actual = modules.map { it.moduleName.lowercase() }
        return actual.size == actual.toSet().size && actual.toSet() == expectedNames
    }

    private fun uniqueCandidate(
        candidates: List<ModuleArrayCandidate>,
        blockers: MutableList<String>,
    ): ModuleArrayCandidate? {
        if (candidates.isEmpty()) return null
        val groups = candidates.groupBy { candidate ->
            candidate.modules.map { module ->
                module.moduleName.lowercase() + ":" +
                    module.methodPointerCount + ":" +
                    module.methodPointersVirtualAddress
            }
        }
        if (groups.size != 1) {
            blockers += "AMBIGUOUS_CODEGEN_MODULE_ARRAY"
            return null
        }
        return groups.values.first().first()
    }

    private fun bindMethods(
        image: ElfImage,
        metadata: Il2CppMetadataModel,
        modules: List<Il2CppCodeGenModuleEvidence>,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        libraryEntry: String,
    ): List<Il2CppMethodBinaryBinding> {
        val modulesByName = modules.groupBy { it.moduleName.lowercase() }
        val out = mutableListOf<Il2CppMethodBinaryBinding>()
        var lastHeartbeat = 0L

        metadata.methods.forEachIndexed { index, method ->
            if (index % 256 == 0) {
                if (cancellation.isCancelled()) throw AnalysisCancelledException()
                val now = System.currentTimeMillis()
                if (now - lastHeartbeat >= HEARTBEAT_MS) {
                    lastHeartbeat = now
                    progress.publish(
                        EngineProgress(
                            engineId = "il2cpp.codegen-bind",
                            scheduleClass = EngineScheduleClass.CONFIRMATION,
                            state = RunState.RUNNING,
                            currentTask = "IL2CPP: metadata token → native slot",
                            currentArtifact = libraryEntry,
                            processed = index.toLong(),
                            total = metadata.methods.size.toLong(),
                            lastHeartbeatEpochMs = now,
                        ),
                    )
                }
            }

            val imageDef = metadata.images.singleOrNull { candidate ->
                method.declaringTypeIndex >= candidate.typeStart &&
                    method.declaringTypeIndex < candidate.typeStart + candidate.typeCount
            } ?: return@forEachIndexed

            val module = modulesByName[imageDef.name.lowercase()]?.singleOrNull()
                ?: return@forEachIndexed

            val rid = (method.token and 0x00ffffffL).toInt()
            if (rid <= 0) return@forEachIndexed
            val slot = rid - 1
            if (slot >= module.methodPointerCount || module.methodPointersVirtualAddress <= 0L) {
                return@forEachIndexed
            }

            val functionVa = image.readPointerAtVa(
                module.methodPointersVirtualAddress + slot.toLong() * image.pointerSize,
            ) ?: return@forEachIndexed

            if (functionVa <= 0L || !image.isExecutableVa(functionVa)) return@forEachIndexed

            out += Il2CppMethodBinaryBinding(
                methodIndex = method.index,
                managedIdentity = method.declaringType + "." + method.name,
                metadataToken = method.token,
                imageName = imageDef.name,
                moduleName = module.moduleName,
                slotIndex = slot,
                functionVirtualAddress = functionVa,
                functionFileOffset = image.fileOffsetForVa(functionVa),
            )
        }
        return out
    }
}
