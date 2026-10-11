package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.modification.*
import org.junit.Assert.*
import org.junit.Test

class ExistingModificationRequestsTest {
    @Test fun normalizesAllSupportedDexReturnWidths() {
        val kinds = mapOf("Z" to ScalarKind.BOOLEAN, "B" to ScalarKind.INT8, "S" to ScalarKind.INT16,
            "C" to ScalarKind.UINT16, "I" to ScalarKind.INT32, "J" to ScalarKind.INT64,
            "F" to ScalarKind.FLOAT32, "D" to ScalarKind.FLOAT64)
        kinds.forEach { (descriptor, kind) ->
            assertEquals(kind, ExistingModificationRequests.dexKind("(JD)$descriptor"))
        }
        assertNull(ExistingModificationRequests.dexKind("()V"))
        assertNull(ExistingModificationRequests.dexKind("()Ljava/lang/String;"))
    }
    @Test fun parameterChangeUpdatesTheCommonRuleAsWellAsTheByteChoice() {
        val request = ExistingModificationRequests.result("exact", "damage", CodeFamily.NATIVE,
            "a".repeat(64), "method-16", ScalarKind.FLOAT32, "2", ScalarRecipeMode.MULTIPLIER)
        val recipe = AutoModRecipe("exact", "damage", "Урон · множитель ×2", "", "",
            scalarMode = ScalarRecipeMode.MULTIPLIER, scalarValue = "2",
            scalarValues = listOf(ScalarRecipeValue("2", "02"), ScalarRecipeValue("5", "05")),
            modificationRequests = listOf(request))
        val chosen = recipe.withScalarValue("5")
        assertEquals(ScalarValue.Single(60f), chosen.modificationRequests.single().operation.apply(ScalarValue.Single(12f)))
        assertEquals("5", chosen.scalarValue)
        assertEquals(ScalarValue.Single(24f), request.operation.apply(ScalarValue.Single(12f)))
        assertEquals(recipe, recipe.withScalarValue("unsupported"))
    }
}
