package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.ProgressSink
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StagingApkVerifierTest {
    @Test
    fun verifiesManifestIntegrityAndMutationDiff() {
        val root = createTempDir(prefix = "modkit-staging-verify-")
        try {
            val apk = File(root, "base.apk")
            val library = ByteArray(16) { it.toByte() }.also {
                byteArrayOf(9, 9, 9, 9).copyInto(it, destinationOffset = 4)
            }
            createZip(
                apk,
                mapOf(
                    "AndroidManifest.xml" to byteArrayOf(1, 2, 3),
                    "lib/arm64-v8a/libil2cpp.so" to library,
                    "assets/data.bin" to byteArrayOf(4, 5, 6),
                ),
            )

            val result = StagingApkVerifier.verify(
                apply = MutationApplyResult(
                    outputFiles = listOf(apk),
                    diffs = listOf(
                        MutationDiff(
                            mutationId = "m1",
                            kind = MutationKind.NATIVE_IN_PLACE_BYTES,
                            container = "base.apk",
                            entryPath = "lib/arm64-v8a/libil2cpp.so",
                            fileOffset = 4,
                            length = 4,
                            beforeSha256 = "0".repeat(64),
                            afterSha256 = sha256(byteArrayOf(9, 9, 9, 9)),
                        ),
                    ),
                    strippedSignatureEntries = emptyList(),
                ),
                cancellation = NeverCancelled,
                progress = NoProgress,
            )

            assertTrue(result.verified)
            assertTrue(result.blockers.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsOldV1SignatureEntry() {
        val root = createTempDir(prefix = "modkit-staging-signature-")
        try {
            val apk = File(root, "base.apk")
            createZip(
                apk,
                mapOf(
                    "AndroidManifest.xml" to byteArrayOf(1),
                    "META-INF/CERT.SF" to byteArrayOf(2),
                ),
            )

            val result = StagingApkVerifier.verify(
                apply = MutationApplyResult(
                    outputFiles = listOf(apk),
                    diffs = emptyList(),
                    strippedSignatureEntries = emptyList(),
                ),
                cancellation = NeverCancelled,
                progress = NoProgress,
            )

            assertFalse(result.verified)
            assertTrue(
                result.blockers.any { "v1-подпись" in it },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private object NeverCancelled : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }

    private object NoProgress : ProgressSink {
        override fun publish(progress: io.github.ffenuss.modkit.domain.EngineProgress) = Unit
    }
}
