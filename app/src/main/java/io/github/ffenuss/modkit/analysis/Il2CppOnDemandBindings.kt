package io.github.ffenuss.modkit.analysis

/**
 * Resolve a MethodDef anywhere in validated metadata, including after the
 * 30k project-first in-memory preview. Disk hits must follow a successful
 * SHA check by the caller; a stale cache is never trusted for patching.
 */
object Il2CppOnDemandBindings {
    fun resolveVerified(
        evidence: Il2CppBinaryEvidence,
        method: Il2CppMethodDefinition,
        imageDef: Il2CppImageDefinition,
    ): Il2CppMethodBinaryBinding? {
        val disk = evidence.bindingIndex ?: return null
        val indexed = disk.lookup(method.index) ?: return null
        val module = evidence.modules.getOrNull(indexed.moduleIndex) ?: return null
        if (!imageDef.name.equals(module.moduleName, ignoreCase = true) ||
            indexed.slotIndex >= module.methodPointerCount
        ) return null
        return Il2CppMethodBinaryBinding(
            methodIndex = method.index,
            managedIdentity = method.declaringType + "." + method.name,
            metadataToken = method.token,
            imageName = imageDef.name,
            moduleName = module.moduleName,
            slotIndex = indexed.slotIndex,
            functionVirtualAddress = indexed.functionVirtualAddress,
            functionFileOffset = indexed.functionFileOffset,
            returnTypeIndex = method.returnTypeIndex,
            returnKind = indexed.returnKind,
            returnTypeProof = if (evidence.metadataRegistrationVirtualAddress != null &&
                indexed.returnKind != Il2CppNativeReturnKind.UNKNOWN
            ) {
                "Il2CppMetadataRegistration.types[" + method.returnTypeIndex + "] @ 0x" +
                    evidence.metadataRegistrationVirtualAddress.toString(16)
            } else null,
        )
    }

    fun find(
        result: FastAnalysisResult,
        token: Long,
        imageName: String,
        libraryEntry: String,
        indexVerified: Boolean = false,
    ): Il2CppMethodBinaryBinding? {
        val evidence = result.il2cppBinaryBinding?.evidence
            ?.singleOrNull { it.libraryEntry == libraryEntry } ?: return null
        val inMemory = evidence.bindings.filter {
            it.metadataToken == token && it.imageName.equals(imageName, ignoreCase = true)
        }
        if (inMemory.isNotEmpty()) return inMemory.singleOrNull()
        val disk = evidence.bindingIndex ?: return null
        if (!indexVerified && !disk.verify()) return null
        val metadata = result.il2cppFastDump?.metadata ?: return null
        val images = metadata.images.filter { it.name.equals(imageName, ignoreCase = true) }
        if (images.size != 1) return null
        val image = images.single()
        val methods = metadata.methods.filter { method ->
            method.token == token && method.declaringTypeIndex >= image.typeStart &&
                method.declaringTypeIndex.toLong() <
                image.typeStart.toLong() + image.typeCount.toLong()
        }
        if (methods.size != 1) return null
        return resolveVerified(evidence, methods.single(), image)
    }

    /**
     * Add proven, semantically relevant project methods beyond the preview
     * window. The full index is still searchable by arbitrary MethodDef through
     * [find]; these extra UI targets never imply verified gameplay effects.
     */
    fun lateGameplayBindings(
        metadata: Il2CppMetadataModel,
        evidence: Il2CppBinaryEvidence,
    ): List<Il2CppMethodBinaryBinding> {
        val disk = evidence.bindingIndex ?: return emptyList()
        if (disk.boundCount <= evidence.bindings.size || !disk.verify()) return emptyList()
        val inMemoryIndices = evidence.bindings.mapTo(HashSet()) { it.methodIndex }
        val ownerImage = arrayOfNulls<Il2CppImageDefinition>(
            (metadata.types.maxOfOrNull { it.index } ?: -1) + 1,
        )
        for (image in metadata.images) {
            val end = minOf(
                ownerImage.size.toLong(),
                image.typeStart.toLong() + image.typeCount,
            ).toInt()
            for (typeIndex in maxOf(0, image.typeStart) until end) {
                if (ownerImage[typeIndex] == null) ownerImage[typeIndex] = image
            }
        }
        val found = ArrayList<Il2CppMethodBinaryBinding>()
        for (method in metadata.methods) {
            if (method.index in inMemoryIndices ||
                !gameplayNameSignal(method)
            ) continue
            val image = ownerImage.getOrNull(method.declaringTypeIndex) ?: continue
            if (!projectImage(image.name)) continue
            val binding = resolveVerified(evidence, method, image) ?: continue
            found += binding
        }
        return found
    }

    private val gameplayNames = Regex(
        "(?i)(health|damage|stamina|energy|mana|speed|move|jump|inventory|" +
            "capacity|experience|level|cooldown|regen|camera|attack|player|" +
            "character|enemy|loot|drop|resource|currency)",
    )

    private fun gameplayNameSignal(method: Il2CppMethodDefinition): Boolean =
        gameplayNames.containsMatchIn(method.name) &&
            !Regex("(?i)(billing|receipt|purchase|entitlement|integrity|signature)")
                .containsMatchIn(method.declaringType + "." + method.name)

    private fun projectImage(name: String): Boolean {
        val base = name.lowercase().removeSuffix(".dll")
        return base == "assembly-csharp" ||
            base.startsWith("assembly-csharp-") ||
            base.endsWith("gameassembly") ||
            base.endsWith("engineassembly")
    }
}
