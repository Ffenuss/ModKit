package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.*
import io.github.ffenuss.modkit.analysis.nativecode.*
import java.io.File

/** Simple mode uses decoded return dataflow. Complex recipes remain visible with a reason. */
object NativeRecipeCatalog {
    fun create(result: FastAnalysisResult, preparation: PatchPreparationPlan,
        analysisRoot: File, cancellation: CancellationSignal): List<AutoModRecipe> {
        val verifiedIndices = result.il2cppBinaryBinding?.evidence.orEmpty().associate {
            it.libraryEntry to (it.functionIndex?.let { index -> index.complete && index.verify() } == true)
        }
        val targets = result.evidenceGraph?.targets.orEmpty().associateBy { it.id }
        return GameplayModificationFinder.find(result, preparation)
            .filter { it.category !in setOf(GameplayModificationCategory.OWNER_ENTITLEMENT,
                GameplayModificationCategory.SENSITIVE_SURFACE) }
            .map { candidate ->
                if (cancellation.isCancelled()) throw AnalysisCancelledException()
                var reason = candidate.blocker
                var description = "Назначение предполагается по метаданным; игровой эффект ещё не проверен."
                if (candidate.selectable && reason == null) {
                    try {
                        val target = requireNotNull(targets[candidate.targetId])
                        require(target.abi == "arm64-v8a") { "Автоматический рецепт поддерживает только ARM64." }
                        require(verifiedIndices[target.artifact] == true) { "Нужен полный проверенный индекс адресов." }
                        val window = Il2CppNativeMutationDraftBuilder.readCodeWindow(result, candidate.targetId, analysisRoot)
                        val body = AArch64MethodAnalyzer.analyze(AArch64Disassembler.disassemble(
                            Il2CppNativeMutationDraftBuilder.parseHex(window.originalHex),
                            window.binaryVirtualAddress ?: window.fileOffset))
                        require(body.shape in setOf(AArch64MethodShape.RETURN_CONSTANT,
                            AArch64MethodShape.INSTANCE_FIELD_GETTER)) {
                            "${body.shape.title}: замена всего тела требует дополнительного анализа."
                        }
                        description = "${body.shape.title}. Предлагается заменить результат; эффект требует проверки в игре."
                    } catch (failure: Exception) { reason = failure.message ?: "Тело метода не подтверждено." }
                } else if (reason == null) reason = "Нет проверенного рецепта для этой цели."
                AutoModRecipe(candidate.id, candidate.category.title.substringBefore(" /"),
                    candidate.title, description, targets[candidate.targetId]?.declaringType?.substringAfterLast('.').orEmpty(),
                    native = candidate, blocker = reason,
                    verification = ModificationVerification(recipePrepared = reason == null))
            }
    }
}
