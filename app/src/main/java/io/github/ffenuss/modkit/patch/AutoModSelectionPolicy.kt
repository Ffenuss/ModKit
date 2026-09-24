package io.github.ffenuss.modkit.patch

/**
 * Ready means the binary method, return type and generated replacement are
 * suitable for preflight. It does not mean the change has been observed to
 * work at runtime. Local entitlement test switches remain opt-in.
 */
object AutoModSelectionPolicy {
    fun defaultSelectedIds(
        opportunities: List<GameplayModificationOpportunity>,
    ): Set<String> =
        opportunities
            .asSequence()
            .filter {
                it.selectable &&
                    !it.replacementHex.isNullOrBlank() &&
                    it.category != GameplayModificationCategory.OWNER_ENTITLEMENT &&
                    it.category != GameplayModificationCategory.SENSITIVE_SURFACE
            }
            .map { it.id }
            .toSet()
}
