package io.github.ffenuss.modkit.build

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltPackageVerifierTest {
    @Test
    fun acceptsOneBaseAndUniqueSplitsWithSameIdentity() {
        val result =
            BuiltPackageVerifier.validateParsed(
                parsed =
                    listOf(
                        apk("base.apk", null),
                        apk("split_config.arm64_v8a.apk", "config.arm64_v8a"),
                        apk("split_config.xxhdpi.apk", "config.xxhdpi"),
                    ),
                expectedFileCount = 3,
            )

        assertTrue(result.verified)
    }

    @Test
    fun rejectsDuplicateSplitName() {
        val result =
            BuiltPackageVerifier.validateParsed(
                parsed =
                    listOf(
                        apk("base.apk", null),
                        apk("a.apk", "config.arm64_v8a"),
                        apk("b.apk", "config.arm64_v8a"),
                    ),
                expectedFileCount = 3,
            )

        assertFalse(result.verified)
        assertTrue(
            result.blockers.any {
                it.contains("splitName")
            },
        )
    }

    @Test
    fun rejectsPackageOrVersionMismatch() {
        val packageMismatch =
            BuiltPackageVerifier.validateParsed(
                parsed =
                    listOf(
                        apk("base.apk", null),
                        apk(
                            "split.apk",
                            "config.arm64_v8a",
                            packageName = "other.pkg",
                        ),
                    ),
                expectedFileCount = 2,
            )
        val versionMismatch =
            BuiltPackageVerifier.validateParsed(
                parsed =
                    listOf(
                        apk("base.apk", null),
                        apk(
                            "split.apk",
                            "config.arm64_v8a",
                            versionCode = 43,
                        ),
                    ),
                expectedFileCount = 2,
            )

        assertFalse(packageMismatch.verified)
        assertFalse(versionMismatch.verified)
    }

    private fun apk(
        fileName: String,
        splitName: String?,
        packageName: String = "game.pkg",
        versionCode: Long = 42,
    ) =
        ParsedApkPackage(
            fileName = fileName,
            packageName = packageName,
            splitName = splitName,
            versionCode = versionCode,
        )
}
