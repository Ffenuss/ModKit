package io.github.ffenuss.modkit.runtime

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepackedRuntimeBuildPreflightTest {
    @Test
    fun verifiedInventoryAndExactSourceRelationshipsAreReady() {
        val root = Files.createTempDirectory("modkit-repacked-preflight-").toFile()
        try {
            val base = File(root, "base.apk").apply { writeText("base") }
            val split = File(root, "split.apk").apply { writeText("split") }
            val inventory = inventory(
                records = listOf(
                    record("base.apk", null),
                    record("split.apk", "config.arm64_v8a"),
                ),
            )

            val preflight = RepackedRuntimeBuildPreflight.validate(
                artifactSha256 = SHA,
                manifestInventory = inventory,
                instrumentedApks = listOf(
                    "base.apk" to base,
                    "split.apk" to split,
                ),
            )

            assertTrue(preflight.ready)
            assertEquals(PACKAGE, preflight.packageName)
            assertTrue(preflight.blockers.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mismatchedArtifactShaBlocksBuildTail() {
        val root = Files.createTempDirectory("modkit-repacked-sha-").toFile()
        try {
            val base = File(root, "base.apk").apply { writeText("base") }

            val preflight = RepackedRuntimeBuildPreflight.validate(
                artifactSha256 = OTHER_SHA,
                manifestInventory = inventory(
                    records = listOf(record("base.apk", null)),
                ),
                instrumentedApks = listOf("base.apk" to base),
            )

            assertFalse(preflight.ready)
            assertTrue(
                preflight.blockers.any {
                    "artifact SHA" in it
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun changedApkSetRelationshipsBlockBuildTail() {
        val root = Files.createTempDirectory("modkit-repacked-rel-").toFile()
        try {
            val base = File(root, "base.apk").apply { writeText("base") }
            val unexpected = File(root, "other.apk").apply { writeText("split") }

            val preflight = RepackedRuntimeBuildPreflight.validate(
                artifactSha256 = SHA,
                manifestInventory = inventory(
                    records = listOf(
                        record("base.apk", null),
                        record("split.apk", "config.en"),
                    ),
                ),
                instrumentedApks = listOf(
                    "base.apk" to base,
                    "other.apk" to unexpected,
                ),
            )

            assertFalse(preflight.ready)
            assertTrue(
                preflight.blockers.any {
                    "source relationships" in it
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun unverifiedManifestInventoryCannotBeByPassedByPackageString() {
        val root = Files.createTempDirectory("modkit-repacked-manifest-").toFile()
        try {
            val base = File(root, "base.apk").apply { writeText("base") }
            val inventory = inventory(
                records = listOf(record("base.apk", null)),
                blockers = listOf("Manifest parser failed."),
            )

            val preflight = RepackedRuntimeBuildPreflight.validate(
                artifactSha256 = SHA,
                manifestInventory = inventory,
                instrumentedApks = listOf("base.apk" to base),
            )

            assertFalse(preflight.ready)
            assertTrue(
                preflight.blockers.any {
                    "Manifest parser failed." in it
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun inventory(
        records: List<RepackedRuntimeManifestRecord>,
        blockers: List<String> = emptyList(),
    ) = RepackedRuntimeManifestInventory(
        artifactSha256 = SHA,
        packageName = PACKAGE,
        baseSourceDisplayName = "base.apk",
        records = records,
        blockers = blockers,
    )

    private fun record(
        source: String,
        split: String?,
    ) = RepackedRuntimeManifestRecord(
        sourceDisplayName = source,
        copiedFilePath = "/tmp/$source",
        packageName = PACKAGE,
        splitName = split,
        applicationClassName = null,
        debuggable = false,
        manifestSha256 = MANIFEST_SHA,
    )

    companion object {
        private const val PACKAGE = "com.example.target"
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val OTHER_SHA =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val MANIFEST_SHA =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
    }
}
