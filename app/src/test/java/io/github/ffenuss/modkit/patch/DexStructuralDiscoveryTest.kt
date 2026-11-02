package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.CancellationSignal
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.NarrowLiteralInstruction
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end structural discovery through the production scanDex pipeline:
 * obfuscated mechanics are found, classified, exposed as opportunities and
 * rewritten by the existing engine without duplicating name-based hits.
 */
class DexStructuralDiscoveryTest {
    private val signal = object : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }
    private val owner = ObfuscatedFixtureDex.OWNER

    @Test
    fun scanDexFindsObfuscatedHealthWithoutAnyNameSignal() {
        val bytes = ObfuscatedFixtureDex.build()
        val scan = DexLocalPatchEngine.scanDex(bytes, 0, "classes.dex", false, signal)

        // No name matched at all: discovery is purely structural.
        assertEquals(0, scan.semanticNamesMatched)
        assertEquals(1, scan.structuralMechanicsConfirmed)
        assertEquals(1, scan.structuralCandidates)

        val opportunity = scan.opportunities.single()
        assertEquals(owner, opportunity.className)
        assertEquals("d", opportunity.methodName)
        assertEquals(DexLocalCategory.HEALTH, opportunity.category)
        assertEquals(DexLocalAction.INT_9999, opportunity.action)
        assertTrue(opportunity.matchedByStructure)
        assertEquals(MechanicConfidence.CONFIRMED, opportunity.confidence)
        assertTrue(opportunity.selectable)
        assertEquals(owner + "->a:I", opportunity.fieldIdentity)
        assertTrue(opportunity.reason.contains("Структурное подтверждение"))
        assertTrue(scan.explanation.contains("Структурный анализ подтвердил"))
    }

    @Test
    fun hypothesesAreReportedForProbableMechanicsToo() {
        val scan = DexLocalPatchEngine.scanDex(
            ObfuscatedFixtureDex.build(), 0, "classes.dex", false, signal,
        )
        // CONFIRMED health + PROBABLE stamina + PROBABLE distance.
        assertEquals(3, scan.mechanicHypotheses.size)
        assertTrue(scan.mechanicHypotheses.any { it.contains("Здоровье") && it.contains("CONFIRMED") })
        assertTrue(scan.mechanicHypotheses.any { it.contains("PROBABLE") })
    }

    @Test
    fun structuralAndNameBasedHitsAreNeverDuplicated() {
        // A DEX where the same getter is found both by name and by structure.
        val bytes = namedHealthDex()
        val scan = DexLocalPatchEngine.scanDex(bytes, 0, "classes.dex", false, signal)
        val getHealth = scan.opportunities.filter { it.methodName == "getHealth" }
        assertEquals(1, getHealth.size)
        // The name path is the primary signal; structure must not shadow it.
        assertTrue(!getHealth.single().matchedByStructure)
        assertTrue(scan.structuralMechanicsConfirmed >= 1)
    }

    @Test
    fun structuralCandidateIsRewrittenByTheExistingEngine() {
        val bytes = ObfuscatedFixtureDex.build()
        val scan = DexLocalPatchEngine.scanDex(bytes, 0, "classes.dex", false, signal)
        val opportunity = scan.opportunities.single()

        val output = File.createTempFile("modkit-structural-rewrite", ".dex")
        try {
            val rewrite = DexLocalPatchEngine.rewriteDex(
                bytes, 0, "classes.dex", listOf(opportunity), output, false, signal,
            )
            assertEquals(setOf(opportunity.id), rewrite.appliedIds)
            val parsed = DexBackedDexFile(Opcodes.getDefault(), output.readBytes())
            val method = parsed.classes.single().methods.first { it.name == "d" }
            val instructions = method.implementation!!.instructions.toList()
            assertEquals(Opcode.CONST, instructions[0].opcode)
            assertEquals(
                9999,
                (instructions[0] as NarrowLiteralInstruction).narrowLiteral,
            )
            assertEquals(Opcode.RETURN, instructions[1].opcode)
        } finally {
            output.delete()
        }
    }

    /** Named health fixture: getHealth + hit + isDead, same shapes as the obfuscated one. */
    private fun namedHealthDex(): ByteArray {
        val owner = "Ldev/named/Player;"
        val field = org.jf.dexlib2.immutable.reference.ImmutableFieldReference(owner, "health", "I")
        val getHealth = org.jf.dexlib2.immutable.reference.ImmutableMethodReference(
            owner, "getHealth", emptyList(), "I",
        )
        val mathMax = org.jf.dexlib2.immutable.reference.ImmutableMethodReference(
            "Ljava/lang/Math;", "max", listOf("I", "I"), "I",
        )
        val getter = org.jf.dexlib2.immutable.ImmutableMethod(
            owner, "getHealth", emptyList(), "I",
            org.jf.dexlib2.AccessFlags.PUBLIC.value, emptySet(), emptySet(),
            org.jf.dexlib2.immutable.ImmutableMethodImplementation(
                2,
                listOf(
                    org.jf.dexlib2.immutable.instruction.ImmutableInstruction22c(
                        org.jf.dexlib2.Opcode.IGET, 0, 1, field,
                    ),
                    org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x(
                        org.jf.dexlib2.Opcode.RETURN, 0,
                    ),
                ),
                emptyList(), emptyList(),
            ),
        )
        val hit = org.jf.dexlib2.immutable.ImmutableMethod(
            owner, "hit", emptyList(), "V",
            org.jf.dexlib2.AccessFlags.PUBLIC.value, emptySet(), emptySet(),
            org.jf.dexlib2.immutable.ImmutableMethodImplementation(
                3,
                listOf(
                    org.jf.dexlib2.immutable.instruction.ImmutableInstruction51l(
                        org.jf.dexlib2.Opcode.INVOKE_VIRTUAL, 0, 2, 3, getHealth,
                    ),
                    org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n(
                        org.jf.dexlib2.Opcode.CONST_4, 1, 7,
                    ),
                    org.jf.dexlib2.immutable.instruction.ImmutableInstruction23x(
                        org.jf.dexlib2.Opcode.ISUB_INT, 0, 0, 1,
                    ),
                    org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n(
                        org.jf.dexlib2.Opcode.CONST_4, 1, 0,
                    ),
                    org.jf.dexlib2.immutable.instruction.ImmutableInstruction51l(
                        org.jf.dexlib2.Opcode.INVOKE_STATIC, 0, 0, 2, mathMax,
                    ),
                    org.jf.dexlib2.immutable.instruction.ImmutableInstruction22c(
                        org.jf.dexlib2.Opcode.IPUT, 0, 2, field,
                    ),
                    org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x(
                        org.jf.dexlib2.Opcode.RETURN_VOID,
                    ),
                ),
                emptyList(), emptyList(),
            ),
        )
        val isDead = org.jf.dexlib2.immutable.ImmutableMethod(
            owner, "isDead", emptyList(), "Z",
            org.jf.dexlib2.AccessFlags.PUBLIC.value, emptySet(), emptySet(),
            org.jf.dexlib2.immutable.ImmutableMethodImplementation(
                3,
                listOf(
                    org.jf.dexlib2.immutable.instruction.ImmutableInstruction51l(
                        org.jf.dexlib2.Opcode.INVOKE_VIRTUAL, 0, 2, 3, getHealth,
                    ),
                    org.jf.dexlib2.immutable.instruction.ImmutableInstruction21t(
                        org.jf.dexlib2.Opcode.IF_GTZ, 0, 6,
                    ),
                    org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n(
                        org.jf.dexlib2.Opcode.CONST_4, 0, 1,
                    ),
                    org.jf.dexlib2.immutable.instruction.ImmutableInstruction10t(
                        org.jf.dexlib2.Opcode.GOTO, 4,
                    ),
                    org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n(
                        org.jf.dexlib2.Opcode.CONST_4, 0, 0,
                    ),
                    org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x(
                        org.jf.dexlib2.Opcode.RETURN, 0,
                    ),
                ),
                emptyList(), emptyList(),
            ),
        )
        val clazz = org.jf.dexlib2.immutable.ImmutableClassDef(
            owner,
            org.jf.dexlib2.AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            null,
            emptySet(),
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(getter, hit, isDead),
        )
        val dex = org.jf.dexlib2.immutable.ImmutableDexFile(
            org.jf.dexlib2.Opcodes.forApi(28), listOf(clazz),
        )
        val path = File.createTempFile("modkit-named-health", ".dex")
        try {
            org.jf.dexlib2.writer.pool.DexPool.writeTo(path.absolutePath, dex)
            return path.readBytes()
        } finally {
            path.delete()
        }
    }
}
