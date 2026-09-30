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
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction35c
import org.jf.dexlib2.immutable.reference.ImmutableMethodReference
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallCompatibilitySurfaceDetectorTest {
    @Test
    fun reportsSigningPlayReflectionAndNativeAsUncertaintyNotBypassProof() {
        val calls: List<Instruction> = listOf(
            invokeStatic(
                ImmutableMethodReference(
                    "Landroid/content/pm/PackageManager;",
                    "checkSignatures",
                    listOf("Ljava/lang/String;", "Ljava/lang/String;"),
                    "I",
                ),
                2,
            ),
            invokeStatic(
                ImmutableMethodReference(
                    "Lcom/google/android/play/core/integrity/IntegrityManagerFactory;",
                    "create",
                    listOf("Landroid/content/Context;"),
                    "Lcom/google/android/play/core/integrity/IntegrityManager;",
                ),
                1,
            ),
            invokeStatic(
                ImmutableMethodReference(
                    "Ljava/lang/Class;",
                    "forName",
                    listOf("Ljava/lang/String;"),
                    "Ljava/lang/Class;",
                ),
                1,
            ),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val method = ImmutableMethod(
            "Ldev/fixture/SecuritySurface;",
            "inspect",
            emptyList(),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            emptySet(),
            emptySet(),
            ImmutableMethodImplementation(4, calls, emptyList(), emptyList()),
        )
        val nativeMethod = ImmutableMethod(
            "Ldev/fixture/SecuritySurface;",
            "nativeCheck",
            emptyList(),
            "Z",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.NATIVE.value,
            emptySet(),
            emptySet(),
            null,
        )
        val scan = InstallCompatibilitySurfaceDetector.scan(
            dex(listOf(method, nativeMethod)),
            AtomicCancellationSignal(),
        )

        assertEquals(1, scan.count(InstallCompatibilitySurfaceKind.SIGNING_API_REFERENCE))
        assertEquals(1, scan.count(InstallCompatibilitySurfaceKind.PLAY_ATTESTATION_REFERENCE))
        assertEquals(1, scan.count(InstallCompatibilitySurfaceKind.REFLECTION_OR_DYNAMIC_CODE))
        assertEquals(1, scan.count(InstallCompatibilitySurfaceKind.NATIVE_METHOD))
        assertTrue(scan.remoteAttestationPossible)
        assertTrue(scan.dexOnlyInspectionIncomplete)
    }

    private fun invokeStatic(reference: ImmutableMethodReference, count: Int) =
        ImmutableInstruction35c(
            Opcode.INVOKE_STATIC,
            count,
            0,
            1,
            2,
            3,
            0,
            reference,
        )

    private fun dex(methods: List<ImmutableMethod>): ByteArray {
        val clazz = ImmutableClassDef(
            "Ldev/fixture/SecuritySurface;",
            AccessFlags.PUBLIC.value,
            "Ljava/lang/Object;",
            emptyList(),
            "SecuritySurface.java",
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
}
