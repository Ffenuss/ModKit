package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableDexFile
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.ImmutableMethodParameter
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21t
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallSourceCheckDetectorTest {
    private val pm = "Landroid/content/pm/PackageManager;"
    private val string = "Ljava/lang/String;"
    private val owner = "Ldev/fixture/InstallGate;"

    @Test
    fun exactInstallerEqualityThatControlsBranchIsConfirmedWithLiteral() {
        val query = ImmutableMethodReference(
            pm, "getInstallerPackageName", listOf(string), string,
        )
        val equals = ImmutableMethodReference(
            string, "equals", listOf("Ljava/lang/Object;"), "Z",
        )
        val code: List<Instruction> = listOf(
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, query),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
            ImmutableInstruction21c(
                Opcode.CONST_STRING, 3,
                ImmutableStringReference("com.android.vending"),
            ),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 2, 3, 0, 0, 0, equals),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 4),
            ImmutableInstruction21t(Opcode.IF_EQZ, 4, 4),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
            ImmutableInstruction11x(Opcode.RETURN, 0),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0),
        )
        val scan = InstallSourceCheckDetector.scan(dex(code, "Z"), AtomicCancellationSignal())
        assertEquals(1, scan.confirmedCount)
        assertEquals(0, scan.queryOnlyCount)
        val item = scan.evidence.single()
        assertEquals(InstallSourceCheckConfidence.LOCAL_BRANCH_CONFIRMED, item.confidence)
        assertEquals("com.android.vending", item.expectedInstallerLiteral)
        assertEquals("PackageManager.getInstallerPackageName", item.api)
    }

    @Test
    fun installerReadWithoutLocalDecisionStaysQueryOnly() {
        val query = ImmutableMethodReference(
            pm, "getInstallerPackageName", listOf(string), string,
        )
        val code: List<Instruction> = listOf(
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, query),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2),
        )
        val scan = InstallSourceCheckDetector.scan(dex(code, string), AtomicCancellationSignal())
        assertEquals(0, scan.confirmedCount)
        assertEquals(1, scan.queryOnlyCount)
        assertNull(scan.evidence.single().expectedInstallerLiteral)
    }

    @Test
    fun nullDecisionIsConfirmedButDoesNotInventStoreName() {
        val query = ImmutableMethodReference(
            pm, "getInstallerPackageName", listOf(string), string,
        )
        val code: List<Instruction> = listOf(
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, query),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
            ImmutableInstruction21t(Opcode.IF_EQZ, 2, 4),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
            ImmutableInstruction11x(Opcode.RETURN, 0),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0),
        )
        val scan = InstallSourceCheckDetector.scan(dex(code, "Z"), AtomicCancellationSignal())
        assertEquals(1, scan.confirmedCount)
        assertNull(scan.evidence.single().expectedInstallerLiteral)
        assertTrue(scan.evidence.single().detail.contains("ветвлением"))
    }

    private fun dex(code: List<Instruction>, returnType: String): ByteArray {
        val parameters = listOf(
            ImmutableMethodParameter(pm, emptySet(), null),
            ImmutableMethodParameter(string, emptySet(), null),
        )
        val method = ImmutableMethod(
            owner,
            "check",
            parameters,
            returnType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            emptySet(),
            emptySet(),
            ImmutableMethodImplementation(5, code, emptyList(), emptyList()),
        )
        val clazz = ImmutableClassDef(
            owner,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            "InstallGate.java",
            emptySet(),
            emptyList(),
            emptyList(),
            listOf(method),
            emptyList(),
        )
        val out = MemoryDataStore()
        return try {
            DexPool.writeTo(out, ImmutableDexFile(Opcodes.forApi(30), listOf(clazz)))
            out.data
        } finally {
            out.close()
        }
    }
}
