package io.github.ffenuss.modkit.patch

import java.io.File
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableField
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10t
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21t
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22s
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction23x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction51l
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.writer.pool.DexPool

/**
 * Builds a synthetic DEX that mirrors testgame's ObfuscatedStats:
 * single-letter members, no gameplay names, but the same resource shapes
 * (decrement + write-back, zero threshold check, guarded consumption).
 * Used to prove structural discovery without relying on identifiers.
 *
 *   a ~ health   (d reads it, f damages it, g is the death check)
 *   b ~ stamina  (e reads it, h is the availability gate, i consumes it)
 *   c ~ distance (j reads it, i increments it)
 */
object ObfuscatedFixtureDex {
    const val OWNER = "Ldev/obf/Stats;"

    private val fieldA = ImmutableFieldReference(OWNER, "a", "I")
    private val fieldB = ImmutableFieldReference(OWNER, "b", "I")
    private val fieldC = ImmutableFieldReference(OWNER, "c", "I")

    private val getD = ImmutableMethodReference(OWNER, "d", emptyList(), "I")
    private val checkH = ImmutableMethodReference(OWNER, "h", emptyList(), "Z")
    private val mathMax = ImmutableMethodReference(
        "Ljava/lang/Math;", "max", listOf("I", "I"), "I",
    )

    fun build(): ByteArray {
        val fields = listOf(
            ImmutableField(OWNER, "a", "I", AccessFlags.PRIVATE.value),
            ImmutableField(OWNER, "b", "I", AccessFlags.PRIVATE.value),
            ImmutableField(OWNER, "c", "I", AccessFlags.PRIVATE.value),
        )
        val methods = listOf(
            getter("d", fieldA),
            getter("e", fieldB),
            getter("j", fieldC),
            damage("f", fieldA, getD, 7),
            deathCheck("g", getD),
            gate("h", fieldB),
            guardedConsume("i", fieldB, fieldC, checkH, 5),
        )
        val clazz = ImmutableClassDef(
            OWNER,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptySet(),
            emptyList(),
            fields,
            emptyList(),
            methods,
        )
        val dex = ImmutableDexFile(Opcodes.forApi(28), listOf(clazz))
        val path = File.createTempFile("modkit-obf-fixture", ".dex")
        try {
            DexPool.writeTo(path.absolutePath, dex)
            return path.readBytes()
        } finally {
            path.delete()
        }
    }

    private fun getter(
        name: String,
        field: org.jf.dexlib2.iface.reference.FieldReference,
    ) = ImmutableMethod(
        OWNER, name, emptyList(), "I",
        AccessFlags.PUBLIC.value, emptySet(), emptySet(),
        ImmutableMethodImplementation(
            2,
            listOf(
                ImmutableInstruction22c(Opcode.IGET, 0, 1, field),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ),
            emptyList(), emptyList(),
        ),
    )

    private fun damage(
        name: String,
        field: org.jf.dexlib2.iface.reference.FieldReference,
        getterRef: ImmutableMethodReference,
        amount: Int,
    ) = ImmutableMethod(
        OWNER, name, emptyList(), "V",
        AccessFlags.PUBLIC.value, emptySet(), emptySet(),
        ImmutableMethodImplementation(
            3,
            listOf(
                ImmutableInstruction51l(Opcode.INVOKE_VIRTUAL, 0, 2, 3, getterRef),
                ImmutableInstruction11n(Opcode.CONST_4, 1, amount),
                ImmutableInstruction23x(Opcode.ISUB_INT, 0, 0, 1),
                ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                ImmutableInstruction51l(Opcode.INVOKE_STATIC, 0, 0, 2, mathMax),
                ImmutableInstruction22c(Opcode.IPUT, 0, 2, field),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
            emptyList(), emptyList(),
        ),
    )

    private fun deathCheck(name: String, getterRef: ImmutableMethodReference) =
        ImmutableMethod(
            OWNER, name, emptyList(), "Z",
            AccessFlags.PUBLIC.value, emptySet(), emptySet(),
            ImmutableMethodImplementation(
                3,
                listOf(
                    ImmutableInstruction51l(Opcode.INVOKE_VIRTUAL, 0, 2, 3, getterRef),
                    ImmutableInstruction21t(Opcode.IF_GTZ, 0, 6),
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                    ImmutableInstruction10t(Opcode.GOTO, 4),
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ),
                emptyList(), emptyList(),
            ),
        )

    private fun gate(
        name: String,
        field: org.jf.dexlib2.iface.reference.FieldReference,
    ) = ImmutableMethod(
        OWNER, name, emptyList(), "Z",
        AccessFlags.PUBLIC.value, emptySet(), emptySet(),
        ImmutableMethodImplementation(
            3,
            listOf(
                ImmutableInstruction22c(Opcode.IGET, 0, 2, field),
                ImmutableInstruction21t(Opcode.IF_LEZ, 0, 6),
                ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                ImmutableInstruction10t(Opcode.GOTO, 4),
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            ),
            emptyList(), emptyList(),
        ),
    )

    private fun guardedConsume(
        name: String,
        resource: org.jf.dexlib2.iface.reference.FieldReference,
        counter: org.jf.dexlib2.iface.reference.FieldReference,
        gateRef: ImmutableMethodReference,
        amount: Int,
    ) = ImmutableMethod(
        OWNER, name, emptyList(), "V",
        AccessFlags.PUBLIC.value, emptySet(), emptySet(),
        ImmutableMethodImplementation(
            4,
            listOf(
                ImmutableInstruction51l(Opcode.INVOKE_VIRTUAL, 0, 3, 4, gateRef),
                ImmutableInstruction21t(Opcode.IF_EQZ, 0, 20),
                ImmutableInstruction22c(Opcode.IGET, 0, 3, counter),
                ImmutableInstruction22s(Opcode.IADD_INT_LIT8, 0, 0, 1),
                ImmutableInstruction22c(Opcode.IPUT, 0, 3, counter),
                ImmutableInstruction22c(Opcode.IGET, 0, 3, resource),
                ImmutableInstruction11n(Opcode.CONST_4, 1, amount),
                ImmutableInstruction23x(Opcode.ISUB_INT, 0, 0, 1),
                ImmutableInstruction11n(Opcode.CONST_4, 1, 0),
                ImmutableInstruction51l(Opcode.INVOKE_STATIC, 0, 0, 2, mathMax),
                ImmutableInstruction22c(Opcode.IPUT, 0, 3, resource),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            ),
            emptyList(), emptyList(),
        ),
    )
}
