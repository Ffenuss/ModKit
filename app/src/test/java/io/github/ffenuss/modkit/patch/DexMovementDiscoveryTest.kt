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
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction23x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.reference.ImmutableFieldReference
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DexMovementDiscoveryTest {
    private val signal = object : CancellationSignal {
        override fun isCancelled() = false
    }
    private val owner = "Ldev/fixture/Motion;"

    @Test
    fun obfuscatedSpeedGetterMustReachTheDisplacementWriteBack() {
        val input = syntheticDex()
        val scan = DexLocalPatchEngine.scanDex(input, 0, "classes.dex", false, signal)
        assertEquals(0, scan.semanticNamesMatched)
        assertEquals(1, scan.structuralMechanicsConfirmed)
        assertEquals(1, scan.structuralCandidates)
        val opportunity = scan.opportunities.single()
        assertEquals("a", opportunity.methodName)
        assertEquals(DexLocalCategory.MOVEMENT, opportunity.category)
        assertEquals(DexLocalAction.FLOAT_2, opportunity.action)
        assertEquals(MechanicConfidence.CONFIRMED, opportunity.confidence)
        assertTrue(opportunity.matchedByStructure)
        assertTrue(opportunity.selectable)
        assertEquals(owner + "->moveSpeed:F", opportunity.fieldIdentity)

        val output = File.createTempFile("modkit-movement-output", ".dex")
        try {
            val rewrite = DexLocalPatchEngine.rewriteDex(
                input, 0, "classes.dex", listOf(opportunity), output, false, signal,
            )
            assertEquals(setOf(opportunity.id), rewrite.appliedIds)
            val dex = DexBackedDexFile(Opcodes.getDefault(), output.readBytes())
            val body = dex.classes.single().methods.single { it.name == "a" }
                .implementation!!.instructions.toList()
            assertEquals(Opcode.CONST, body.first().opcode)
            assertEquals(2.0f.toBits(), (body.first() as NarrowLiteralInstruction).narrowLiteral)
            assertEquals(Opcode.RETURN, body[1].opcode)
        } finally {
            output.delete()
        }
    }

    @Test
    fun noDisplacementWriteBackMeansNoStructuralMovementRecipe() {
        val scan = DexLocalPatchEngine.scanDex(
            syntheticDex(writePosition = false), 0, "classes.dex", false, signal,
        )
        assertEquals(0, scan.structuralMechanicsConfirmed)
        assertEquals(0, scan.structuralCandidates)
        assertTrue(scan.opportunities.none { it.matchedByStructure })
    }

    @Test
    fun arbitraryFloatScoreMustNotBeConfusedWithTravel() {
        val scan = DexLocalPatchEngine.scanDex(
            syntheticDex(positionName = "score"), 0, "classes.dex", false, signal,
        )
        assertEquals(0, scan.structuralMechanicsConfirmed)
        assertTrue(scan.opportunities.none { it.matchedByStructure })
    }

    @Test
    fun unrelatedSpeedLabelCannotConfirmAChangeToActualDisplacement() {
        val scan = DexLocalPatchEngine.scanDex(
            syntheticDex(speedName = "renderSpeed"), 0, "classes.dex", false, signal,
        )
        assertEquals(0, scan.structuralMechanicsConfirmed)
        assertFalse(scan.opportunities.any { it.matchedByStructure })
    }

    @Test
    fun namedGetterAndStructuralEvidenceMustProduceOneRecipe() {
        val scan = DexLocalPatchEngine.scanDex(
            syntheticDex(getterName = "getMoveSpeed"), 0, "classes.dex", false, signal,
        )
        val all = scan.opportunities.filter {
            it.methodName == "getMoveSpeed" && it.category == DexLocalCategory.MOVEMENT
        }
        assertEquals(1, all.size)
        assertTrue(all.single().matchedByStructure)
        assertEquals(MechanicConfidence.CONFIRMED, all.single().confidence)
    }

    private fun syntheticDex(
        getterName: String = "a",
        speedName: String = "moveSpeed",
        positionName: String = "distance",
        writePosition: Boolean = true,
    ): ByteArray {
        val speed = ImmutableFieldReference(owner, speedName, "F")
        val distance = ImmutableFieldReference(owner, positionName, "F")
        val getRef = ImmutableMethodReference(owner, getterName, emptyList(), "F")
        fun body(ins: List<org.jf.dexlib2.iface.instruction.Instruction>) =
            ImmutableMethodImplementation(3, ins, emptyList(), emptyList())
        val getter = ImmutableMethod(
            owner, getterName, emptyList(), "F", AccessFlags.PUBLIC.value,
            emptySet(), emptySet(),
            body(listOf(
                ImmutableInstruction22c(Opcode.IGET, 0, 2, speed),
                ImmutableInstruction11x(Opcode.RETURN, 0),
            )),
        )
        val stepOps = listOf(
            ImmutableInstruction22c(Opcode.IGET, 0, 2, distance),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 2, 0, 0, 0, 0, getRef),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 1),
            ImmutableInstruction23x(Opcode.ADD_FLOAT, 0, 0, 1),
        ) + (if (writePosition) listOf(
            ImmutableInstruction22c(Opcode.IPUT, 0, 2, distance),
        ) else emptyList()) + listOf(ImmutableInstruction10x(Opcode.RETURN_VOID))
        val step = ImmutableMethod(
            owner, "b", emptyList(), "V", AccessFlags.PUBLIC.value,
            emptySet(), emptySet(), body(stepOps),
        )
        val fields = listOf(speedName, positionName).map {
            ImmutableField(owner, it, "F", AccessFlags.PRIVATE.value,
                null, emptySet(), emptySet())
        }
        val clazz = ImmutableClassDef(
            owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
            emptyList(), null, emptySet(), emptyList(), fields,
            emptyList(), listOf(getter, step),
        )
        val dex = ImmutableDexFile(Opcodes.forApi(28), listOf(clazz))
        val file = File.createTempFile("modkit-speed-fixture", ".dex")
        try {
            DexPool.writeTo(file.absolutePath, dex)
            return file.readBytes()
        } finally {
            file.delete()
        }
    }
}
