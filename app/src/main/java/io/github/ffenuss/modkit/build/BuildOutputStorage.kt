package io.github.ffenuss.modkit.build

import java.io.File
import java.nio.file.Files

/**
 * Every build gets a unique directory beneath its source SHA. No automatic
 * pruning: user's prior successful APKs and reports survive failed retries.
 */
object BuildOutputStorage {
    private val ARTIFACT_SHA = Regex("[0-9a-fA-F]{64}")

    fun createRunDirectory(
        filesDir: File,
        artifactSha256: String,
    ): File {
        require(ARTIFACT_SHA.matches(artifactSha256)) {
            "Cannot create build directory for an invalid artifact SHA-256."
        }
        val sourceDir = File(
            filesDir,
            "patch-build/" + artifactSha256.lowercase(),
        )
        require(sourceDir.isDirectory || sourceDir.mkdirs()) {
            "Cannot create APK build storage."
        }
        return Files.createTempDirectory(
            sourceDir.toPath(),
            "run-",
        ).toFile()
    }
}
