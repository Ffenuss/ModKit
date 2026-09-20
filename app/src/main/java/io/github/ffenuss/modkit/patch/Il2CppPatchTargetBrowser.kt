package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.EvidenceTarget

object Il2CppPatchTargetBrowser {
    private const val METHOD_PREFIX = "il2cpp:method:"

    fun imageName(target: EvidenceTarget): String? {
        val id = target.id
        if (!id.startsWith(METHOD_PREFIX)) return null
        val rest = id.removePrefix(METHOD_PREFIX)
        val separator = rest.indexOf(':')
        if (separator <= 0) return null
        return rest.substring(0, separator)
            .takeIf { it.isNotBlank() }
    }

    fun matches(
        target: EvidenceTarget,
        query: String,
    ): Boolean {
        val normalized = query.trim().lowercase()
        if (normalized.isBlank()) return true

        return sequenceOf(
            target.displayName,
            target.id,
            target.declaringType,
            target.memberName,
            imageName(target),
        )
            .filterNotNull()
            .any {
                it.lowercase().contains(normalized)
            }
    }

    fun isAssemblyCSharp(
        target: EvidenceTarget,
    ): Boolean =
        imageName(target)
            ?.lowercase()
            ?.let {
                it == "assembly-csharp" ||
                    it == "assembly-csharp.dll"
            } == true
}
