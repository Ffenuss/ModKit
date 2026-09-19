package io.github.ffenuss.modkit.analysis

import io.github.ffenuss.modkit.runtime.ProcMapsCaptureSource
import io.github.ffenuss.modkit.runtime.RuntimeEvidenceBundle
import io.github.ffenuss.modkit.runtime.RuntimeEvidenceObservation
import io.github.ffenuss.modkit.runtime.RuntimeEvidenceObservationKind
import io.github.ffenuss.modkit.runtime.RuntimeEvidenceObservationStrength
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeEvidenceCacheTest {
    @Test
    fun persistsRuntimeBundleOnlyUnderMatchingArtifactSha() {
        val root = Files.createTempDirectory("modkit-runtime-cache").toFile()
        try {
            val cache = EngineResultCache(File(root, "cache"))
            val bundle = RuntimeEvidenceBundle(
                artifactSha256 = SHA,
                procMapsSha256 = MAPS_SHA,
                moduleMappings = emptyList(),
                addressConfirmations = emptyList(),
                blockers = listOf("runtime target still unresolved"),
                captureSource = ProcMapsCaptureSource.NON_ROOT_PROCESS,
                capturePid = 123,
                capturedAtEpochMs = 456,
                additionalObservations = listOf(
                    RuntimeEvidenceObservation(
                        id = "jni:sample",
                        kind = RuntimeEvidenceObservationKind.JNI_DLSYM_OBSERVED,
                        strength = RuntimeEvidenceObservationStrength.OBSERVED,
                        subjectId = "JNI_OnLoad",
                        artifactSha256 = SHA,
                        captureSha256 = MAPS_SHA,
                        captureSource = ProcMapsCaptureSource.NON_ROOT_PROCESS,
                        capturedAtEpochMs = 456,
                        summary = "Observed JNI symbol lookup.",
                    ),
                ),
            )

            assertTrue(cache.saveRuntimeEvidence(SHA, bundle))
            assertEquals(bundle, cache.loadRuntimeEvidence(SHA))
            assertNull(cache.loadRuntimeEvidence(OTHER_SHA))
            assertFalse(cache.saveRuntimeEvidence(OTHER_SHA, bundle))
        } finally {
            root.deleteRecursively()
        }
    }

    companion object {
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val OTHER_SHA =
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        private const val MAPS_SHA =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
