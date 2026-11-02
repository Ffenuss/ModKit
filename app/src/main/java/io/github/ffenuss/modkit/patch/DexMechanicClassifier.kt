package io.github.ffenuss.modkit.patch

import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.Method

/**
 * A concrete, patchable method produced from a CONFIRMED hypothesis.
 * Every candidate is a proven scalar getter or a proven zero-threshold check
 * of the confirmed field, so the existing rewrite engine can apply it safely.
 */
data class StructuralModCandidate(
    val method: Method,
    val category: DexLocalCategory,
    val action: DexLocalAction,
    val field: String,
    val confidence: MechanicConfidence,
    val reason: String,
)

data class DexMechanicClassification(
    val hypotheses: List<MechanicHypothesis>,
    val candidates: List<StructuralModCandidate>,
)

/**
 * Turns structural behavioral patterns plus name signals into mechanic
 * hypotheses with an explicit confidence ladder.
 *
 * Precision rules (fail-closed):
 *  - HEALTH is confirmed by structure alone when a field is both decremented
 *    and written back AND has a "depleted" threshold check (true when <= 0):
 *    that combination is a distinctive death/health signature.
 *  - STAMINA / AMMO / other resources are ambiguous from structure alone
 *    (a retry counter looks identical), so they need a matching name anchor
 *    on the same field to be CONFIRMED; otherwise they stay PROBABLE.
 *  - A name signal that is not corroborated by the matching structural
 *    pattern never reaches CONFIRMED (prevents name-only false positives).
 */
object DexMechanicClassifier {

    fun classify(model: DexBehavioralModel, dex: DexFile): DexMechanicClassification {
        val methodsById = HashMap<String, Method>()
        for (classDef in dex.classes) {
            if (DexLocalPatchEngine.excludedClass(classDef.type)) continue
            for (method in classDef.methods) {
                if (method.implementation != null) {
                    methodsById[DexBehavioralAnalyzer.methodIdPublic(method)] = method
                }
            }
        }
        val gettersByField = HashMap<String, MutableList<Method>>()
        for ((methodId, field) in model.provenGetters) {
            methodsById[methodId]?.let {
                gettersByField.getOrPut(field) { ArrayList() }.add(it)
            }
        }

        val fieldsWithPatterns = model.patterns.map { it.field }.toHashSet()
        val fieldsWithGetters = gettersByField.keys
        val allFields = (fieldsWithPatterns + fieldsWithGetters).toHashSet()

        val hypotheses = ArrayList<MechanicHypothesis>()
        val candidates = ArrayList<StructuralModCandidate>()

        for (field in allFields) {
            val patterns = model.patterns.filter { it.field == field }
            val patternTypes = patterns.map { it.type }.toSet()
            val thresholdActions = patterns.mapNotNull { it.thresholdAction }.toSet()
            val hasDamage = patternTypes.contains(DexBehavioralPatternType.DAMAGE_WRITE_BACK)
            val hasIncrement = patternTypes.contains(DexBehavioralPatternType.CLAMPED_INCREMENT)
            val hasZeroFalse = thresholdActions.contains(DexLocalAction.FALSE) &&
                patternTypes.contains(DexBehavioralPatternType.ZERO_THRESHOLD_CHECK)
            // A balance that can both increase and decrease is NOT health simply
            // because it also has an empty/zero check. Currency and regenerating
            // health can have identical instruction shapes: require a field/getter
            // semantic anchor before confirming local currency.
            val currencyAnchor = hasDamage && hasIncrement &&
                hasCurrencyNameAnchor(field, gettersByField[field].orEmpty())
            val structMechanic = when {
                currencyAnchor -> GameMechanic.CURRENCY
                hasDamage && hasIncrement -> GameMechanic.UNKNOWN
                else -> structuralMechanic(patternTypes, thresholdActions)
            }

            val touching = ArrayList<Method>()
            gettersByField[field]?.let { touching += it }
            model.fieldReaders[field]?.let { ids -> ids.forEach { id -> methodsById[id]?.let { touching += it } } }
            model.fieldWriters[field]?.let { ids -> ids.forEach { id -> methodsById[id]?.let { touching += it } } }
            val nameMechanics = HashSet<GameMechanic>()
            for (m in touching) {
                val category = DexLocalPatchEngine.classify(m.name, m.returnType, m.definingClass)?.first
                category?.let { nameMechanics += mechanicFromCategory(it) }
            }
            nameMechanics.remove(GameMechanic.UNKNOWN)
            if (currencyAnchor) nameMechanics += GameMechanic.CURRENCY

            val distinctPatterns = patternTypes.size
            val nameMatchesStruct = structMechanic != GameMechanic.UNKNOWN &&
                nameMechanics.contains(structMechanic)

            val confidence: MechanicConfidence? = when {
                structMechanic == GameMechanic.HEALTH && hasDamage && !hasIncrement && hasZeroFalse ->
                    MechanicConfidence.CONFIRMED
                structMechanic != GameMechanic.UNKNOWN && nameMatchesStruct ->
                    MechanicConfidence.CONFIRMED
                nameMechanics.isNotEmpty() || structMechanic != GameMechanic.UNKNOWN ||
                    distinctPatterns >= 1 ->
                    MechanicConfidence.PROBABLE
                else -> null
            }
            if (confidence == null) continue

            val mechanic = when {
                nameMatchesStruct -> structMechanic
                structMechanic != GameMechanic.UNKNOWN -> structMechanic
                nameMechanics.isNotEmpty() -> nameMechanics.first()
                else -> GameMechanic.UNKNOWN
            }

            val evidence = ArrayList<MechanicEvidence>()
            for (p in patterns) {
                evidence += MechanicEvidence.StructuralPattern(p.type.label, p.method, field)
            }
            for (getter in gettersByField[field].orEmpty()) {
                evidence += MechanicEvidence.GetterLink(
                    DexBehavioralAnalyzer.methodIdPublic(getter), field,
                )
            }
            for (nm in nameMechanics) {
                evidence += MechanicEvidence.NameSignal(mechanic.label, nm)
            }
            val reasons = ArrayList<String>()
            if (structMechanic != GameMechanic.UNKNOWN) {
                reasons += "структурный паттерн: " + structMechanic.label
            }
            if (nameMechanics.isNotEmpty()) {
                reasons += "семантический якорь: " + nameMechanics.joinToString(", ") { it.label }
            }
            if (confidence == MechanicConfidence.PROBABLE) {
                reasons += "требуется подтверждение (недостаточно доказательств)"
            }

            hypotheses += MechanicHypothesis(mechanic, confidence, field, evidence, reasons)

            if (confidence != MechanicConfidence.CONFIRMED) continue
            emitCandidates(
                mechanic = mechanic,
                field = field,
                getters = gettersByField[field].orEmpty(),
                thresholdMethods = patterns
                    .filter { it.type == DexBehavioralPatternType.ZERO_THRESHOLD_CHECK }
                    .mapNotNull { p -> p.thresholdAction?.let { a -> p.method to a } },
                methodsById = methodsById,
                confidence = confidence,
                candidates = candidates,
            )
        }

        return DexMechanicClassification(hypotheses, candidates.distinctBy { it.method })
    }

    /**
     * The exact identifier only disambiguates two proven write-back patterns.
     * A name alone is never a selectable currency recipe. Avoid generic words
     * like tokens, points, balance, premium or credits, which often describe
     * authentication, UI or network state rather than owned offline currency.
     */
    private fun hasCurrencyNameAnchor(field: String, getters: List<Method>): Boolean {
        val names = setOf(
            "coins", "coinbalance", "walletcoins",
            "gamemoney", "playermoney", "walletmoney",
            "gamecash", "walletcash", "goldcoins",
            "gamecurrency", "softcurrency", "hardcurrency",
        )
        val property = field.substringAfter("->").substringBefore(':')
            .lowercase().filter(Char::isLetterOrDigit)
        if (property in names) return true
        val getterNames = setOf(
            "getcoins", "getcoinbalance", "getwalletcoins",
            "getgamemoney", "getplayermoney", "getwalletmoney",
            "getgamecash", "getwalletcash", "getgoldcoins",
            "getgamecurrency", "getsoftcurrency", "gethardcurrency",
        )
        return getters.any { it.name.lowercase().filter(Char::isLetterOrDigit) in getterNames }
    }

    private fun emitCandidates(
        mechanic: GameMechanic,
        field: String,
        getters: List<Method>,
        thresholdMethods: List<Pair<String, DexLocalAction>>,
        methodsById: Map<String, Method>,
        confidence: MechanicConfidence,
        candidates: MutableList<StructuralModCandidate>,
    ) {
        val category = categoryForMechanic(mechanic) ?: return
        val reason = "Структурное подтверждение: " + mechanic.label +
            " (поле " + field + "). Эффект в игре ещё не проверен."
        for (getter in getters) {
            val body = DexMethodBodyInspector.inspect(getter)
            if (!body.supportsScalarReplacement) continue
            if (getter.returnType != "I" && getter.returnType != "F") continue
            val action = if (getter.returnType == "F") DexLocalAction.FLOAT_2 else DexLocalAction.INT_9999
            candidates += StructuralModCandidate(getter, category, action, field, confidence, reason)
        }
        for ((methodId, action) in thresholdMethods) {
            val method = methodsById[methodId] ?: continue
            val body = DexMethodBodyInspector.inspect(method)
            if (!body.supportsScalarReplacement) continue
            if (method.returnType != "Z") continue
            candidates += StructuralModCandidate(method, category, action, field, confidence, reason)
        }
    }

    private fun structuralMechanic(
        patternTypes: Set<DexBehavioralPatternType>,
        thresholdActions: Set<DexLocalAction>,
    ): GameMechanic {
        val hasDamage = patternTypes.contains(DexBehavioralPatternType.DAMAGE_WRITE_BACK)
        val hasZero = patternTypes.contains(DexBehavioralPatternType.ZERO_THRESHOLD_CHECK)
        val hasZeroFalse = hasZero && thresholdActions.contains(DexLocalAction.FALSE)
        val hasZeroTrue = hasZero && thresholdActions.contains(DexLocalAction.TRUE)
        val hasGuarded = patternTypes.contains(DexBehavioralPatternType.GUARDED_CONSUMPTION)
        return when {
            hasDamage && hasZeroFalse -> GameMechanic.HEALTH
            hasDamage && (hasZeroTrue || hasGuarded) -> GameMechanic.STAMINA
            else -> GameMechanic.UNKNOWN
        }
    }

    private fun mechanicFromCategory(category: DexLocalCategory): GameMechanic = when (category) {
        DexLocalCategory.HEALTH -> GameMechanic.HEALTH
        DexLocalCategory.STAMINA -> GameMechanic.STAMINA
        DexLocalCategory.AMMO -> GameMechanic.AMMO
        DexLocalCategory.MOVEMENT -> GameMechanic.SPEED
        DexLocalCategory.COOLDOWN -> GameMechanic.COOLDOWN
        DexLocalCategory.EXPERIENCE -> GameMechanic.PROGRESSION
        DexLocalCategory.INVENTORY -> GameMechanic.INVENTORY
        else -> GameMechanic.UNKNOWN
    }

    private fun categoryForMechanic(mechanic: GameMechanic): DexLocalCategory? = when (mechanic) {
        GameMechanic.HEALTH -> DexLocalCategory.HEALTH
        GameMechanic.STAMINA -> DexLocalCategory.STAMINA
        GameMechanic.AMMO -> DexLocalCategory.AMMO
        GameMechanic.CURRENCY -> DexLocalCategory.CURRENCY
        GameMechanic.SPEED -> DexLocalCategory.MOVEMENT
        else -> null
    }
}
