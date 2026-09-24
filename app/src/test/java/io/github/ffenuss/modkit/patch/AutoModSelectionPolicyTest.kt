package io.github.ffenuss.modkit.patch

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoModSelectionPolicyTest {
    private fun opportunity(
        id: String,
        category: GameplayModificationCategory,
        selectable: Boolean = true,
        replacementHex: String? = "C0 03 5F D6",
    ) = GameplayModificationOpportunity(
        id = id,
        category = category,
        title = id,
        targetId = id,
        targetDisplayName = id,
        action = GameplayMutationAction.SKIP_METHOD,
        replacementHex = replacementHex,
        selectable = selectable,
        blocker = if (selectable) null else "not validated",
        evidenceSummary = "exact binding",
        confidence = GameplayModificationConfidence.EXACT_ACTION,
    )

    @Test
    fun preselectsOnlyReadyLocalGameplayModifications() {
        val found = listOf(
            opportunity("damage", GameplayModificationCategory.DAMAGE),
            opportunity("health", GameplayModificationCategory.SURVIVABILITY),
            opportunity("numeric", GameplayModificationCategory.STAMINA),
            opportunity("guess", GameplayModificationCategory.PROGRESSION, false),
            opportunity("no-patch", GameplayModificationCategory.MOVEMENT,
                replacementHex = null),
            opportunity("premium", GameplayModificationCategory.OWNER_ENTITLEMENT),
            opportunity("receipt", GameplayModificationCategory.SENSITIVE_SURFACE),
        )
        assertEquals(
            setOf("damage", "health", "numeric"),
            AutoModSelectionPolicy.defaultSelectedIds(found),
        )
    }

    @Test
    fun emptyResultsAreHonestAndDoNotSelectAnything() {
        assertEquals(
            emptySet<String>(),
            AutoModSelectionPolicy.defaultSelectedIds(emptyList()),
        )
    }
}
