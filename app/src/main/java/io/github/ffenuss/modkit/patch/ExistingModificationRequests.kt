package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.modification.*

/** Normalization only: a request does not imply an installed Space/root interceptor. */
object ExistingModificationRequests {
    fun dexKind(signature: String): ScalarKind? = when (signature.substringAfter(')', "")) {
        "Z" -> ScalarKind.BOOLEAN
        "B" -> ScalarKind.INT8
        "S" -> ScalarKind.INT16
        "C" -> ScalarKind.UINT16
        "I" -> ScalarKind.INT32
        "J" -> ScalarKind.INT64
        "F" -> ScalarKind.FLOAT32
        "D" -> ScalarKind.FLOAT64
        else -> null
    }

    fun result(id: String, purpose: String, family: CodeFamily, artifactSha256: String,
        locator: String, kind: ScalarKind, literal: String, mode: ScalarRecipeMode): ModificationRequest {
        val scalar = ScalarValue.parse(kind, literal)
        return ModificationRequest(id, purpose,
            ModificationTarget(family, artifactSha256, locator, TargetShape.METHOD, kind),
            InterceptionPoint.RESULT, if (mode == ScalarRecipeMode.MULTIPLIER)
                ValueOperation.Multiply(scalar) else ValueOperation.Replace(scalar))
    }
}
