package io.github.ffenuss.modkit.patch

/**
 * Safety boundary for already installed Android applications.
 *
 * A repacked APK has a different signing certificate and cannot replace
 * the installed original. In-place patching without an independently
 * verified execution environment is not implemented. Therefore no
 * installed app is ever offered a selectable repack recipe here.
 */
object InstalledOriginalRecipePolicy {
    const val ORIGINAL_PRESERVED_BLOCKER =
        "Оригинальный APK остаётся установленным. Этот кандидат не проверен " +
            "в игре, а переупаковка и установка вместо оригинала отключены."

    /**
     * Android's CATEGORY_GAME metadata is necessary for surfacing named
     * gameplay hints, not proof of actual game semantics. An APK editor,
     * file manager or uncategorized utility must never display a
     * "Здоровье" hit as a mod merely because DEX has a getHealth() method.
     */
    fun researchOnly(
        candidates: List<AutoModRecipe>,
        androidGameCategory: Boolean,
    ): List<AutoModRecipe> =
        if (!androidGameCategory) emptyList()
        else candidates.map { recipe ->
            recipe.copy(
                blocker = ORIGINAL_PRESERVED_BLOCKER,
                verification = recipe.verification.copy(
                    purposeConfirmed = false,
                    runtimeConfirmed = false,
                    runtimeEvidence = null,
                    apkBuilt = false,
                ),
            )
        }
}
