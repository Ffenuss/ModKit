package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.modification.*

/** One lowering path for menu validation and instrumentation. Never silently drops an operation. */
object DexRecipeRuntimeSelection {
    fun create(recipe: AutoModRecipe): List<DexRuntimeSelection> {
        require(recipe.selectable && recipe.native == null && recipe.dex.isNotEmpty())
        require(recipe.dex.map { it.id }.distinct().size == recipe.dex.size)
        val requests = recipe.modificationRequests
        require(requests.size == recipe.dex.size && requests.map { it.id }.toSet() == recipe.dex.map { it.id }.toSet()) {
            "DEX recipe has missing, duplicate or unrelated common requests"
        }
        return recipe.dex.map { method ->
            require(method.selectable && method.runtimeBlocker == null)
            val request = requests.single { it.id == method.id }
            val canonical = requireNotNull(ExistingModificationRequests.dex(method))
            val expected = if (request.operation is ValueOperation.Multiply &&
                request.target.valueKind in setOf(ScalarKind.FLOAT32, ScalarKind.FLOAT64))
                canonical.copy(operation = request.operation) else canonical
            require(request == expected) { "DEX request differs from its bound method/type/source" }
            require((recipe.scalarMode == ScalarRecipeMode.MULTIPLIER) == (request.operation is ValueOperation.Multiply))
            if (recipe.scalarMode == ScalarRecipeMode.MULTIPLIER) require(request.operation == ValueOperation.Multiply(
                ScalarValue.parse(request.target.valueKind, requireNotNull(recipe.scalarValue)))) {
                "DEX coefficient differs from menu selection"
            }
            DexRuntimeSelection(method, DexRuntimeSwitchRewriter.switchId(recipe.id),
                request.operation as? ValueOperation.Multiply, request)
        }
    }
}
