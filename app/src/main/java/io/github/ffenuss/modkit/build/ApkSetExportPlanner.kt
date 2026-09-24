package io.github.ffenuss.modkit.build

/**
 * Destination names for exporting a rebuilt application to Android Downloads.
 *
 * An APK-set is not a universal single APK: preserve all signed files in
 * one dedicated folder so none of the device-specific splits gets lost.
 * ZIP remains an explicitly requested backup format only.
 */
data class ApkSetExportPlan(
    val relativeDirectory: String,
    val displayDirectory: String,
    val fileNames: List<String>,
) {
    val isSplitSet: Boolean get() = fileNames.size > 1
}

object ApkSetExportPlanner {
    fun plan(
        packageName: String?,
        builtAtEpochMs: Long,
        signedFileNames: List<String>,
    ): ApkSetExportPlan {
        require(signedFileNames.isNotEmpty()) {
            "Нечего сохранять: подписанные APK отсутствуют."
        }
        val packagePart = packageName.orEmpty()
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trim('.', '_', '-')
            .take(80)
            .ifBlank { "application" }
        require(builtAtEpochMs > 0L) {
            "Не определено время создания сборки."
        }
        val safeNames = if (signedFileNames.size == 1) {
            listOf("ModKit-$packagePart-$builtAtEpochMs.apk")
        } else {
            signedFileNames.map { input ->
                val name = input.substringAfterLast('/')
                    .substringAfterLast('\\')
                require(
                    name == input &&
                        name.endsWith(".apk", ignoreCase = true) &&
                        name.matches(Regex("[A-Za-z0-9._-]{1,180}")) &&
                        name != "." && name != ".."
                ) {
                    "Некорректное имя APK в наборе: $input"
                }
                name
            }
        }
        require(safeNames.distinct().size == safeNames.size) {
            "В APK-set повторяются имена файлов."
        }
        val folder = if (safeNames.size == 1) {
            "ModKit"
        } else {
            "ModKit/$packagePart-$builtAtEpochMs"
        }
        return ApkSetExportPlan(
            relativeDirectory = folder,
            displayDirectory = "Загрузки/$folder",
            fileNames = safeNames,
        )
    }
}
