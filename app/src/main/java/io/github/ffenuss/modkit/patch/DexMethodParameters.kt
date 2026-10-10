package io.github.ffenuss.modkit.patch

import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.iface.Method

/** Primitive DEX inputs occupy the trailing register words of each method frame. */
internal object DexMethodParameters {
    private val names = mapOf("Z" to "boolean", "B" to "byte", "C" to "char", "S" to "short",
        "I" to "int", "J" to "long", "F" to "float", "D" to "double")
    fun supported(types: List<CharSequence>) = types.size <= 8 && types.all { it.toString() in names }
    fun words(types: List<CharSequence>) = types.sumOf { if (DexScalarReplacement.isWide(it.toString())) 2 else 1 }
    fun inputWords(method: Method) = words(method.parameterTypes) + if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
    fun thisRegister(method: Method, registers: Int) = registers - inputWords(method)
    fun signature(method: Method) = "(" + method.parameterTypes.joinToString("") + ")" + method.returnType
    fun label(method: DexLocalOpportunity): String {
        val parameters = method.signature.substringAfter('(').substringBefore(')')
        return if (parameters.isEmpty() && method.signature.substringAfter(')') !in setOf("B", "S", "C")) "" else " · ${method.methodName}(" +
            parameters.map { names[it.toString()] ?: it.toString() }.joinToString(", ") + ")"
    }
}
