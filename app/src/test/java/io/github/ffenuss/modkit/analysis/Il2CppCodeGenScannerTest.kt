package io.github.ffenuss.modkit.analysis

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Il2CppCodeGenScannerTest {
    @Test
    fun bindsMetadataTokenThroughCodeRegistrationSymbol() {
        val result = scanFixture(includeCodeRegistrationSymbol = true)

        assertEquals(1, result.modules.size)
        assertEquals("Assembly-CSharp.dll", result.modules.single().moduleName)
        assertEquals(1, result.bindings.size)
        assertEquals(0x200100L, result.codeRegistrationVirtualAddress)
        assertTrue(result.moduleArrayDiscovery.orEmpty().startsWith("CODE_REGISTRATION_PAIR_"))
        assertBinding(result)
    }

    @Test
    fun strippedBinaryFallsBackToUniqueMetadataImageSet() {
        val result = scanFixture(includeCodeRegistrationSymbol = false)

        assertNull(result.codeRegistrationVirtualAddress)
        assertEquals(1, result.modules.size)
        assertTrue(result.moduleArrayDiscovery.orEmpty().startsWith("BOUNDED_IMAGE_SET_SCAN@"))
        assertTrue("CODE_REGISTRATION_SYMBOL_UNRESOLVED" !in result.blockers)
        assertBinding(result)
    }

    @Test
    fun legitimateNobitsSectionOutsideFileDoesNotAbortBinding() {
        val result = scanFixture(
            includeCodeRegistrationSymbol = true,
            includeOutsideFileNobits = true,
        )

        assertBinding(result)
    }

    private fun scanFixture(
        includeCodeRegistrationSymbol: Boolean,
        includeOutsideFileNobits: Boolean = false,
    ): Il2CppBinaryEvidence {
        val file = Files.createTempFile("modkit-codegen", ".so").toFile()
        file.writeBytes(
            elfFixture(
                includeCodeRegistrationSymbol,
                includeOutsideFileNobits,
            ),
        )
        try {
            return Il2CppCodeGenScanner.scan(
                file = file,
                libraryEntry = "lib/arm64-v8a/libil2cpp.so",
                metadata = metadataFixture(),
                cancellation = neverCancelled(),
                progress = ProgressSink { },
            )
        } finally {
            file.delete()
        }
    }

    private fun assertBinding(result: Il2CppBinaryEvidence) {
        val binding = result.bindings.single()
        assertEquals(0, binding.slotIndex)
        assertEquals("Game.Player.Hit", binding.managedIdentity)
        assertEquals(0x100900L, binding.functionVirtualAddress)
        assertEquals(0x900L, binding.functionFileOffset)
        assertTrue(result.exactBindingAvailable)
    }

    private fun metadataFixture() = Il2CppMetadataModel(
        sizeBytes = 1024,
        magicValid = true,
        metadataVersion = 29,
        layoutProfile = "IL2CPP_METADATA_V27_V30",
        tableRanges = emptyList(),
        declaredTypeCount = 1,
        declaredMethodCount = 1,
        declaredFieldCount = 0,
        declaredImageCount = 1,
        images = listOf(
            Il2CppImageDefinition(
                index = 0,
                name = "Assembly-CSharp.dll",
                assemblyIndex = 0,
                typeStart = 0,
                typeCount = 1,
                token = 1,
            ),
        ),
        types = listOf(
            Il2CppTypeDefinition(
                index = 0,
                namespace = "Game",
                name = "Player",
                fullName = "Game.Player",
                methodStart = 0,
                methodCount = 1,
                fieldStart = 0,
                fieldCount = 0,
                token = 0x02000001,
            ),
        ),
        methods = listOf(
            Il2CppMethodDefinition(
                index = 0,
                declaringTypeIndex = 0,
                declaringType = "Game.Player",
                name = "Hit",
                parameterCount = 0,
                token = 0x06000001,
                flags = 6,
            ),
        ),
        fields = emptyList(),
        structuredSupported = true,
        truncated = false,
        warnings = emptyList(),
    )

    private fun elfFixture(
        includeCodeRegistrationSymbol: Boolean,
        includeOutsideFileNobits: Boolean,
    ): ByteArray {
        val bytes = ByteArray(0x2600)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2
        bytes[5] = 1
        bytes[6] = 1

        buffer.putShort(16, 3.toShort())
        buffer.putShort(18, 183.toShort())
        buffer.putInt(20, 1)
        buffer.putLong(32, 64)
        buffer.putLong(40, 0x2200)
        buffer.putShort(52, 64.toShort())
        buffer.putShort(54, 56.toShort())
        buffer.putShort(56, 2.toShort())
        buffer.putShort(58, 64.toShort())
        buffer.putShort(
            60,
            (if (includeOutsideFileNobits) 4 else 3).toShort(),
        )
        buffer.putShort(62, 0.toShort())

        // PT_LOAD #0: executable code.
        buffer.putInt(64, 1)
        buffer.putInt(68, 5)
        buffer.putLong(72, 0)
        buffer.putLong(80, 0x100000)
        buffer.putLong(88, 0x100000)
        buffer.putLong(96, 0x1000)
        buffer.putLong(104, 0x1000)
        buffer.putLong(112, 0x1000)

        // PT_LOAD #1: non-executable file-backed data.
        val ph1 = 64 + 56
        buffer.putInt(ph1, 1)
        buffer.putInt(ph1 + 4, 6)
        buffer.putLong(ph1 + 8, 0x1000)
        buffer.putLong(ph1 + 16, 0x200000)
        buffer.putLong(ph1 + 24, 0x200000)
        buffer.putLong(ph1 + 32, 0x1000)
        buffer.putLong(ph1 + 40, 0x1000)
        buffer.putLong(ph1 + 48, 0x1000)

        val dynstrText = if (includeCodeRegistrationSymbol) {
            "\u0000g_CodeRegistration\u0000g_MetadataRegistration\u0000il2cpp_codegen_register\u0000"
        } else {
            "\u0000g_MetadataRegistration\u0000il2cpp_codegen_register\u0000"
        }
        val dynstr = dynstrText.toByteArray(Charsets.US_ASCII)
        dynstr.copyInto(bytes, 0x300)
        val codeName = dynstrText.indexOf("g_CodeRegistration")
        val metadataName = dynstrText.indexOf("g_MetadataRegistration")
        val registerName = dynstrText.indexOf("il2cpp_codegen_register")

        val strSection = 0x2200 + 64
        buffer.putInt(strSection + 4, 3)
        buffer.putLong(strSection + 24, 0x300)
        buffer.putLong(strSection + 32, dynstr.size.toLong())

        val symbolCount = if (includeCodeRegistrationSymbol) 4 else 3
        val symSection = 0x2200 + 128
        buffer.putInt(symSection + 4, 11)
        buffer.putLong(symSection + 24, 0x400)
        buffer.putLong(symSection + 32, (symbolCount * 24).toLong())
        buffer.putInt(symSection + 40, 1)
        buffer.putLong(symSection + 56, 24)

        if (includeOutsideFileNobits) {
            val bssSection = 0x2200 + 192
            buffer.putInt(bssSection + 4, 8)
            buffer.putLong(bssSection + 24, 0x5000)
            buffer.putLong(bssSection + 32, 0x2000)
        }

        fun symbol(index: Int, nameOffset: Int, value: Long, typeInfo: Int = 0x11) {
            val base = 0x400 + index * 24
            buffer.putInt(base, nameOffset)
            bytes[base + 4] = typeInfo.toByte()
            buffer.putShort(base + 6, 1.toShort())
            buffer.putLong(base + 8, value)
            buffer.putLong(base + 16, 8)
        }

        if (includeCodeRegistrationSymbol) {
            symbol(1, codeName, 0x200100)
            symbol(2, metadataName, 0x200180)
            symbol(3, registerName, 0x100950, 0x12)
        } else {
            symbol(1, metadataName, 0x200180)
            symbol(2, registerName, 0x100950, 0x12)
        }

        // CodeRegistration candidate pair #0 at VA 0x200100.
        buffer.putInt(0x1100, 1)
        buffer.putLong(0x1108, 0x200200)

        // CodeGenModule* array.
        buffer.putLong(0x1200, 0x200250)

        // Il2CppCodeGenModule: name*, methodPointerCount, methodPointers*.
        buffer.putLong(0x1250, 0x200300)
        buffer.putInt(0x1258, 1)
        buffer.putLong(0x1260, 0x200380)

        "Assembly-CSharp.dll\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0x1300)
        buffer.putLong(0x1380, 0x100900)

        // Executable method body sample.
        bytes[0x900] = 0xC0.toByte()
        bytes[0x901] = 0x03
        bytes[0x902] = 0x5F
        bytes[0x903] = 0xD6.toByte()

        return bytes
    }

    private fun neverCancelled() = object : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }
}
