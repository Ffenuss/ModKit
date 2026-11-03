package io.github.ffenuss.modkit.space

import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallApk
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallPlan
import io.github.ffenuss.modkit.ui.AutoModBuildRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceBuildProfileIndexTest {
    private val signer = "b".repeat(64)

    private fun record(
        pkg: String,
        sha: String = "a".repeat(64),
        builtAt: Long = 100L,
        signers: Set<String> = setOf(signer),
    ): AutoModBuildRecord {
        val file = RepackedRuntimeInstallApk("base.apk", "/unused/base.apk", "c".repeat(64), 32L)
        val plan = RepackedRuntimeInstallPlan(
            artifactSha256 = sha,
            packageName = pkg,
            signerCertificateSha256 = signers,
            apks = listOf(file),
            totalBytes = 32,
            blockers = emptyList(),
        )
        return AutoModBuildRecord(plan, builtAt, listOf("Speed"), "")
    }

    @Test
    fun keepsLatestVerifiedBuildForEachPackageInStableOrder() {
        val gameA = "dev.modkit.gamea"
        val gameB = "dev.modkit.gameb"
        val records = listOf(
            record(gameA, "a".repeat(64), 10L),
            record(gameB, "b".repeat(64), 20L),
            record(gameA, "c".repeat(64), 30L),
        )
        val actual = SpaceBuildProfileIndex.select(records)
        assertEquals(listOf(gameA, gameB), actual.map { it.plan.packageName })
        assertEquals("c".repeat(64), actual.first().plan.artifactSha256)
    }

    @Test
    fun rejectsInvalidPackageAndUnsignedBuildRecords() {
        val valid = record("dev.modkit.game")
        assertEquals(
            listOf(valid),
            SpaceBuildProfileIndex.select(
                listOf(
                    record("invalid", builtAt = 300),
                    record("dev.modkit.bad", signers = emptySet(), builtAt = 200),
                    record("dev.modkit.bad2", signers = setOf("invalid"), builtAt = 200),
                    valid,
                ),
            ),
        )
    }

    @Test
    fun isDeterministicForEqualBuildTimestamps() {
        val a = record("dev.modkit.game", "a".repeat(64), 200L)
        val b = record("dev.modkit.game", "f".repeat(64), 200L)
        assertEquals(listOf(b), SpaceBuildProfileIndex.select(listOf(a, b)))
        assertEquals(listOf(b), SpaceBuildProfileIndex.select(listOf(b, a)))
    }

    @Test
    fun rejectsUnreadyRecordsWithMissingOrMalformedApks() {
        val base = record("dev.modkit.game")
        val invalid = base.copy(
            plan = base.plan.copy(
                apks = listOf(base.plan.apks[0].copy(expectedSha256 = "broken")),
            ),
        )
        assertTrue(SpaceBuildProfileIndex.select(listOf(invalid)).isEmpty())
    }
}

class SpaceLaunchIdentityPolicyTest {
    private val sha = "a".repeat(64)
    private val signer = "b".repeat(64)
    private val record = AutoModBuildRecord(
        RepackedRuntimeInstallPlan(
            sha, "dev.modkit.fixture", setOf(signer),
            listOf(RepackedRuntimeInstallApk("base.apk", "/unused/base.apk", sha, 1L)),
            1L, emptyList(),
        ), 1L, emptyList(), "",
    )

    @Test
    fun allowsOnlyExactPackageAndCurrentSignerWithLauncher() {
        val installed = SpaceInstalledIdentity(
            "dev.modkit.fixture", setOf(signer.uppercase()), true, listOf(sha),
        )
        assertEquals(SpaceLaunchStatus.READY, SpaceLaunchIdentityPolicy.evaluate(record, installed))
        assertEquals(SpaceLaunchStatus.NOT_INSTALLED, SpaceLaunchIdentityPolicy.evaluate(record, null))
        assertEquals(
            SpaceLaunchStatus.NOT_INSTALLED,
            SpaceLaunchIdentityPolicy.evaluate(record, installed.copy(packageName = "dev.other")),
        )
        assertEquals(
            SpaceLaunchStatus.DIFFERENT_SIGNATURE,
            SpaceLaunchIdentityPolicy.evaluate(record, installed.copy(currentSignerSha256 = setOf("d".repeat(64)))),
        )
        assertEquals(
            SpaceLaunchStatus.NO_LAUNCHER,
            SpaceLaunchIdentityPolicy.evaluate(record, installed.copy(hasLauncher = false)),
        )
        assertEquals(
            SpaceLaunchStatus.APK_CONTENT_MISMATCH,
            SpaceLaunchIdentityPolicy.evaluate(
                record, installed.copy(installedApkSha256 = listOf("f".repeat(64))),
            ),
        )
    }

    @Test
    fun splitApkHashComparisonIsOrderIndependentButExact() {
        val split = "f".repeat(64)
        val two = record.copy(plan = record.plan.copy(
            apks = record.plan.apks + RepackedRuntimeInstallApk(
                "config.arm64.apk", "/unused/config.arm64.apk", split, 2L,
            ),
            totalBytes = 3L,
        ))
        val installed = SpaceInstalledIdentity(
            "dev.modkit.fixture", setOf(signer), true, listOf(split, sha),
        )
        assertEquals(SpaceLaunchStatus.READY, SpaceLaunchIdentityPolicy.evaluate(two, installed))
        assertEquals(
            SpaceLaunchStatus.APK_CONTENT_MISMATCH,
            SpaceLaunchIdentityPolicy.evaluate(two, installed.copy(installedApkSha256 = listOf(sha))),
        )
    }

    @Test
    fun rejectsPartialSignerMatchesAndUnknownSigner() {
        val withTwo = record.copy(plan = record.plan.copy(
            signerCertificateSha256 = setOf(signer, "e".repeat(64)),
        ))
        val oneSigner = SpaceInstalledIdentity("dev.modkit.fixture", setOf(signer), true)
        assertEquals(
            SpaceLaunchStatus.DIFFERENT_SIGNATURE,
            SpaceLaunchIdentityPolicy.evaluate(withTwo, oneSigner),
        )
        assertEquals(
            SpaceLaunchStatus.DIFFERENT_SIGNATURE,
            SpaceLaunchIdentityPolicy.evaluate(record, oneSigner.copy(currentSignerSha256 = emptySet())),
        )
    }
}
