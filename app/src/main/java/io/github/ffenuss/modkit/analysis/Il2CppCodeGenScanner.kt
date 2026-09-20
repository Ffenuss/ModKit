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

enum class Il2CppNativeReturnKind {
    VOID,
    BOOLEAN,
    INTEGER,
    POINTER_OR_REFERENCE,
    FLOATING_POINT,
    VALUE_TYPE,
    UNKNOWN,
}

data class Il2CppMethodBinaryBinding(
    val methodIndex: Int,
    val managedIdentity: String,
    val metadataToken: Long,
    val imageName: String,
    val moduleName: String,
    val slotIndex: Int,
    val functionVirtualAddress: Long,
    val functionFileOffset: Long?,
    val returnTypeIndex: Int = -1,
    val returnKind: Il2CppNativeReturnKind = Il2CppNativeReturnKind.UNKNOWN,
    val returnTypeProof: String? = null,
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
    private const val MAX_FALLBACK_SCAN_BYTES = 256L * 1024L * 1024L
    private const val FALLBACK_WINDOW_BYTES = 1 * 1024 * 1024
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
            val metadataRegistrationSymbol =
                uniqueDefinedSymbol(image, "metadataregistration")
            val metadataRegistration =
                metadataRegistrationSymbol
                    ?: recoverMetadataRegistrationByRelocations(
                        image = image,
                        metadata = metadata,
                        cancellation = cancellation,
                        progress = progress,
                        libraryEntry = libraryEntry,
                    )
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

            if (
                candidate == null &&
                metadata.images.isNotEmpty() &&
                image.relativeRelocationCount > 0
            ) {
                candidate = recoverByRelativeRelocations(
                    image = image,
                    metadata = metadata,
                    cancellation = cancellation,
                    progress = progress,
                    libraryEntry = libraryEntry,
                    blockers = blockers,
                )
            }

            if (
                candidate == null &&
                metadata.images.isNotEmpty() &&
                image.relativeRelocationCount > 0
            ) {
                candidate = recoverByRelocatedModuleStructs(
                    image = image,
                    metadata = metadata,
                    cancellation = cancellation,
                    progress = progress,
                    libraryEntry = libraryEntry,
                    blockers = blockers,
                )
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
            }

            if (candidate != null) {
                blockers.remove("CODE_REGISTRATION_SYMBOL_UNRESOLVED")
                blockers.remove("CODEGEN_MODULE_ARRAY_UNRESOLVED")
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
                    metadataRegistrationVa = metadataRegistration,
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

    private fun recoverMetadataRegistrationByRelocations(
        image: ElfImage,
        metadata: Il2CppMetadataModel,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        libraryEntry: String,
    ): Long? {
        if (
            image.relativeRelocationCount <= 0 ||
            metadata.truncated ||
            !metadata.structuredSupported
        ) {
            return null
        }

        val typeDefinitionCount =
            metadata.declaredTypeCount
                ?.takeIf {
                    it > 0 &&
                        metadata.types.size == it
                }
                ?: return null
        val maxReturnTypeIndex =
            metadata.methods
                .asSequence()
                .map { it.returnTypeIndex }
                .filter { it >= 0 }
                .maxOrNull()
                ?: return null

        val pointerSize = image.pointerSize
        val pairStride = pointerSize * 2L
        val candidateBases = LinkedHashSet<Long>()
        var inspected = 0L
        var lastHeartbeat = 0L

        image.forEachRelativeRelocation { pointerFieldVa, _ ->
            inspected++
            if (inspected % 4096L == 0L) {
                if (cancellation.isCancelled()) {
                    throw AnalysisCancelledException()
                }
                val now = System.currentTimeMillis()
                if (now - lastHeartbeat >= HEARTBEAT_MS) {
                    lastHeartbeat = now
                    progress.publish(
                        EngineProgress(
                            engineId = "il2cpp.codegen-bind",
                            scheduleClass =
                                EngineScheduleClass.CONFIRMATION,
                            state = RunState.RUNNING,
                            currentTask =
                                "IL2CPP: relocation-guided MetadataRegistration recovery",
                            currentArtifact = libraryEntry,
                            processed = inspected,
                            total =
                                image.relativeRelocationCount
                                    .toLong(),
                            lastHeartbeatEpochMs = now,
                        ),
                    )
                }
            }

            for (
                pairIndex in
                intArrayOf(
                    METADATA_REGISTRATION_FIELD_OFFSETS_PAIR_INDEX,
                    METADATA_REGISTRATION_TYPE_SIZES_PAIR_INDEX,
                )
            ) {
                val base =
                    pointerFieldVa -
                        pairIndex * pairStride -
                        pointerSize
                if (base <= 0L) continue
                if (
                    validateMetadataRegistrationCandidate(
                        image = image,
                        baseVa = base,
                        typeDefinitionCount =
                            typeDefinitionCount,
                        maxReturnTypeIndex =
                            maxReturnTypeIndex,
                        sampleReturnTypeIndices =
                            metadata.methods
                                .asSequence()
                                .map { it.returnTypeIndex }
                                .filter { it >= 0 }
                                .distinct()
                                .take(MAX_RETURN_TYPE_SAMPLES)
                                .toList(),
                    )
                ) {
                    candidateBases += base
                    if (candidateBases.size > 1) {
                        return@forEachRelativeRelocation false
                    }
                }
            }
            true
        }

        return candidateBases.singleOrNull()
    }

    private fun validateMetadataRegistrationCandidate(
        image: ElfImage,
        baseVa: Long,
        typeDefinitionCount: Int,
        maxReturnTypeIndex: Int,
        sampleReturnTypeIndices: List<Int>,
    ): Boolean {
        val pointerSize = image.pointerSize
        val pairStride = pointerSize * 2L

        fun count(pairIndex: Int): Long? =
            image.readU32AtVa(
                baseVa + pairIndex * pairStride,
            )

        fun pointer(pairIndex: Int): Long? =
            image.readPointerAtVa(
                baseVa +
                    pairIndex * pairStride +
                    pointerSize,
            )

        val typesCount =
            count(METADATA_REGISTRATION_TYPES_PAIR_INDEX)
                ?: return false
        if (
            typesCount <= maxReturnTypeIndex.toLong() ||
            typesCount > MAX_METADATA_TYPES
        ) {
            return false
        }
        if (
            count(
                METADATA_REGISTRATION_FIELD_OFFSETS_PAIR_INDEX,
            ) != typeDefinitionCount.toLong() ||
            count(
                METADATA_REGISTRATION_TYPE_SIZES_PAIR_INDEX,
            ) != typeDefinitionCount.toLong()
        ) {
            return false
        }

        val typesArray =
            pointer(METADATA_REGISTRATION_TYPES_PAIR_INDEX)
                ?: return false
        val fieldOffsets =
            pointer(
                METADATA_REGISTRATION_FIELD_OFFSETS_PAIR_INDEX,
            ) ?: return false
        val typeSizes =
            pointer(
                METADATA_REGISTRATION_TYPE_SIZES_PAIR_INDEX,
            ) ?: return false
        if (
            typesArray <= 0L ||
            fieldOffsets <= 0L ||
            typeSizes <= 0L ||
            !image.isFileBackedVa(
                fieldOffsets,
                pointerSize.toLong(),
            ) ||
            !image.isFileBackedVa(
                typeSizes,
                pointerSize.toLong(),
            )
        ) {
            return false
        }

        if (sampleReturnTypeIndices.isEmpty()) {
            return false
        }
        return sampleReturnTypeIndices.all { typeIndex ->
            if (typeIndex.toLong() >= typesCount) {
                false
            } else {
                val typeVa =
                    image.readPointerAtVa(
                        typesArray +
                            typeIndex.toLong() *
                            pointerSize,
                    )
                        ?: return@all false
                val raw =
                    readIl2CppTypeCode(
                        image = image,
                        typeVa = typeVa,
                    )
                        ?: return@all false
                raw in MIN_KNOWN_IL2CPP_TYPE_CODE..
                    MAX_KNOWN_IL2CPP_TYPE_CODE
            }
        }
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

    private fun recoverByRelativeRelocations(
        image: ElfImage,
        metadata: Il2CppMetadataModel,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        libraryEntry: String,
        blockers: MutableList<String>,
    ): ModuleArrayCandidate? {
        val expectedNames =
            metadata.images
                .map { it.name.lowercase() }
                .toSet()
        if (
            expectedNames.isEmpty() ||
            expectedNames.size > MAX_MODULES
        ) {
            return null
        }

        val pointerSize = image.pointerSize
        val requiredArrayBytes =
            expectedNames.size.toLong() * pointerSize
        val candidates =
            mutableListOf<ModuleArrayCandidate>()
        var inspected = 0L
        var lastHeartbeat = 0L

        image.forEachRelativeRelocation { pointerFieldVa, modulesVa ->
            inspected++
            if (inspected % 1024L == 0L) {
                if (cancellation.isCancelled()) {
                    throw AnalysisCancelledException()
                }
                val now = System.currentTimeMillis()
                if (now - lastHeartbeat >= HEARTBEAT_MS) {
                    lastHeartbeat = now
                    progress.publish(
                        EngineProgress(
                            engineId = "il2cpp.codegen-bind",
                            scheduleClass =
                                EngineScheduleClass.CONFIRMATION,
                            state = RunState.RUNNING,
                            currentTask =
                                "IL2CPP: relocation-guided CodeGenModule recovery",
                            currentArtifact = libraryEntry,
                            processed = inspected,
                            total =
                                image.relativeRelocationCount
                                    .toLong(),
                            lastHeartbeatEpochMs = now,
                        ),
                    )
                }
            }

            if (pointerFieldVa < pointerSize) {
                return@forEachRelativeRelocation true
            }
            if (
                modulesVa <= 0L ||
                !image.isFileBackedVa(
                    modulesVa,
                    requiredArrayBytes,
                )
            ) {
                return@forEachRelativeRelocation true
            }

            val countVa = pointerFieldVa - pointerSize
            val count =
                image.readU32AtVa(countVa)?.toInt()
                    ?: return@forEachRelativeRelocation true
            if (count != expectedNames.size) {
                return@forEachRelativeRelocation true
            }

            val modules =
                readModuleArray(
                    image = image,
                    modulesVa = modulesVa,
                    count = count,
                    cancellation = cancellation,
                ) ?: return@forEachRelativeRelocation true
            if (!matchesImageSet(modules, expectedNames)) {
                return@forEachRelativeRelocation true
            }

            candidates +=
                ModuleArrayCandidate(
                    modules = modules,
                    discovery =
                        "RELATIVE_RELOCATION_PAIR@0x" +
                            countVa.toString(16),
                )
            candidates.size <= 8
        }

        return uniqueCandidate(
            candidates = candidates,
            blockers = blockers,
        )
    }

    /**
     * Some stripped Android IL2CPP binaries preserve no recoverable
     * CodeRegistration count/pointer pair. In those binaries the linker still
     * has to relocate the Il2CppCodeGenModule fields themselves.
     *
     * Treat an individual module as a positive exact candidate only when:
     * - the first field is a relative relocation to an exact metadata image name;
     * - the method-pointer table field is itself relocation-backed;
     * - the table is file-backed; and
     * - sampled method pointers resolve into executable PT_LOAD segments.
     *
     * A complete module set is not required for positive bindings. Ambiguous
     * module names are dropped rather than guessed.
     */
    private fun recoverByRelocatedModuleStructs(
        image: ElfImage,
        metadata: Il2CppMetadataModel,
        cancellation: CancellationSignal,
        progress: ProgressSink,
        libraryEntry: String,
        blockers: MutableList<String>,
    ): ModuleArrayCandidate? {
        val expectedNames =
            metadata.images
                .map { it.name.lowercase() }
                .toSet()
        if (
            expectedNames.isEmpty() ||
            expectedNames.size > MAX_MODULES
        ) {
            return null
        }

        val pointerSize = image.pointerSize
        val methodPointerOffset =
            if (pointerSize == 8) 16L else 8L
        val candidatesByName =
            LinkedHashMap<
                String,
                MutableList<Il2CppCodeGenModuleEvidence>,
            >()
        var inspected = 0L
        var lastHeartbeat = 0L

        image.forEachRelativeRelocation { moduleVa, nameVa ->
            inspected++
            if (inspected % 1024L == 0L) {
                if (cancellation.isCancelled()) {
                    throw AnalysisCancelledException()
                }
                val now = System.currentTimeMillis()
                if (now - lastHeartbeat >= HEARTBEAT_MS) {
                    lastHeartbeat = now
                    progress.publish(
                        EngineProgress(
                            engineId = "il2cpp.codegen-bind",
                            scheduleClass =
                                EngineScheduleClass.CONFIRMATION,
                            state = RunState.RUNNING,
                            currentTask =
                                "IL2CPP: relocated CodeGenModule signatures",
                            currentArtifact = libraryEntry,
                            processed = inspected,
                            total =
                                image.relativeRelocationCount
                                    .toLong(),
                            lastHeartbeatEpochMs = now,
                        ),
                    )
                }
            }

            if (
                moduleVa <= 0L ||
                nameVa <= 0L ||
                !image.isFileBackedVa(
                    moduleVa,
                    methodPointerOffset + pointerSize,
                ) ||
                !image.isFileBackedVa(nameVa, 1L)
            ) {
                return@forEachRelativeRelocation true
            }

            val tableVa =
                image.relativeRelocationValueAt(
                    moduleVa + methodPointerOffset,
                ) ?: return@forEachRelativeRelocation true
            if (tableVa <= 0L) {
                return@forEachRelativeRelocation true
            }

            val methodCount =
                image.readU32AtVa(
                    moduleVa + pointerSize,
                )?.toInt()
                    ?: return@forEachRelativeRelocation true
            if (methodCount !in 1..MAX_METHOD_POINTERS) {
                return@forEachRelativeRelocation true
            }

            val sampleCount =
                minOf(
                    methodCount,
                    MAX_SAMPLED_POINTERS,
                )
            if (
                !image.isFileBackedVa(
                    tableVa,
                    sampleCount.toLong() * pointerSize,
                )
            ) {
                return@forEachRelativeRelocation true
            }

            var sampled = 0
            var executable = 0
            repeat(sampleCount) { slot ->
                val functionVa =
                    image.readPointerAtVa(
                        tableVa +
                            slot.toLong() * pointerSize,
                    )
                if (
                    functionVa != null &&
                    functionVa > 0L
                ) {
                    sampled++
                    if (image.isExecutableVa(functionVa)) {
                        executable++
                    }
                }
            }
            if (sampled == 0) {
                return@forEachRelativeRelocation true
            }
            val ratio =
                executable.toDouble() /
                    sampled.toDouble()
            val required =
                if (sampled < 4) 1.0 else 0.75
            if (ratio < required) {
                return@forEachRelativeRelocation true
            }

            val moduleName =
                image.readCStringAtVa(
                    nameVa,
                    512,
                ) ?: return@forEachRelativeRelocation true
            val normalizedName = moduleName.lowercase()
            if (normalizedName !in expectedNames) {
                return@forEachRelativeRelocation true
            }

            val evidence =
                Il2CppCodeGenModuleEvidence(
                    moduleName = moduleName,
                    moduleVirtualAddress = moduleVa,
                    methodPointerCount = methodCount,
                    methodPointersVirtualAddress = tableVa,
                    sampledPointers = sampled,
                    executablePointers = executable,
                )
            val bucket =
                candidatesByName.getOrPut(
                    normalizedName,
                ) {
                    mutableListOf()
                }
            if (
                bucket.none {
                    it.moduleVirtualAddress ==
                        moduleVa
                }
            ) {
                bucket += evidence
            }
            true
        }

        val ambiguousCount =
            candidatesByName.count {
                it.value.size > 1
            }
        if (ambiguousCount > 0) {
            blockers +=
                "AMBIGUOUS_RELOCATED_CODEGEN_MODULES:" +
                    ambiguousCount
        }

        val uniqueByName =
            candidatesByName
                .filterValues { it.size == 1 }
                .mapValues { it.value.single() }
        if (uniqueByName.isEmpty()) {
            return null
        }

        val modules =
            metadata.images
                .mapNotNull {
                    uniqueByName[
                        it.name.lowercase()
                    ]
                }
        if (modules.isEmpty()) {
            return null
        }

        if (modules.size < expectedNames.size) {
            blockers +=
                "PARTIAL_RELOCATED_CODEGEN_MODULE_SET:" +
                    modules.size +
                    "/" +
                    expectedNames.size
        }

        return ModuleArrayCandidate(
            modules = modules,
            discovery =
                "RELOCATED_MODULE_SIGNATURES_" +
                    modules.size +
                    "_OF_" +
                    expectedNames.size,
        )
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
        val expectedNames =
            metadata.images
                .map { it.name.lowercase() }
                .toSet()
        if (
            expectedNames.isEmpty() ||
            expectedNames.size > MAX_MODULES
        ) {
            return null
        }

        val pointerSize = image.pointerSize
        val pairSize = pointerSize * 2L
        val candidates =
            mutableListOf<ModuleArrayCandidate>()
        var scanned = 0L
        var lastHeartbeat = 0L

        val segments =
            image.loadSegments
                .filter {
                    !it.executable &&
                        it.fileSize >= pairSize
                }
                .sortedBy { it.virtualAddress }

        var total = 0L
        for (segment in segments) {
            if (total >= MAX_FALLBACK_SCAN_BYTES) break
            total +=
                minOf(
                    segment.fileSize,
                    MAX_FALLBACK_SCAN_BYTES - total,
                )
        }

        outer@ for (segment in segments) {
            var relative = 0L
            while (
                relative + 4L <= segment.fileSize &&
                scanned < MAX_FALLBACK_SCAN_BYTES
            ) {
                if (cancellation.isCancelled()) {
                    throw AnalysisCancelledException()
                }

                val remainingBudget =
                    MAX_FALLBACK_SCAN_BYTES - scanned
                val request =
                    minOf(
                        FALLBACK_WINDOW_BYTES.toLong(),
                        segment.fileSize - relative,
                        remainingBudget,
                    ).toInt()
                if (request <= 0) break

                val fieldVa =
                    segment.virtualAddress + relative
                val window =
                    image.readFileWindowAtVa(
                        virtualAddress = fieldVa,
                        maxBytes = request,
                    ) ?: break
                if (window.size < 4) break

                var local = 0
                while (local + 4 <= window.size) {
                    val count =
                        u32le(window, local)
                    if (count == expectedNames.size.toLong()) {
                        val candidateFieldVa =
                            fieldVa + local
                        val modulesVa =
                            image.readPointerAtVa(
                                candidateFieldVa +
                                    pointerSize,
                            )
                        if (
                            modulesVa != null &&
                            modulesVa > 0L &&
                            image.isFileBackedVa(
                                modulesVa,
                                expectedNames.size
                                    .toLong() *
                                    pointerSize,
                            )
                        ) {
                            val modules =
                                readModuleArray(
                                    image = image,
                                    modulesVa = modulesVa,
                                    count = expectedNames.size,
                                    cancellation =
                                        cancellation,
                                )
                            if (
                                modules != null &&
                                matchesImageSet(
                                    modules,
                                    expectedNames,
                                )
                            ) {
                                candidates +=
                                    ModuleArrayCandidate(
                                        modules = modules,
                                        discovery =
                                            "BOUNDED_IMAGE_SET_SCAN@0x" +
                                                candidateFieldVa
                                                    .toString(16),
                                    )
                                if (candidates.size > 8) {
                                    break@outer
                                }
                            }
                        }
                    }
                    local += pointerSize
                }

                val advance =
                    (window.size / pointerSize) *
                        pointerSize
                if (advance <= 0) break
                relative += advance
                scanned += advance

                val now = System.currentTimeMillis()
                if (now - lastHeartbeat >= HEARTBEAT_MS) {
                    lastHeartbeat = now
                    progress.publish(
                        EngineProgress(
                            engineId = "il2cpp.codegen-bind",
                            scheduleClass =
                                EngineScheduleClass.CONFIRMATION,
                            state = RunState.RUNNING,
                            currentTask =
                                "IL2CPP: stripped CodeGenModule fallback",
                            currentArtifact = libraryEntry,
                            processed = scanned,
                            total = total,
                            lastHeartbeatEpochMs = now,
                        ),
                    )
                }
            }
            if (scanned >= MAX_FALLBACK_SCAN_BYTES) {
                break
            }
        }

        if (
            scanned >= MAX_FALLBACK_SCAN_BYTES &&
            segments.sumOf {
                minOf(
                    it.fileSize,
                    MAX_FALLBACK_SCAN_BYTES + 1L,
                )
            } > MAX_FALLBACK_SCAN_BYTES
        ) {
            blockers +=
                "CODEGEN_FALLBACK_SCAN_LIMIT_REACHED"
        }

        val result =
            uniqueCandidate(
                candidates = candidates,
                blockers = blockers,
            )
        if (result == null && candidates.isEmpty()) {
            blockers +=
                "STRIPPED_CODEGEN_MODULE_ARRAY_UNRESOLVED"
        }
        return result
    }

    private fun u32le(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        (bytes[offset].toLong() and 0xffL) or
            (
                (bytes[offset + 1].toLong() and 0xffL)
                    shl 8
                ) or
            (
                (bytes[offset + 2].toLong() and 0xffL)
                    shl 16
                ) or
            (
                (bytes[offset + 3].toLong() and 0xffL)
                    shl 24
                )

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
        metadataRegistrationVa: Long?,
    ): List<Il2CppMethodBinaryBinding> {
        val modulesByName =
            modules.groupBy {
                it.moduleName.lowercase()
            }
        val imageByTypeIndex =
            HashMap<Int, Il2CppImageDefinition>(
                metadata.types.size * 2,
            )
        val parsedTypeLimit =
            (
                metadata.types
                    .maxOfOrNull { it.index }
                    ?: -1
                ) + 1
        metadata.images.forEach { imageDef ->
            if (
                imageDef.typeStart >= 0 &&
                imageDef.typeStart < parsedTypeLimit &&
                imageDef.typeCount > 0
            ) {
                val endExclusive =
                    minOf(
                        imageDef.typeStart.toLong() +
                            imageDef.typeCount.toLong(),
                        parsedTypeLimit.toLong(),
                    ).toInt()
                for (
                    typeIndex in
                    imageDef.typeStart until endExclusive
                ) {
                    imageByTypeIndex.putIfAbsent(
                        typeIndex,
                        imageDef,
                    )
                }
            }
        }

        val out =
            ArrayList<Il2CppMethodBinaryBinding>(
                metadata.methods.size,
            )
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

            val imageDef =
                imageByTypeIndex[
                    method.declaringTypeIndex
                ] ?: return@forEachIndexed

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
                returnTypeIndex = method.returnTypeIndex,
                returnKind = resolveReturnKind(
                    image = image,
                    metadataRegistrationVa = metadataRegistrationVa,
                    returnTypeIndex = method.returnTypeIndex,
                ),
                returnTypeProof = metadataRegistrationVa?.let {
                    "Il2CppMetadataRegistration.types[" + method.returnTypeIndex + "]"
                },
            )
        }
        return out
    }

    private fun resolveReturnKind(
        image: ElfImage,
        metadataRegistrationVa: Long?,
        returnTypeIndex: Int,
    ): Il2CppNativeReturnKind {
        if (metadataRegistrationVa == null || returnTypeIndex < 0) {
            return Il2CppNativeReturnKind.UNKNOWN
        }

        val pairStride = image.pointerSize * 2L
        val typesPairVa =
            metadataRegistrationVa +
                METADATA_REGISTRATION_TYPES_PAIR_INDEX * pairStride
        val typeCount = image.readU32AtVa(typesPairVa)?.toLong()
            ?: return Il2CppNativeReturnKind.UNKNOWN
        if (returnTypeIndex.toLong() >= typeCount || typeCount <= 0L || typeCount > MAX_METADATA_TYPES) {
            return Il2CppNativeReturnKind.UNKNOWN
        }

        val typesArrayVa = image.readPointerAtVa(typesPairVa + image.pointerSize)
            ?: return Il2CppNativeReturnKind.UNKNOWN
        if (typesArrayVa <= 0L) return Il2CppNativeReturnKind.UNKNOWN

        val typeVa = image.readPointerAtVa(
            typesArrayVa + returnTypeIndex.toLong() * image.pointerSize,
        ) ?: return Il2CppNativeReturnKind.UNKNOWN
        if (typeVa <= 0L) return Il2CppNativeReturnKind.UNKNOWN

        val raw =
            readIl2CppTypeCode(
                image = image,
                typeVa = typeVa,
            )
                ?: return Il2CppNativeReturnKind.UNKNOWN

        return when (raw) {
            0x01 -> Il2CppNativeReturnKind.VOID
            0x02 -> Il2CppNativeReturnKind.BOOLEAN
            0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09,
            0x0a, 0x0b, 0x18, 0x19 -> Il2CppNativeReturnKind.INTEGER
            0x0c, 0x0d -> Il2CppNativeReturnKind.FLOATING_POINT
            0x0e, 0x0f, 0x12, 0x1c, 0x1d -> Il2CppNativeReturnKind.POINTER_OR_REFERENCE
            0x11 -> Il2CppNativeReturnKind.VALUE_TYPE
            else -> Il2CppNativeReturnKind.UNKNOWN
        }
    }

    private fun readIl2CppTypeCode(
        image: ElfImage,
        typeVa: Long,
    ): Int? =
        image.readFileWindowAtVa(
            typeVa + image.pointerSize + 2L,
            1,
        )
            ?.firstOrNull()
            ?.toInt()
            ?.and(0xff)

    private const val METADATA_REGISTRATION_TYPES_PAIR_INDEX = 3
    private const val METADATA_REGISTRATION_FIELD_OFFSETS_PAIR_INDEX = 5
    private const val METADATA_REGISTRATION_TYPE_SIZES_PAIR_INDEX = 6
    private const val MAX_METADATA_TYPES = 10_000_000L
    private const val MAX_RETURN_TYPE_SAMPLES = 12
    private const val MIN_KNOWN_IL2CPP_TYPE_CODE = 0x01
    private const val MAX_KNOWN_IL2CPP_TYPE_CODE = 0x1d
}
