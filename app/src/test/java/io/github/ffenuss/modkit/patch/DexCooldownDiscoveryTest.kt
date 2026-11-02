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
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10t
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21t
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22b
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22c
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Proven local countdown + same-field positive predicate; no name-only promotion. */
class DexCooldownDiscoveryTest {
    private val signal = object : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }
    private val owner = "Ldev/fixture/CooldownFixture;"

    @Test
    fun confirmsLocalCooldownAndRewritesOnlyItsPredicate() {
        val bytes = fixtureDex()
        val scan = DexLocalPatchEngine.scanDex(bytes, 0, "classes.dex", false, signal)
        assertEquals(1, scan.structuralMechanicsConfirmed)
        assertEquals(1, scan.structuralCandidates)
        val opportunity = scan.opportunities.single { it.category == DexLocalCategory.COOLDOWN }
        assertEquals("isOnCooldown", opportunity.methodName)
        assertTrue(opportunity.selectable)
        assertTrue(opportunity.matchedByStructure)
        assertEquals(MechanicConfidence.CONFIRMED, opportunity.confidence)
        assertEquals(DexLocalAction.FALSE, opportunity.action)
        assertEquals(owner + "->cooldownTicks:I", opportunity.fieldIdentity)

        val out = File.createTempFile("modkit-cooldown", ".dex")
        try {
            val rewrite = DexLocalPatchEngine.rewriteDex(
                bytes, 0, "classes.dex", listOf(opportunity), out, false, signal,
            )
            assertEquals(setOf(opportunity.id), rewrite.appliedIds)
            val dex = DexBackedDexFile(Opcodes.getDefault(), out.readBytes())
            val method = dex.classes.single().methods.single { it.name == "isOnCooldown" }
            val instructions = method.implementation!!.instructions.toList()
            assertEquals(Opcode.CONST_4, instructions[0].opcode)
            assertEquals(0, (instructions[0] as NarrowLiteralInstruction).narrowLiteral)
            assertEquals(Opcode.RETURN, instructions[1].opcode)
        } finally {
            out.delete()
        }
    }

    @Test
    fun unchangedCounterShapeWithoutCooldownFieldNeverClaimsStructuralCooldown() {
        val scan = DexLocalPatchEngine.scanDex(
            fixtureDex(fieldName = "retryCounter"), 0, "classes.dex", false, signal,
        )
        assertEquals(0, scan.structuralMechanicsConfirmed)
        assertEquals(0, scan.structuralCandidates)
        assertFalse(scan.opportunities.any { it.matchedByStructure && it.category == DexLocalCategory.COOLDOWN })
    }

    @Test
    fun exactCooldownNameWithoutCountdownDoesNotConfirmMechanic() {
        val scan = DexLocalPatchEngine.scanDex(
            fixtureDex(includeTick = false), 0, "classes.dex", false, signal,
        )
        assertEquals(0, scan.structuralMechanicsConfirmed)
        assertEquals(0, scan.structuralCandidates)
        assertFalse(scan.opportunities.any { it.matchedByStructure })
    }

    @Test
    fun invertedPredicateIsNotPatchedAsPositiveOnCooldownGate() {
        val scan = DexLocalPatchEngine.scanDex(
            fixtureDex(predicateJump = Opcode.IF_GTZ), 0, "classes.dex", false, signal,
        )
        assertEquals(0, scan.structuralMechanicsConfirmed)
        assertEquals(0, scan.structuralCandidates)
        assertFalse(scan.opportunities.any { it.matchedByStructure })
    }

    private fun fixtureDex(
        fieldName: String = "cooldownTicks",
        includeTick: Boolean = true,
        predicateJump: Opcode = Opcode.IF_LEZ,
    ): ByteArray {
        val field = ImmutableFieldReference(owner, fieldName, "I")
        val predicate = ImmutableMethod(
            owner, "isOnCooldown", emptyList(), "Z",
            AccessFlags.PUBLIC.value, emptySet(), emptySet(),
            ImmutableMethodImplementation(
                3,
                listOf(
                    ImmutableInstruction22c(Opcode.IGET, 0, 2, field),
                    ImmutableInstruction21t(predicateJump, 0, 4),
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                    ImmutableInstruction10t(Opcode.GOTO, 2),
                    ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                    ImmutableInstruction11x(Opcode.RETURN, 0),
                ),
                emptyList(), emptyList(),
            ),
        )
        val tick = ImmutableMethod(
            owner, "b", emptyList(), "V",
            AccessFlags.PUBLIC.value, emptySet(), emptySet(),
            ImmutableMethodImplementation(
                3,
                listOf(
                    ImmutableInstruction22c(Opcode.IGET, 0, 2, field),
                    ImmutableInstruction22b(Opcode.ADD_INT_LIT8, 0, 0, -1),
                    ImmutableInstruction22c(Opcode.IPUT, 0, 2, field),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ),
                emptyList(), emptyList(),
            ),
        )
        val clazz = ImmutableClassDef(
            owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
            emptyList(), null, emptySet(), emptyList(),
            listOf(ImmutableField(owner, fieldName, "I", AccessFlags.PRIVATE.value,
                null, emptySet(), emptySet())),
            emptyList(), listOfNotNull(predicate, if (includeTick) tick else null),
        )
        val dex = ImmutableDexFile(Opcodes.forApi(28), listOf(clazz))
        val file = File.createTempFile("modkit-cooldown-fixture", ".dex")
        try {
            DexPool.writeTo(file.absolutePath, dex)
            return file.readBytes()
        } finally {
            file.delete()
        }
    }
}
