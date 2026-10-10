package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.modification.*

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

enum class ScalarRecipeMode(val label: String, val titleMarker: String) {
    VALUE("Значение", " · значение "), MULTIPLIER("Множитель", " · множитель ×")
}

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
    val scalarMode: ScalarRecipeMode = ScalarRecipeMode.VALUE,
    val modificationRequests: List<ModificationRequest> = emptyList(),
) {
    val selectable: Boolean get() = blocker == null && (dex.isNotEmpty() || native != null)

    fun withScalarValue(value: String): AutoModRecipe {
        val choice = scalarValues.singleOrNull { it.value == value } ?: return this
        return copy(scalarValue = value, native = native?.copy(replacementHex = choice.replacementHex),
            title = title.substringBefore(scalarMode.titleMarker) + scalarMode.titleMarker + value,
            modificationRequests = modificationRequests.map { request ->
                val scalar = ScalarValue.parse(request.target.valueKind, value)
                request.copy(operation = when (request.operation) {
                    is ValueOperation.Multiply -> ValueOperation.Multiply(scalar)
                    is ValueOperation.Replace -> ValueOperation.Replace(scalar)
                    is ValueOperation.Clamp -> error("A clamp cannot be changed by a single scalar choice")
                })
            })
    }
}

object DexRecipeCatalog {
    fun create(scan: DexLocalScan): List<AutoModRecipe> = scan.opportunities
        .filter { it.category !in setOf(DexLocalCategory.FULL_VERSION, DexLocalCategory.DEBUG_UI) }
        // Only getters reading the SAME proven field with the SAME action form a bundle.
        // Unrelated methods in a category must never become one silent multi-method patch.
        .groupBy { "${it.apkIndex}:${it.dexEntry}:${it.fieldIdentity ?: it.id}:${it.action}" +
            if (it.fieldIdentity != null && !it.signature.startsWith("()")) ":${it.signature}" else "" }
        .map { (key, methods) ->
            val first = methods.first()
            AutoModRecipe(
                id = "dex:$key", category = first.category.label.substringBefore(" /"),
                title = first.category.label.substringBefore(" /") + " · " + when (first.action) {
                    DexLocalAction.INT_9999 -> "значение " + if (first.signature.substringAfter(')') == "B") "127" else "9999"
                    DexLocalAction.INT_99 -> "значение 99"
                    DexLocalAction.FLOAT_2 -> "значение 2.0"
                    DexLocalAction.TRUE -> "включить"
                    DexLocalAction.FALSE -> "выключить"
                } + DexMethodParameters.label(first),
                description = if (methods.size > 1)
                    "Согласованное изменение ${methods.size} чтений одного поля. Эффект требует проверки в приложении."
                else "Изменение возвращаемого значения. Эффект требует проверки в приложении.",
                targetLabel = first.className.substringAfterLast('/').removeSuffix(";"),
                dex = methods,
                modificationRequests = methods.mapNotNull(ExistingModificationRequests::dex),
                blocker = methods.firstOrNull { !it.selectable }?.reason ?: methods.firstNotNullOfOrNull { it.runtimeBlocker },
                verification = ModificationVerification(recipePrepared = methods.all { it.selectable }),
            )
        }
}
