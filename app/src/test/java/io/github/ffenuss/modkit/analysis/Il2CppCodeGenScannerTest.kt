package io.github.ffenuss.modkit.analysis

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Il2CppCodeGenScannerTest {
    @Test
    fun bindsMetadataTokenToExecutableMethodPointerSlot() {
        val file = Files.createTempFile("modkit-codegen", ".so").toFile()
        file.writeBytes(elfFixture())
        try {
            val metadata = Il2CppMetadataModel(
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

            val result = Il2CppCodeGenScanner.scan(
                file = file,
                libraryEntry = "lib/arm64-v8a/libil2cpp.so",
                metadata = metadata,
                cancellation = neverCancelled(),
                progress = ProgressSink { },
            )

            assertEquals(1, result.modules.size)
            assertEquals("Assembly-CSharp.dll", result.modules.single().moduleName)
            assertEquals(1, result.bindings.size)
            val binding = result.bindings.single()
            assertEquals(0, binding.slotIndex)
            assertEquals(0x100900L, binding.functionVirtualAddress)
            assertEquals(0x900L, binding.functionFileOffset)
            assertTrue(result.exactBindingAvailable)
        } finally {
            file.delete()
        }
    }

    private fun elfFixture(): ByteArray {
        val bytes = ByteArray(0x1400)
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
        buffer.putLong(40, 0x1000)
        buffer.putShort(52, 64.toShort())
        buffer.putShort(54, 56.toShort())
        buffer.putShort(56, 1.toShort())
        buffer.putShort(58, 64.toShort())
        buffer.putShort(60, 3.toShort())
        buffer.putShort(62, 0.toShort())

        buffer.putInt(64, 1)
        buffer.putInt(68, 5)
        buffer.putLong(72, 0)
        buffer.putLong(80, 0x100000)
        buffer.putLong(88, 0x100000)
        buffer.putLong(96, 0x1000)
        buffer.putLong(104, 0x1000)
        buffer.putLong(112, 0x1000)

        val dynstrText = "\u0000g_CodeRegistration\u0000g_MetadataRegistration\u0000il2cpp_codegen_register\u0000"
        val dynstr = dynstrText.toByteArray(Charsets.US_ASCII)
        dynstr.copyInto(bytes, 0x300)
        val codeName = dynstrText.indexOf("g_CodeRegistration")
        val metadataName = dynstrText.indexOf("g_MetadataRegistration")
        val registerName = dynstrText.indexOf("il2cpp_codegen_register")

        val strSection = 0x1000 + 64
        buffer.putInt(strSection + 4, 3)
        buffer.putLong(strSection + 24, 0x300)
        buffer.putLong(strSection + 32, dynstr.size.toLong())

        val symSection = 0x1000 + 128
        buffer.putInt(symSection + 4, 11)
        buffer.putLong(symSection + 24, 0x400)
        buffer.putLong(symSection + 32, 96)
        buffer.putInt(symSection + 40, 1)
        buffer.putLong(symSection + 56, 24)

        fun symbol(index: Int, nameOffset: Int, value: Long, typeInfo: Int = 0x11) {
            val base = 0x400 + index * 24
            buffer.putInt(base, nameOffset)
            bytes[base + 4] = typeInfo.toByte()
            buffer.putShort(base + 6, 1.toShort())
            buffer.putLong(base + 8, value)
            buffer.putLong(base + 16, 8)
        }

        symbol(1, codeName, 0x100500)
        symbol(2, metadataName, 0x100580)
        symbol(3, registerName, 0x100950, 0x12)

        buffer.putInt(0x500, 1)
        buffer.putLong(0x508, 0x100600)

        buffer.putLong(0x600, 0x100650)
        buffer.putLong(0x650, 0x100700)
        buffer.putInt(0x658, 1)
        buffer.putLong(0x660, 0x100780)

        "Assembly-CSharp.dll\u0000".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0x700)
        buffer.putLong(0x780, 0x100900)
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
