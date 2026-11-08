package io.github.ffenuss.modkit.patch

import org.junit.Assert.*
import org.junit.Test

/** Regression for the reported MT Manager false "Health" mod hit. */
class InstalledOriginalRecipePolicyTest {
    private val utilityHealth = AutoModRecipe(
        id = "dex:bin.mt.plus:GetHealth",
        category = "Здоровье",
        title = "Здоровье · значение 9999",
        description = "Named getHealth getter",
        targetLabel = "ApkEditor",
        dex = listOf(
            DexLocalOpportunity(
                id = "utility-health",
                apkIndex = 0,
                dexEntry = "classes.dex",
                className = "Lbin/mt/plus/ApkEditor;",
                methodName = "getHealth",
                signature = "()I",
                originalDexSha256 = "ab".repeat(32),
                category = DexLocalCategory.HEALTH,
                action = DexLocalAction.INT_9999,
                selectable = true,
                reason = "Name match only",
            ),
        ),
        verification = ModificationVerification(recipePrepared = true, apkBuilt = true,
            runtimeConfirmed = true, runtimeEvidence = "stale"),
    )

    @Test fun apkEditorOrUnknownAppNeverGetsHealthAsGameplayMod() {
        val matches = InstalledOriginalRecipePolicy.researchOnly(
            listOf(utilityHealth), androidGameCategory = false,
        )
        assertTrue("Utility method names are not gameplay mods", matches.isEmpty())
    }

    @Test fun evenAndroidGameIsShownAsResearchOnlyNotRepacked() {
        val recipes = InstalledOriginalRecipePolicy.researchOnly(
            listOf(utilityHealth), androidGameCategory = true,
        )
        assertEquals(1, recipes.size)
        assertFalse(recipes.single().selectable)
        assertTrue(recipes.single().blocker!!.contains("перепаковка").not() ||
            recipes.single().blocker!!.contains("переупаковка"))
        assertFalse(recipes.single().verification.runtimeConfirmed)
        assertFalse(recipes.single().verification.apkBuilt)
        assertFalse(recipes.single().verification.purposeConfirmed)
        assertNull(recipes.single().verification.runtimeEvidence)
    }

    @Test fun originalSafetyPolicyNeverPromotesEmptyCandidates() {
        assertTrue(InstalledOriginalRecipePolicy.researchOnly(emptyList(), true).isEmpty())
    }
}
