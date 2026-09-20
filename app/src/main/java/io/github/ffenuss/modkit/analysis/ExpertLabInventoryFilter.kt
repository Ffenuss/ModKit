package io.github.ffenuss.modkit.analysis

object ExpertLabInventoryFilter {
    fun filterEngines(
        engines: List<PlannedEngine>,
        query: String,
    ): List<PlannedEngine> {
        val tokens = tokens(query)
        if (tokens.isEmpty()) return engines
        return engines.filter { engine ->
            matches(
                tokens = tokens,
                fields = listOf(
                    engine.id,
                    engine.scheduleClass.name,
                    engine.reason,
                    if (engine.availableNow) "available connected" else
                        "unavailable missing",
                ),
            )
        }
    }

    fun filterTargets(
        targets: List<EvidenceTarget>,
        query: String,
    ): List<EvidenceTarget> {
        val tokens = tokens(query)
        if (tokens.isEmpty()) return targets
        return targets.filter { target ->
            matches(
                tokens = tokens,
                fields = buildList {
                    add(target.id)
                    add(target.runtimeId)
                    add(target.kind.name)
                    add(target.displayName)
                    add(target.proofLevel.name)
                    add(target.userStatus.name)
                    target.artifact?.let(::add)
                    target.abi?.let(::add)
                    target.declaringType?.let(::add)
                    target.memberName?.let(::add)
                    target.blockers.forEach { blocker ->
                        add(blocker.code)
                        add(blocker.message)
                    }
                    target.facts.forEach { fact ->
                        add(fact.engineId)
                        add(fact.kind)
                        add(fact.summary)
                    }
                },
            )
        }
    }

    private fun tokens(
        query: String,
    ): List<String> =
        query.trim()
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .distinct()

    private fun matches(
        tokens: List<String>,
        fields: List<String>,
    ): Boolean {
        val searchable = fields.joinToString("\n").lowercase()
        return tokens.all { it in searchable }
    }
}
