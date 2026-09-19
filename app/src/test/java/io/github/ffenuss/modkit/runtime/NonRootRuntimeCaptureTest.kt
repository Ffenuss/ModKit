package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NonRootRuntimeCaptureTest {
    @Test
    fun captureIsAcceptedOnlyWhenIdentityIsStableBeforeAndAfterMapsRead() {
        val probe = StatefulProbe(
            pid = 201,
            commandLines = mutableListOf(
                PACKAGE,
                PACKAGE,
                PACKAGE,
            ),
        )
        val capture = capture(pid = 201)

        val verified = NonRootRuntimeCaptureCoordinator.captureMaps(
            packageName = PACKAGE,
            probe = probe,
            cancellation = AtomicCancellationSignal(),
            mapsProvider = NonRootMapsCaptureProvider { pid, _ ->
                assertEquals(201, pid)
                capture
            },
        )

        assertEquals(PACKAGE, verified.packageName)
        assertEquals(201, verified.pid)
        assertEquals(capture, verified.capture)
    }

    @Test
    fun pidIdentityRaceBlocksRuntimeCapturePromotion() {
        val probe = StatefulProbe(
            pid = 202,
            commandLines = mutableListOf(
                PACKAGE,
                PACKAGE,
                "com.other.process",
            ),
        )

        val failure = runCatching {
            NonRootRuntimeCaptureCoordinator.captureMaps(
                packageName = PACKAGE,
                probe = probe,
                cancellation = AtomicCancellationSignal(),
                mapsProvider = NonRootMapsCaptureProvider { pid, _ ->
                    capture(pid)
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "changed during maps capture",
            ),
        )
    }

    @Test
    fun wrongPidFromCaptureProviderIsRejected() {
        val probe = StatefulProbe(
            pid = 203,
            commandLines = mutableListOf(
                PACKAGE,
                PACKAGE,
            ),
        )

        val failure = runCatching {
            NonRootRuntimeCaptureCoordinator.captureMaps(
                packageName = PACKAGE,
                probe = probe,
                cancellation = AtomicCancellationSignal(),
                mapsProvider = NonRootMapsCaptureProvider { _, _ ->
                    capture(pid = 999)
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains("different PID"),
        )
    }

    private class StatefulProbe(
        private val pid: Int,
        private val commandLines: MutableList<String?>,
    ) : NonRootProcessProbe {
        override fun activityManagerProcesses(): List<NonRootProcessHint> =
            emptyList()

        override fun procPids(): List<Int> =
            listOf(pid)

        override fun readCmdline(pid: Int): String? =
            if (commandLines.isEmpty()) {
                null
            } else {
                commandLines.removeAt(0)
            }

        override fun mapsReadable(pid: Int): Boolean =
            pid == this.pid
    }

    private fun capture(pid: Int) =
        ProcMapsCapture(
            source = ProcMapsCaptureSource.NON_ROOT_PROCESS,
            pid = pid,
            capturedAtEpochMs = 1,
            text = "1000-2000 r-xp 00000000 00:00 1 /data/app/libsample.so",
            sha256 =
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            truncated = false,
        )

    companion object {
        private const val PACKAGE = "com.example.target"
    }
}
