package io.github.ffenuss.modkit.patch

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.dexbacked.DexBackedDexFile
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.MethodReference
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DexInstallerCompatibilityRewriterTest {
    private val pm = "Landroid/content/pm/PackageManager;"
    private val source = "Landroid/content/pm/InstallSourceInfo;"
    private val string = "Ljava/lang/String;"
    private val owner = "Ldev/fixture/InstallGate;"
    private val signal = AtomicCancellationSignal()

    @Test
    fun rewritesOnlyConfirmedLegacyDecisionAndLeavesQueryOnlyMethodUntouched() {
        val query = ImmutableMethodReference(pm, "getInstallerPackageName", listOf(string), string)
        val confirmed = legacyCheck("confirmed", query, true)
        val queryOnly = legacyCheck("queryOnly", query, false)
        val input = dex(listOf(confirmed, queryOnly))

        val result = DexInstallerCompatibilityRewriter.rewrite(input, signal)
        assertEquals(1, result.confirmedChecks)
        assertEquals(1, result.queryOnlyChecks)
        assertEquals(1, result.redirectedCalls)

        val methods = DexBackedDexFile(null, result.bytes).classes.single().methods.associateBy { it.name }
        val confirmedRefs = refs(methods.getValue("confirmed"))
        assertTrue(confirmedRefs.any {
            it.definingClass == DexInstallerCompatibilityRewriter.BRIDGE &&
                it.name == "getInstallerPackageName"
        })
        val queryRefs = refs(methods.getValue("queryOnly"))
        assertTrue(queryRefs.any {
            it.definingClass == pm && it.name == "getInstallerPackageName"
        })
        assertTrue(queryRefs.none {
            it.definingClass == DexInstallerCompatibilityRewriter.BRIDGE
        })
    }

    @Test
    fun modernConfirmedDecisionBridgesProducerAndInstallingPackageNameTogether() {
        val getSource = ImmutableMethodReference(
            pm, "getInstallSourceInfo", listOf(string), source,
        )
        val getInstaller = ImmutableMethodReference(
            source, "getInstallingPackageName", emptyList(), string,
        )
        val equals = ImmutableMethodReference(
            string, "equals", listOf("Ljava/lang/Object;"), "Z",
        )
        val code: List<Instruction> = listOf(
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, getSource),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 2, 0, 0, 0, 0, getInstaller),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 3),
            ImmutableInstruction21c(
                Opcode.CONST_STRING, 4,
                ImmutableStringReference("com.android.vending"),
            ),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 3, 4, 0, 0, 0, equals),
            ImmutableInstruction11x(Opcode.MOVE_RESULT, 5),
            ImmutableInstruction21t(Opcode.IF_EQZ, 5, 4),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
            ImmutableInstruction11x(Opcode.RETURN, 0),
            ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
            ImmutableInstruction11x(Opcode.RETURN, 0),
        )
        val input = dex(listOf(method("modern", "Z", code, 6)))
        val result = DexInstallerCompatibilityRewriter.rewrite(input, signal)

        assertEquals(1, result.confirmedChecks)
        assertEquals(2, result.redirectedCalls)
        val refs = refs(
            DexBackedDexFile(null, result.bytes).classes.single().methods.single(),
        )
        assertTrue(refs.any {
            it.definingClass == DexInstallerCompatibilityRewriter.BRIDGE &&
                it.name == "getInstallSourceInfo"
        })
        assertTrue(refs.any {
            it.definingClass == DexInstallerCompatibilityRewriter.BRIDGE &&
                it.name == "getInstallingPackageName"
        })
    }

    @Test
    fun dexWithOnlyQueryEvidenceIsReturnedUnchanged() {
        val query = ImmutableMethodReference(pm, "getInstallerPackageName", listOf(string), string)
        val input = dex(listOf(legacyCheck("queryOnly", query, false)))
        val result = DexInstallerCompatibilityRewriter.rewrite(input, signal)
        assertEquals(0, result.redirectedCalls)
        assertEquals(0, result.confirmedChecks)
        assertEquals(1, result.queryOnlyChecks)
        assertSame(input, result.bytes)
    }

    private fun legacyCheck(
        name: String,
        query: ImmutableMethodReference,
        branch: Boolean,
    ): ImmutableMethod {
        val code = mutableListOf<Instruction>(
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 0, 1, 0, 0, 0, query),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
        )
        if (!branch) {
            code += ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2)
            return method(name, string, code, 5)
        }
        val equals = ImmutableMethodReference(
            string, "equals", listOf("Ljava/lang/Object;"), "Z",
        )
        code += listOf(
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
        return method(name, "Z", code, 5)
    }

    private fun method(
        name: String,
        returnType: String,
        code: List<Instruction>,
        registers: Int,
    ): ImmutableMethod = ImmutableMethod(
        owner,
        name,
        listOf(
            ImmutableMethodParameter(pm, emptySet(), null),
            ImmutableMethodParameter(string, emptySet(), null),
        ),
        returnType,
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
        emptySet(),
        emptySet(),
        ImmutableMethodImplementation(registers, code, emptyList(), emptyList()),
    )

    private fun dex(methods: List<ImmutableMethod>): ByteArray {
        val clazz = ImmutableClassDef(
            owner,
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            "InstallGate.java",
            emptySet(),
            emptyList(),
            emptyList(),
            methods,
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

    private fun refs(method: org.jf.dexlib2.iface.Method): List<MethodReference> =
        method.implementation!!.instructions.mapNotNull {
            ((it as? ReferenceInstruction)?.reference as? MethodReference)
        }.toList()
}
