package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.FastAnalysisResult
import io.github.ffenuss.modkit.runtime.RepackedRuntimeTestMenuItem
import io.github.ffenuss.modkit.runtime.RepackedRuntimeTestMenuItemMode
import java.io.File

data class RuntimeGameplayTestMenuSpec(
    val items: List<RepackedRuntimeTestMenuItem>,
) {
    val patchItemCount: Int
        get() =
            items.count {
                it.mode ==
                    RepackedRuntimeTestMenuItemMode.PATCH
            }

    val infoItemCount: Int
        get() =
            items.count {
                it.mode ==
                    RepackedRuntimeTestMenuItemMode.INFO
            }
}

/**
 * Converts strict AutoMod findings into a runtime test-menu manifest.
 *
 * Runtime PATCH entries are emitted only for opportunities already marked
 * selectable by GameplayModificationFinder and only for explicitly allowed
 * local gameplay categories. Sensitive/economy/RNG findings are exported as
 * read-only INFO entries so the overlay can help validate discovery without
 * turning those surfaces into automatic bypasses.
 */
object RuntimeGameplayTestMenuBuilder {
    private const val MAX_ITEMS = 64
    private const val MAX_PATCH_ITEMS = 24

    fun build(
        result: FastAnalysisResult,
        preparation: PatchPreparationPlan,
        analysisResultsRoot: File,
        stagingRoot: File,
    ): RuntimeGameplayTestMenuSpec {
        val opportunities =
            GameplayModificationFinder.find(
                result = result,
                preparation = preparation,
                projectCodeOnly = true,
                limit = 128,
                perCategoryLimit = 16,
            )

        val patches =
            opportunities
                .asSequence()
                .filter { it.selectable }
                .filter {
                    it.category in
                        runtimePatchableCategories
                }
                .mapNotNull { opportunity ->
                    val replacement =
                        opportunity.replacementHex
                            ?: return@mapNotNull null
                    val target =
                        result.evidenceGraph
                            ?.targets
                            .orEmpty()
                            .singleOrNull {
                                it.id ==
                                    opportunity.targetId
                            }
                            ?: return@mapNotNull null
                    val binding =
                        Il2CppPatchTargetBrowser
                            .bindingFor(
                                result = result,
                                target = target,
                            )
                            ?: return@mapNotNull null
                    if (
                        !target.abi
                            .orEmpty()
                            .equals(
                                "arm64-v8a",
                                ignoreCase = true,
                            )
                    ) {
                        return@mapNotNull null
                    }
                    val module =
                        target.artifact
                            ?.substringAfterLast(':')
                            ?.substringAfterLast('/')
                            ?.takeIf {
                                it.endsWith(
                                    ".so",
                                    ignoreCase = true,
                                )
                            }
                            ?: return@mapNotNull null

                    val draft =
                        runCatching {
                            Il2CppNativeMutationDraftBuilder
                                .build(
                                    result = result,
                                    targetId =
                                        opportunity.targetId,
                                    replacementHex =
                                        replacement,
                                    analysisResultsRoot =
                                        analysisResultsRoot,
                                    stagingRoot =
                                        stagingRoot,
                                )
                        }.getOrNull()
                            ?: return@mapNotNull null

                    RepackedRuntimeTestMenuItem(
                        id =
                            "patch:" +
                                opportunity.id,
                        label = opportunity.title,
                        detail =
                            opportunity.targetDisplayName +
                                " · " +
                                opportunity.evidenceSummary,
                        mode =
                            RepackedRuntimeTestMenuItemMode
                                .PATCH,
                        moduleName = module,
                        binaryVirtualAddress =
                            binding.functionVirtualAddress,
                        originalHex = draft.originalHex,
                        replacementHex =
                            draft.replacementHex,
                    )
                }
                .distinctBy { it.id }
                .take(MAX_PATCH_ITEMS)
                .toList()

        val patchTargetIds =
            opportunities
                .filter { it.selectable }
                .map { it.targetId }
                .toSet()

        val info =
            opportunities
                .asSequence()
                .filter {
                    !it.selectable ||
                        it.category !in
                        runtimePatchableCategories
                }
                .filterNot {
                    it.targetId in patchTargetIds &&
                        it.category in
                        runtimePatchableCategories
                }
                .map { opportunity ->
                    RepackedRuntimeTestMenuItem(
                        id =
                            "info:" +
                                opportunity.id,
                        label = opportunity.title,
                        detail =
                            buildString {
                                append(
                                    opportunity
                                        .targetDisplayName,
                                )
                                append(" · ")
                                append(
                                    opportunity.blocker
                                        ?: opportunity
                                            .evidenceSummary,
                                )
                            },
                        mode =
                            RepackedRuntimeTestMenuItemMode
                                .INFO,
                    )
                }
                .distinctBy { it.id }
                .take(
                    (MAX_ITEMS - patches.size)
                        .coerceAtLeast(0),
                )
                .toList()

        return RuntimeGameplayTestMenuSpec(
            items =
                (patches + info)
                    .take(MAX_ITEMS),
        )
    }

    private val runtimePatchableCategories =
        setOf(
            GameplayModificationCategory.SURVIVABILITY,
            GameplayModificationCategory.DAMAGE,
            GameplayModificationCategory.MOVEMENT,
            GameplayModificationCategory.COLLISION,
            GameplayModificationCategory.STAMINA,
            GameplayModificationCategory.COOLDOWN,
            GameplayModificationCategory.CONTROL,
            GameplayModificationCategory.ATTACK_SPEED,
            GameplayModificationCategory.REGENERATION,
            GameplayModificationCategory.PROGRESSION,
            GameplayModificationCategory.INVENTORY,
            GameplayModificationCategory.DROPS,
            GameplayModificationCategory.DIFFICULTY,
            GameplayModificationCategory.WORLD,
            GameplayModificationCategory.CAMERA,
        )
}
