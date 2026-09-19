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
)

data class Il2CppMethodBinaryBinding(
    val methodIndex: Int,
    val managedIdentity: String,
    val metadataToken: Long,
    val imageName: String,
    val moduleName: String,
    val slotIndex: Int,
    val functionVirtualAddress: Long,
    val functionFileOffset: Long?,
)

data class Il2CppBinaryEvidence(
    val libraryEntry: String,
    val machine: Int,
    val pointerSize: Int,
    val codeRegistrationVirtualAddress: Long?,
    val metadataRegistrationVirtualAddress: Long?,
    val codegenRegisterVirtualAddress: Long?,
    val modules: List<Il2CppCodeGenModuleEvidence>,
    val bindings: List<Il2CppMethodBinaryBinding>,
    val blockers: List<String>,
) {
    val exactBindingAvailable: Boolean
        get() = bindings.isNotEmpty()
}

object Il2CppCodeGenScanner {
    private const val MAX_MODULES = 4_096
    private const val MAX_METHOD_POINTERS = 5_000_000
    private const val MAX_SAMPLED_POINTERS = 32
    private const val HEARTBEAT_MS = 1_500L

    fun scan(
        file: File,
        libraryEntry: String,
        metadata: Il2CppMetadataModel,
        cancellation: CancellationSignal,
        progress: ProgressSink,
    ): Il2CppBinaryEvidence {
        ElfImage.open(file, cancellation).use { image ->
            val blockers = mutableListOf<String>()
            val codeRegistration = uniqueDefinedSymbol(image, "coderegistration")
            val metadataRegistration = uniqueDefinedSymbol(image, "metadataregistration")
            val codegenRegister = uniqueDefinedSymbol(image, "il2cpp_codegen_register")

            if (codeRegistration == null) {
                blockers += "CODE_REGISTRATION_SYMBOL_UNRESOLVED"
            }
            if (metadata.images.isEmpty()) {
                blockers += "METADATA_IMAGE_MAP_UNAVAILABLE"
            }

            val modules = if (codeRegistration != null) {
                recoverModules(
                    image = image,
                    codeRegistrationVa = codeRegistration,
                    cancellation = cancellation,
                    progress = progress,
                    libraryEntry = libraryEntry,
                    blockers = blockers,
                )
            } else {
                emptyList()
            }

            if (codeRegistration != null && modules.isEmpty()) {
                blockers += "CODEGEN_MODULE_ARRAY_UNRESOLVED"
            }

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
                codeRegistrationVirtualAddress = codeRegistration,
                metadataRegistrationVirtualAddress = metadataRegistration,
                codegenRegisterVirtualAddress = codegenRegister,
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

    private fun recoverModules(
        image: ElfImage,
        codeRegistrationVa: Long,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        libraryEntry: String,
        blockers: MutableList<String>,
    ): List<Il2CppCodeGenModuleEvidence> {
        val pointerSize = image.pointerSize
        val pairStride = pointerSize * 2L
        val pointerOffset = pointerSize.toLong()
        val methodPointerOffset = if (pointerSize == 8) 16L else 8L
        val candidates = mutableListOf<List<Il2CppCodeGenModuleEvidence>>()
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
                        currentTask = "IL2CPP: поиск CodeGenModule array",
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
            val modulesVa = image.readPointerAtVa(fieldVa + pointerOffset) ?: continue
            if (modulesVa <= 0L || !image.isFileBackedVa(modulesVa, count.toLong() * pointerSize)) continue

            val modules = ArrayList<Il2CppCodeGenModuleEvidence>(count)
            var candidateValid = true
            repeat(count) { moduleIndex ->
                if (!candidateValid) return@repeat
                if (moduleIndex % 64 == 0 && cancellation.isCancelled()) {
                    throw AnalysisCancelledException()
                }

                val moduleVa = image.readPointerAtVa(modulesVa + moduleIndex.toLong() * pointerSize)
                if (moduleVa == null || moduleVa <= 0L ||
                    !image.isFileBackedVa(moduleVa, methodPointerOffset + pointerSize)
                ) {
                    candidateValid = false
                    return@repeat
                }

                val nameVa = image.readPointerAtVa(moduleVa)
                val moduleName = nameVa?.let { image.readCStringAtVa(it, 512) }
                if (moduleName == null ||
                    !(moduleName.endsWith(".dll", ignoreCase = true) ||
                        moduleName.endsWith(".exe", ignoreCase = true))
                ) {
                    candidateValid = false
                    return@repeat
                }

                val methodCount = image.readU32AtVa(moduleVa + pointerSize)?.toInt()
                if (methodCount == null || methodCount !in 0..MAX_METHOD_POINTERS) {
                    candidateValid = false
                    return@repeat
                }
                val tableVa = image.readPointerAtVa(moduleVa + methodPointerOffset)
                if (methodCount > 0 && (tableVa == null || tableVa <= 0L)) {
                    candidateValid = false
                    return@repeat
                }

                var sampled = 0
                var executable = 0
                if (methodCount > 0 && tableVa != null) {
                    val sampleCount = minOf(methodCount, MAX_SAMPLED_POINTERS)
                    if (!image.isFileBackedVa(tableVa, sampleCount.toLong() * pointerSize)) {
                        candidateValid = false
                        return@repeat
                    }
                    repeat(sampleCount) { slot ->
                        val functionVa = image.readPointerAtVa(tableVa + slot.toLong() * pointerSize)
                        if (functionVa != null && functionVa > 0L) {
                            sampled++
                            if (image.isExecutableVa(functionVa)) executable++
                        }
                    }
                    if (sampled == 0) {
                        candidateValid = false
                        return@repeat
                    }
                    val ratio = executable.toDouble() / sampled.toDouble()
                    val required = if (sampled < 4) 1.0 else 0.75
                    if (ratio < required) {
                        candidateValid = false
                        return@repeat
                    }
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

            if (candidateValid && modules.size == count) {
                candidates += modules
            }
        }

        if (candidates.isEmpty()) return emptyList()

        val signatures = candidates.groupBy { list ->
            list.map { module ->
                module.moduleName.lowercase() + ":" +
                    module.methodPointerCount + ":" +
                    module.methodPointersVirtualAddress
            }
        }
        if (signatures.size != 1) {
            blockers += "AMBIGUOUS_CODEGEN_MODULE_ARRAY"
            return emptyList()
        }
        return signatures.values.first().first()
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

            if (functionVa <= 0L || !image.isExecutableVa(functionVa)) {
                return@forEachIndexed
            }

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
