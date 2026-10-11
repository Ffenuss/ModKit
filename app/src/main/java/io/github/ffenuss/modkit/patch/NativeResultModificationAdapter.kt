package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.Il2CppNativeReturnKind
import io.github.ffenuss.modkit.analysis.nativecode.AArch64ReadOnlyBody
import io.github.ffenuss.modkit.modification.*
import java.security.MessageDigest

/** Lowers common RESULT rules to existing ARM64 in-place bytes. It does not install hooks.
 * Binding/index/alias evidence must come from the existing native catalog, not symbol names. */
class NativeResultModificationAdapter(
    private val target: ModificationTarget,
    original: ByteArray,
    private val capacity: Int,
    private val evidence: BindingEvidence,
) : ModificationAdapter {
    private val original = original.copyOf()
    private val payloads = mutableMapOf<String, Pair<ModificationRequest, String>>()
    override val capabilities = AdapterCapabilities("arm64-bounded-result-v1", CodeFamily.NATIVE,
        ExecutionHost.SPACE, setOf(InterceptionPoint.RESULT), setOf(OperationKind.REPLACE, OperationKind.MULTIPLY),
        setOf(ScalarKind.BOOLEAN, ScalarKind.INT32, ScalarKind.FLOAT32, ScalarKind.FLOAT64))

    @Synchronized override fun prepare(request: ModificationRequest): AdapterPreparation {
        if (request.target != target)
            return AdapterPreparation.Blocked(capabilities.id, listOf("Bound target mismatch"))
        val blockers = ModificationPlanner.blockers(request, ExecutionHost.SPACE, capabilities, evidence)
        if (blockers.isNotEmpty()) return AdapterPreparation.Blocked(capabilities.id, blockers)
        return try {
            require(capacity in 4..original.size && capacity % 4 == 0)
            val proof = AArch64ReadOnlyBody.inspect(original)
            require(proof.supported) { proof.reason }
            val kind = when (target.valueKind) {
                ScalarKind.BOOLEAN, ScalarKind.INT32 -> Il2CppNativeReturnKind.INTEGER
                ScalarKind.FLOAT32 -> Il2CppNativeReturnKind.FLOAT32
                ScalarKind.FLOAT64 -> Il2CppNativeReturnKind.FLOAT64
                else -> error("Unsupported ARM64 result width")
            }
            val replacement = when (val operation = request.operation) {
                is ValueOperation.Multiply -> AArch64ResultTransformEncoder.encodeHex(original, kind,
                    literal(operation.coefficient), minOf(capacity, 64))
                is ValueOperation.Replace -> {
                    val prefix = proof.entryLandingPad?.let { word ->
                        (0..3).joinToString(" ") { "%02X".format((word ushr (it * 8)) and 255) } + " "
                    }.orEmpty()
                    prefix + AArch64ScalarReturnEncoder.encodeHex(kind, literal(operation.replacement))
                }
                is ValueOperation.Clamp -> error("Clamp lowering is unsupported")
            }
            val bytes = Il2CppNativeMutationDraftBuilder.parseHex(replacement)
            require(bytes.size <= minOf(capacity, 64) && !bytes.contentEquals(original.copyOf(bytes.size)))
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val payloadId = "${request.id}:$digest"
            payloads[payloadId] = request to replacement
            AdapterPreparation.Prepared(capabilities.id, request, payloadId)
        } catch (failure: IllegalArgumentException) {
            AdapterPreparation.Blocked(capabilities.id, listOf(failure.message ?: "Native lowering rejected"))
        }
    }

    @Synchronized fun replacementHex(prepared: AdapterPreparation.Prepared): String {
        require(prepared.adapterId == capabilities.id)
        val payload = requireNotNull(payloads[prepared.payloadId]) { "Unknown prepared payload" }
        require(payload.first == prepared.request) { "Prepared request changed" }
        return payload.second
    }

    private fun literal(value: ScalarValue): String = when (value) {
        is ScalarValue.Logical -> if (value.value) "1" else "0"
        is ScalarValue.Integral -> value.value.toString()
        is ScalarValue.Single -> value.value.toString()
        is ScalarValue.DoublePrecision -> value.value.toString()
    }
}
