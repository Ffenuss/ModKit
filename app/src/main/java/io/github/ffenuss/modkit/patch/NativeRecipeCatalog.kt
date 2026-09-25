package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.*
import io.github.ffenuss.modkit.analysis.nativecode.*
import java.io.File

/** Every enabled recipe has a type, unique address, bounded body and concrete bytes. */
object NativeRecipeCatalog {
    fun create(result: FastAnalysisResult, preparation: PatchPreparationPlan,
        analysisRoot: File, cancellation: CancellationSignal): List<AutoModRecipe> {
        val evidence = result.il2cppBinaryBinding?.evidence.orEmpty().associateBy { it.libraryEntry }
        val verifiedIndices = evidence.mapValues { (_, e) -> e.functionIndex?.let { it.complete && it.verify() } == true }
        val targets = result.evidenceGraph?.targets.orEmpty().associateBy { it.id }
        return GameplayModificationFinder.find(result, preparation)
            .filter { it.category !in setOf(GameplayModificationCategory.OWNER_ENTITLEMENT,
                GameplayModificationCategory.SENSITIVE_SURFACE) }
            .map { candidate ->
                if (cancellation.isCancelled()) throw AnalysisCancelledException()
                var effective = candidate
                var reason = candidate.blocker
                var title = candidate.title
                var selectedValue: String? = null
                var description = "Назначение предполагается по метаданным; игровой эффект ещё не проверен."
                var values = emptyList<ScalarRecipeValue>()
                val numeric = candidate.confidence == GameplayModificationConfidence.STRONG_NUMERIC_CANDIDATE ||
                    candidate.action in setOf(GameplayMutationAction.FORCE_TWO, GameplayMutationAction.FORCE_SCALAR_DEFAULT)
                if (candidate.selectable || numeric) {
                    try {
                        val target = requireNotNull(targets[candidate.targetId])
                        require(target.abi == "arm64-v8a") { "Автоматический рецепт поддерживает только ARM64." }
                        val library = requireNotNull(evidence[target.artifact])
                        require(verifiedIndices[target.artifact] == true) { "Нужен полный проверенный индекс адресов. Повторите анализ." }
                        val span = requireNotNull(library.functionIndex?.lookup(requireNotNull(target.fileOffset)))
                        require(span.references == 1) { "Это тело используют ${span.references} методов. Нужен согласованный рецепт для всех." }
                        val binding = library.bindings.singleOrNull { it.metadataToken == target.metadataToken &&
                            it.imageName == Il2CppPatchTargetBrowser.imageName(target) }
                        requireNotNull(binding) { "Тип результата не привязан к единственному методу." }
                        val window = Il2CppNativeMutationDraftBuilder.readCodeWindow(result, candidate.targetId, analysisRoot, 1024)
                        val code = Il2CppNativeMutationDraftBuilder.parseHex(window.originalHex)
                        val proof = AArch64ReadOnlyBody.inspect(code)
                        val shape = AArch64MethodAnalyzer.analyze(AArch64Disassembler.disassemble(code,
                            window.binaryVirtualAddress ?: window.fileOffset, 256)).shape
                        if (binding.returnKind == Il2CppNativeReturnKind.VOID) {
                            require(candidate.action == GameplayMutationAction.SKIP_METHOD &&
                                shape == AArch64MethodShape.INSTANCE_FIELD_SETTER) {
                                "Метод меняет состояние. Пока поддерживается только доказанная одиночная запись поля без вызовов."
                            }
                        } else require(proof.supported) { proof.reason }
                        val prefix = proof.entryLandingPad?.let { word ->
                            (0..3).joinToString(" ") { b -> "%02X".format((word ushr (b * 8)) and 255) } + " "
                        }.orEmpty()
                        if (numeric) {
                            require(binding.returnKind in setOf(Il2CppNativeReturnKind.INTEGER,
                                Il2CppNativeReturnKind.FLOAT32, Il2CppNativeReturnKind.FLOAT64)) { "Ширина числового результата не доказана." }
                            val choices = if (binding.returnKind == Il2CppNativeReturnKind.INTEGER)
                                listOf("0", "1", "2", "5", "99", "999", "9999")
                            else listOf("0", "0.5", "1", "2", "3", "5", "99", "999")
                            values = choices.map { value -> ScalarRecipeValue(value,
                                prefix + AArch64ScalarReturnEncoder.encodeHex(binding.returnKind, value)) }
                                .filter { Il2CppNativeMutationDraftBuilder.parseHex(it.replacementHex).size <= window.byteLength }
                            require(values.isNotEmpty()) { "Патч не помещается до следующего метода." }
                            val preferred = when (candidate.category) {
                                GameplayModificationCategory.SURVIVABILITY, GameplayModificationCategory.STAMINA,
                                GameplayModificationCategory.ECONOMY -> "999"
                                GameplayModificationCategory.COOLDOWN -> "0"
                                GameplayModificationCategory.INVENTORY, GameplayModificationCategory.PROGRESSION -> "99"
                                else -> "2"
                            }
                            val chosen = values.firstOrNull { it.value == preferred } ?: values.first()
                            selectedValue = chosen.value
                            effective = candidate.copy(selectable = true, blocker = null,
                                action = GameplayMutationAction.FORCE_SCALAR_DEFAULT, replacementHex = chosen.replacementHex)
                            title = candidate.category.title.substringBefore(" /") + ": " +
                                target.memberName.orEmpty().removePrefix("get_") + " · значение ${chosen.value}"
                        } else {
                            val replacement = prefix + requireNotNull(candidate.replacementHex)
                            require(Il2CppNativeMutationDraftBuilder.parseHex(replacement).size <= window.byteLength) {
                                "Патч не помещается до следующего метода."
                            }
                            effective = candidate.copy(replacementHex = replacement)
                        }
                        reason = null
                        description = if (binding.returnKind == Il2CppNativeReturnKind.VOID)
                            "Отключает единственную запись поля в этом методе. Проверяйте, каких игровых объектов это касается."
                        else "Меняет результат вычисления без удаления вызовов и записи состояния. Проверьте эффект в игре."
                    } catch (failure: AnalysisCancelledException) { throw failure }
                    catch (failure: Exception) { reason = failure.message ?: "Тело метода не подтверждено." }
                } else if (reason == null) reason = "Нет проверенного рецепта для этой цели."
                AutoModRecipe(candidate.id, candidate.category.title.substringBefore(" /"),
                    title, description, targets[candidate.targetId]?.declaringType?.substringAfterLast('.').orEmpty(),
                    native = effective, blocker = reason, scalarValues = values, scalarValue = selectedValue,
                    verification = ModificationVerification(recipePrepared = reason == null))
            }
    }
}
