package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.File
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableField
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22b
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22c
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The names of every executable method are obfuscated. An exact balance
 * identifier is deliberately only a secondary signal: two distinct write-back
 * operations must also exist before we can call the property local currency.
 */
class DexCurrencyDiscoveryTest {
    private val signal = object : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }
    private val owner = "Ldev/fixture/Economy;"

    @Test
    fun bidirectionalCoinBalanceOffersOneProvenGetterAndRewritesIt() {
        val bytes = fixtureDex("coins")
        val scan = DexLocalPatchEngine.scanDex(bytes, 0, "classes.dex", false, signal)
        assertEquals(0, scan.semanticNamesMatched)
        assertEquals(1, scan.structuralMechanicsConfirmed)
        assertEquals(1, scan.structuralCandidates)
        val candidate = scan.opportunities.single()
        assertEquals("a", candidate.methodName)
        assertEquals(owner + "->coins:I", candidate.fieldIdentity)
        assertEquals(DexLocalCategory.CURRENCY, candidate.category)
        assertEquals(DexLocalAction.INT_9999, candidate.action)
        assertEquals(MechanicConfidence.CONFIRMED, candidate.confidence)
        assertTrue(candidate.matchedByStructure)
        assertTrue(candidate.selectable)

        val output = File.createTempFile("modkit-local-coins", ".dex")
        try {
            val result = DexLocalPatchEngine.rewriteDex(
                bytes, 0, "classes.dex", listOf(candidate), output, false, signal,
            )
            assertEquals(setOf(candidate.id), result.appliedIds)
            val method = DexBackedDexFile(Opcodes.getDefault(), output.readBytes())
                .classes.single().methods.first { it.name == "a" }
            val ops = method.implementation!!.instructions.toList()
            assertEquals(Opcode.CONST, ops.first().opcode)
            assertEquals(9999, (ops.first() as NarrowLiteralInstruction).narrowLiteral)
            assertEquals(Opcode.RETURN, ops[1].opcode)
        } finally {
            output.delete()
        }
    }

    @Test
    fun sameMutationShapesWithoutExactCurrencyNameStayProbable() {
        val scan = DexLocalPatchEngine.scanDex(fixtureDex("count"), 0, "classes.dex", false, signal)
        assertEquals(0, scan.structuralMechanicsConfirmed)
        assertEquals(0, scan.structuralCandidates)
        assertTrue(scan.opportunities.none { it.selectable })
        assertTrue(scan.mechanicHypotheses.any {
            it.contains("PROBABLE") && it.contains("count:I")
        })
        assertTrue(scan.mechanicHypotheses.none { it.contains("HEALTH") && it.contains("CONFIRMED") })
    }

    @Test
    fun incrementAloneIsNotProofOfCurrency() {
        val scan = DexLocalPatchEngine.scanDex(
            fixtureDex("coins", includeSpend = false), 0, "classes.dex", false, signal,
        )
        assertEquals(0, scan.structuralMechanicsConfirmed)
        assertEquals(0, scan.structuralCandidates)
        assertTrue(scan.opportunities.isEmpty())
    }

    private fun fixtureDex(property: String, includeSpend: Boolean = true): ByteArray {
        val field = ImmutableFieldReference(owner, property, "I")
        fun body(ops: List<org.jf.dexlib2.iface.instruction.Instruction>) =
            ImmutableMethodImplementation(3, ops, emptyList(), emptyList())
        val getter = ImmutableMethod(
            owner, "a", emptyList(), "I", AccessFlags.PUBLIC.value,
            emptySet(), emptySet(),
            body(listOf(
                ImmutableInstruction22c(Opcode.IGET, 0, 2, field),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            )),
        )
        fun change(name: String, delta: Int) = ImmutableMethod(
            owner, name, emptyList(), "V", AccessFlags.PUBLIC.value,
            emptySet(), emptySet(),
            body(listOf(
                ImmutableInstruction22c(Opcode.IGET, 0, 2, field),
                ImmutableInstruction22b(Opcode.ADD_INT_LIT8, 0, 0, delta),
                ImmutableInstruction22c(Opcode.IPUT, 0, 2, field),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )),
        )
        val methods = listOfNotNull(getter, change("b", 5), if (includeSpend) change("c", -10) else null)
        val clazz = ImmutableClassDef(
            owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
            emptyList(), null, emptySet(), emptyList(),
            listOf(ImmutableField(owner, property, "I", AccessFlags.PRIVATE.value, null, emptySet(), emptySet())),
            emptyList(), methods,
        )
        val dex = ImmutableDexFile(Opcodes.forApi(28), listOf(clazz))
        val file = File.createTempFile("modkit-currency-fixture", ".dex")
        try {
            DexPool.writeTo(file.absolutePath, dex)
            return file.readBytes()
        } finally {
            file.delete()
        }
    }
}
