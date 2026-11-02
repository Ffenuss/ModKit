package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.CancellationSignal
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves that resource shapes are recovered from code structure alone,
 * without any identifier: the fixture uses single-letter names only.
 */
class DexBehavioralAnalyzerTest {
    private val signal = object : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }

    private fun analyze() = DexBehavioralAnalyzer.analyze(
        DexBackedDexFile(Opcodes.getDefault(), ObfuscatedFixtureDex.build()), signal,
    )

    @Test
    fun recoversProvenGettersByDataflowNotByName() {
        val model = analyze()
        val owner = ObfuscatedFixtureDex.OWNER
        assertEquals(
            setOf(
                owner + "->d()I" to owner + "->a:I",
                owner + "->e()I" to owner + "->b:I",
                owner + "->j()I" to owner + "->c:I",
            ),
            model.provenGetters.toSet(),
        )
    }

    @Test
    fun recognizesDamageWriteBackThroughProvenGetterAndClamp() {
        val model = analyze()
        val owner = ObfuscatedFixtureDex.OWNER
        val damage = model.patterns.filter {
            it.type == DexBehavioralPatternType.DAMAGE_WRITE_BACK
        }
        assertEquals(
            setOf(owner + "->a:I" to owner + "->f()V", owner + "->b:I" to owner + "->i()V"),
            damage.map { it.field to it.method }.toSet(),
        )
    }

    @Test
    fun distinguishesDeathCheckFromAvailabilityGateByThresholdDirection() {
        val model = analyze()
        val owner = ObfuscatedFixtureDex.OWNER
        val checks = model.patterns.filter {
            it.type == DexBehavioralPatternType.ZERO_THRESHOLD_CHECK
        }
        val death = checks.single { it.method == owner + "->g()Z" }
        assertEquals(owner + "->a:I", death.field)
        assertEquals(DexLocalAction.FALSE, death.thresholdAction)
        val gate = checks.single { it.method == owner + "->h()Z" }
        assertEquals(owner + "->b:I", gate.field)
        assertEquals(DexLocalAction.TRUE, gate.thresholdAction)
    }

    @Test
    fun recognizesGuardedCounterIncrementAndDoesNotInventPatterns() {
        val model = analyze()
        val owner = ObfuscatedFixtureDex.OWNER
        val increments = model.patterns.filter {
            it.type == DexBehavioralPatternType.CLAMPED_INCREMENT
        }
        assertEquals(
            setOf(owner + "->c:I" to owner + "->i()V"),
            increments.map { it.field to it.method }.toSet(),
        )
        // The gate is external (a call), so no same-method guarded
        // consumption is claimed; no pattern may reference a foreign field.
        assertTrue(
            model.patterns.none {
                it.type == DexBehavioralPatternType.GUARDED_CONSUMPTION
            },
        )
        model.patterns.forEach {
            assertTrue(it.field.startsWith(owner + "->"))
        }
    }

    @Test
    fun tracksFieldReadersAndWritersAcrossMethods() {
        val model = analyze()
        val owner = ObfuscatedFixtureDex.OWNER
        assertEquals(
            setOf(owner + "->d()I"),
            model.fieldReaders[owner + "->a:I"],
        )
        assertEquals(
            setOf(owner + "->f()V"),
            model.fieldWriters[owner + "->a:I"],
        )
        assertEquals(
            setOf(owner + "->h()Z", owner + "->i()V"),
            model.fieldReaders[owner + "->b:I"],
        )
        assertFalse(model.fieldWriters.containsKey(owner + "->d()I"))
    }

    @Test
    fun recordsCallRelationsForInterMethodDependency() {
        val model = analyze()
        val owner = ObfuscatedFixtureDex.OWNER
        assertTrue(model.calls[owner + "->f()V"]!!.contains(owner + "->d()I"))
        assertTrue(model.calls[owner + "->g()Z"]!!.contains(owner + "->d()I"))
        assertTrue(model.calls[owner + "->i()V"]!!.contains(owner + "->h()Z"))
    }
}
