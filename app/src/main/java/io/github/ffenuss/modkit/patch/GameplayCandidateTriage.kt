package io.github.ffenuss.modkit.patch

/**
 * Helps the user navigate large, semantically noisy IL2CPP inventories.
 *
 * A name/owner hint is never promoted to an executable patch or evidence
 * that a modification works in game. Triage only changes presentation order
 * and explains the exact evidence still missing.
 */
enum class GameplayCandidateRole(
    val label: String,
    val description: String,
    val priority: Int,
) {
    STATE_MUTATOR(
        "Изменяет состояние",
        "Имя метода и класс указывают на изменение игрового состояния; эффект ещё не доказан.",
        0,
    ),
    STATE_READER(
        "Читает игровое состояние",
        "Вероятный источник значения, но он может использоваться и интерфейсом.",
        1,
    ),
    GAMEPLAY_ACTION(
        "Игровое действие",
        "Метод похож на действие или ограничение, которое следует проверить в игре.",
        2,
    ),
    CONFIGURATION(
        "Данные / настройки",
        "Значение может быть конфигурацией, а не текущим состоянием персонажа.",
        3,
    ),
    PRESENTATION(
        "Отображение / интерфейс",
        "Этот метод может только показывать игровое значение и не менять механику.",
        4,
    ),
    UNCLASSIFIED(
        "Роль не доказана",
        "Имени и класса недостаточно для определения назначения метода.",
        5,
    ),
    METADATA_ONLY(
        "Только поле metadata",
        "Нет доказанного адреса экземпляра и безопасной записи значения.",
        6,
    ),
    SENSITIVE_DIAGNOSTIC(
        "Только диагностика",
        "Платёжные, серверные и защитные проверки не предлагаются для автоматического изменения.",
        7,
    ),
}

enum class GameplayPatchReadiness(
    val label: String,
) {
    STATIC_PATCH_READY("Шаблон проверен — тест в игре ещё нужен"),
    NEEDS_RESEARCH("Кандидат — нужен анализ"),
    METADATA_SIGNAL("Только metadata — адрес не доказан"),
    DIAGNOSTIC_ONLY("Только просмотр"),
}

data class GameplayCategoryOverview(
    val category: GameplayModificationCategory,
    val total: Int,
    val ready: Int,
    val needsResearch: Int,
    val diagnostics: Int,
    val stateCandidates: Int,
    val displayOnlyCandidates: Int,
)

object GameplayCandidateTriage {
    private val presentationHints = listOf(
        "hud", "ui", "display", "screen", "widget", "view",
        "tooltip", "popup", "label", "textbox", "infobox",
        "itembar", "textmb", "image", "animation", "visual",
    )
    private val stateHints = listOf(
        "player", "character", "hero", "health", "stamina",
        "energy", "inventory", "experience", "progression",
        "wallet", "currency", "movement", "combat",
    )
    private val configHints = listOf(
        "config", "settings", "upgrade", "trinket",
        "modifier", "definition", "statdata", "itemdata",
    )
    private val mutationNames = listOf(
        "set", "change", "modify", "increase", "decrease",
        "take", "apply", "spend", "consume", "remove",
        "add", "heal", "restore", "grant",
    )
    private val actionNames = listOf(
        "attack", "jump", "move", "run", "dash", "roll",
        "fire", "dodge", "collide", "spawn", "deal",
        "calculate", "activate",
    )

    fun readiness(
        candidate: GameplayModificationOpportunity,
    ): GameplayPatchReadiness =
        when {
            candidate.category == GameplayModificationCategory.SENSITIVE_SURFACE ->
                GameplayPatchReadiness.DIAGNOSTIC_ONLY
            candidate.selectable && !candidate.replacementHex.isNullOrBlank() ->
                GameplayPatchReadiness.STATIC_PATCH_READY
            candidate.confidence == GameplayModificationConfidence.SEMANTIC_MODEL_SIGNAL ->
                GameplayPatchReadiness.METADATA_SIGNAL
            else ->
                GameplayPatchReadiness.NEEDS_RESEARCH
        }

    fun role(
        candidate: GameplayModificationOpportunity,
    ): GameplayCandidateRole {
        if (candidate.category == GameplayModificationCategory.SENSITIVE_SURFACE) {
            return GameplayCandidateRole.SENSITIVE_DIAGNOSTIC
        }
        if (candidate.confidence == GameplayModificationConfidence.SEMANTIC_MODEL_SIGNAL) {
            return GameplayCandidateRole.METADATA_ONLY
        }

        val displayName = candidate.targetDisplayName
        val owner = displayName.substringBeforeLast('.', "")
            .substringAfterLast('.')
            .lowercase()
        val method = displayName.substringAfterLast('.', displayName).lowercase()
        val type = displayName.substringBeforeLast('.', "")
            .lowercase()
        if (presentationHints.any { it in owner }) {
            return GameplayCandidateRole.PRESENTATION
        }

        val stateOwner = stateHints.any { it in owner }
        val mutates = mutationNames.any {
            method.startsWith(it) || method.startsWith("set_")
        }
        val reads =
            method.startsWith("get_") || method.startsWith("is") ||
                method.startsWith("has") || method.startsWith("owns")
        if (stateOwner && mutates) {
            return GameplayCandidateRole.STATE_MUTATOR
        }
        if (stateOwner && reads) {
            return GameplayCandidateRole.STATE_READER
        }

        if (configHints.any { it in owner }) {
            return GameplayCandidateRole.CONFIGURATION
        }
        if (actionNames.any(method::startsWith)) {
            return GameplayCandidateRole.GAMEPLAY_ACTION
        }
        if (mutates && stateHints.any { it in type }) {
            return GameplayCandidateRole.STATE_MUTATOR
        }
        return GameplayCandidateRole.UNCLASSIFIED
    }

    fun overview(
        opportunities: List<GameplayModificationOpportunity>,
    ): List<GameplayCategoryOverview> =
        opportunities.groupBy { it.category }
            .map { (category, group) ->
                GameplayCategoryOverview(
                    category = category,
                    total = group.size,
                    ready = group.count {
                        readiness(it) == GameplayPatchReadiness.STATIC_PATCH_READY
                    },
                    needsResearch = group.count {
                        readiness(it) == GameplayPatchReadiness.NEEDS_RESEARCH ||
                            readiness(it) == GameplayPatchReadiness.METADATA_SIGNAL
                    },
                    diagnostics = group.count {
                        readiness(it) == GameplayPatchReadiness.DIAGNOSTIC_ONLY
                    },
                    stateCandidates = group.count {
                        role(it) == GameplayCandidateRole.STATE_MUTATOR ||
                            role(it) == GameplayCandidateRole.STATE_READER
                    },
                    displayOnlyCandidates = group.count {
                        role(it) == GameplayCandidateRole.PRESENTATION
                    },
                )
            }
            .sortedWith(
                compareBy<GameplayCategoryOverview> { it.category.priority }
                    .thenBy { it.category.title },
            )

    fun prioritized(
        opportunities: List<GameplayModificationOpportunity>,
    ): List<GameplayModificationOpportunity> =
        opportunities.sortedWith(
            compareByDescending<GameplayModificationOpportunity> {
                readiness(it) == GameplayPatchReadiness.STATIC_PATCH_READY
            }
                .thenBy { role(it).priority }
                .thenBy { it.targetDisplayName.lowercase() },
        )
}
