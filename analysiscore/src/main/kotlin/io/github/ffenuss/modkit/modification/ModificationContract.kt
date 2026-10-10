package io.github.ffenuss.modkit.modification

/** Execution representation, not a game/engine name. */
enum class CodeFamily { DEX, NATIVE, SCRIPT }
enum class InterceptionPoint { RESULT, ARGUMENT, FIELD_READ, FIELD_WRITE }
enum class ScalarKind { BOOLEAN, INT8, INT16, UINT16, INT32, INT64, FLOAT32, FLOAT64 }
enum class TargetShape { METHOD, FIELD }
enum class ExecutionHost { REPACKAGED_APP, SPACE, ROOT }
enum class OperationKind { REPLACE, MULTIPLY, CLAMP }

sealed class ScalarValue(val kind: ScalarKind) {
    data class Logical(val value: Boolean) : ScalarValue(ScalarKind.BOOLEAN)
    data class Integral(val type: ScalarKind, val value: Long) : ScalarValue(type) {
        init {
            val range = when (type) {
                ScalarKind.INT8 -> -128L..127L
                ScalarKind.INT16 -> -32768L..32767L
                ScalarKind.UINT16 -> 0L..65535L
                ScalarKind.INT32 -> Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()
                ScalarKind.INT64 -> Long.MIN_VALUE..Long.MAX_VALUE
                else -> error("Integral value requires an integer kind")
            }
            require(value in range) { "Value does not fit $type" }
        }
    }
    data class Single(val value: Float) : ScalarValue(ScalarKind.FLOAT32)
    data class DoublePrecision(val value: Double) : ScalarValue(ScalarKind.FLOAT64)

    companion object {
        fun parse(kind: ScalarKind, text: String): ScalarValue = when (kind) {
            ScalarKind.BOOLEAN -> Logical(when (text) {
                "true", "1" -> true
                "false", "0" -> false
                else -> error("Invalid Boolean literal")
            })
            ScalarKind.FLOAT32 -> Single(text.toFloat().also { require(it.isFinite()) })
            ScalarKind.FLOAT64 -> DoublePrecision(text.toDouble().also { require(it.isFinite()) })
            else -> Integral(kind, text.toLong())
        }
    }
}

/** Parameters are typed too: never narrow a long through Double or reinterpret Boolean as int. */
sealed class ValueOperation(val operationKind: OperationKind) {
    abstract val valueKind: ScalarKind
    abstract fun apply(original: ScalarValue): ScalarValue
    protected fun check(original: ScalarValue) = require(original.kind == valueKind) { "Scalar kind mismatch" }

    data class Replace(val replacement: ScalarValue) : ValueOperation(OperationKind.REPLACE) {
        override val valueKind = replacement.kind
        override fun apply(original: ScalarValue): ScalarValue { check(original); return replacement }
    }
    data class Multiply(val coefficient: ScalarValue) : ValueOperation(OperationKind.MULTIPLY) {
        init {
            require(coefficient !is ScalarValue.Logical)
            if (coefficient is ScalarValue.Single) require(coefficient.value.isFinite())
            if (coefficient is ScalarValue.DoublePrecision) require(coefficient.value.isFinite())
        }
        override val valueKind = coefficient.kind
        override fun apply(original: ScalarValue): ScalarValue {
            check(original)
            return when (original) {
                is ScalarValue.Integral -> ScalarValue.Integral(original.kind,
                    Math.multiplyExact(original.value, (coefficient as ScalarValue.Integral).value))
                is ScalarValue.Single -> ScalarValue.Single(original.value * (coefficient as ScalarValue.Single).value)
                is ScalarValue.DoublePrecision -> ScalarValue.DoublePrecision(original.value * (coefficient as ScalarValue.DoublePrecision).value)
                is ScalarValue.Logical -> error("Boolean multiplication is unsupported")
            }
        }
    }
    data class Clamp(val minimum: ScalarValue, val maximum: ScalarValue) : ValueOperation(OperationKind.CLAMP) {
        override val valueKind = minimum.kind
        init {
            require(minimum.kind == maximum.kind)
            when (minimum) {
                is ScalarValue.Integral -> require(minimum.value <= (maximum as ScalarValue.Integral).value)
                is ScalarValue.Single -> require(minimum.value.isFinite() && (maximum as ScalarValue.Single).value.isFinite() && minimum.value <= maximum.value)
                is ScalarValue.DoublePrecision -> require(minimum.value.isFinite() && (maximum as ScalarValue.DoublePrecision).value.isFinite() && minimum.value <= maximum.value)
                is ScalarValue.Logical -> error("Boolean clamp is unsupported")
            }
        }
        override fun apply(original: ScalarValue): ScalarValue {
            check(original)
            return when (original) {
                is ScalarValue.Integral -> ScalarValue.Integral(original.kind, original.value.coerceIn(
                    (minimum as ScalarValue.Integral).value, (maximum as ScalarValue.Integral).value))
                is ScalarValue.Single -> ScalarValue.Single(original.value.coerceIn((minimum as ScalarValue.Single).value, (maximum as ScalarValue.Single).value))
                is ScalarValue.DoublePrecision -> ScalarValue.DoublePrecision(original.value.coerceIn((minimum as ScalarValue.DoublePrecision).value, (maximum as ScalarValue.DoublePrecision).value))
                is ScalarValue.Logical -> error("Boolean clamp is unsupported")
            }
        }
    }
}

data class ModificationTarget(
    val family: CodeFamily,
    val artifactSha256: String,
    val locator: String,
    val shape: TargetShape,
    val valueKind: ScalarKind,
) {
    init {
        require(artifactSha256.matches(Regex("[0-9a-fA-F]{64}")))
        require(locator.isNotBlank() && locator.length <= 4096)
    }
}

data class ModificationRequest(
    val id: String,
    val purpose: String,
    val target: ModificationTarget,
    val point: InterceptionPoint,
    val operation: ValueOperation,
    val argumentIndex: Int? = null,
) {
    init {
        require(id.isNotBlank() && purpose.isNotBlank())
        require(operation.valueKind == target.valueKind)
        require((point == InterceptionPoint.ARGUMENT) == (argumentIndex != null))
        require(argumentIndex == null || argumentIndex >= 0)
        require(target.shape == if (point in setOf(InterceptionPoint.RESULT, InterceptionPoint.ARGUMENT)) TargetShape.METHOD else TargetShape.FIELD)
    }
}

/** Finding a symbol is not proof that an interception backend can execute this request. */
data class BindingEvidence(
    val exactTarget: Boolean = false,
    val typeVerified: Boolean = false,
    val originalVerified: Boolean = false,
    val behaviorPreserved: Boolean = false,
    val restorationPrepared: Boolean = false,
    val localAuthority: Boolean = false,
)

data class AdapterCapabilities(
    val id: String,
    val family: CodeFamily,
    val host: ExecutionHost,
    val points: Set<InterceptionPoint>,
    val operations: Set<OperationKind>,
    val kinds: Set<ScalarKind>,
)

sealed class AdapterPreparation {
    data class Prepared(val adapterId: String, val request: ModificationRequest, val payloadId: String) : AdapterPreparation()
    data class Blocked(val adapterId: String, val reasons: List<String>) : AdapterPreparation()
}

interface ModificationAdapter {
    val capabilities: AdapterCapabilities
    /** Actual code lowering/verification must happen here, not in the common capability planner. */
    fun prepare(request: ModificationRequest): AdapterPreparation
}

object ModificationPlanner {
    fun blockers(request: ModificationRequest, host: ExecutionHost, adapter: AdapterCapabilities,
        evidence: BindingEvidence): List<String> = buildList {
        if (adapter.host != host) add("Adapter does not execute in $host")
        if (request.target.family != adapter.family) add("Code family mismatch")
        if (request.point !in adapter.points) add("Interception point is unsupported")
        if (request.operation.operationKind !in adapter.operations) add("Operation is unsupported")
        if (request.target.valueKind !in adapter.kinds) add("Scalar kind is unsupported")
        if (!evidence.exactTarget) add("Exact binding is missing")
        if (!evidence.typeVerified) add("Scalar type is unverified")
        if (!evidence.originalVerified) add("Original code/data is unverified")
        if (!evidence.behaviorPreserved) add("Original behavior preservation is unverified")
        if (!evidence.restorationPrepared) add("Restoration is unprepared")
        if (!evidence.localAuthority) add("Local authority is unverified")
    }
}

/** One evaluation path for all four interception points. A backend supplies the actual typed value.
 * This dispatcher neither installs a hook nor claims a game-specific effect. */
class ModificationDispatcher(private val request: ModificationRequest) {
    data class Outcome(val value: ScalarValue, val changed: Boolean, val failure: String? = null)
    private var enabled = false
    private var closed = false

    @Synchronized fun setEnabled(value: Boolean): Boolean {
        if (closed) return false
        enabled = value
        return true
    }
    @Synchronized fun close() { enabled = false; closed = true }
    @Synchronized fun transform(target: ModificationTarget, point: InterceptionPoint,
        original: ScalarValue, argumentIndex: Int? = null): Outcome {
        if (closed || !enabled || target != request.target || point != request.point || argumentIndex != request.argumentIndex)
            return Outcome(original, false)
        return try {
            val changed = request.operation.apply(original)
            Outcome(changed, changed != original)
        } catch (failure: IllegalArgumentException) {
            Outcome(original, false, failure.message ?: "Invalid transformation")
        } catch (failure: ArithmeticException) {
            Outcome(original, false, failure.message ?: "Arithmetic overflow")
        }
    }
}
