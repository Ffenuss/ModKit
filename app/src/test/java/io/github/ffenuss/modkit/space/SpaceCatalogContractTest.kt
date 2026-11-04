package io.github.ffenuss.modkit.space

import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallApk
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallPlan
import io.github.ffenuss.modkit.ui.AutoModBuildRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceCatalogContractTest {
    private fun record(pkg: String, artifact: String, timestamp: Long) =
        AutoModBuildRecord(
            RepackedRuntimeInstallPlan(
                artifactSha256 = artifact,
                packageName = pkg,
                signerCertificateSha256 = setOf("b".repeat(64)),
                apks = listOf(RepackedRuntimeInstallApk(
                    "base.apk", "/unused/base.apk", "c".repeat(64), 20L,
                )),
                totalBytes = 20L,
                blockers = emptyList(),
            ),
            timestamp, emptyList(), "",
        )

    @Test fun keepsOneLatestBuildPerGameWithoutMixingSources() {
        val old = record("dev.modkit.first", "a".repeat(64), 1)
        val next = record("dev.modkit.first", "d".repeat(64), 3)
        val second = record("dev.modkit.second", "e".repeat(64), 2)
        val entries = SpaceCatalogContract.entries(listOf(old, second, next))
        assertEquals(2, entries.size)
        assertEquals("dev.modkit.first", entries[0].packageName)
        assertEquals("d".repeat(64), entries[0].artifactSha256)
        assertEquals("dev.modkit.second", entries[1].packageName)
        assertEquals("e".repeat(64), entries[1].artifactSha256)
        assertEquals(listOf(SpaceLegacySource("c".repeat(64), 20L)), entries[1].sources)
        assertEquals(listOf("b".repeat(64)), entries[0].signers)
    }

    @Test fun invalidSignerAndSourceSetsAreNeverPublished() {
        val valid = record("dev.modkit.first", "a".repeat(64), 1)
        val unsigned = valid.copy(
            plan = valid.plan.copy(signerCertificateSha256 = emptySet()),
        )
        val ambiguous = valid.copy(
            plan = valid.plan.copy(apks = valid.plan.apks + valid.plan.apks[0]),
        )
        assertTrue(SpaceCatalogContract.entries(listOf(unsigned)).isEmpty())
        assertTrue(SpaceCatalogContract.entries(listOf(ambiguous)).isEmpty())
    }

    @Test fun catalogIsBoundedToThirtyTwoIndependentPackages() {
        val input = (1..48).map {
            record("dev.modkit.game$it", "%064x".format(it), it.toLong())
        }
        val entries = SpaceCatalogContract.entries(input)
        assertEquals(SpaceCatalogContract.MAX_PROFILES, entries.size)
        assertEquals(entries.size, entries.map { it.packageName }.distinct().size)
    }
}
