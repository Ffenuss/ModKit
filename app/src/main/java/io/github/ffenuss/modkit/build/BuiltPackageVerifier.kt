package io.github.ffenuss.modkit.build

import com.android.apksig.internal.apk.AndroidBinXmlParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile

data class ParsedApkPackage(
    val fileName: String,
    val packageName: String,
    val splitName: String?,
    val versionCode: Long,
)

data class InstallabilityVerification(
    val verified: Boolean,
    val packageName: String?,
    val files: List<ParsedApkPackage>,
    val blockers: List<String>,
)

/**
 * Local APK/APK-set structural verification.
 *
 * PackageManager.getPackageArchiveInfo() parses an archive as a monolithic
 * package and therefore cannot be used to validate an individual split APK.
 * Read the binary AndroidManifest.xml directly so base and split identities
 * are checked with the same rules before PackageInstaller sees the set.
 */
object BuiltPackageVerifier {
    private const val ANDROID_MANIFEST = "AndroidManifest.xml"
    private const val ANDROID_NAMESPACE =
        "http://schemas.android.com/apk/res/android"
    private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024

    fun verify(
        files: List<File>,
    ): InstallabilityVerification {
        val blockers = mutableListOf<String>()
        val parsed = files.mapNotNull { file ->
            if (!file.isFile || !file.canRead()) {
                blockers += file.name + ": APK недоступен для проверки."
                null
            } else {
                runCatching {
                    parseManifestIdentity(file)
                }.getOrElse { failure ->
                    blockers += file.name + ": " +
                        (failure.message ?: failure.javaClass.simpleName)
                    null
                }
            }
        }

        val structural = validateParsed(
            parsed = parsed,
            expectedFileCount = files.size,
        )
        val allBlockers =
            (blockers + structural.blockers)
                .distinct()

        return structural.copy(
            verified = allBlockers.isEmpty(),
            blockers = allBlockers,
        )
    }

    internal fun validateParsed(
        parsed: List<ParsedApkPackage>,
        expectedFileCount: Int,
    ): InstallabilityVerification {
        val blockers = mutableListOf<String>()
        if (expectedFileCount <= 0) {
            blockers += "APK-set пуст."
        }
        if (parsed.size != expectedFileCount) {
            blockers += "Не все APK удалось разобрать."
        }

        val duplicateFileNames =
            parsed.groupingBy { it.fileName }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        if (duplicateFileNames.isNotEmpty()) {
            blockers += "APK-set содержит повторяющиеся имена файлов."
        }

        val packages = parsed.map { it.packageName }.distinct()
        if (packages.size > 1) {
            blockers += "APK-set содержит разные packageName."
        }

        val versions = parsed.map { it.versionCode }.distinct()
        if (versions.size > 1) {
            blockers += "APK-set содержит разные versionCode."
        }

        val bases = parsed.count { it.splitName == null }
        if (parsed.isNotEmpty() && bases != 1) {
            blockers += "APK-set должен содержать ровно один base APK."
        }

        val duplicateSplits =
            parsed.mapNotNull { it.splitName }
                .groupingBy { it }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        if (duplicateSplits.isNotEmpty()) {
            blockers += "APK-set содержит повторяющиеся splitName."
        }

        return InstallabilityVerification(
            verified = blockers.isEmpty() &&
                parsed.isNotEmpty() &&
                parsed.size == expectedFileCount,
            packageName = packages.singleOrNull(),
            files = parsed,
            blockers = blockers.distinct(),
        )
    }

    private fun parseManifestIdentity(
        file: File,
    ): ParsedApkPackage {
        val manifestBytes =
            ZipFile(file).use { zip ->
                val entry =
                    zip.getEntry(ANDROID_MANIFEST)
                        ?: error("AndroidManifest.xml отсутствует.")
                require(
                    entry.size < 0L ||
                        entry.size <= MAX_MANIFEST_BYTES,
                ) {
                    "AndroidManifest.xml превышает внутренний лимит."
                }
                zip.getInputStream(entry).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_MANIFEST_BYTES) {
                            "AndroidManifest.xml превышает внутренний лимит."
                        }
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            }
        require(manifestBytes.isNotEmpty()) {
            "AndroidManifest.xml пуст."
        }

        val parser =
            AndroidBinXmlParser(
                ByteBuffer.wrap(manifestBytes),
            )
        var event = parser.eventType
        while (
            event !=
            AndroidBinXmlParser.EVENT_END_DOCUMENT
        ) {
            if (
                event ==
                AndroidBinXmlParser.EVENT_START_ELEMENT &&
                parser.depth == 1 &&
                parser.name == "manifest" &&
                parser.namespace.isEmpty()
            ) {
                var packageName: String? = null
                var splitName: String? = null
                var versionCode: Long? = null
                var versionCodeMajor = 0L

                for (
                    index in
                    0 until parser.attributeCount
                ) {
                    val namespace =
                        parser.getAttributeNamespace(index)
                    val name =
                        parser.getAttributeName(index)
                    val valueType =
                        parser.getAttributeValueType(index)

                    when {
                        namespace.isEmpty() &&
                            name == "package" &&
                            valueType ==
                            AndroidBinXmlParser.VALUE_TYPE_STRING ->
                            packageName =
                                parser.getAttributeStringValue(index)

                        namespace.isEmpty() &&
                            name == "split" &&
                            valueType ==
                            AndroidBinXmlParser.VALUE_TYPE_STRING ->
                            splitName =
                                parser.getAttributeStringValue(index)
                                    .takeIf { it.isNotBlank() }

                        namespace == ANDROID_NAMESPACE &&
                            name == "versionCode" &&
                            valueType ==
                            AndroidBinXmlParser.VALUE_TYPE_INT ->
                            versionCode =
                                parser.getAttributeIntValue(index)
                                    .toLong() and 0xffffffffL

                        namespace == ANDROID_NAMESPACE &&
                            name == "versionCodeMajor" &&
                            valueType ==
                            AndroidBinXmlParser.VALUE_TYPE_INT ->
                            versionCodeMajor =
                                parser.getAttributeIntValue(index)
                                    .toLong() and 0xffffffffL
                    }
                }

                val packageId =
                    packageName?.takeIf { it.isNotBlank() }
                        ?: error("manifest packageName отсутствует.")
                val lowVersion =
                    versionCode
                        ?: error("manifest versionCode отсутствует.")
                return ParsedApkPackage(
                    fileName = file.name,
                    packageName = packageId,
                    splitName = splitName,
                    versionCode =
                        (versionCodeMajor shl 32) or
                            lowVersion,
                )
            }
            event = parser.next()
        }

        error("Корневой manifest element не найден.")
    }
}
