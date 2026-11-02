package io.github.ffenuss.modkit.patch

/**
 * Engine-agnostic semantic model of gameplay mechanics.
 *
 * A mechanic is defined by the ROLE its state plays in the game loop
 * (depleting resource, availability gate, threshold check), not by the
 * method or field names and not by a specific engine. Names remain a
 * secondary signal that can confirm or refine a structural hypothesis.
 */
enum class GameMechanic(val label: String) {
    HEALTH("Здоровье"),
    STAMINA("Энергия / выносливость"),
    AMMO("Боезапас"),
    SPEED("Скорость"),
    CURRENCY("Валюта / ресурсы"),
    COOLDOWN("Ограничения / таймеры"),
    PROGRESSION("Прогрессия"),
    INVENTORY("Инвентарь"),
    UNKNOWN("Неопределённая механика"),
}

/**
 * Confidence ladder. Static analysis alone can reach at most CONFIRMED:
 *  - DISCOVERED: only a name signal matched (the legacy path);
 *  - PROBABLE: at least one structural/behavioral pattern, no semantic anchor;
 *  - CONFIRMED: structural pattern(s) corroborated by a semantic anchor on the
 *    same field, or two independent structural patterns;
 *  - MODIFIABLE: confirmed and a proven, side-effect-free body exists for an
 *    executable recipe on this exact target;
 *  - FUNCTIONALLY_VERIFIED: the effect was observed in a running app.
 *
 * Static code must never claim MODIFIABLE or FUNCTIONALLY_VERIFIED without
 * the corresponding proof.
 */
enum class MechanicConfidence {
    DISCOVERED,
    PROBABLE,
    CONFIRMED,
    MODIFIABLE,
    FUNCTIONALLY_VERIFIED,
}

sealed class MechanicEvidence {
    abstract val summary: String

    /** Legacy name-based signal. Kept as an additional signal, never the only one for CONFIRMED. */
    data class NameSignal(val method: String, val mechanic: GameMechanic) : MechanicEvidence() {
        override val summary: String get() = "Имя метода " + method + " соответствует " + mechanic.label
    }

    /** Structural pattern proven inside a method body, independent of names. */
    data class StructuralPattern(
        val pattern: String,
        val method: String,
        val field: String,
    ) : MechanicEvidence() {
        override val summary: String get() = pattern + " в " + method + " для поля " + field
    }

    /** One-level interprocedural dataflow: a proven getter returns the field. */
    data class GetterLink(val getter: String, val field: String) : MechanicEvidence() {
        override val summary: String get() = getter + " возвращает поле " + field
    }
}

/**
 * A hypothesis that a field participates in a gameplay mechanic.
 * The hypothesis is evidence, not consent: only CONFIRMED hypotheses with a
 * proven patchable body become selectable recipes.
 */
data class MechanicHypothesis(
    val mechanic: GameMechanic,
    val confidence: MechanicConfidence,
    val field: String,
    val evidence: List<MechanicEvidence>,
    val reasons: List<String>,
) {
    val isStructuralOnly: Boolean
        get() = evidence.none { it is MechanicEvidence.NameSignal }

    /**
     * Full ladder: static confidence + proven patchable body.
     * Only CONFIRMED static hypotheses become MODIFIABLE; PROBABLE stays
     * non-selectable until a semantic anchor or a second pattern appears.
     */
    fun effectiveState(bodyPatchable: Boolean): MechanicConfidence = when {
        confidence == MechanicConfidence.CONFIRMED && bodyPatchable -> MechanicConfidence.MODIFIABLE
        else -> confidence
    }

    val summary: String
        get() = mechanic.label + ": поле " + field + " — " + confidence.name +
            (if (reasons.isEmpty()) "" else " (" + reasons.joinToString("; ") + ")")
}
