package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.EvidenceTarget
import io.github.ffenuss.modkit.data.InstalledAppTarget
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

object RootModProfileWriter {
    const val MIME_TYPE =
        "application/json"

    fun build(
        app: InstalledAppTarget,
        discovery: RootModDiscoveryResult,
        selectedIds: Set<String>,
    ): String {
        require(
            selectedIds.isNotEmpty(),
        ) {
            "Не выбраны модификации."
        }

        val selected =
            discovery.opportunities
                .filter {
                    it.id in
                        selectedIds &&
                        it.selectable &&
                        it.category !=
                            GameplayModificationCategory
                                .SENSITIVE_SURFACE &&
                        !it.replacementHex
                            .isNullOrBlank()
                }
        require(
            selected.isNotEmpty(),
        ) {
            "Среди выбранного нет подтверждённых модификаций для профиля."
        }

        val targets =
            discovery.analysisResult
                .evidenceGraph
                ?.targets
                .orEmpty()
                .associateBy {
                    it.id
                }

        val modifications =
            JSONArray()
        selected.forEach {
            opportunity ->
            val target =
                targets[
                    opportunity
                        .targetId
                ]
                    ?: error(
                        "Цель модификации больше не найдена: " +
                            opportunity
                                .targetDisplayName,
                    )
            modifications.put(
                modificationJson(
                    opportunity =
                        opportunity,
                    target =
                        target,
                ),
            )
        }

        return JSONObject()
            .put(
                "schema",
                "modkit/root-mod-profile/1",
            )
            .put(
                "createdAtEpochMs",
                System.currentTimeMillis(),
            )
            .put(
                "packageName",
                app.packageName,
            )
            .put(
                "label",
                app.label,
            )
            .put(
                "versionName",
                app.versionName,
            )
            .put(
                "versionCode",
                app.versionCode,
            )
            .put(
                "artifactSha256",
                discovery.analysisResult
                    .index
                    .artifactSha256,
            )
            .put(
                "runtimeApplyModel",
                "resolve-module-file-offset-at-launch",
            )
            .put(
                "modifications",
                modifications,
            )
            .toString(
                2,
            )
    }

    fun write(
        output: File,
        app: InstalledAppTarget,
        discovery: RootModDiscoveryResult,
        selectedIds: Set<String>,
    ): File {
        output.parentFile
            ?.mkdirs()
        output.writeText(
            build(
                app =
                    app,
                discovery =
                    discovery,
                selectedIds =
                    selectedIds,
            ),
            Charsets.UTF_8,
        )
        return output
    }

    private fun modificationJson(
        opportunity:
            GameplayModificationOpportunity,
        target:
            EvidenceTarget,
    ): JSONObject =
        JSONObject()
            .put(
                "id",
                opportunity.id,
            )
            .put(
                "title",
                opportunity.title,
            )
            .put(
                "category",
                opportunity.category
                    .name,
            )
            .put(
                "targetId",
                opportunity.targetId,
            )
            .put(
                "targetDisplayName",
                opportunity
                    .targetDisplayName,
            )
            .put(
                "action",
                opportunity.action
                    .name,
            )
            .put(
                "replacementHex",
                opportunity
                    .replacementHex,
            )
            .put(
                "runtimeId",
                target.runtimeId,
            )
            .put(
                "artifact",
                target.artifact,
            )
            .put(
                "moduleName",
                target.artifact
                    ?.substringAfterLast(
                        '/',
                    ),
            )
            .put(
                "abi",
                target.abi,
            )
            .put(
                "fileOffset",
                target.fileOffset,
            )
            .put(
                "metadataToken",
                target.metadataToken,
            )
            .put(
                "declaringType",
                target.declaringType,
            )
            .put(
                "memberName",
                target.memberName,
            )
            .put(
                "proofLevel",
                target.proofLevel
                    .name,
            )
}
