package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.CancellationSignal
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the confidence ladder on a fully obfuscated fixture:
 *  - health (decrement + write-back + "depleted" check) is CONFIRMED by
 *    structure alone and yields a patchable getter candidate;
 *  - stamina (decrement + availability gate) stays PROBABLE because a
 *    retry counter is structurally identical;
 *  - the distance counter stays PROBABLE/UNKNOWN;
 *  - nothing is over-claimed: no CONFIRMED hypothesis without proof.
 */
class DexMechanicClassifierTest {
    private val signal = object : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }
    private val owner = ObfuscatedFixtureDex.OWNER

    private fun classification(): DexMechanicClassification {
        val dex = DexBackedDexFile(Opcodes.getDefault(), ObfuscatedFixtureDex.build())
        val model = DexBehavioralAnalyzer.analyze(dex, signal)
        return DexMechanicClassifier.classify(model, dex)
    }

    @Test
    fun confirmsObfuscatedHealthFromStructureAlone() {
        val result = classification()
        val health = result.hypotheses.single { it.field == owner + "->a:I" }
        assertEquals(GameMechanic.HEALTH, health.mechanic)
        assertEquals(MechanicConfidence.CONFIRMED, health.confidence)
        assertTrue(health.isStructuralOnly)
    }

    @Test
    fun keepsObfuscatedStaminaProbableWithoutSemanticAnchor() {
        val result = classification()
        val stamina = result.hypotheses.single { it.field == owner + "->b:I" }
        assertEquals(GameMechanic.STAMINA, stamina.mechanic)
        assertEquals(MechanicConfidence.PROBABLE, stamina.confidence)
        // A probable hypothesis must not produce an executable candidate.
        assertTrue(
            result.candidates.none { it.field == owner + "->b:I" },
        )
    }

    @Test
    fun leavesDistanceCounterAsUnknownProbable() {
        val result = classification()
        val distance = result.hypotheses.single { it.field == owner + "->c:I" }
        assertEquals(GameMechanic.UNKNOWN, distance.mechanic)
        assertEquals(MechanicConfidence.PROBABLE, distance.confidence)
    }

    @Test
    fun emitsOnlyTheProvenHealthGetterAsExecutableCandidate() {
        val result = classification()
        val candidate = result.candidates.single()
        assertEquals(owner + "->d()I", DexBehavioralAnalyzer.methodIdPublic(candidate.method))
        assertEquals(DexLocalCategory.HEALTH, candidate.category)
        assertEquals(DexLocalAction.INT_9999, candidate.action)
        assertEquals(owner + "->a:I", candidate.field)
        assertEquals(MechanicConfidence.CONFIRMED, candidate.confidence)
        // The death check g() calls the getter, so its body is not a proven
        // scalar replacement and it must NOT be offered as a candidate.
        assertTrue(
            result.candidates.none {
                DexBehavioralAnalyzer.methodIdPublic(it.method) == owner + "->g()Z"
            },
        )
    }

    @Test
    fun everyHypothesisCarriesConcreteEvidence() {
        val result = classification()
        assertTrue(result.hypotheses.size == 3)
        result.hypotheses.forEach { hypothesis ->
            assertTrue(
                "Hypothesis must carry evidence: " + hypothesis.field,
                hypothesis.evidence.isNotEmpty(),
            )
            assertTrue(
                "Hypothesis must carry reasons: " + hypothesis.field,
                hypothesis.reasons.isNotEmpty(),
            )
        }
    }
}
