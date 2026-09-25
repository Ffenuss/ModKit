package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.immutable.*
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Test

class DexCoverageRegressionTest {
    private val active = object : CancellationSignal {
        override fun isCancelled() = false
    }

    @Test fun doesNotStopAnalysisAtTheOldDisplayCap() {
        val scan = DexLocalPatchEngine.scanDex(fixture(320), 0, "classes.dex", false, active)
        assertEquals(320, scan.methodsExamined)
        assertEquals(320, scan.opportunities.size)
    }

    @Test fun visitsLaterDexEntriesAfterMoreThan256Candidates() {
        val apk = File.createTempFile("coverage-", ".apk")
        try {
            ZipOutputStream(apk.outputStream()).use { zip ->
                for (name in listOf("classes.dex", "classes2.dex")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(fixture(320))
                    zip.closeEntry()
                }
            }
            val scan = DexLocalPatchEngine.scanApks(listOf(apk), false, active)
            assertEquals(2, scan.dexFilesExamined)
            assertEquals(640, scan.methodsExamined)
            assertEquals(640, scan.opportunities.size)
        } finally { apk.delete() }
    }

    companion object {
        fun fixture(count: Int): ByteArray {
            val classes = (0 until count).map { index ->
                val owner = "Ldev/game/Player" + index.toString().padStart(4, '0') + ";"
                val method = ImmutableMethod(owner, "getHealth", emptyList(), "I",
                    AccessFlags.PUBLIC.value, emptySet(), emptySet(),
                    ImmutableMethodImplementation(1, listOf(
                        ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                        ImmutableInstruction11x(Opcode.RETURN, 0),
                    ), emptyList(), emptyList()))
                ImmutableClassDef(owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
                    emptyList(), null, emptySet(), emptyList(), listOf(method))
            }
            val dex = File.createTempFile("coverage-fixture-", ".dex")
            return try {
                DexPool.writeTo(dex.absolutePath, ImmutableDexFile(Opcodes.getDefault(), classes))
                dex.readBytes()
            } finally { dex.delete() }
        }
    }
}
