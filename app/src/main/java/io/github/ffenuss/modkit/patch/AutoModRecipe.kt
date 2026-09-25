package io.github.ffenuss.modkit.patch

/** Independent evidence flags: a successful build must never imply observed gameplay. */
data class ModificationVerification(
    val candidateFound: Boolean = true,
    val purposeConfirmed: Boolean = false,
    val recipePrepared: Boolean = false,
    val staticVerified: Boolean = false,
    val apkBuilt: Boolean = false,
    val runtimeConfirmed: Boolean = false,
    val runtimeEvidence: String? = null,
)

data class ScalarRecipeValue(val value: String, val replacementHex: String)

data class AutoModRecipe(
    val id: String,
    val category: String,
    val title: String,
    val description: String,
    val targetLabel: String,
    val dex: List<DexLocalOpportunity> = emptyList(),
    val native: GameplayModificationOpportunity? = null,
    val blocker: String? = null,
    val verification: ModificationVerification = ModificationVerification(),
    val scalarValues: List<ScalarRecipeValue> = emptyList(),
    val scalarValue: String? = null,
) {
    val selectable: Boolean get() = blocker == null && (dex.isNotEmpty() || native != null)

    fun withScalarValue(value: String): AutoModRecipe {
        val choice = scalarValues.singleOrNull { it.value == value } ?: return this
        return copy(scalarValue = value, native = native?.copy(replacementHex = choice.replacementHex),
            title = title.substringBefore(" · значение ") + " · значение $value")
    }
}

object DexRecipeCatalog {
    fun create(scan: DexLocalScan): List<AutoModRecipe> = scan.opportunities
        .filter { it.category !in setOf(DexLocalCategory.FULL_VERSION, DexLocalCategory.DEBUG_UI) }
        // Only getters reading the SAME proven field with the SAME action form a bundle.
        // Unrelated methods in a category must never become one silent multi-method patch.
        .groupBy { "${it.apkIndex}:${it.dexEntry}:${it.fieldIdentity ?: it.id}:${it.action}" }
        .map { (key, methods) ->
            val first = methods.first()
            AutoModRecipe(
                id = "dex:$key", category = first.category.label.substringBefore(" /"),
                title = first.category.label.substringBefore(" /") + " · " + when (first.action) {
                    DexLocalAction.INT_9999 -> "значение 9999"
                    DexLocalAction.INT_99 -> "значение 99"
                    DexLocalAction.FLOAT_2 -> "значение 2.0"
                    DexLocalAction.TRUE -> "включить"
                    DexLocalAction.FALSE -> "выключить"
                },
                description = if (methods.size > 1)
                    "Согласованное изменение ${methods.size} чтений одного поля. Эффект требует проверки в приложении."
                else "Изменение возвращаемого значения. Эффект требует проверки в приложении.",
                targetLabel = first.className.substringAfterLast('/').removeSuffix(";"),
                dex = methods,
                blocker = methods.firstOrNull { !it.selectable }?.reason,
                verification = ModificationVerification(recipePrepared = methods.all { it.selectable }),
            )
        }
}
