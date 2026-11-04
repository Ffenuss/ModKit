package io.github.ffenuss.modkit.space

import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallApk
import io.github.ffenuss.modkit.runtime.RepackedRuntimeInstallPlan
import io.github.ffenuss.modkit.ui.AutoModBuildRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Selection is explicit, exact, and independent for each game/package. */
class SpaceActiveProfileSelectionTest {
    private val signer = "b".repeat(64)

    private fun profile(pkg: String, artifact: String): AutoModBuildRecord =
        AutoModBuildRecord(
            RepackedRuntimeInstallPlan(
                artifactSha256 = artifact,
                packageName = pkg,
                signerCertificateSha256 = setOf(signer),
                apks = listOf(RepackedRuntimeInstallApk(
                    "base.apk", "/unused/base.apk", "c".repeat(64), 32L,
                )),
                totalBytes = 32L,
                blockers = emptyList(),
            ),
            builtAt = 42L,
            changes = listOf("Test"),
            reportPath = "",
        )

    @Test
    fun choosesOnlyExplicitGameAndExactBuild() {
        val first = profile("dev.modkit.first", "a".repeat(64))
        val second = profile("dev.modkit.second", "d".repeat(64))
        val profiles = listOf(first to SpaceLaunchStatus.READY, second to SpaceLaunchStatus.READY)
        assertNull(SpaceActiveProfileSelection.resolve(null, profiles))
        assertEquals(second, SpaceActiveProfileSelection.resolve(
            SpaceActiveProfileKey.of(second), profiles,
        ))
        assertEquals(first, SpaceActiveProfileSelection.resolve(
            SpaceActiveProfileKey.of(first), profiles,
        ))
    }

    @Test
    fun newerBuildDoesNotSilentlyReplacePreviousSelection() {
        val old = profile("dev.modkit.first", "a".repeat(64))
        val replacement = profile("dev.modkit.first", "d".repeat(64))
        assertNull(SpaceActiveProfileSelection.resolve(
            SpaceActiveProfileKey.of(old), listOf(replacement to SpaceLaunchStatus.READY),
        ))
    }

    @Test
    fun installedIdentityFailureDisablesSelectedGameWithoutFallback() {
        val first = profile("dev.modkit.first", "a".repeat(64))
        val second = profile("dev.modkit.second", "d".repeat(64))
        val selection = SpaceActiveProfileKey.of(first)
        for (status in SpaceLaunchStatus.entries.filter { it != SpaceLaunchStatus.READY }) {
            assertNull(SpaceActiveProfileSelection.resolve(
                selection, listOf(first to status, second to SpaceLaunchStatus.READY),
            ))
        }
    }

    @Test
    fun duplicatedMatchesAndMismatchedPackagesNeverResolve() {
        val a = profile("dev.modkit.first", "a".repeat(64))
        assertNull(SpaceActiveProfileSelection.resolve(
            SpaceActiveProfileKey.of(a),
            listOf(a to SpaceLaunchStatus.READY, a to SpaceLaunchStatus.READY),
        ))
        assertNull(SpaceActiveProfileSelection.resolve(
            SpaceActiveProfileKey("dev.modkit.other", a.plan.artifactSha256),
            listOf(a to SpaceLaunchStatus.READY),
        ))
    }

    @Test
    fun malformedUnsignedRecordCannotBeActive() {
        val invalid = profile("dev.modkit.first", "a".repeat(64)).copy(
            plan = profile("dev.modkit.first", "a".repeat(64)).plan.copy(
                signerCertificateSha256 = emptySet(),
            ),
        )
        assertNull(SpaceActiveProfileSelection.resolve(
            SpaceActiveProfileKey.of(invalid), listOf(invalid to SpaceLaunchStatus.READY),
        ))
    }
}
