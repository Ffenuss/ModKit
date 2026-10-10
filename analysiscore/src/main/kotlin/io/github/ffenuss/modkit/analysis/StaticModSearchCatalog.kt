package io.github.ffenuss.modkit.analysis

/** Operations a future executable recipe may require, not capabilities granted by a name match. */
enum class ModExecutionMechanism {
    RESULT_OVERRIDE, RESULT_TRANSFORM, BEFORE_AFTER_HOOK, FIELD_MAINTENANCE,
    OWNED_EFFECT, WORLD_RULE, GROUPED_PHYSICS, GAME_ACTION, INPUT_JUDGEMENT,
    CONTENT_REPLACEMENT, SAVE_EDIT, SCRIPT_ADAPTER,
}

data class StaticModSearchRule(
    val id: String,
    val title: String,
    val genres: Set<GameGenre>,
    val phrases: List<String>,
    val mechanisms: Set<ModExecutionMechanism>,
)

/** Engine-neutral search knowledge. No addresses, values or executable permissions are inferred.
 * Every genre runs the complete catalog; its genre changes order only.
 * These are lexical discovery signals. Obfuscated or absent symbols need a separate structural backend.
 */
object StaticModSearchCatalog {
    const val VERSION = 1
    private fun rule(id: String, title: String, genres: Set<GameGenre>, phrases: String,
        vararg mechanisms: ModExecutionMechanism) = StaticModSearchRule(id, title, genres,
        phrases.split('|'), mechanisms.toSet())
    private val combat = setOf(GameGenre.RPG, GameGenre.SHOOTER, GameGenre.ACTION,
        GameGenre.ROGUELIKE, GameGenre.SURVIVAL, GameGenre.FIGHTING, GameGenre.MMO)
    private val economy = setOf(GameGenre.RPG, GameGenre.STRATEGY, GameGenre.SIMULATION,
        GameGenre.IDLE, GameGenre.TOWER_DEFENSE, GameGenre.SANDBOX)
    val rules: List<StaticModSearchRule> = listOf(
        rule("health", "Здоровье и защита", combat,
            "health|hit points|max hp|current hp|take damage|invincible|invulnerable|shield|armor",
            ModExecutionMechanism.RESULT_OVERRIDE, ModExecutionMechanism.BEFORE_AFTER_HOOK,
            ModExecutionMechanism.FIELD_MAINTENANCE),
        rule("damage", "Урон и критические попадания", combat,
            "damage|attack power|critical chance|critical damage|damage multiplier",
            ModExecutionMechanism.RESULT_TRANSFORM, ModExecutionMechanism.OWNED_EFFECT),
        rule("stamina", "Энергия, мана и выносливость", combat + GameGenre.SPORTS + GameGenre.CARD,
            "stamina|energy|mana|fatigue|consume energy|spend stamina|use energy",
            ModExecutionMechanism.BEFORE_AFTER_HOOK, ModExecutionMechanism.FIELD_MAINTENANCE),
        rule("ammunition", "Боезапас и перезарядка", setOf(GameGenre.SHOOTER, GameGenre.ACTION),
            "ammo|ammunition|magazine|reload time|reload speed|consume ammo",
            ModExecutionMechanism.BEFORE_AFTER_HOOK, ModExecutionMechanism.RESULT_OVERRIDE),
        rule("weapon-control", "Отдача и разброс", setOf(GameGenre.SHOOTER),
            "recoil|weapon spread|bullet spread|aim sway|aim sensitivity",
            ModExecutionMechanism.RESULT_TRANSFORM, ModExecutionMechanism.OWNED_EFFECT),
        rule("cooldown", "Перезарядка способностей", combat + GameGenre.TOWER_DEFENSE,
            "cooldown|recharge time|ability cost|skill cost|attack rate|fire rate",
            ModExecutionMechanism.RESULT_TRANSFORM, ModExecutionMechanism.OWNED_EFFECT),
        rule("movement", "Движение и прыжок", combat + GameGenre.PLATFORMER + GameGenre.SPORTS,
            "move speed|movement speed|run speed|jump force|jump height|gravity scale|dash speed",
            ModExecutionMechanism.RESULT_TRANSFORM, ModExecutionMechanism.OWNED_EFFECT),
        rule("collision", "Коллизии и перемещение", setOf(GameGenre.ACTION, GameGenre.PLATFORMER, GameGenre.SANDBOX),
            "collision|collidable layers|is trigger|use gravity|teleport|out of bounds",
            ModExecutionMechanism.GROUPED_PHYSICS, ModExecutionMechanism.GAME_ACTION),
        rule("camera", "Камера и поле зрения", combat + GameGenre.RACING + GameGenre.ADVENTURE,
            "field of view|camera fov|camera distance|zoom|camera shake",
            ModExecutionMechanism.RESULT_TRANSFORM, ModExecutionMechanism.FIELD_MAINTENANCE),
        rule("currency", "Локальная валюта и стоимость", economy,
            "coins|coin count|gold|gems|credits|money|wallet|currency|purchase cost|upgrade cost",
            ModExecutionMechanism.BEFORE_AFTER_HOOK, ModExecutionMechanism.GAME_ACTION),
        rule("inventory", "Инвентарь, количество и износ", economy + GameGenre.SURVIVAL + GameGenre.ADVENTURE,
            "inventory capacity|item count|stack size|carry weight|durability|consume item",
            ModExecutionMechanism.RESULT_OVERRIDE, ModExecutionMechanism.BEFORE_AFTER_HOOK),
        rule("progression", "Опыт и развитие", combat + GameGenre.IDLE,
            "experience|xp|skill points|talent points|level up|experience multiplier",
            ModExecutionMechanism.RESULT_TRANSFORM, ModExecutionMechanism.GAME_ACTION),
        rule("rewards", "Дроп, награды и вероятности", combat + economy,
            "drop rate|drop chance|loot chance|reward multiplier|rarity|probability|pity",
            ModExecutionMechanism.RESULT_TRANSFORM, ModExecutionMechanism.GAME_ACTION),
        rule("puzzle-budget", "Ходы, подсказки и время раунда", setOf(GameGenre.PUZZLE, GameGenre.BOARD),
            "remaining moves|moves left|move count|hint count|remaining hints|time left|remaining time|round time",
            ModExecutionMechanism.RESULT_OVERRIDE, ModExecutionMechanism.BEFORE_AFTER_HOOK),
        rule("puzzle-actions", "Перемешивание и инструменты головоломки", setOf(GameGenre.PUZZLE),
            "shuffle count|undo count|booster count|hint cost|shuffle cost|can undo",
            ModExecutionMechanism.GAME_ACTION, ModExecutionMechanism.BEFORE_AFTER_HOOK),
        rule("world-clock", "Время и скорость симуляции", setOf(GameGenre.SIMULATION, GameGenre.IDLE, GameGenre.STRATEGY),
            "time scale|game time interval|simulation speed|day length|tick rate",
            ModExecutionMechanism.WORLD_RULE, ModExecutionMechanism.FIELD_MAINTENANCE),
        rule("survival-needs", "Голод, жажда и температура", setOf(GameGenre.SURVIVAL, GameGenre.SIMULATION, GameGenre.SANDBOX),
            "hunger|satiety|thirst|oxygen|body temperature|starvation|breath",
            ModExecutionMechanism.BEFORE_AFTER_HOOK, ModExecutionMechanism.FIELD_MAINTENANCE),
        rule("crafting", "Производство и строительство", economy + GameGenre.SURVIVAL,
            "craft time|crafting cost|build time|build cost|build speed|research time|production rate",
            ModExecutionMechanism.RESULT_TRANSFORM, ModExecutionMechanism.BEFORE_AFTER_HOOK),
        rule("strategy-rules", "Правила мира и лимиты", setOf(GameGenre.STRATEGY, GameGenre.TOWER_DEFENSE, GameGenre.SANDBOX),
            "infinite resources|unit cap|wave timer|wave spacing|population limit|build range",
            ModExecutionMechanism.WORLD_RULE),
        rule("vehicle", "Параметры транспорта", setOf(GameGenre.RACING, GameGenre.SIMULATION),
            "engine torque|max speed|traction|steering|brake force|nitro|boost charge|fuel consumption",
            ModExecutionMechanism.RESULT_TRANSFORM, ModExecutionMechanism.GROUPED_PHYSICS),
        rule("race-clock", "Гонки и таймер круга", setOf(GameGenre.RACING),
            "lap time|race time|checkpoint|lap count|vehicle damage",
            ModExecutionMechanism.BEFORE_AFTER_HOOK, ModExecutionMechanism.GAME_ACTION),
        rule("cards", "Карты, добор и цена действий", setOf(GameGenre.CARD, GameGenre.ROGUELIKE),
            "card cost|draw count|hand size|deck size|draw card|use energy|action points",
            ModExecutionMechanism.BEFORE_AFTER_HOOK, ModExecutionMechanism.GAME_ACTION),
        rule("turns", "Ходы и время принятия решения", setOf(GameGenre.BOARD, GameGenre.CARD, GameGenre.STRATEGY),
            "turn time|turn limit|remaining turns|action points|dice roll|can move piece",
            ModExecutionMechanism.WORLD_RULE, ModExecutionMechanism.GAME_ACTION),
        rule("rhythm", "Окна попадания и комбо", setOf(GameGenre.RHYTHM),
            "judgement window|judgment window|hit window|perfect window|note speed|combo count|timing offset",
            ModExecutionMechanism.INPUT_JUDGEMENT, ModExecutionMechanism.RESULT_TRANSFORM),
        rule("sports", "Физика спорта и выносливость", setOf(GameGenre.SPORTS),
            "shot power|kick power|ball speed|stamina drain|match time|jump power",
            ModExecutionMechanism.RESULT_TRANSFORM, ModExecutionMechanism.BEFORE_AFTER_HOOK),
        rule("fighting", "Комбо, блок и шкалы", setOf(GameGenre.FIGHTING),
            "combo window|block stun|hit stun|super meter|guard meter|frame advantage",
            ModExecutionMechanism.BEFORE_AFTER_HOOK, ModExecutionMechanism.OWNED_EFFECT),
        rule("idle", "Доход и локальный офлайн-прогресс", setOf(GameGenre.IDLE, GameGenre.SIMULATION),
            "income rate|production multiplier|click power|offline reward|offline duration|prestige gain",
            ModExecutionMechanism.RESULT_TRANSFORM, ModExecutionMechanism.GAME_ACTION),
        rule("adventure", "Предметы и условия локальных событий", setOf(GameGenre.ADVENTURE, GameGenre.VISUAL_NOVEL),
            "quest progress|dialogue choice|affection|relationship points|text speed|skip read text",
            ModExecutionMechanism.GAME_ACTION, ModExecutionMechanism.FIELD_MAINTENANCE),
        rule("content", "Текстуры, звук и интерфейс", setOf(GameGenre.SANDBOX, GameGenre.ADVENTURE, GameGenre.APPLICATION),
            "texture|sprite|localization|subtitle|audio volume|ui scale|color palette",
            ModExecutionMechanism.CONTENT_REPLACEMENT, ModExecutionMechanism.SCRIPT_ADAPTER),
        rule("local-settings", "Локальные настройки и сохранения", setOf(GameGenre.APPLICATION, GameGenre.VISUAL_NOVEL),
            "save slot|local settings|difficulty|accessibility|input sensitivity|display scale",
            ModExecutionMechanism.SAVE_EDIT, ModExecutionMechanism.SCRIPT_ADAPTER),
    )

    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val acronymBoundary = Regex("([A-Z]+)([A-Z][a-z])")
    private val wordBoundary = Regex("([a-z0-9])([A-Z])")
    private fun tokens(name: String): List<String> = name
        .replace(acronymBoundary, "$1 $2").replace(wordBoundary, "$1 $2")
        .lowercase(java.util.Locale.ROOT).split(separators).filter { it.isNotEmpty() }

    private data class IndexedPhrase(val words: List<String>, val rule: StaticModSearchRule)
    private val byFirstWord = rules.flatMap { rule -> rule.phrases.map { IndexedPhrase(tokens(it), rule) } }
        .groupBy { it.words.first() }

    fun signals(memberName: String): List<StaticModSearchRule> {
        val words = tokens(memberName)
        val found = linkedMapOf<String, StaticModSearchRule>()
        words.forEachIndexed { start, word ->
            byFirstWord[word].orEmpty().forEach { phrase ->
                if (start + phrase.words.size <= words.size && phrase.words.indices.all {
                        words[start + it] == phrase.words[it]
                    }) found[phrase.rule.id] = phrase.rule
            }
        }
        return found.values.toList()
    }

    fun orderedFor(genre: GameGenre): List<StaticModSearchRule> =
        rules.sortedBy { if (genre in it.genres) 0 else 1 }
}

/** Bounded aggregate: each symbol increments each matching set once, without retaining names. */
class StaticModSearchSurvey(private val cancellation: CancellationSignal) {
    var symbolsExamined: Long = 0
        private set
    private val counts = linkedMapOf<String, Long>()
    fun observe(name: String) {
        if (symbolsExamined % 128L == 0L && cancellation.isCancelled()) throw AnalysisCancelledException()
        symbolsExamined++
        StaticModSearchCatalog.signals(name).forEach { counts[it.id] = (counts[it.id] ?: 0L) + 1L }
    }
    fun hitCount(ruleId: String): Long = counts[ruleId] ?: 0L
}
