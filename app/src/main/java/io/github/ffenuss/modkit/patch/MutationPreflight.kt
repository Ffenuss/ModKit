package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind

enum class MutationKind {
    NATIVE_IN_PLACE_BYTES,
    DEX_METHOD_BODY,
    MANAGED_IL_BODY,
    RESOURCE_REPLACE,
    FILE_REPLACE,
    CONFIG_VALUE,
}

data class MutationPayloadRef(
    val sha256: String,
    val size: Long,
    val storagePath: String? = null,
)

data class MutationRequest(
    val id: String,
    val artifactSha256: String,
    val targetId: String,
    val kind: MutationKind,
    val expectedOriginalSha256: String? = null,
    val expectedOriginalSize: Long? = null,
    val replacement: MutationPayloadRef? = null,
    val parameters: Map<String, String> = emptyMap(),
)

enum class MutationPreflightStatus {
    READY,
    BLOCKED,
}

data class MutationPreflightItem(
    val request: MutationRequest,
    val target: EvidenceTarget?,
    val status: MutationPreflightStatus,
    val blockers: List<String>,
)

data class MutationPreflightResult(
    val artifactSha256: String,
    val validatedAtEpochMs: Long,
    val items: List<MutationPreflightItem>,
    val globalBlockers: List<String>,
) {
    val readyItems: List<MutationPreflightItem>
        get() = items.filter { it.status == MutationPreflightStatus.READY }

    val blockedItems: List<MutationPreflightItem>
        get() = items.filter { it.status == MutationPreflightStatus.BLOCKED }

    val readyForApply: Boolean
        get() = globalBlockers.isEmpty() &&
            items.isNotEmpty() &&
            blockedItems.isEmpty()
}

/**
 * Internal mutation preflight.
 *
 * This validates a concrete mutation specification against the already
 * confirmed Evidence Graph. It does not apply bytes and it never upgrades an
 * unconfirmed target into a writable target.
 */
object MutationPreflightEngine {
    private val SHA256 = Regex("[0-9a-fA-F]{64}")

    fun validate(
        preparation: PatchPreparationPlan,
        requests: List<MutationRequest>,
    ): MutationPreflightResult {
        val globalBlockers = buildList {
            if (!preparation.sourceShaVerified) {
                add("SHA исходной цели не подтверждён.")
            }
            addAll(preparation.globalBlockers)
            if (requests.isEmpty()) {
                add("Не выбрано ни одного конкретного изменения.")
            }

            val duplicateIds = requests.groupingBy { it.id }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            if (duplicateIds.isNotEmpty()) {
                add("ID изменений должны быть уникальными.")
            }

            val duplicateTargets = requests.groupingBy { it.targetId }
                .eachCount()
                .filterValues { it > 1 }
                .keys
            if (duplicateTargets.isNotEmpty()) {
                add("Для одной цели нельзя одновременно применять несколько конфликтующих изменений.")
            }
        }.distinct()

        val targets = preparation.targets.associateBy { it.target.id }
        val items = requests.map { request ->
            val prepared = targets[request.targetId]
            val blockers = buildList {
                if (!request.artifactSha256.equals(preparation.artifactSha256, ignoreCase = true)) {
                    add("Изменение относится к другой версии цели.")
                }
                if (prepared == null) {
                    add("Цель изменения отсутствует в текущем плане подтверждений.")
                } else {
                    if (
                        prepared.status != PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE &&
                        prepared.status != PreparationTargetStatus.READY
                    ) {
                        addAll(prepared.blockers.ifEmpty {
                            listOf("Цель ещё не подтверждена достаточно для изменения.")
                        })
                    }
                    addAll(validateKind(request, prepared.target))
                }
            }.distinct()

            MutationPreflightItem(
                request = request,
                target = prepared?.target,
                status = if (globalBlockers.isEmpty() && blockers.isEmpty()) {
                    MutationPreflightStatus.READY
                } else {
                    MutationPreflightStatus.BLOCKED
                },
                blockers = blockers,
            )
        }

        return MutationPreflightResult(
            artifactSha256 = preparation.artifactSha256,
            validatedAtEpochMs = System.currentTimeMillis(),
            items = items,
            globalBlockers = globalBlockers,
        )
    }

    private fun validateKind(
        request: MutationRequest,
        target: EvidenceTarget,
    ): List<String> = buildList {
        when (request.kind) {
            MutationKind.NATIVE_IN_PLACE_BYTES -> {
                if (target.kind !in setOf(
                        EvidenceTargetKind.METHOD,
                        EvidenceTargetKind.NATIVE_FUNCTION,
                    )
                ) {
                    add("In-place native patch требует подтверждённую функцию/метод.")
                }
                if (target.fileOffset == null) {
                    add("Для native patch отсутствует подтверждённый file offset.")
                }
                if (target.binaryVirtualAddress == null && target.rva == null) {
                    add("Для native patch отсутствует подтверждённый executable address.")
                }
                validateReplacement(request, requireSameSize = true)
                val size = request.expectedOriginalSize
                if (size != null && size > MAX_NATIVE_PATCH_BYTES) {
                    add("In-place native patch превышает внутренний лимит preflight.")
                }
            }

            MutationKind.DEX_METHOD_BODY,
            MutationKind.MANAGED_IL_BODY -> {
                if (target.kind != EvidenceTargetKind.METHOD) {
                    add("Изменение тела метода требует подтверждённую method target.")
                }
                if (target.declaringType.isNullOrBlank() || target.memberName.isNullOrBlank()) {
                    add("У метода отсутствует стабильная identity для повторной проверки.")
                }
                validateReplacement(request, requireSameSize = false)
            }

            MutationKind.RESOURCE_REPLACE -> {
                if (target.kind != EvidenceTargetKind.RESOURCE) {
                    add("Resource replace требует подтверждённую resource target.")
                }
                if (target.artifact.isNullOrBlank()) {
                    add("Не определён исходный resource artifact.")
                }
                validateReplacement(request, requireSameSize = false)
            }

            MutationKind.FILE_REPLACE -> {
                if (target.artifact.isNullOrBlank()) {
                    add("Не определён исходный файл для замены.")
                }
                validateReplacement(request, requireSameSize = false)
            }

            MutationKind.CONFIG_VALUE -> {
                if (target.artifact.isNullOrBlank()) {
                    add("Не определён конфигурационный artifact.")
                }
                if (request.parameters["key"].isNullOrBlank()) {
                    add("Не указан ключ конфигурации.")
                }
                if (!request.parameters.containsKey("value")) {
                    add("Не указано новое значение конфигурации.")
                }
            }
        }
    }

    private fun MutableList<String>.validateReplacement(
        request: MutationRequest,
        requireSameSize: Boolean,
    ) {
        val originalSha = request.expectedOriginalSha256
        val originalSize = request.expectedOriginalSize
        val replacement = request.replacement

        if (originalSha == null || !SHA256.matches(originalSha)) {
            add("Не задан SHA-256 ожидаемых исходных байтов.")
        }
        if (originalSize == null || originalSize <= 0L) {
            add("Не задан валидный размер ожидаемых исходных данных.")
        }
        if (replacement == null) {
            add("Не задано содержимое замены.")
            return
        }
        if (!SHA256.matches(replacement.sha256)) {
            add("SHA-256 содержимого замены имеет неверный формат.")
        }
        if (replacement.size <= 0L) {
            add("Содержимое замены пустое.")
        }
        if (
            requireSameSize &&
            originalSize != null &&
            originalSize > 0L &&
            replacement.size != originalSize
        ) {
            add("In-place patch должен сохранять размер заменяемого диапазона.")
        }
    }

    private const val MAX_NATIVE_PATCH_BYTES = 4096L
}
