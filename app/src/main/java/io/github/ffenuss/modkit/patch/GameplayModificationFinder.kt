package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.analysis.EvidenceTargetKind
import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.analysis.Il2CppMethodBinaryBinding
import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind

enum class GameplayModificationCategory(
    val title: String,
    val priority: Int,
) {
    SURVIVABILITY("Здоровье / бессмертие", 0),
    DAMAGE("Урон / защита", 1),
    MOVEMENT("Скорость / движение", 2),
    COLLISION("Noclip / коллизии", 3),
    STAMINA("Стамина / энергия", 4),
    COOLDOWN("Кулдауны / таймеры", 5),
    CONTROL("Оглушение / ограничения", 6),
    ATTACK_SPEED("Скорость атаки", 7),
    REGENERATION("Регенерация / лечение", 8),
    PROGRESSION("Уровень / опыт / навыки", 9),
    INVENTORY("Инвентарь / предметы", 10),
    DROPS("Дроп / награды", 11),
    ECONOMY("Валюта / ресурсы", 12),
    RNG_REWARDS("Шанс / редкость / RNG", 13),
    DIFFICULTY("Сложность / параметры врагов", 14),
    WORLD("Прыжок / гравитация / время", 15),
    CAMERA("Камера / FOV", 16),
    OWNER_ENTITLEMENT("Full / Premium — локальный тест", 17),
    SENSITIVE_SURFACE("Billing / auth / anti-cheat", 90),
}

enum class GameplayMutationAction(
    val presetId: String?,
) {
    SKIP_METHOD("arm64-return-void"),
    FORCE_FALSE("arm64-return-zero"),
    FORCE_TRUE("arm64-return-one"),
    FORCE_ZERO("arm64-return-zero"),
    FORCE_TWO(null),
    FORCE_SCALAR_DEFAULT(null),
    DISCOVERY_ONLY(null),
}

enum class GameplayModificationConfidence {
    EXACT_ACTION,
    STRONG_NUMERIC_CANDIDATE,
    SEMANTIC_METHOD_SIGNAL,
    SEMANTIC_MODEL_SIGNAL,
    SENSITIVE_SURFACE_SIGNAL,
}

data class GameplayModificationOpportunity(
    val id: String,
    val category: GameplayModificationCategory,
    val title: String,
    val targetId: String,
    val targetDisplayName: String,
    val action: GameplayMutationAction,
    val replacementHex: String?,
    val selectable: Boolean,
    val blocker: String?,
    val evidenceSummary: String,
    val confidence: GameplayModificationConfidence,
)

/**
 * High precision gameplay modification discovery.
 *
 * Rules intentionally operate on tokenized method names rather than raw
 * substring matches. Example: "UIGradient" must never match "die".
 * Declaring type is used only to reject infrastructure/generated surfaces,
 * not to invent gameplay meaning for generic methods such as ctor/Invoke.
 */
object GameplayModificationFinder {
    fun find(
        result: FastAnalysisResult,
        preparation: PatchPreparationPlan,
        projectCodeOnly: Boolean = true,
        limit: Int = 64,
        perCategoryLimit: Int = 4,
    ): List<GameplayModificationOpportunity> {
        require(limit in 1..512) {
            "Gameplay modification result limit is out of bounds."
        }
        require(perCategoryLimit in 1..64) {
            "Gameplay category result limit is out of bounds."
        }

        /*
         * Keep the first pass deliberately cheap. Large IL2CPP titles can have
         * 100k+ exact targets; building binding/body maps for all of them caused
         * large transient allocations and UI/export crashes. We first retain
         * only semantically plausible gameplay/sensitive targets, then build
         * exact lookup tables for that much smaller candidate set.
         */
        val candidates =
            preparation.targets
                .asSequence()
                .filter(::isEligible)
                .mapNotNull { prepared ->
                    val target = prepared.target
                    val memberName =
                        target.memberName ?: return@mapNotNull null
                    val methodTokens =
                        semanticMethodTokens(memberName)
                    if (methodTokens.isEmpty()) {
                        return@mapNotNull null
                    }

                    val ownerEntitlementLabel =
                        ownerEntitlementLabel(
                            target = target,
                            methodTokens = methodTokens,
                        )
                    val sensitiveKind =
                        if (ownerEntitlementLabel == null) {
                            sensitiveSurfaceKind(
                                target = target,
                                methodTokens = methodTokens,
                            )
                        } else {
                            null
                        }
                    if (
                        projectCodeOnly &&
                        !Il2CppPatchTargetBrowser
                            .isProjectCode(target) &&
                        sensitiveKind == null
                    ) {
                        return@mapNotNull null
                    }
                    if (
                        isInfrastructureOrGenerated(target) &&
                        sensitiveKind == null
                    ) {
                        return@mapNotNull null
                    }

                    val category =
                        when {
                            ownerEntitlementLabel != null ->
                                GameplayModificationCategory
                                    .OWNER_ENTITLEMENT
                            sensitiveKind != null ->
                                GameplayModificationCategory
                                    .SENSITIVE_SURFACE
                            else ->
                                classify(methodTokens)
                                    ?: return@mapNotNull null
                        }
                    if (
                        ownerEntitlementLabel == null &&
                        sensitiveKind == null &&
                        !isSemanticallyPlausible(
                            target = target,
                            category = category,
                            methodTokens = methodTokens,
                        )
                    ) {
                        return@mapNotNull null
                    }

                    val artifact =
                        target.artifact ?: return@mapNotNull null
                    val token =
                        target.metadataToken ?: return@mapNotNull null
                    val abi =
                        target.abi ?: return@mapNotNull null
                    val offset =
                        target.fileOffset ?: return@mapNotNull null
                    val imageName =
                        Il2CppPatchTargetBrowser.imageName(target)
                            ?: return@mapNotNull null

                    MethodCandidate(
                        target = target,
                        artifact = artifact,
                        token = token,
                        abi = abi,
                        offset = offset,
                        imageName = imageName,
                        methodTokens = methodTokens,
                        category = category,
                        ownerEntitlementLabel =
                            ownerEntitlementLabel,
                        sensitiveKind = sensitiveKind,
                    )
                }
                .toList()

        val candidateBindingKeys =
            candidates
                .asSequence()
                .map {
                    bindingKey(
                        artifact = it.artifact,
                        imageName = it.imageName,
                        token = it.token,
                    )
                }
                .toHashSet()

        val bindingByArtifactImageToken =
            HashMap<String, MutableList<Il2CppMethodBinaryBinding>>()
        result.il2cppBinaryBinding
            ?.evidence
            .orEmpty()
            .forEach { evidence ->
                evidence.bindings.forEach { binding ->
                    val key =
                        bindingKey(
                            artifact = evidence.libraryEntry,
                            imageName = binding.imageName,
                            token = binding.metadataToken,
                        )
                    if (key in candidateBindingKeys) {
                        bindingByArtifactImageToken
                            .getOrPut(key) { mutableListOf() }
                            .add(binding)
                    }
                }
            }

        val candidateBodyKeys =
            candidates
                .asSequence()
                .map {
                    bodyKey(
                        artifact = it.artifact,
                        offset = it.offset,
                    )
                }
                .toHashSet()
        val sharedBodyCounts =
            HashMap<String, Int>()
        result.evidenceGraph
            ?.targets
            .orEmpty()
            .forEach { target ->
                if (
                    target.runtimeId != "unity_il2cpp" ||
                    target.kind != EvidenceTargetKind.METHOD
                ) {
                    return@forEach
                }
                val artifact =
                    target.artifact ?: return@forEach
                val offset =
                    target.fileOffset ?: return@forEach
                val key = bodyKey(artifact, offset)
                if (key in candidateBodyKeys) {
                    sharedBodyCounts[key] =
                        (sharedBodyCounts[key] ?: 0) + 1
                }
            }

        val ranked =
            candidates
                .asSequence()
                .mapNotNull { candidate ->
                    val target = candidate.target
                    val memberName =
                        target.memberName ?: return@mapNotNull null
                    val binding =
                        bindingByArtifactImageToken[
                            bindingKey(
                                artifact = candidate.artifact,
                                imageName = candidate.imageName,
                                token = candidate.token,
                            )
                        ]
                            ?.singleOrNull()
                    val returnKind =
                        binding?.returnKind
                            ?: Il2CppNativeReturnKind.UNKNOWN

                    val sharedCount =
                        sharedBodyCounts[
                            bodyKey(
                                candidate.artifact,
                                candidate.offset,
                            )
                        ] ?: 0

                    if (
                        candidate.ownerEntitlementLabel !=
                            null
                    ) {
                        val action =
                            GameplayMutationAction.FORCE_TRUE
                        val preset =
                            presetForAction(
                                abi = candidate.abi,
                                action = action,
                                returnKind = returnKind,
                            )
                        val blocker =
                            when {
                                sharedCount != 1 ->
                                    "Native body общий для " +
                                        sharedCount +
                                        " metadata-методов; автоматический patch заблокирован."
                                binding == null ->
                                    "Не доказана сигнатура return type для exact binary target."
                                returnKind !=
                                    Il2CppNativeReturnKind
                                        .BOOLEAN ->
                                    "Entitlement-кандидат найден, но метод не доказан как Boolean."
                                preset == null ->
                                    "Для ABI/Boolean return type нет безопасного preset."
                                else -> null
                            }
                        return@mapNotNull GameplayModificationOpportunity(
                                id =
                                    "owner-entitlement:" +
                                        target.id,
                                category =
                                    GameplayModificationCategory
                                        .OWNER_ENTITLEMENT,
                                title =
                                    "Локальная проверка: " +
                                        candidate
                                            .ownerEntitlementLabel +
                                        " → куплено",
                                targetId = target.id,
                                targetDisplayName =
                                    target.displayName,
                                action = action,
                                replacementHex =
                                    preset?.replacementHex,
                                selectable =
                                    blocker == null &&
                                        preset != null,
                                blocker = blocker,
                                evidenceSummary =
                                    "Точная IL2CPP binary-привязка · " +
                                        (
                                            if (
                                                sharedCount ==
                                                    1
                                            ) {
                                                "unique body"
                                            } else {
                                                "shared body: " +
                                                    sharedCount
                                            }
                                            ) +
                                        " · " +
                                        Il2CppPatchTargetBrowser
                                            .returnKindLabel(
                                                returnKind,
                                            ) +
                                        " · local entitlement test",
                                confidence =
                                    GameplayModificationConfidence
                                        .EXACT_ACTION,
                            )
                    }

                    if (candidate.sensitiveKind != null) {
                        return@mapNotNull GameplayModificationOpportunity(
                            id =
                                "sensitive:" +
                                    candidate.sensitiveKind.lowercase() + ":" +
                                    target.id,
                            category =
                                GameplayModificationCategory
                                    .SENSITIVE_SURFACE,
                            title =
                                "Чувствительная поверхность: " +
                                    candidate.sensitiveKind,
                            targetId = target.id,
                            targetDisplayName =
                                target.displayName,
                            action =
                                GameplayMutationAction
                                    .DISCOVERY_ONLY,
                            replacementHex = null,
                            selectable = false,
                            blocker =
                                "Поверхность доступна для анализа и ручного сопоставления. " +
                                    "Автоматический bypass/preset не формируется; при точной " +
                                    "binary-привязке цель остаётся доступна в ручном Patch Lab.",
                            evidenceSummary =
                                "Точная IL2CPP binary-привязка · " +
                                    Il2CppPatchTargetBrowser
                                        .returnKindLabel(returnKind),
                            confidence =
                                GameplayModificationConfidence
                                    .SENSITIVE_SURFACE_SIGNAL,
                        )
                    }

                    val category = candidate.category
                    val action =
                        resolveAction(
                            target = target,
                            category = category,
                            methodTokens = candidate.methodTokens,
                            returnKind = returnKind,
                        )
                    val numericCandidate =
                        action == GameplayMutationAction.DISCOVERY_ONLY &&
                            isStrongNumericCandidate(
                                memberName = memberName,
                                category = category,
                                returnKind = returnKind,
                            )
                    val semanticOnlyCandidate =
                        action == GameplayMutationAction.DISCOVERY_ONLY &&
                            !numericCandidate

                    val numericAuto =
                        if (numericCandidate &&
                            binding != null &&
                            sharedCount == 1
                        ) {
                            autoNumericPreset(
                                target = target,
                                category = category,
                                returnKind = returnKind,
                                abi = candidate.abi,
                            )
                        } else {
                            null
                        }
                    val effectiveAction =
                        if (numericAuto != null) {
                            GameplayMutationAction.FORCE_SCALAR_DEFAULT
                        } else {
                            action
                        }
                    val preset =
                        presetForAction(
                            abi = candidate.abi,
                            action = action,
                            returnKind = returnKind,
                        )
                    val replacementHex =
                        numericAuto?.replacementHex ?: preset?.replacementHex
                    val blocker =
                        when {
                            sharedCount != 1 ->
                                "Native body общий для " +
                                    sharedCount +
                                    " metadata-методов; автоматический patch заблокирован."
                            binding == null ->
                                "Не доказана сигнатура return type для exact binary target."
                            numericCandidate && numericAuto == null ->
                                numericBlocker(
                                    category = category,
                                    returnKind = returnKind,
                                )
                            semanticOnlyCandidate ->
                                "Метод относится к категории «" +
                                    category.title +
                                    "» по имени и контексту класса. " +
                                    "Готового безопасного автопатча нет; " +
                                    "откройте код метода для ручного изменения."
                            replacementHex == null ->
                                "Для ABI/return type пока нет безопасного готового preset."
                            else -> null
                        }
                    val selectable =
                        blocker == null &&
                            replacementHex != null &&
                            effectiveAction != GameplayMutationAction.DISCOVERY_ONLY

                    GameplayModificationOpportunity(
                        id =
                            category.name.lowercase() + ":" +
                                target.id + ":" +
                                effectiveAction.name.lowercase(),
                        category = category,
                        title =
                            if (numericAuto != null) {
                                category.title + ": " +
                                    readableMethod(memberName) +
                                    "() → " + numericAuto.valueLabel +
                                    " (тест)"
                            } else if (semanticOnlyCandidate) {
                                category.title +
                                    ": кандидат " +
                                    readableMethod(memberName) +
                                    "()"
                            } else {
                                actionTitle(
                                    category = category,
                                    action = action,
                                    methodName = memberName,
                                )
                            },
                        targetId = target.id,
                        targetDisplayName = target.displayName,
                        action = effectiveAction,
                        replacementHex = replacementHex,
                        selectable = selectable,
                        blocker = blocker,
                        evidenceSummary =
                            "Точная binary-привязка · " +
                                (
                                    if (sharedCount == 1) {
                                        "unique body"
                                    } else {
                                        "shared body: " +
                                            sharedCount
                                    }
                                    ) +
                                " · " +
                                Il2CppPatchTargetBrowser
                                    .returnKindLabel(returnKind) +
                                if (numericAuto != null) {
                                    " · рекомендованное тестовое значение, эффект в игре не подтверждён"
                                } else {
                                    ""
                                },
                        confidence =
                            when {
                                numericCandidate ->
                                    GameplayModificationConfidence
                                        .STRONG_NUMERIC_CANDIDATE
                                semanticOnlyCandidate ->
                                    GameplayModificationConfidence
                                        .SEMANTIC_METHOD_SIGNAL
                                else ->
                                    GameplayModificationConfidence
                                        .EXACT_ACTION
                            },
                    )
                }
                .distinctBy { it.targetId }
                .sortedWith(
                    compareByDescending<GameplayModificationOpportunity> {
                        it.selectable
                    }
                        .thenBy { it.category.priority }
                        .thenBy {
                            // Under the per-category result cap, show likely
                            // game state before HUD/display-only methods.
                            // This changes ranking, not proof of behavior.
                            GameplayCandidateTriage.role(it).priority
                        }
                        .thenBy {
                            it.targetDisplayName.lowercase()
                        },
                )
                .toList()

        val metadataSignals =
            discoverMetadataFieldSignals(
                result = result,
                projectCodeOnly = projectCodeOnly,
            )

        val categoryCounts =
            mutableMapOf<GameplayModificationCategory, Int>()
        return (ranked + metadataSignals)
            .asSequence()
            .filter { opportunity ->
                val count =
                    categoryCounts[opportunity.category] ?: 0
                if (count >= perCategoryLimit) {
                    false
                } else {
                    categoryCounts[opportunity.category] =
                        count + 1
                    true
                }
            }
            .take(limit)
            .toList()
    }

    private data class MethodCandidate(
        val target: EvidenceTarget,
        val artifact: String,
        val token: Long,
        val abi: String,
        val offset: Long,
        val imageName: String,
        val methodTokens: List<String>,
        val category: GameplayModificationCategory,
        val ownerEntitlementLabel: String?,
        val sensitiveKind: String?,
    )

    private fun isEligible(
        prepared: PreparedTarget,
    ): Boolean =
        (
            prepared.status ==
                PreparationTargetStatus.CONFIRMED_NEEDS_CHANGE ||
                prepared.status ==
                PreparationTargetStatus.READY
            ) &&
            prepared.target.runtimeId == "unity_il2cpp" &&
            prepared.target.fileOffset != null &&
            prepared.target.abi != null &&
            prepared.target.metadataToken != null

    private fun isInfrastructureOrGenerated(
        target: EvidenceTarget,
    ): Boolean {
        val member =
            target.memberName
                .orEmpty()
                .trim()
        if (
            member.equals(".ctor", ignoreCase = true) ||
            member.equals(".cctor", ignoreCase = true) ||
            member.equals("ctor", ignoreCase = true) ||
            member.equals("cctor", ignoreCase = true) ||
            member.startsWith("<")
        ) {
            return true
        }

        return isInfrastructureOrGeneratedType(
            target.declaringType.orEmpty(),
        )
    }

    private fun isSemanticallyPlausible(
        target: EvidenceTarget,
        category: GameplayModificationCategory,
        methodTokens: List<String>,
    ): Boolean =
        isSemanticallyPlausible(
            declaringType = target.declaringType.orEmpty(),
            category = category,
            semanticTokens = stripAccessor(methodTokens),
        )

    private fun isSemanticallyPlausible(
        declaringType: String,
        category: GameplayModificationCategory,
        semanticTokens: List<String>,
    ): Boolean {
        val typeTokens =
            tokenizeIdentifier(declaringType)
        val semantic =
            semanticTokens

        val rejectedTypePhrases =
            categoryRejectedTypePhrases[category].orEmpty()
        if (
            rejectedTypePhrases.any {
                containsPhrase(typeTokens, it)
            }
        ) {
            return false
        }

        val contextPhrases =
            categoryContextPhrases[category].orEmpty()
        if (contextPhrases.isEmpty()) {
            return true
        }
        if (
            contextPhrases.any {
                containsPhrase(typeTokens, it)
            }
        ) {
            return true
        }

        return strongStandaloneMethodPhrases[category]
            .orEmpty()
            .any {
                containsPhrase(semantic, it)
            }
    }

    private fun discoverMetadataFieldSignals(
        result: FastAnalysisResult,
        projectCodeOnly: Boolean,
    ): List<GameplayModificationOpportunity> {
        val model =
            result.il2cppFastDump
                ?.metadata
                ?: return emptyList()
        if (!model.structuredSupported) return emptyList()

        val imageByTypeIndex =
            HashMap<Int, String>()
        model.images.forEach { image ->
            if (image.typeStart < 0 || image.typeCount <= 0) {
                return@forEach
            }
            repeat(image.typeCount) { relative ->
                imageByTypeIndex.putIfAbsent(
                    image.typeStart + relative,
                    image.name,
                )
            }
        }

        return model.fields
            .asSequence()
            .mapNotNull { field ->
                val imageName =
                    imageByTypeIndex[field.declaringTypeIndex]
                if (
                    projectCodeOnly &&
                    !Il2CppPatchTargetBrowser
                        .isProjectImageName(imageName)
                ) {
                    return@mapNotNull null
                }

                val fieldTokens =
                    tokenizeIdentifier(field.name)
                if (fieldTokens.isEmpty()) {
                    return@mapNotNull null
                }
                val category =
                    classifyField(fieldTokens)
                        ?: return@mapNotNull null
                if (
                    !isSemanticallyPlausible(
                        declaringType = field.declaringType,
                        category = category,
                        semanticTokens = fieldTokens,
                    )
                ) {
                    return@mapNotNull null
                }
                if (
                    isInfrastructureOrGeneratedType(
                        field.declaringType,
                    )
                ) {
                    return@mapNotNull null
                }

                GameplayModificationOpportunity(
                    id =
                        "model-field:" +
                            category.name.lowercase() + ":" +
                            field.declaringTypeIndex + ":" +
                            field.token.toString(16),
                    category = category,
                    title =
                        category.title +
                            ": сигнал поля " +
                            field.name,
                    targetId =
                        "il2cpp:field:" +
                            field.declaringTypeIndex + ":" +
                            field.token.toString(16),
                    targetDisplayName =
                        field.declaringType + "." +
                            field.name,
                    action =
                        GameplayMutationAction
                            .DISCOVERY_ONLY,
                    replacementHex = null,
                    selectable = false,
                    blocker =
                        "Поле найдено в IL2CPP metadata, но runtime offset, экземпляр объекта " +
                            "и безопасная запись не доказаны. Автопатч запрещён.",
                    evidenceSummary =
                        "IL2CPP metadata field · " +
                            (imageName ?: "image не определён") +
                            " · token 0x" +
                            field.token.toString(16),
                    confidence =
                        GameplayModificationConfidence
                            .SEMANTIC_MODEL_SIGNAL,
                )
            }
            .distinctBy {
                it.targetDisplayName.lowercase()
            }
            .sortedWith(
                compareBy<GameplayModificationOpportunity> {
                    it.category.priority
                }
                    .thenBy {
                        it.targetDisplayName.lowercase()
                    },
            )
            .toList()
    }

    private fun classifyField(
        fieldTokens: List<String>,
    ): GameplayModificationCategory? =
        fieldCategoryPhrases
            .firstOrNull { (_, phrases) ->
                phrases.any {
                    containsPhrase(fieldTokens, it)
                }
            }
            ?.first

    private fun isInfrastructureOrGeneratedType(
        declaringType: String,
    ): Boolean {
        val type =
            declaringType.lowercase()
        if (
            type.endsWith("wrap") ||
            type.contains("luabinder") ||
            type.contains("objecttranslator")
        ) {
            return true
        }
        if (
            infrastructureTypePrefixes.any {
                type.startsWith(it)
            }
        ) {
            return true
        }
        return generatedTypeMarkers.any {
            it in type
        }
    }

    private fun ownerEntitlementLabel(
        target: EvidenceTarget,
        methodTokens: List<String>,
    ): String? {
        if (
            !Il2CppPatchTargetBrowser
                .isProjectCode(target)
        ) {
            return null
        }

        val semantic =
            stripAccessor(methodTokens)
        if (semantic.isEmpty()) return null

        val blocked =
            ownerEntitlementBlockedPhrases.any {
                containsPhrase(semantic, it)
            }
        if (blocked) return null

        val matched =
            ownerEntitlementPhrases
                .firstOrNull { (_, phrases) ->
                    phrases.any {
                        containsPhrase(
                            semantic,
                            it,
                        )
                    }
                }
                ?: return null

        val booleanShape =
            methodTokens.firstOrNull() in
                ownerEntitlementBooleanPrefixes ||
                isGetter(
                    target.memberName.orEmpty(),
                ) ||
                containsPhrase(
                    semantic,
                    p("full version"),
                ) ||
                containsPhrase(
                    semantic,
                    p("premium unlocked"),
                )
        if (!booleanShape) return null

        return matched.first
    }

    fun sensitiveSurfaceLabel(
        target: EvidenceTarget,
    ): String? {
        val tokens = semanticMethodTokens(
            target.memberName.orEmpty(),
        )
        if (
            ownerEntitlementLabel(
                target = target,
                methodTokens = tokens,
            ) != null
        ) return null
        return sensitiveSurfaceKind(
            target = target,
            methodTokens = tokens,
        )
    }

    private fun isSensitiveTarget(
        target: EvidenceTarget,
    ): Boolean =
        sensitiveSurfaceLabel(target) != null

    private fun sensitiveSurfaceKind(
        target: EvidenceTarget,
        methodTokens: List<String>,
    ): String? {
        val combined =
            tokenizeIdentifier(
                target.declaringType.orEmpty(),
            ) + stripAccessor(methodTokens)

        return sensitiveSurfacePhrases
            .firstOrNull { (_, phrases) ->
                phrases.any {
                    containsPhrase(combined, it)
                }
            }
            ?.first
    }

    private fun classify(
        methodTokens: List<String>,
    ): GameplayModificationCategory? {
        val semantic =
            stripAccessor(methodTokens)
        return categoryPhrases
            .firstOrNull { (_, phrases) ->
                phrases.any {
                    containsPhrase(semantic, it)
                }
            }
            ?.first
    }

    private fun resolveAction(
        target: EvidenceTarget,
        category: GameplayModificationCategory,
        methodTokens: List<String>,
        returnKind: Il2CppNativeReturnKind,
    ): GameplayMutationAction {
        val semantic =
            stripAccessor(methodTokens)

        if (returnKind == Il2CppNativeReturnKind.BOOLEAN) {
            if (
                forceFalsePhrases.any {
                    containsPhrase(semantic, it)
                }
            ) {
                return GameplayMutationAction.FORCE_FALSE
            }
            if (
                forceTruePhrases.any {
                    containsPhrase(semantic, it)
                }
            ) {
                if (
                    category != GameplayModificationCategory.COLLISION ||
                    isPlayerLike(target)
                ) {
                    return GameplayMutationAction.FORCE_TRUE
                }
            }
        }

        if (returnKind == Il2CppNativeReturnKind.VOID) {
            val phrases =
                when (category) {
                    GameplayModificationCategory.DAMAGE,
                    GameplayModificationCategory.SURVIVABILITY ->
                        voidDamagePhrases
                    GameplayModificationCategory.STAMINA ->
                        voidStaminaPhrases
                    GameplayModificationCategory.COOLDOWN ->
                        voidCooldownPhrases
                    GameplayModificationCategory.MOVEMENT ->
                        voidMovementLimitPhrases
                    GameplayModificationCategory.COLLISION ->
                        if (isPlayerLike(target)) {
                            voidCollisionPhrases
                        } else {
                            emptyList()
                        }
                    GameplayModificationCategory.INVENTORY ->
                        voidInventoryPhrases
                    GameplayModificationCategory.PROGRESSION ->
                        voidProgressionPhrases
                    GameplayModificationCategory.CONTROL ->
                        voidControlPhrases
                    else -> emptyList()
                }
            if (
                phrases.any {
                    containsPhrase(semantic, it)
                }
            ) {
                return GameplayMutationAction.SKIP_METHOD
            }
        }

        if (
            isGetter(target.memberName.orEmpty()) &&
            (
                returnKind == Il2CppNativeReturnKind.FLOAT32 ||
                    returnKind == Il2CppNativeReturnKind.FLOAT64
                ) &&
            category in doubleValueAutoCategories &&
            doubleValuePhrases.any {
                containsPhrase(semantic, it)
            }
        ) {
            return GameplayMutationAction.FORCE_TWO
        }

        if (
            returnKind == Il2CppNativeReturnKind.INTEGER &&
            category == GameplayModificationCategory.COOLDOWN &&
            isGetter(target.memberName.orEmpty()) &&
            cooldownNumericPhrases.any {
                containsPhrase(semantic, it)
            }
        ) {
            return GameplayMutationAction.FORCE_ZERO
        }

        return GameplayMutationAction.DISCOVERY_ONLY
    }

    private fun presetForAction(
        abi: String,
        action: GameplayMutationAction,
        returnKind: Il2CppNativeReturnKind,
    ): NativePatchPreset? =
        when (action) {
            GameplayMutationAction.FORCE_TWO ->
                when (returnKind) {
                    Il2CppNativeReturnKind.FLOAT32 ->
                        NativePatchPresetCatalog.find(
                            abi = abi,
                            id = "arm64-return-f32-two",
                        )
                    Il2CppNativeReturnKind.FLOAT64 ->
                        NativePatchPresetCatalog.find(
                            abi = abi,
                            id = "arm64-return-f64-two",
                        )
                    else -> null
                }
            else ->
                action.presetId?.let { presetId ->
                    NativePatchPresetCatalog.find(
                        abi = abi,
                        id = presetId,
                    )
                }
        }

    private fun isStrongNumericCandidate(
        memberName: String,
        category: GameplayModificationCategory,
        returnKind: Il2CppNativeReturnKind,
    ): Boolean {
        if (!isGetter(memberName)) return false
        if (
            returnKind != Il2CppNativeReturnKind.INTEGER &&
            returnKind != Il2CppNativeReturnKind.FLOAT32 &&
            returnKind != Il2CppNativeReturnKind.FLOAT64 &&
            returnKind != Il2CppNativeReturnKind.FLOATING_POINT
        ) {
            return false
        }
        return category in numericCategories
    }

    private fun actionTitle(
        category: GameplayModificationCategory,
        action: GameplayMutationAction,
        methodName: String,
    ): String =
        when (action) {
            GameplayMutationAction.SKIP_METHOD ->
                category.title + ": отключить " +
                    readableMethod(methodName) + "()"
            GameplayMutationAction.FORCE_FALSE ->
                category.title + ": " +
                    readableMethod(methodName) +
                    "() → false"
            GameplayMutationAction.FORCE_TRUE ->
                category.title + ": " +
                    readableMethod(methodName) +
                    "() → true"
            GameplayMutationAction.FORCE_ZERO ->
                category.title + ": " +
                    readableMethod(methodName) +
                    "() → 0"
            GameplayMutationAction.FORCE_TWO ->
                category.title + ": " +
                    readableMethod(methodName) +
                    "() → 2.0"
            GameplayMutationAction.FORCE_SCALAR_DEFAULT ->
                category.title + ": автоматическое числовое значение " +
                    readableMethod(methodName)
            GameplayMutationAction.DISCOVERY_ONLY ->
                category.title + ": найден числовой параметр " +
                    readableMethod(methodName)
        }

    private data class AutoNumericPreset(
        val valueLabel: String,
        val replacementHex: String,
    )

    /**
     * Suggest a numeric default only when BOTH the proven return type and
     * the gameplay-owner class have a narrow match. A DiskInfoBox/
     * HUD/Upgrade getter is not the player's underlying health or stamina.
     * Every proposed body must still pass the normal exact-offset and
     * minimum-length preflight before a staged copy can be written.
     */
    private fun autoNumericPreset(
        target: EvidenceTarget,
        category: GameplayModificationCategory,
        returnKind: Il2CppNativeReturnKind,
        abi: String,
    ): AutoNumericPreset? {
        if (!abi.equals("arm64-v8a", ignoreCase = true)) return null
        if (returnKind !in listOf(
                Il2CppNativeReturnKind.INTEGER,
                Il2CppNativeReturnKind.FLOAT32,
                Il2CppNativeReturnKind.FLOAT64,
            )
        ) return null
        val method = target.memberName.orEmpty()
        if (!isGetter(method)) return null
        val name = stripAccessor(semanticMethodTokens(method))
        val owner = tokenizeIdentifier(target.declaringType.orEmpty())
        if (owner.any {
                it in setOf(
                    "ui", "hud", "display", "info", "box", "text",
                    "tooltip", "upgrade", "trinket", "enemy",
                )
            }
        ) return null
        val playerContext = owner.any {
            it in setOf("character", "player", "hero")
        }
        if (!playerContext) return null

        val value = when {
            category == GameplayModificationCategory.SURVIVABILITY &&
                name == p("max health") &&
                "health" in owner -> "999"
            category == GameplayModificationCategory.STAMINA &&
                name == p("max stamina") &&
                "stamina" in owner -> "999"
            category == GameplayModificationCategory.STAMINA &&
                name == p("max energy") &&
                "energy" in owner -> "999"
            else -> return null
        }
        val hex = runCatching {
            AArch64ScalarReturnEncoder.encodeHex(
                returnKind = returnKind,
                valueText = value,
            )
        }.getOrNull() ?: return null
        return AutoNumericPreset(valueLabel = value, replacementHex = hex)
    }

    private fun numericBlocker(
        category: GameplayModificationCategory,
        returnKind: Il2CppNativeReturnKind,
    ): String =
        when (returnKind) {
            Il2CppNativeReturnKind.FLOAT32,
            Il2CppNativeReturnKind.FLOAT64 ->
                "Найден точный числовой параметр «" +
                    category.title +
                    "». Ширина float/double доказана; выберите значение " +
                    "0/0.5/1/2/3/5 в ручном Patch Lab."
            Il2CppNativeReturnKind.FLOATING_POINT ->
                "Найден старый float/double-кандидат без доказанной ширины. " +
                    "Пересканируйте APK для безопасного ARM64 preset."
            Il2CppNativeReturnKind.INTEGER ->
                "Найден точный числовой параметр «" +
                    category.title +
                    "», но величина изменения ещё не выбрана и не провалидирована."
            else ->
                "Числовая модификация пока не готова."
        }

    private fun isGetter(
        memberName: String,
    ): Boolean =
        memberName.startsWith("get_", ignoreCase = true)

    private fun isPlayerLike(
        target: EvidenceTarget,
    ): Boolean {
        val tokens =
            tokenizeIdentifier(
                target.declaringType.orEmpty(),
            )
        return playerContextPhrases.any {
            containsPhrase(tokens, it)
        }
    }

    private fun semanticMethodTokens(
        memberName: String,
    ): List<String> =
        tokenizeIdentifier(memberName)
            .filterNot {
                it == "ctor" || it == "cctor"
            }

    private fun stripAccessor(
        tokens: List<String>,
    ): List<String> =
        if (
            tokens.firstOrNull() == "get" ||
            tokens.firstOrNull() == "set"
        ) {
            tokens.drop(1)
        } else {
            tokens
        }

    private fun tokenizeIdentifier(
        value: String,
    ): List<String> {
        if (value.isBlank()) return emptyList()
        val spaced =
            value
                .replace(
                    Regex("([a-z0-9])([A-Z])"),
                    "$1 $2",
                )
                .replace(
                    Regex("([A-Z]+)([A-Z][a-z])"),
                    "$1 $2",
                )
                .replace(
                    Regex("[^A-Za-z0-9]+"),
                    " ",
                )
        return spaced
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { it.lowercase() }
    }

    private fun containsPhrase(
        tokens: List<String>,
        phrase: List<String>,
    ): Boolean {
        if (
            phrase.isEmpty() ||
            phrase.size > tokens.size
        ) {
            return false
        }
        for (
            index in 0..tokens.size - phrase.size
        ) {
            var matches = true
            for (offset in phrase.indices) {
                if (
                    tokens[index + offset] !=
                    phrase[offset]
                ) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun p(
        value: String,
    ): List<String> =
        value.split(' ')

    private fun readableMethod(
        value: String,
    ): String =
        value.removePrefix("get_")
            .removePrefix("set_")

    private fun bindingKey(
        artifact: String,
        imageName: String,
        token: Long,
    ): String =
        artifact + "#" +
            imageName.lowercase() + "#" +
            token.toString(16)

    private fun bodyKey(
        artifact: String,
        offset: Long,
    ): String =
        artifact + "@" + offset

    private val infrastructureTypePrefixes =
        listOf(
            "system.",
            "microsoft.",
            "unityengine.",
            "coffee.uiextensions.",
            "tmpro.",
            "newtonsoft.",
            "google.",
            "cysharp.",
            "dg.tweening.",
        )

    private val generatedTypeMarkers =
        listOf(
            "proxy_auto",
            "ifixbaseproxy",
            "<>",
            "generatedproxy",
        )

    private val ownerEntitlementBooleanPrefixes =
        setOf(
            "is",
            "has",
            "get",
            "owns",
            "can",
        )

    private val ownerEntitlementPhrases =
        listOf(
            "Full version" to
                listOf(
                    p("full version"),
                    p("is full"),
                    p("has full"),
                ),
            "Premium" to
                listOf(
                    p("premium"),
                    p("is premium"),
                    p("has premium"),
                    p("premium unlocked"),
                ),
            "Pro version" to
                listOf(
                    p("pro version"),
                    p("is pro"),
                    p("has pro"),
                ),
            "Purchase entitlement" to
                listOf(
                    p("is purchased"),
                    p("has purchased"),
                    p("is owned"),
                    p("owns product"),
                ),
            "Entitlement" to
                listOf(
                    p("is entitled"),
                    p("has entitlement"),
                    p("is unlocked"),
                ),
        )

    private val ownerEntitlementBlockedPhrases =
        listOf(
            p("buy"),
            p("purchase"),
            p("restore purchase"),
            p("consume"),
            p("acknowledge"),
            p("checkout"),
            p("billing"),
            p("receipt"),
            p("validate"),
            p("verify"),
            p("server"),
            p("login"),
            p("authentication"),
            p("anti cheat"),
        )

    private val sensitiveSurfacePhrases =
        listOf(
            "purchase / billing" to
                listOf(
                    p("purchase"),
                    p("in app purchase"),
                    p("iap"),
                    p("billing"),
                    p("payment"),
                    p("checkout"),
                    p("subscription"),
                    p("store"),
                ),
            "receipt / entitlement" to
                listOf(
                    p("receipt"),
                    p("entitlement"),
                    p("license"),
                    p("owned"),
                    p("ownership"),
                ),
            "authentication / login" to
                listOf(
                    p("server auth"),
                    p("authentication"),
                    p("authorize"),
                    p("authorization"),
                    p("login"),
                    p("sign in"),
                    p("session token"),
                    p("access token"),
                ),
            "integrity / anti-cheat" to
                listOf(
                    p("anti cheat"),
                    p("anticheat"),
                    p("integrity check"),
                    p("tamper"),
                    p("root detection"),
                    p("debugger detection"),
                    p("signature check"),
                ),
            "server reward / gacha" to
                listOf(
                    p("gacha"),
                    p("summon result"),
                    p("draw result"),
                    p("loot box"),
                    p("lootbox"),
                    p("pity"),
                    p("premium currency"),
                    p("purchase currency"),
                ),
            "persistent account data" to
                listOf(
                    p("local storage"),
                    p("database"),
                    p("preferences"),
                    p("account data"),
                ),
        )

    private val doubleValueAutoCategories =
        setOf(
            GameplayModificationCategory.MOVEMENT,
            GameplayModificationCategory.ATTACK_SPEED,
            GameplayModificationCategory.WORLD,
        )

    private val doubleValuePhrases =
        listOf(
            p("time scale"),
            p("move speed"),
            p("movement speed"),
            p("run speed"),
            p("sprint speed"),
            p("speed multiplier"),
            p("attack speed"),
        )

    private val forceTruePhrases =
        listOf(
            p("is invincible"),
            p("is invulnerable"),
            p("is immortal"),
            p("god mode"),
            p("is alive"),
            p("can move"),
            p("can run"),
            p("can sprint"),
            p("can jump"),
            p("can dash"),
            p("can fly"),
            p("can attack"),
            p("can cast"),
            p("has stamina"),
            p("has energy"),
            p("has ammo"),
            p("has item"),
            p("ignore collision"),
            p("can pass through"),
            p("no clip"),
        )

    private val forceFalsePhrases =
        listOf(
            p("is dead"),
            p("is dying"),
            p("on cooldown"),
            p("is on cooldown"),
            p("cooldown active"),
            p("movement blocked"),
            p("is stunned"),
            p("is rooted"),
            p("is silenced"),
        )

    private val voidDamagePhrases =
        listOf(
            p("take damage"),
            p("receive damage"),
            p("apply damage"),
            p("on damage"),
            p("hurt"),
            p("apply hurt"),
            p("die"),
            p("on death"),
            p("kill player"),
        )

    private val voidStaminaPhrases =
        listOf(
            p("consume stamina"),
            p("spend stamina"),
            p("drain stamina"),
            p("consume energy"),
            p("spend energy"),
            p("drain energy"),
        )

    private val voidCooldownPhrases =
        listOf(
            p("start cooldown"),
            p("begin cooldown"),
            p("apply cooldown"),
            p("set cooldown"),
        )

    private val voidMovementLimitPhrases =
        listOf(
            p("apply speed limit"),
            p("limit speed"),
            p("clamp speed"),
            p("block movement"),
        )

    private val voidCollisionPhrases =
        listOf(
            p("apply collision"),
            p("resolve collision"),
            p("block by collision"),
        )

    private val voidInventoryPhrases =
        listOf(
            p("consume ammo"),
            p("spend ammo"),
            p("remove ammo"),
            p("decrement ammo"),
            p("consume item"),
            p("spend item"),
            p("remove item"),
            p("decrement item"),
        )

    private val voidProgressionPhrases =
        listOf(
            p("spend skill point"),
            p("consume skill point"),
            p("spend talent point"),
            p("consume talent point"),
        )

    private val voidControlPhrases =
        listOf(
            p("apply stun"),
            p("apply root"),
            p("apply silence"),
            p("apply slow"),
        )

    private val cooldownNumericPhrases =
        listOf(
            p("cooldown"),
            p("cooldown time"),
            p("recharge time"),
        )

    private val categoryPhrases =
        listOf(
            GameplayModificationCategory.COLLISION to
                listOf(
                    p("no clip"),
                    p("ignore collision"),
                    p("can pass through"),
                    p("apply collision"),
                    p("resolve collision"),
                ),
            GameplayModificationCategory.STAMINA to
                listOf(
                    p("stamina"),
                    p("max stamina"),
                    p("stamina regen"),
                    p("consume stamina"),
                    p("spend stamina"),
                    p("energy"),
                    p("max energy"),
                    p("consume energy"),
                    p("spend energy"),
                ),
            GameplayModificationCategory.COOLDOWN to
                listOf(
                    p("cooldown"),
                    p("cooldown time"),
                    p("recharge time"),
                    p("start cooldown"),
                    p("is on cooldown"),
                ),
            GameplayModificationCategory.CONTROL to
                listOf(
                    p("is stunned"),
                    p("is rooted"),
                    p("is silenced"),
                    p("apply stun"),
                    p("apply root"),
                    p("apply silence"),
                    p("apply slow"),
                ),
            GameplayModificationCategory.ATTACK_SPEED to
                listOf(
                    p("attack speed"),
                    p("attack rate"),
                    p("fire rate"),
                    p("shot speed"),
                ),
            GameplayModificationCategory.REGENERATION to
                listOf(
                    p("health regen"),
                    p("health regeneration"),
                    p("regen rate"),
                    p("regeneration rate"),
                    p("heal rate"),
                    p("healing rate"),
                ),
            GameplayModificationCategory.MOVEMENT to
                listOf(
                    p("move speed"),
                    p("movement speed"),
                    p("walk speed"),
                    p("run speed"),
                    p("sprint speed"),
                    p("dash speed"),
                    p("can move"),
                    p("can run"),
                    p("can sprint"),
                    p("can jump"),
                    p("can dash"),
                    p("jump height"),
                    p("jump distance"),
                    p("jump force"),
                    p("gravity scale"),
                    p("block movement"),
                ),
            GameplayModificationCategory.SURVIVABILITY to
                listOf(
                    p("is invincible"),
                    p("is invulnerable"),
                    p("is immortal"),
                    p("god mode"),
                    p("is alive"),
                    p("is dead"),
                    p("is dying"),
                    p("max health"),
                    p("max hp"),
                    p("hit points"),
                    p("player hp"),
                    p("on death"),
                    p("die"),
                ),
            GameplayModificationCategory.DAMAGE to
                listOf(
                    p("take damage"),
                    p("receive damage"),
                    p("apply damage"),
                    p("on damage"),
                    p("damage"),
                    p("base damage"),
                    p("attack damage"),
                    p("attack power"),
                    p("damage bonus"),
                    p("damage multiplier"),
                    p("critical chance"),
                    p("critical damage"),
                    p("crit chance"),
                    p("crit damage"),
                    p("hurt"),
                ),
            GameplayModificationCategory.PROGRESSION to
                listOf(
                    p("experience"),
                    p("experience points"),
                    p("xp"),
                    p("xp gain"),
                    p("player xp"),
                    p("level"),
                    p("player level"),
                    p("skill point"),
                    p("skill points"),
                    p("talent point"),
                    p("talent points"),
                    p("level up"),
                ),
            GameplayModificationCategory.INVENTORY to
                listOf(
                    p("ammo"),
                    p("max ammo"),
                    p("item count"),
                    p("inventory capacity"),
                    p("carry weight"),
                    p("backpack capacity"),
                    p("consume ammo"),
                    p("remove ammo"),
                    p("consume item"),
                    p("remove item"),
                    p("has ammo"),
                    p("has item"),
                ),
            GameplayModificationCategory.DROPS to
                listOf(
                    p("drop rate"),
                    p("drop chance"),
                    p("loot chance"),
                    p("loot multiplier"),
                    p("reward multiplier"),
                ),
            GameplayModificationCategory.ECONOMY to
                listOf(
                    p("currency"),
                    p("soft currency"),
                    p("hard currency"),
                    p("gold"),
                    p("coins"),
                    p("coin count"),
                    p("gems"),
                    p("gem count"),
                    p("credits"),
                    p("money"),
                    p("wallet"),
                    p("resource count"),
                ),
            GameplayModificationCategory.RNG_REWARDS to
                listOf(
                    p("rarity"),
                    p("rarity chance"),
                    p("rare chance"),
                    p("probability"),
                    p("random chance"),
                    p("reward chance"),
                    p("summon chance"),
                    p("draw chance"),
                    p("roll chance"),
                    p("pity"),
                ),
            GameplayModificationCategory.DIFFICULTY to
                listOf(
                    p("difficulty"),
                    p("enemy health"),
                    p("enemy damage"),
                    p("enemy speed"),
                ),
            GameplayModificationCategory.WORLD to
                listOf(
                    p("time scale"),
                    p("game time"),
                    p("gravity scale"),
                    p("jump height"),
                    p("jump force"),
                ),
            GameplayModificationCategory.CAMERA to
                listOf(
                    p("field of view"),
                    p("camera fov"),
                    p("fov"),
                    p("zoom"),
                ),
        )

    private val categoryContextPhrases =
        mapOf(
            GameplayModificationCategory.SURVIVABILITY to
                listOf(
                    p("player"),
                    p("hero"),
                    p("character"),
                    p("actor"),
                    p("battle"),
                    p("combat"),
                    p("health"),
                    p("life"),
                ),
            GameplayModificationCategory.DAMAGE to
                listOf(
                    p("player"),
                    p("hero"),
                    p("character"),
                    p("actor"),
                    p("battle"),
                    p("combat"),
                    p("skill"),
                    p("weapon"),
                    p("damage"),
                ),
            GameplayModificationCategory.MOVEMENT to
                listOf(
                    p("player"),
                    p("hero"),
                    p("character"),
                    p("actor"),
                    p("locomotion"),
                    p("movement"),
                    p("motor"),
                    p("controller"),
                ),
            GameplayModificationCategory.COLLISION to
                listOf(
                    p("player"),
                    p("hero"),
                    p("character"),
                    p("actor"),
                    p("movement"),
                    p("motor"),
                    p("controller"),
                ),
            GameplayModificationCategory.STAMINA to
                listOf(
                    p("player"),
                    p("hero"),
                    p("character"),
                    p("actor"),
                    p("battle"),
                    p("combat"),
                    p("skill"),
                ),
            GameplayModificationCategory.COOLDOWN to
                listOf(
                    p("player"),
                    p("hero"),
                    p("character"),
                    p("battle"),
                    p("combat"),
                    p("skill"),
                    p("ability"),
                    p("weapon"),
                ),
            GameplayModificationCategory.CONTROL to
                listOf(
                    p("player"),
                    p("hero"),
                    p("character"),
                    p("actor"),
                    p("battle"),
                    p("combat"),
                ),
            GameplayModificationCategory.ATTACK_SPEED to
                listOf(
                    p("player"),
                    p("hero"),
                    p("character"),
                    p("battle"),
                    p("combat"),
                    p("attack"),
                    p("weapon"),
                ),
            GameplayModificationCategory.REGENERATION to
                listOf(
                    p("player"),
                    p("hero"),
                    p("character"),
                    p("actor"),
                    p("battle"),
                    p("combat"),
                    p("health"),
                ),
            GameplayModificationCategory.PROGRESSION to
                listOf(
                    p("player"),
                    p("hero"),
                    p("role"),
                    p("profile"),
                    p("progress"),
                    p("progression"),
                    p("skill"),
                    p("talent"),
                ),
            GameplayModificationCategory.INVENTORY to
                listOf(
                    p("inventory"),
                    p("bag"),
                    p("backpack"),
                    p("item"),
                    p("ammo"),
                    p("equipment"),
                    p("equip"),
                    p("weapon"),
                    p("player"),
                    p("hero"),
                ),
            GameplayModificationCategory.DROPS to
                listOf(
                    p("drop"),
                    p("loot"),
                    p("reward"),
                    p("enemy"),
                    p("monster"),
                    p("battle"),
                ),
            GameplayModificationCategory.ECONOMY to
                listOf(
                    p("player"),
                    p("hero"),
                    p("profile"),
                    p("inventory"),
                    p("wallet"),
                    p("currency"),
                    p("economy"),
                    p("resource"),
                ),
            GameplayModificationCategory.RNG_REWARDS to
                listOf(
                    p("reward"),
                    p("drop"),
                    p("loot"),
                    p("random"),
                    p("rng"),
                    p("rarity"),
                    p("summon"),
                    p("draw"),
                    p("roll"),
                    p("hero"),
                    p("character"),
                ),
            GameplayModificationCategory.DIFFICULTY to
                listOf(
                    p("difficulty"),
                    p("enemy"),
                    p("monster"),
                    p("battle"),
                    p("combat"),
                ),
            GameplayModificationCategory.WORLD to
                listOf(
                    p("game time"),
                    p("world"),
                    p("battle"),
                    p("simulation"),
                    p("player"),
                    p("character"),
                ),
        )

    private val categoryRejectedTypePhrases =
        mapOf(
            GameplayModificationCategory.MOVEMENT to
                listOf(
                    p("virtual texture"),
                    p("line renderer"),
                    p("camera"),
                    p("pathfinding"),
                    p("timeline"),
                    p("dialogue"),
                    p("profiler"),
                    p("joystick"),
                    p("input"),
                    p("drag"),
                ),
            GameplayModificationCategory.INVENTORY to
                listOf(
                    p("quad tree"),
                    p("quadtree"),
                    p("scroll view"),
                    p("collection"),
                    p("list view"),
                    p("tree node"),
                    p("ui elements"),
                ),
            GameplayModificationCategory.PROGRESSION to
                listOf(
                    p("quality"),
                    p("render"),
                    p("renderer"),
                    p("lod"),
                    p("mipmap"),
                    p("texture"),
                    p("light"),
                    p("device"),
                    p("performance"),
                    p("battery"),
                    p("shader"),
                    p("clipping"),
                    p("system info"),
                    p("memory"),
                ),
            GameplayModificationCategory.COOLDOWN to
                listOf(
                    p("flow canvas"),
                    p("behaviour tree"),
                    p("behavior tree"),
                    p("fmod"),
                ),
            GameplayModificationCategory.WORLD to
                listOf(
                    p("tween"),
                    p("spine"),
                    p("timeline"),
                    p("text animator"),
                    p("animation"),
                    p("particle"),
                ),
            GameplayModificationCategory.CAMERA to
                listOf(
                    p("image cropper"),
                    p("ui camera"),
                    p("graph"),
                ),
        )

    private val strongStandaloneMethodPhrases =
        mapOf(
            GameplayModificationCategory.SURVIVABILITY to
                listOf(
                    p("is invincible"),
                    p("is invulnerable"),
                    p("is immortal"),
                    p("god mode"),
                    p("max health"),
                    p("player hp"),
                ),
            GameplayModificationCategory.DAMAGE to
                listOf(
                    p("take damage"),
                    p("receive damage"),
                    p("apply damage"),
                    p("attack damage"),
                    p("damage multiplier"),
                ),
            GameplayModificationCategory.COLLISION to
                listOf(
                    p("no clip"),
                    p("ignore collision"),
                    p("can pass through"),
                ),
            GameplayModificationCategory.STAMINA to
                listOf(
                    p("stamina"),
                    p("consume stamina"),
                    p("spend stamina"),
                ),
            GameplayModificationCategory.ATTACK_SPEED to
                listOf(
                    p("attack speed"),
                    p("fire rate"),
                ),
            GameplayModificationCategory.REGENERATION to
                listOf(
                    p("health regen"),
                    p("health regeneration"),
                ),
            GameplayModificationCategory.PROGRESSION to
                listOf(
                    p("experience points"),
                    p("xp gain"),
                    p("player xp"),
                    p("player level"),
                    p("hero level"),
                    p("skill point"),
                    p("talent point"),
                    p("level up"),
                ),
            GameplayModificationCategory.DROPS to
                listOf(
                    p("drop rate"),
                    p("drop chance"),
                    p("loot chance"),
                    p("reward multiplier"),
                ),
            GameplayModificationCategory.ECONOMY to
                listOf(
                    p("player currency"),
                    p("player gold"),
                    p("coin count"),
                    p("gem count"),
                ),
            GameplayModificationCategory.RNG_REWARDS to
                listOf(
                    p("rarity chance"),
                    p("rare chance"),
                    p("reward chance"),
                    p("summon chance"),
                    p("draw chance"),
                ),
            GameplayModificationCategory.DIFFICULTY to
                listOf(
                    p("enemy health"),
                    p("enemy damage"),
                    p("enemy speed"),
                ),
        )

    private val fieldCategoryPhrases =
        listOf(
            GameplayModificationCategory.COLLISION to
                listOf(
                    p("no clip"),
                    p("ignore collision"),
                    p("collision"),
                    p("block move type"),
                ),
            GameplayModificationCategory.STAMINA to
                listOf(
                    p("max stamina"),
                    p("stamina"),
                    p("max energy"),
                    p("energy"),
                ),
            GameplayModificationCategory.COOLDOWN to
                listOf(
                    p("cooldown"),
                    p("cooldown time"),
                    p("recharge time"),
                ),
            GameplayModificationCategory.ATTACK_SPEED to
                listOf(
                    p("attack speed"),
                    p("attack rate"),
                    p("fire rate"),
                ),
            GameplayModificationCategory.REGENERATION to
                listOf(
                    p("health regen"),
                    p("health regeneration"),
                    p("regen rate"),
                    p("healing rate"),
                ),
            GameplayModificationCategory.MOVEMENT to
                listOf(
                    p("move speed"),
                    p("movement speed"),
                    p("max speed"),
                    p("speed multiplier"),
                    p("speed mutiplien"),
                    p("speed mutiplier"),
                    p("run speed"),
                    p("walk speed"),
                    p("sprint speed"),
                    p("jump height"),
                    p("jump force"),
                    p("gravity scale"),
                ),
            GameplayModificationCategory.SURVIVABILITY to
                listOf(
                    p("max health"),
                    p("max hp"),
                    p("health"),
                    p("hit points"),
                    p("player hp"),
                ),
            GameplayModificationCategory.DAMAGE to
                listOf(
                    p("attack damage"),
                    p("base damage"),
                    p("damage multiplier"),
                    p("critical damage"),
                    p("crit damage"),
                    p("damage"),
                    p("attack power"),
                ),
            GameplayModificationCategory.PROGRESSION to
                listOf(
                    p("experience"),
                    p("experience points"),
                    p("xp"),
                    p("player xp"),
                    p("player level"),
                    p("level"),
                    p("rank"),
                    p("quality"),
                    p("skill point"),
                    p("skill points"),
                    p("talent point"),
                    p("talent points"),
                ),
            GameplayModificationCategory.INVENTORY to
                listOf(
                    p("max ammo"),
                    p("ammo"),
                    p("item count"),
                    p("inventory capacity"),
                    p("carry weight"),
                    p("backpack capacity"),
                ),
            GameplayModificationCategory.DROPS to
                listOf(
                    p("drop rate"),
                    p("drop chance"),
                    p("loot chance"),
                    p("loot multiplier"),
                    p("reward multiplier"),
                ),
            GameplayModificationCategory.ECONOMY to
                listOf(
                    p("currency"),
                    p("soft currency"),
                    p("hard currency"),
                    p("gold"),
                    p("coins"),
                    p("coin count"),
                    p("gems"),
                    p("gem count"),
                    p("credits"),
                    p("money"),
                    p("wallet"),
                    p("resource count"),
                ),
            GameplayModificationCategory.RNG_REWARDS to
                listOf(
                    p("rarity"),
                    p("rarity chance"),
                    p("rare chance"),
                    p("probability"),
                    p("random chance"),
                    p("reward chance"),
                    p("summon chance"),
                    p("draw chance"),
                    p("roll chance"),
                    p("pity"),
                ),
            GameplayModificationCategory.DIFFICULTY to
                listOf(
                    p("difficulty"),
                    p("enemy health"),
                    p("enemy damage"),
                    p("enemy speed"),
                ),
            GameplayModificationCategory.WORLD to
                listOf(
                    p("time scale"),
                    p("game time"),
                    p("gravity scale"),
                    p("jump height"),
                    p("jump force"),
                ),
            GameplayModificationCategory.CAMERA to
                listOf(
                    p("field of view"),
                    p("camera fov"),
                    p("fov"),
                    p("zoom rate"),
                    p("zoom"),
                ),
        )

    private val numericCategories =
        setOf(
            GameplayModificationCategory.SURVIVABILITY,
            GameplayModificationCategory.DAMAGE,
            GameplayModificationCategory.MOVEMENT,
            GameplayModificationCategory.STAMINA,
            GameplayModificationCategory.COOLDOWN,
            GameplayModificationCategory.ATTACK_SPEED,
            GameplayModificationCategory.REGENERATION,
            GameplayModificationCategory.PROGRESSION,
            GameplayModificationCategory.INVENTORY,
            GameplayModificationCategory.DROPS,
            GameplayModificationCategory.ECONOMY,
            GameplayModificationCategory.RNG_REWARDS,
            GameplayModificationCategory.DIFFICULTY,
            GameplayModificationCategory.WORLD,
            GameplayModificationCategory.CAMERA,
        )

    private val playerContextPhrases =
        listOf(
            p("player"),
            p("hero"),
            p("character"),
            p("avatar"),
            p("player controller"),
            p("character controller"),
            p("player motor"),
            p("character motor"),
        )
}
