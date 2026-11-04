package io.github.ffenuss.modkit.space

import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallApk
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallPlan
import io.github.ffenuss.modkit.ui.AutoModBuildRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceLegacyMenuContractTest {
    private val artifact = "a".repeat(64)
    private val signer = "b".repeat(64)
    private val baseHash = "c".repeat(64)

    private fun record(
        packageName: String = "dev.modkit.fixture",
        files: List<RepackedRuntimeInstallApk> = listOf(
            RepackedRuntimeInstallApk("base.apk", "/unused/base.apk", baseHash, 1024L),
        ),
        signers: Set<String> = setOf(signer),
    ) = AutoModBuildRecord(
        RepackedRuntimeInstallPlan(
            artifactSha256 = artifact,
            packageName = packageName,
            signerCertificateSha256 = signers,
            apks = files,
            totalBytes = files.sumOf { it.size },
            blockers = emptyList(),
        ), 12L, listOf("static speed"), "",
    )

    @Test
    fun producesLegacyCompatibleIdentityButNoExecutableItems() {
        val profile = requireNotNull(SpaceLegacyProfileContract.from(record()))
        assertEquals("dev.modkit.fixture", profile.packageName)
        assertEquals(artifact, profile.artifactSha256)
        assertEquals("dev.modkit.fixture-$artifact.json", profile.fileName)
        assertEquals(listOf(SpaceLegacySource(baseHash, 1024L)), profile.sources)
    }

    @Test
    fun splitIdentityHasDeterministicShaAndSizeOrdering() {
        val split = RepackedRuntimeInstallApk(
            "config.arm64.apk", "/unused/config.apk", "f".repeat(64), 123L,
        )
        val files = listOf(split, record().plan.apks.single())
        val actual = SpaceLegacyProfileContract.from(record(files = files))
        assertEquals(listOf(baseHash, "f".repeat(64)), actual!!.sources.map { it.sha256 })
        assertEquals(listOf(1024L, 123L), actual.sources.map { it.size })
    }

    @Test
    fun rejectsUnreadyOrAmbiguousBuildsAndUntrustedSigners() {
        assertNull(SpaceLegacyProfileContract.from(record(signers = emptySet())))
        assertNull(SpaceLegacyProfileContract.from(record(files = emptyList())))
        assertNull(SpaceLegacyProfileContract.from(record(files = listOf(
            record().plan.apks.single(), record().plan.apks.single(),
        ))))
        assertNull(SpaceLegacyProfileContract.from(record(packageName = "bad")))
    }

    @Test
    fun legacyHostRequiresExactlyOnePinnedCertificate() {
        assertTrue(SpaceTrustedHost.accepts(listOf(
            "03498720af5c326fc3a399d7c96aed5fdea2f378ec1799580bb119e1bcbc4b5f",
        )))
        assertFalse(SpaceTrustedHost.accepts(emptySet()))
        assertFalse(SpaceTrustedHost.accepts(setOf("f".repeat(64))))
        assertFalse(SpaceTrustedHost.accepts(listOf(
            "03498720af5c326fc3a399d7c96aed5fdea2f378ec1799580bb119e1bcbc4b5f",
            "f".repeat(64),
        )))
    }
}
