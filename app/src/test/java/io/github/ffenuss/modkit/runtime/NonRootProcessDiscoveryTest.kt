package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NonRootProcessDiscoveryTest {
    @Test
    fun activityManagerHintAloneNeverConfirmsProcessIdentity() {
        val probe = FakeProbe(
            activity = listOf(
                NonRootProcessHint(100, PACKAGE),
            ),
            proc = listOf(100),
            cmdline = emptyMap(),
            readableMaps = setOf(100),
        )

        val result = NonRootProcessDiscovery.discover(
            packageName = PACKAGE,
            probe = probe,
            cancellation = AtomicCancellationSignal(),
        )

        assertNull(result.selectedPid)
        assertFalse(result.readyForMapsCapture)
        assertFalse(result.candidates.single().identityConfirmed)
        assertTrue(result.blockers.isNotEmpty())
    }

    @Test
    fun exactProcCmdlineAndReadableMapsConfirmPrimaryWithoutRoot() {
        val probe = FakeProbe(
            activity = emptyList(),
            proc = listOf(101),
            cmdline = mapOf(101 to PACKAGE),
            readableMaps = setOf(101),
        )

        val result = NonRootProcessDiscovery.discover(
            packageName = PACKAGE,
            probe = probe,
            cancellation = AtomicCancellationSignal(),
        )

        assertEquals(101, result.selectedPid)
        assertTrue(result.readyForMapsCapture)
        val candidate = result.candidates.single()
        assertTrue(candidate.identityConfirmed)
        assertTrue(candidate.exactMainProcess)
        assertTrue(
            NonRootProcessDiscoverySource.PROC_CMDLINE in
                candidate.sources,
        )
    }

    @Test
    fun packageChildProcessIsObservedButNotAutoSelectedAsMain() {
        val probe = FakeProbe(
            activity = listOf(
                NonRootProcessHint(102, "$PACKAGE:remote"),
            ),
            proc = listOf(102),
            cmdline = mapOf(102 to "$PACKAGE:remote"),
            readableMaps = setOf(102),
        )

        val result = NonRootProcessDiscovery.discover(
            packageName = PACKAGE,
            probe = probe,
            cancellation = AtomicCancellationSignal(),
        )

        assertNull(result.selectedPid)
        val candidate = result.candidates.single()
        assertTrue(candidate.identityConfirmed)
        assertFalse(candidate.exactMainProcess)
        assertTrue(result.blockers.isNotEmpty())
    }

    @Test
    fun ambiguousExactMainCandidatesBlockAutomaticPidSelection() {
        val probe = FakeProbe(
            activity = emptyList(),
            proc = listOf(103, 104),
            cmdline = mapOf(
                103 to PACKAGE,
                104 to PACKAGE,
            ),
            readableMaps = setOf(103, 104),
        )

        val result = NonRootProcessDiscovery.discover(
            packageName = PACKAGE,
            probe = probe,
            cancellation = AtomicCancellationSignal(),
        )

        assertNull(result.selectedPid)
        assertTrue(
            result.blockers.any {
                "Multiple" in it
            },
        )
    }

    @Test
    fun identityRecheckRequiresExactMainCmdline() {
        val probe = FakeProbe(
            activity = emptyList(),
            proc = emptyList(),
            cmdline = mapOf(
                105 to PACKAGE,
                106 to "$PACKAGE:worker",
            ),
            readableMaps = setOf(105, 106),
        )

        assertTrue(
            NonRootProcessDiscovery.verifyIdentity(
                PACKAGE,
                105,
                probe,
            ),
        )
        assertFalse(
            NonRootProcessDiscovery.verifyIdentity(
                PACKAGE,
                106,
                probe,
            ),
        )
    }

    private class FakeProbe(
        private val activity: List<NonRootProcessHint>,
        private val proc: List<Int>,
        private val cmdline: Map<Int, String>,
        private val readableMaps: Set<Int>,
    ) : NonRootProcessProbe {
        override fun activityManagerProcesses(): List<NonRootProcessHint> =
            activity

        override fun procPids(): List<Int> =
            proc

        override fun readCmdline(pid: Int): String? =
            cmdline[pid]

        override fun mapsReadable(pid: Int): Boolean =
            pid in readableMaps
    }

    companion object {
        private const val PACKAGE = "com.example.target"
    }
}
