package io.github.ffenuss.modkit.runtime

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepackedRuntimeNativeTraceExportProtocolTest {
    @Test
    fun exactStoppedTraceParsesAndKeepsPassiveAcquisitionMode() {
        val trace =
            "DLSYM\tlibsample.so\tResolveTarget\t0x70020100\n" +
                "JNI_ON_LOAD\tlibsample.so\tJNI_OnLoad\t0x70020200\n"
        val export =
            RepackedRuntimeNativeTraceExportProtocol.parse(
                exportBytes(
                    trace = trace,
                    eventCount = 2,
                    truncated = false,
                ),
            )

        assertEquals(PACKAGE, export.packageName)
        assertEquals(PID, export.pid)
        assertEquals(2, export.eventCount)
        assertFalse(export.truncated)

        val capture =
            RepackedRuntimeNativeTraceExportProtocol.toCapture(
                export = export,
                artifactSha256 = SHA,
                source =
                    RuntimeNativeTraceSource.REPACKED_TEST_RUNTIME,
            )
        assertEquals(
            RuntimeNativeAcquisitionMode.PASSIVE_TRACE,
            capture.acquisitionMode,
        )
        assertFalse(capture.truncated)
    }

    @Test
    fun traceShaMismatchFailsClosed() {
        val bytes = exportBytes(
            trace =
                "DLSYM\tlibsample.so\tResolveTarget\t0x70020100\n",
            eventCount = 1,
            truncated = false,
        )
        bytes[bytes.lastIndex] =
            (bytes.last().toInt() xor 1).toByte()

        val failure = runCatching {
            RepackedRuntimeNativeTraceExportProtocol.parse(
                bytes,
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "SHA-256 mismatch",
            ),
        )
    }

    @Test
    fun declaredEventCountMustMatchTraceLines() {
        val failure = runCatching {
            RepackedRuntimeNativeTraceExportProtocol.parse(
                exportBytes(
                    trace =
                        "DLSYM\tlibsample.so\tResolveTarget\t0x70020100\n",
                    eventCount = 2,
                    truncated = false,
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "event count",
                ignoreCase = true,
            ),
        )
    }

    @Test
    fun truncatedExportParsesButTraceParserRejectsItForProof() {
        val export =
            RepackedRuntimeNativeTraceExportProtocol.parse(
                exportBytes(
                    trace =
                        "DLSYM\tlibsample.so\tResolveTarget\t0x70020100\n",
                    eventCount = 1,
                    truncated = true,
                ),
            )
        assertTrue(export.truncated)

        val capture =
            RepackedRuntimeNativeTraceExportProtocol.toCapture(
                export = export,
                artifactSha256 = SHA,
                source =
                    RuntimeNativeTraceSource.REPACKED_TEST_RUNTIME,
            )
        val parsed =
            RuntimeNativeTraceParser.parse(capture)
        assertTrue(parsed.events.isEmpty())
        assertTrue(
            parsed.blockers.any {
                "bounded capture limit" in it
            },
        )
    }

    private fun exportBytes(
        trace: String,
        eventCount: Int,
        truncated: Boolean,
    ): ByteArray {
        val traceBytes =
            trace.toByteArray(Charsets.UTF_8)
        val header = buildString {
            appendLine(
                RepackedRuntimeNativeTraceExportProtocol
                    .HEADER_MAGIC,
            )
            appendLine("packageName=$PACKAGE")
            appendLine("pid=$PID")
            appendLine("processIdentity=$PACKAGE")
            appendLine("sessionId=session-1")
            appendLine("startedAtEpochMs=1000")
            appendLine("stoppedAtEpochMs=2000")
            appendLine("eventCount=$eventCount")
            appendLine(
                "traceSha256=" +
                    sha256(traceBytes),
            )
            appendLine(
                "traceBytes=" + traceBytes.size,
            )
            append("truncated=$truncated")
        }.toByteArray(Charsets.UTF_8)
        return header +
            RepackedRuntimeNativeTraceExportProtocol
                .TRACE_DELIMITER +
            traceBytes
    }

    private fun sha256(
        bytes: ByteArray,
    ): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }

    companion object {
        private const val PID = 456
        private const val PACKAGE = "com.example.target"
        private const val SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
