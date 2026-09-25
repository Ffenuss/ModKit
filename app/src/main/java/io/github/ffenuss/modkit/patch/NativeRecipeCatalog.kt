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
        val verifiedDiskBindings = evidence.mapValues { (_, e) -> e.bindingIndex?.verify() == true }
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
                val target = targets[candidate.targetId]
                val library = evidence[target?.artifact]
                val binding = if (target == null || library == null) null else {
                    val token = target.metadataToken
                    val imageName = Il2CppPatchTargetBrowser.imageName(target)
                    if (token == null || imageName == null) null
                    else if (verifiedDiskBindings[target.artifact] == true) {
                        io.github.ffenuss.modkit.analysis.Il2CppOnDemandBindings.find(
                            result, token, imageName, requireNotNull(target.artifact),
                            indexVerified = true,
                        )
                    } else {
                        library.bindings.filter {
                            it.metadataToken == token &&
                                it.imageName.equals(imageName, ignoreCase = true)
                        }.singleOrNull()
                    }
                }
                val presentation = target?.declaringType.orEmpty().let { owner ->
                    Regex("(?i)(InfoBox|Display|Tooltip|(^|[._])UI([._]|$)|HUD|HealthBar|TextView)").containsMatchIn(owner)
                }
                val numeric = candidate.confidence == GameplayModificationConfidence.STRONG_NUMERIC_CANDIDATE ||
                    candidate.action in setOf(GameplayMutationAction.FORCE_TWO, GameplayMutationAction.FORCE_SCALAR_DEFAULT) ||
                    candidate.confidence == GameplayModificationConfidence.SEMANTIC_METHOD_SIGNAL &&
                    binding?.returnKind in setOf(Il2CppNativeReturnKind.INTEGER, Il2CppNativeReturnKind.FLOAT32,
                        Il2CppNativeReturnKind.FLOAT64, Il2CppNativeReturnKind.BOOLEAN)
                if (candidate.selectable || numeric) {
                    try {
                        requireNotNull(target) { "Нет точной привязки к телу метода." }
                        require(target.abi == "arm64-v8a") { "Автоматический рецепт поддерживает только ARM64." }
                        requireNotNull(library) { "Нет подтверждённой нативной библиотеки." }
                        require(verifiedIndices[target.artifact] == true) { "Нужен полный проверенный индекс адресов. Повторите анализ." }
                        val span = requireNotNull(library.functionIndex?.lookup(requireNotNull(target.fileOffset)))
                        require(span.references == 1) { "Это тело используют ${span.references} методов. Нужен согласованный рецепт для всех." }
                        requireNotNull(binding) { "Тип результата не привязан к единственному методу." }
                        val window = Il2CppNativeMutationDraftBuilder.readCodeWindow(result, candidate.targetId, analysisRoot, 1024)
                        val code = Il2CppNativeMutationDraftBuilder.parseHex(window.originalHex)
                        // Match the draft builder's final-method rule. Reading a large
                        // window is not proof that a multi-instruction patch fits.
                        val patchCapacity = if (span.nextOffset == null) minOf(4, window.byteLength) else window.byteLength
                        val proof = AArch64ReadOnlyBody.inspect(code)
                        val bodyAnalysis = AArch64MethodAnalyzer.analyze(AArch64Disassembler.disassemble(code,
                            window.binaryVirtualAddress ?: window.fileOffset, 256))
                        if (binding.returnKind == Il2CppNativeReturnKind.VOID) {
                            require(candidate.action == GameplayMutationAction.SKIP_METHOD &&
                                bodyAnalysis.shape == AArch64MethodShape.INSTANCE_FIELD_SETTER) {
                                "Метод меняет состояние. Пока поддерживается только доказанная одиночная запись поля без вызовов."
                            }
                        } else require(proof.supported) { proof.reason }
                        val prefix = proof.entryLandingPad?.let { word ->
                            (0..3).joinToString(" ") { b -> "%02X".format((word ushr (b * 8)) and 255) } + " "
                        }.orEmpty()
                        if (numeric) {
                            require(binding.returnKind in setOf(Il2CppNativeReturnKind.INTEGER,
                                Il2CppNativeReturnKind.FLOAT32, Il2CppNativeReturnKind.FLOAT64,
                                Il2CppNativeReturnKind.BOOLEAN)) { "Ширина скалярного результата не доказана." }
                            val choices = when (binding.returnKind) {
                                Il2CppNativeReturnKind.BOOLEAN -> listOf("0", "1")
                                Il2CppNativeReturnKind.INTEGER -> listOf("0", "1", "2", "5", "99", "999", "9999")
                                else -> listOf("0", "0.5", "1", "2", "3", "5", "99", "999")
                            }
                            val encodedKind = if (binding.returnKind == Il2CppNativeReturnKind.BOOLEAN)
                                Il2CppNativeReturnKind.INTEGER else binding.returnKind
                            values = choices.map { value -> ScalarRecipeValue(value,
                                prefix + AArch64ScalarReturnEncoder.encodeHex(encodedKind, value)) }
                                .filter {
                                    val bytes = Il2CppNativeMutationDraftBuilder.parseHex(it.replacementHex)
                                    bytes.size <= patchCapacity && !bytes.contentEquals(code.copyOf(bytes.size))
                                }
                            require(values.isNotEmpty()) {
                                if (span.nextOffset == null) "Граница следующего метода не доказана; многокомандный патч пока недоступен."
                                else "Нет отличающегося патча, который помещается до следующего метода."
                            }
                            val member = target.memberName.orEmpty().removePrefix("get_")
                            val preferred = when {
                                binding.returnKind == Il2CppNativeReturnKind.BOOLEAN -> if (member.startsWith("Can")) "1" else "0"
                                member.contains("Cost") || member.startsWith("GetNeeded") -> "0"
                                else -> when (candidate.category) {
                                GameplayModificationCategory.SURVIVABILITY, GameplayModificationCategory.STAMINA,
                                GameplayModificationCategory.ECONOMY -> "999"
                                GameplayModificationCategory.COOLDOWN -> "0"
                                GameplayModificationCategory.INVENTORY, GameplayModificationCategory.PROGRESSION -> "99"
                                else -> "2"
                                }
                            }
                            val chosen = values.firstOrNull { it.value == preferred } ?: values.first()
                            selectedValue = chosen.value
                            effective = candidate.copy(selectable = true, blocker = null,
                                action = GameplayMutationAction.FORCE_SCALAR_DEFAULT, replacementHex = chosen.replacementHex)
                            title = parameterLabel(member) + " · значение ${chosen.value}"
                        } else {
                            val replacement = prefix + requireNotNull(candidate.replacementHex)
                            val bytes = Il2CppNativeMutationDraftBuilder.parseHex(replacement)
                            require(bytes.size <= patchCapacity) {
                                "Безопасная граница патча не доказана."
                            }
                            val booleanValue = when (candidate.action) {
                                GameplayMutationAction.FORCE_TRUE -> 1L
                                GameplayMutationAction.FORCE_FALSE -> 0L
                                else -> null
                            }
                            val sameBoolean = binding.returnKind == Il2CppNativeReturnKind.BOOLEAN &&
                                booleanValue != null && bodyAnalysis.constantBits == booleanValue
                            require(!sameBoolean && !bytes.contentEquals(code.copyOf(bytes.size))) {
                                "Метод уже возвращает выбранное значение; изменение не требуется."
                            }
                            effective = candidate.copy(replacementHex = replacement)
                        }
                        reason = null
                        description = if (binding.returnKind == Il2CppNativeReturnKind.VOID)
                            "Отключает единственную запись поля в этом методе. Проверяйте, каких игровых объектов это касается."
                        else "Меняет результат вычисления без удаления вызовов и записи состояния. Проверьте эффект в игре."
                        if (binding.returnKind == Il2CppNativeReturnKind.BOOLEAN && numeric)
                            description += " 1 — да, 0 — нет."
                        if (presentation) description = "По контексту это элемент отображения. Изменение показателя на экране не доказывает изменение игровой механики."
                    } catch (failure: AnalysisCancelledException) { throw failure }
                    catch (failure: Exception) { reason = failure.message ?: "Тело метода не подтверждено." }
                } else if (reason == null) reason = "Нет проверенного рецепта для этой цели."
                AutoModRecipe(candidate.id, if (presentation) "Визуальные изменения" else candidate.category.title.substringBefore(" /"),
                    title, description, targets[candidate.targetId]?.declaringType?.substringAfterLast('.').orEmpty(),
                    native = effective, blocker = reason, scalarValues = values, scalarValue = selectedValue,
                    verification = ModificationVerification(recipePrepared = reason == null))
            }
    }

    private fun parameterLabel(member: String): String = when (member.removePrefix("Get")) {
        "MaxHealth" -> "Максимальное здоровье"
        "MaxHealthRaw" -> "Базовый предел здоровья"
        "Damage" -> "Значение урона"
        "TotalDamage" -> "Суммарный урон"
        "DamageMultiplier" -> "Множитель урона"
        "CurrentEnergy" -> "Текущая энергия"
        "NeededEnergy" -> "Расход энергии"
        "ItemCount" -> "Количество предметов"
        "LevelUpCost" -> "Стоимость повышения уровня"
        "Level" -> "Уровень"
        "CanLevelUp" -> "Возможность повышения уровня"
        "ReachedMaxLevel" -> "Проверка максимального уровня"
        "Zoom" -> "Масштаб камеры"
        else -> member.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").replace('_', ' ').trim()
    }
}
