package io.github.ffenuss.modkit.runtime

import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

data class RuntimeProbeNativePayload(
    val abi: String,
    val bytes: ByteArray,
    val sha256: String,
    val machine: Int,
    val is64Bit: Boolean,
)

data class RuntimeProbeNativePayloadValidation(
    val valid: Boolean,
    val machine: Int?,
    val is64Bit: Boolean?,
    val blockers: List<String>,
)

object RuntimeProbeNativePayloadValidator {
    private const val ET_DYN = 3
    private val bridgeMarkers = listOf(
        "RuntimeNativeBridge_nativeResolveLoadedSymbol",
        "RuntimeNativeBridge_nativeStartPassiveDlsymTrace",
        "RuntimeNativeBridge_nativeStopPassiveDlsymTrace",
        "RuntimeNativeBridge_nativeStartPassiveJniTrace",
        "RuntimeNativeBridge_nativeStopPassiveJniTrace",
        "RuntimeNativeBridge_nativePassiveJniOnLoadInvocationReady",
        "RuntimeNativeBridge_nativePatchCode",
    ).map {
        it.toByteArray(Charsets.US_ASCII)
    }

    private data class ExpectedAbi(
        val machine: Int,
        val is64Bit: Boolean,
    )

    private val expected = mapOf(
        "armeabi-v7a" to ExpectedAbi(40, false),
        "arm64-v8a" to ExpectedAbi(183, true),
        "x86" to ExpectedAbi(3, false),
        "x86_64" to ExpectedAbi(62, true),
    )

    fun validate(
        bytes: ByteArray,
        abi: String,
    ): RuntimeProbeNativePayloadValidation {
        val blockers = mutableListOf<String>()
        val expectedAbi = expected[abi]
        if (expectedAbi == null) {
            return RuntimeProbeNativePayloadValidation(
                valid = false,
                machine = null,
                is64Bit = null,
                blockers = listOf(
                    "Unsupported runtime probe ABI: $abi",
                ),
            )
        }
        if (bytes.size < 52) {
            return RuntimeProbeNativePayloadValidation(
                valid = false,
                machine = null,
                is64Bit = null,
                blockers = listOf(
                    "Runtime probe ELF header is truncated.",
                ),
            )
        }
        val magicValid =
            bytes[0] == 0x7f.toByte() &&
                bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() &&
                bytes[3] == 'F'.code.toByte()
        if (!magicValid) {
            blockers += "Runtime probe native payload has invalid ELF magic."
        }

        val elfClass = bytes[4].toInt() and 0xff
        val is64 = when (elfClass) {
            1 -> false
            2 -> true
            else -> null
        }
        if (is64 == null) {
            blockers += "Runtime probe ELF class is unsupported."
        }
        if ((bytes[5].toInt() and 0xff) != 1) {
            blockers += "Runtime probe ELF must be little-endian."
        }

        val type = u16(bytes, 16)
        if (type != ET_DYN) {
            blockers += "Runtime probe native payload is not an ELF shared object."
        }
        val machine = u16(bytes, 18)
        if (
            machine != expectedAbi.machine ||
            is64 != expectedAbi.is64Bit
        ) {
            blockers +=
                "Runtime probe ELF machine/class does not match ABI $abi."
        }
        val missingMarkers =
            bridgeMarkers.count { !contains(bytes, it) }
        if (missingMarkers > 0) {
            blockers +=
                "Runtime probe ELF does not expose all expected JNI bridge markers."
        }

        return RuntimeProbeNativePayloadValidation(
            valid = blockers.isEmpty(),
            machine = machine,
            is64Bit = is64,
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

    private fun u16(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
}

object RuntimeProbeNativePayloadSource {
    const val LIBRARY_NAME = "libmodkit_runtime_probe.so"
    private const val MAX_NATIVE_PAYLOAD_BYTES = 4 * 1024 * 1024
    val supportedAbis = listOf(
        "arm64-v8a",
        "armeabi-v7a",
        "x86",
        "x86_64",
    )

    fun loadAll(
        context: Context,
        cancellation: CancellationSignal,
    ): Map<String, RuntimeProbeNativePayload> =
        supportedAbis.associateWith { abi ->
            load(
                context = context,
                abi = abi,
                cancellation = cancellation,
            )
        }

    fun load(
        context: Context,
        abi: String,
        cancellation: CancellationSignal,
    ): RuntimeProbeNativePayload {
        require(abi in supportedAbis) {
            "Unsupported runtime probe ABI: $abi"
        }
        val path =
            "modkit-runtime-probe-native/$abi/$LIBRARY_NAME"
        val bytes = context.assets.open(path).use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                checkCancelled(cancellation)
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                total += read
                require(total <= MAX_NATIVE_PAYLOAD_BYTES) {
                    "Runtime probe native payload exceeds bounded size."
                }
                output.write(buffer, 0, read)
            }
            output.toByteArray()
        }

        val validation =
            RuntimeProbeNativePayloadValidator.validate(
                bytes = bytes,
                abi = abi,
            )
        require(validation.valid) {
            validation.blockers.firstOrNull()
                ?: "Runtime probe native payload is invalid."
        }
        return RuntimeProbeNativePayload(
            abi = abi,
            bytes = bytes,
            sha256 = sha256(bytes),
            machine = requireNotNull(validation.machine),
            is64Bit = requireNotNull(validation.is64Bit),
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }
}
