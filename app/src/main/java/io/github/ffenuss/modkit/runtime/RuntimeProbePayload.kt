package io.github.ffenuss.modkit.runtime

import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.Serializable
import java.security.MessageDigest
import java.util.zip.Adler32

data class RuntimeProbePayload(
    val bytes: ByteArray,
    val sha256: String,
    val dexVersion: String,
    val providerDescriptorPresent: Boolean,
)

data class RuntimeProbePayloadValidation(
    val valid: Boolean,
    val dexVersion: String?,
    val declaredFileSize: Int?,
    val checksumMatches: Boolean,
    val signatureMatches: Boolean,
    val providerDescriptorPresent: Boolean,
    val blockers: List<String>,
)

object RuntimeProbePayloadSource {
    const val ASSET_NAME = "modkit-runtime-probe.dex"
    const val PROVIDER_DESCRIPTOR =
        "Lio/github/ffenuss/modkit/runtimeprobe/RuntimeEvidenceProvider;"
    private const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    fun load(
        context: Context,
        cancellation: CancellationSignal,
    ): RuntimeProbePayload {
        val bytes = context.assets.open(ASSET_NAME).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                checkCancelled(cancellation)
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                require(total <= MAX_PAYLOAD_BYTES) {
                    "Runtime probe DEX exceeds the bounded payload limit."
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }

        val validation = RuntimeProbeDexValidator.validate(bytes)
        require(validation.valid) {
            validation.blockers.firstOrNull()
                ?: "Embedded runtime probe DEX is invalid."
        }
        return RuntimeProbePayload(
            bytes = bytes,
            sha256 = sha256(bytes),
            dexVersion = requireNotNull(validation.dexVersion),
            providerDescriptorPresent =
                validation.providerDescriptorPresent,
        )
    }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * Structural validator for the generated executable probe DEX.
 *
 * Validation checks:
 * - standard DEX magic/version and NUL terminator;
 * - header/file sizes and endian tag;
 * - Adler32 checksum from byte 12 to EOF;
 * - SHA-1 DEX signature from byte 32 to EOF;
 * - exact provider class descriptor embedded in the DEX.
 */
object RuntimeProbeDexValidator {
    private const val HEADER_SIZE = 0x70
    private const val ENDIAN_CONSTANT = 0x12345678L

    fun validate(bytes: ByteArray): RuntimeProbePayloadValidation {
        val blockers = mutableListOf<String>()
        if (bytes.size < HEADER_SIZE) {
            return RuntimeProbePayloadValidation(
                valid = false,
                dexVersion = null,
                declaredFileSize = null,
                checksumMatches = false,
                signatureMatches = false,
                providerDescriptorPresent = false,
                blockers = listOf("Runtime probe DEX header is truncated."),
            )
        }

        val magicValid =
            bytes[0] == 'd'.code.toByte() &&
                bytes[1] == 'e'.code.toByte() &&
                bytes[2] == 'x'.code.toByte() &&
                bytes[3] == '\n'.code.toByte() &&
                bytes[7] == 0.toByte()
        if (!magicValid) {
            blockers += "Runtime probe payload does not have standard DEX magic."
        }

        val version = if (magicValid) {
            String(bytes, 4, 3, Charsets.US_ASCII)
                .takeIf { value -> value.all(Char::isDigit) }
        } else {
            null
        }
        if (version == null) {
            blockers += "Runtime probe DEX version is invalid."
        }

        val declaredFileSize = u32(bytes, 32)
        if (declaredFileSize != bytes.size.toLong()) {
            blockers +=
                "Runtime probe DEX file_size does not match payload length."
        }
        val declaredHeaderSize = u32(bytes, 36)
        if (declaredHeaderSize != HEADER_SIZE.toLong()) {
            blockers += "Runtime probe DEX header_size is invalid."
        }
        val endianTag = u32(bytes, 40)
        if (endianTag != ENDIAN_CONSTANT) {
            blockers += "Runtime probe DEX endian_tag is unsupported."
        }

        val expectedChecksum = u32(bytes, 8)
        val checksum = Adler32().apply {
            update(bytes, 12, bytes.size - 12)
        }.value
        val checksumMatches = checksum == expectedChecksum
        if (!checksumMatches) {
            blockers += "Runtime probe DEX Adler32 checksum mismatch."
        }

        val expectedSignature = bytes.copyOfRange(12, 32)
        val actualSignature = MessageDigest.getInstance("SHA-1")
            .digest(bytes.copyOfRange(32, bytes.size))
        val signatureMatches =
            expectedSignature.contentEquals(actualSignature)
        if (!signatureMatches) {
            blockers += "Runtime probe DEX SHA-1 signature mismatch."
        }

        val descriptor = RuntimeProbePayloadSource.PROVIDER_DESCRIPTOR
            .toByteArray(Charsets.UTF_8)
        val providerDescriptorPresent = contains(bytes, descriptor)
        if (!providerDescriptorPresent) {
            blockers +=
                "Runtime probe provider descriptor is absent from the DEX."
        }

        return RuntimeProbePayloadValidation(
            valid = blockers.isEmpty(),
            dexVersion = version,
            declaredFileSize = declaredFileSize
                .takeIf { it <= Int.MAX_VALUE.toLong() }
                ?.toInt(),
            checksumMatches = checksumMatches,
            signatureMatches = signatureMatches,
            providerDescriptorPresent = providerDescriptorPresent,
            blockers = blockers,
        )
    }

    private fun contains(
        haystack: ByteArray,
        needle: ByteArray,
    ): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        outer@ for (start in 0..haystack.size - needle.size) {
            for (index in needle.indices) {
                if (haystack[start + index] != needle[index]) {
                    continue@outer
                }
            }
            return true
        }
        return false
    }

    private fun u32(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
}
