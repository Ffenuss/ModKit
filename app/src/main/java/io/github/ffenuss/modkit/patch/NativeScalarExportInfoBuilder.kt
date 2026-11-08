package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.UniversalElfInventoryResult
import io.github.ffenuss.modkit.runtime.RepackedRuntimeTestMenuItem
import io.github.ffenuss.modkit.runtime.RepackedRuntimeTestMenuItemMode
import java.security.MessageDigest

/**
 * Exposes structural ELF scalar discoveries as read-only runtime menu hints.
 * These have not been assigned a verified gameplay meaning or return type;
 * never expose a native PATCH toggle based on the structural match alone.
 */
object NativeScalarExportInfoBuilder {
    const val MAX_INFO_ITEMS = 12

    fun build(
        inventory: UniversalElfInventoryResult?,
        limit: Int = MAX_INFO_ITEMS,
    ): List<RepackedRuntimeTestMenuItem> {
        require(limit in 0..MAX_INFO_ITEMS) { "Unbounded native hint count" }
        if (inventory == null || limit == 0) return emptyList()
        return inventory.records.asSequence()
            .filter { !it.abiPathMismatch }
            .flatMap { record ->
                record.scalarExportCandidates.asSequence()
                    .filter { !it.selectable }
                    .filter { it.abi == record.architecture }
                    .map { candidate ->
                        val source = listOf(
                            record.container, record.entryPath, candidate.abi,
                            candidate.symbol, candidate.patchVirtualAddress.toString(),
                            candidate.expectedPatchHex,
                        ).joinToString("|")
                        val id = MessageDigest.getInstance("SHA-256")
                            .digest(source.toByteArray(Charsets.UTF_8))
                            .take(12)
                            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                        RepackedRuntimeTestMenuItem(
                            id = "info:native:$id",
                            label = "Native export · " + candidate.symbol.take(80),
                            detail = buildString {
                                append(candidate.abi)
                                append(" · ")
                                append(record.entryPath.take(120))
                                append(" · VA 0x")
                                append(candidate.binaryVirtualAddress.toString(16))
                                append(" · returns ")
                                append(candidate.value)
                                append(" · структурная находка, эффект не проверен")
                            },
                            mode = RepackedRuntimeTestMenuItemMode.INFO,
                        )
                    }
            }
            .distinctBy { it.id }
            .take(limit)
            .toList()
    }
}
