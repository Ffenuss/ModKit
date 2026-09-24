package io.github.ffenuss.modkit.build

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkSetExportPlannerTest {
    @Test
    fun oneApkUsesRealApkExtensionInDownloads() {
        val plan = ApkSetExportPlanner.plan(
            packageName = "com.example.game",
            builtAtEpochMs = 1790000000000L,
            signedFileNames = listOf("base.apk"),
        )
        assertEquals("Загрузки/ModKit", plan.displayDirectory)
        assertEquals(
            listOf("ModKit-com.example.game-1790000000000.apk"),
            plan.fileNames,
        )
        assertEquals(false, plan.isSplitSet)
    }

    @Test
    fun splitPackageRetainsEveryApkInOneFolder() {
        val names = listOf(
            "base.apk",
            "split_config.arm64_v8a.apk",
            "split_UnityDataAssetPack.apk",
            "split_gpdeku.apk",
            "split_gpdeku.config.arm64_v8a.apk",
        )
        val plan = ApkSetExportPlanner.plan(
            packageName = "com.noodlecake.flickshotrogues",
            builtAtEpochMs = 1790000000000L,
            signedFileNames = names,
        )
        assertEquals(names, plan.fileNames)
        assertTrue(plan.isSplitSet)
        assertEquals(
            "Загрузки/ModKit/com.noodlecake.flickshotrogues-1790000000000",
            plan.displayDirectory,
        )
    }

    @Test
    fun rejectsDuplicateAndPathTraversalEntries() {
        assertThrows(IllegalArgumentException::class.java) {
            ApkSetExportPlanner.plan(
                packageName = "com.example",
                builtAtEpochMs = 1L,
                signedFileNames = listOf("base.apk", "base.apk"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ApkSetExportPlanner.plan(
                packageName = "com.example",
                builtAtEpochMs = 1L,
                signedFileNames = listOf("base.apk", "../split.apk"),
            )
        }
    }
}
