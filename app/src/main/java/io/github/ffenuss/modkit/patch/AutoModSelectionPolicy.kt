package io.github.ffenuss.modkit.patch

/** A discovered recipe is never consent to select every change. */
object AutoModSelectionPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun defaultSelectedIds(opportunities: List<GameplayModificationOpportunity>): Set<String> = emptySet()
}
