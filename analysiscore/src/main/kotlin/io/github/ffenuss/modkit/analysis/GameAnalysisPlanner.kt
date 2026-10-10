package io.github.ffenuss.modkit.analysis

enum class GameGenre(val title: String) {
    UNKNOWN("Не определён"), RPG("RPG"), SHOOTER("Шутер"), RACING("Гонки"),
    STRATEGY("Стратегия"), PUZZLE("Головоломка"), SIMULATION("Симулятор"), APPLICATION("Приложение")
}
data class GenreHint(val genre: GameGenre, val evidence: List<String>) : java.io.Serializable
data class GameAnalysisPlan(val engines: List<RuntimeProfile>, val genre: GenreHint,
    val searchPriorities: List<String>, val limitations: List<String>) : java.io.Serializable

/** Genre directs discovery; it never supplies offsets, executable recipes or capability proofs. */
object GameAnalysisPlanner {
    fun plan(index: ArtifactIndex, declaredSymbols: Iterable<String>, selectedGenre: GameGenre? = null,
        packageName: String? = null): GameAnalysisPlan {
        val rules = linkedMapOf(
            GameGenre.RPG to setOf("quest", "experience", "inventory", "skilltree"),
            GameGenre.SHOOTER to setOf("ammo", "reload", "recoil", "crosshair"),
            GameGenre.RACING to setOf("lap", "gear", "steering", "drift"),
            GameGenre.STRATEGY to setOf("construction", "research", "population", "turn"),
            GameGenre.PUZZLE to setOf("puzzle", "moves", "hint", "match3"),
            GameGenre.SIMULATION to setOf("hunger", "thirst", "crafting", "farming"),
        )
        val termHits = rules.mapValues { mutableSetOf<String>() }
        val evidence = rules.mapValues { mutableSetOf<String>() }
        for (symbol in declaredSymbols) {
            val words = symbol.replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1 $2")
                .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
                .lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
            val tokens = (words + words.zipWithNext { first, second -> first + second }).toSet()
            for ((genre, terms) in rules) {
                val hits = tokens.intersect(terms)
                if (hits.isNotEmpty()) {
                    termHits.getValue(genre).addAll(hits)
                    if (evidence.getValue(genre).size < 12) evidence.getValue(genre).add(symbol)
                }
            }
        }
        val matches = rules.mapValues { (genre, _) -> termHits.getValue(genre).size to evidence.getValue(genre).toList() }
        val best = matches.maxByOrNull { it.value.first }
        val unambiguous = best != null && best.value.first >= 2 && best.value.second.size >= 2 &&
            matches.count { it.value.first == best.value.first } == 1
        val hint = when {
            selectedGenre != null -> GenreHint(selectedGenre, listOf("Жанр выбран пользователем"))
            unambiguous -> GenreHint(best!!.key, best.value.second)
            else -> publishedGenreHint(packageName) ?: GenreHint(GameGenre.UNKNOWN, emptyList())
        }
        return GameAnalysisPlan(index.runtimeProfiles, hint, priorities(hint.genre), buildList {
            if (index.truncated) add("Индекс файлов неполный")
            addAll(index.warnings)
            add("Жанр — гипотеза для поиска. Игровой эффект требует отдельного доказательства.")
        })
    }

    /** Publisher context only, never an engine, version, address or patch proof.
     * User selection and unambiguous local symbol evidence take precedence.
     */
    private fun publishedGenreHint(packageName: String?): GenreHint? {
        val genre = when (packageName) {
            "com.x.aniimos" -> GameGenre.RPG
            "com.cat.hole.puzzle.aos" -> GameGenre.PUZZLE
            else -> return null
        }
        return GenreHint(genre, listOf("Жанр из описания издателя; ориентир поиска, не доказательство совместимости",
            "https://play.google.com/store/apps/details?id=$packageName"))
    }

    fun priorities(genre: GameGenre): List<String> = when (genre) {
            GameGenre.RPG -> listOf("Здоровье", "Опыт / навыки", "Инвентарь", "Выносливость", "Кулдауны")
            GameGenre.SHOOTER -> listOf("Боезапас", "Перезарядка", "Отдача", "Здоровье", "Камера")
            GameGenre.RACING -> listOf("Скорость", "Управление", "Круги / таймеры", "Камера")
            GameGenre.STRATEGY -> listOf("Локальные ресурсы", "Строительство", "Исследования", "Время")
            GameGenre.PUZZLE -> listOf("Локальный счёт", "Ходы", "Подсказки", "Таймеры")
            GameGenre.SIMULATION -> listOf("Выносливость", "Локальные ресурсы", "Скорость", "Время")
            GameGenre.APPLICATION -> listOf("Локальные настройки", "Интерфейс", "Локальные таймеры")
            GameGenre.UNKNOWN -> listOf("Локальное состояние", "Числовые параметры", "Камера / время")
    }
}
