package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.CancellationSignal

fun interface NonRootMapsCaptureProvider {
    fun capture(
        pid: Int,
        cancellation: CancellationSignal,
    ): ProcMapsCapture
}

data class VerifiedNonRootProcessCapture(
    val packageName: String,
    val pid: Int,
    val capture: ProcMapsCapture,
)

/**
 * Captures /proc/<pid>/maps only after package identity was confirmed and
 * verifies the same exact main-process cmdline again immediately afterward.
 *
 * The before/after check prevents a PID-reuse race from being promoted into
 * runtime proof.
 */
object NonRootRuntimeCaptureCoordinator {
    val defaultMapsProvider = NonRootMapsCaptureProvider { pid, cancellation ->
        ProcMapsCaptureReader.process(
            pid = pid,
            cancellation = cancellation,
        )
    }

    fun captureMaps(
        packageName: String,
        probe: NonRootProcessProbe,
        cancellation: CancellationSignal,
        mapsProvider: NonRootMapsCaptureProvider = defaultMapsProvider,
    ): VerifiedNonRootProcessCapture {
        val discovery = NonRootProcessDiscovery.discover(
            packageName = packageName,
            probe = probe,
            cancellation = cancellation,
        )
        require(discovery.readyForMapsCapture) {
            discovery.blockers.joinToString("; ").ifBlank {
                "Non-root process discovery did not produce a safe capture target."
            }
        }
        val pid = requireNotNull(discovery.selectedPid)

        require(
            NonRootProcessDiscovery.verifyIdentity(
                packageName = packageName,
                pid = pid,
                probe = probe,
            ),
        ) {
            "Target process identity changed before maps capture."
        }

        val capture = mapsProvider.capture(pid, cancellation)
        require(capture.source == ProcMapsCaptureSource.NON_ROOT_PROCESS) {
            "Non-root runtime capture provider returned the wrong capture source."
        }
        require(capture.pid == pid) {
            "Non-root runtime capture provider returned evidence for a different PID."
        }
        require(!capture.truncated) {
            "Truncated process maps cannot be promoted to runtime proof."
        }

        require(
            NonRootProcessDiscovery.verifyIdentity(
                packageName = packageName,
                pid = pid,
                probe = probe,
            ),
        ) {
            "Target process identity changed during maps capture."
        }

        return VerifiedNonRootProcessCapture(
            packageName = packageName,
            pid = pid,
            capture = capture,
        )
    }
}
