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

    @Test
    fun strippedBinaryRecoversCodegenPointersThroughRelativeRelocations() {
        val result = scanFixture(
            includeCodeRegistrationSymbol = false,
            includeRelativeRelocations = true,
        )

        assertNull(result.codeRegistrationVirtualAddress)
        assertTrue(
            result.moduleArrayDiscovery.orEmpty().startsWith(
                "RELATIVE_RELOCATION_PAIR@",
            ),
        )
        assertBinding(result)
    }

    @Test
    fun strippedMetadataRegistrationRecoversReturnKindThroughRelativeRelocations() {
        val result = scanFixture(
            includeCodeRegistrationSymbol = false,
            includeMetadataRegistrationSymbol = false,
            includeRelativeRelocations = true,
            includeMetadataRegistrationRelocations = true,
        )

        assertEquals(
            0x200500L,
            result.metadataRegistrationVirtualAddress,
        )
        val binding = result.bindings.single()
        assertEquals(7, binding.returnTypeIndex)
        assertEquals(
            Il2CppNativeReturnKind.VOID,
            binding.returnKind,
        )
        assertTrue(
            binding.returnTypeProof
                .orEmpty()
                .contains("types[7]"),
        )
    }

    @Test
    fun strippedBinaryRecoversCodegenPointersThroughAndroidAps2() {
        val result = scanFixture(
            includeCodeRegistrationSymbol = false,
            includeAndroidPackedRelocations = true,
        )

        assertNull(result.codeRegistrationVirtualAddress)
        assertTrue(
            result.moduleArrayDiscovery.orEmpty().startsWith(
                "RELATIVE_RELOCATION_PAIR@",
            ),
        )
        assertBinding(result)
    }

    @Test
    fun strippedBinaryRecoversAps2FromPtDynamicWithoutSectionHeader() {
        val result = scanFixture(
            includeCodeRegistrationSymbol = false,
            includeAndroidPackedRelocations = true,
            androidPackedRelocationsInDynamicOnly = true,
        )

        assertNull(result.codeRegistrationVirtualAddress)
        assertTrue(result.relativeRelocationCount > 0)
        assertTrue(
            result.moduleArrayDiscovery.orEmpty().startsWith(
                "RELATIVE_RELOCATION_PAIR@",
            ),
        )
        assertBinding(result)
    }

    @Test
    fun strippedBinaryRecoversModuleWithoutRegistrationPair() {
        val result = scanFixture(
            includeCodeRegistrationSymbol = false,
            includeRelativeRelocations = true,
            codeRegistrationPairCount = 2,
        )

        assertNull(result.codeRegistrationVirtualAddress)
        assertTrue(
            result.moduleArrayDiscovery.orEmpty().startsWith(
                "RELOCATED_MODULE_SIGNATURES_1_OF_1",
            ),
        )
        assertTrue(
            "CODE_REGISTRATION_SYMBOL_UNRESOLVED" !in
                result.blockers,
        )
        assertBinding(result)
    }

    private fun scanFixture(
        includeCodeRegistrationSymbol: Boolean,
        includeMetadataRegistrationSymbol: Boolean = true,
        includeOutsideFileNobits: Boolean = false,
        includeRelativeRelocations: Boolean = false,
        includeMetadataRegistrationRelocations: Boolean = false,
        includeAndroidPackedRelocations: Boolean = false,
        androidPackedRelocationsInDynamicOnly: Boolean = false,
        codeRegistrationPairCount: Int = 1,
    ): Il2CppBinaryEvidence {
        val file = Files.createTempFile("modkit-codegen", ".so").toFile()
        file.writeBytes(
            elfFixture(
                includeCodeRegistrationSymbol,
                includeMetadataRegistrationSymbol,
                includeOutsideFileNobits,
                includeRelativeRelocations,
                includeMetadataRegistrationRelocations,
                includeAndroidPackedRelocations,
                androidPackedRelocationsInDynamicOnly,
                codeRegistrationPairCount,
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
                returnTypeIndex = 7,
            ),
        ),
        fields = emptyList(),
        structuredSupported = true,
        truncated = false,
        warnings = emptyList(),
    )

    private fun elfFixture(
        includeCodeRegistrationSymbol: Boolean,
        includeMetadataRegistrationSymbol: Boolean,
        includeOutsideFileNobits: Boolean,
        includeRelativeRelocations: Boolean,
        includeMetadataRegistrationRelocations: Boolean,
        includeAndroidPackedRelocations: Boolean,
        androidPackedRelocationsInDynamicOnly: Boolean,
        codeRegistrationPairCount: Int,
    ): ByteArray {
        require(
            !(includeRelativeRelocations &&
                includeAndroidPackedRelocations),
        )
        require(
            !androidPackedRelocationsInDynamicOnly ||
                includeAndroidPackedRelocations,
        )
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
        buffer.putShort(
            56,
            (
                if (androidPackedRelocationsInDynamicOnly) {
                    3
                } else {
                    2
                }
                ).toShort(),
        )
        buffer.putShort(58, 64.toShort())
        val sectionCount =
            3 +
                (if (includeOutsideFileNobits) 1 else 0) +
                (
                    if (
                        includeRelativeRelocations ||
                        (
                            includeAndroidPackedRelocations &&
                                !androidPackedRelocationsInDynamicOnly
                            )
                    ) {
                        1
                    } else {
                        0
                    }
                    )
        buffer.putShort(60, sectionCount.toShort())
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

        if (androidPackedRelocationsInDynamicOnly) {
            val ph2 = 64 + 56 * 2
            buffer.putInt(ph2, 2)
            buffer.putInt(ph2 + 4, 6)
            buffer.putLong(ph2 + 8, 0x1a00)
            buffer.putLong(ph2 + 16, 0x200a00)
            buffer.putLong(ph2 + 24, 0x200a00)
            buffer.putLong(ph2 + 32, 0x30)
            buffer.putLong(ph2 + 40, 0x30)
            buffer.putLong(ph2 + 48, 8)
        }

        val dynstrText = buildString {
            append('\u0000')
            if (includeCodeRegistrationSymbol) {
                append("g_CodeRegistration\u0000")
            }
            if (includeMetadataRegistrationSymbol) {
                append("g_MetadataRegistration\u0000")
            }
            append("il2cpp_codegen_register\u0000")
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

        val symbolCount =
            1 +
                (if (includeCodeRegistrationSymbol) 1 else 0) +
                (if (includeMetadataRegistrationSymbol) 1 else 0) +
                1
        val symSection = 0x2200 + 128
        buffer.putInt(symSection + 4, 11)
        buffer.putLong(symSection + 24, 0x400)
        buffer.putLong(symSection + 32, (symbolCount * 24).toLong())
        buffer.putInt(symSection + 40, 1)
        buffer.putLong(symSection + 56, 24)

        var nextSectionIndex = 3
        if (includeOutsideFileNobits) {
            val bssSection =
                0x2200 + nextSectionIndex * 64
            buffer.putInt(bssSection + 4, 8)
            buffer.putLong(bssSection + 24, 0x5000)
            buffer.putLong(bssSection + 32, 0x2000)
            nextSectionIndex++
        }
        val aps2 =
            if (includeAndroidPackedRelocations) {
                androidPackedRelocations()
            } else {
                null
            }
        if (
            includeRelativeRelocations ||
            includeAndroidPackedRelocations
        ) {
            if (!androidPackedRelocationsInDynamicOnly) {
                val relocationSection =
                    0x2200 + nextSectionIndex * 64
                buffer.putInt(
                    relocationSection + 4,
                    if (includeAndroidPackedRelocations) {
                        0x60000002
                    } else {
                        4
                    },
                )
                buffer.putLong(
                    relocationSection + 24,
                    0x1800,
                )
                buffer.putLong(
                    relocationSection + 32,
                    aps2?.size?.toLong()
                        ?: (
                            if (includeMetadataRegistrationRelocations) {
                                9L
                            } else {
                                5L
                            }
                            ) * 24L,
                )
                if (includeRelativeRelocations) {
                    buffer.putLong(
                        relocationSection + 56,
                        24,
                    )
                }
            }
            aps2?.copyInto(bytes, 0x1800)
        }

        if (androidPackedRelocationsInDynamicOnly) {
            val dynamic = 0x1a00
            buffer.putLong(dynamic, 0x60000011L)
            buffer.putLong(dynamic + 8, 0x200800L)
            buffer.putLong(dynamic + 16, 0x60000012L)
            buffer.putLong(
                dynamic + 24,
                aps2?.size?.toLong() ?: 0L,
            )
            buffer.putLong(dynamic + 32, 0L)
            buffer.putLong(dynamic + 40, 0L)
        }

        fun symbol(index: Int, nameOffset: Int, value: Long, typeInfo: Int = 0x11) {
            val base = 0x400 + index * 24
            buffer.putInt(base, nameOffset)
            bytes[base + 4] = typeInfo.toByte()
            buffer.putShort(base + 6, 1.toShort())
            buffer.putLong(base + 8, value)
            buffer.putLong(base + 16, 8)
        }

        var symbolIndex = 1
        if (includeCodeRegistrationSymbol) {
            symbol(symbolIndex++, codeName, 0x200100)
        }
        if (includeMetadataRegistrationSymbol) {
            symbol(symbolIndex++, metadataName, 0x200180)
        }
        symbol(symbolIndex, registerName, 0x100950, 0x12)

        // CodeRegistration candidate pair #0 at VA 0x200100.
        buffer.putInt(
            0x1100,
            codeRegistrationPairCount,
        )

        if (
            includeRelativeRelocations ||
            includeAndroidPackedRelocations
        ) {
            if (includeRelativeRelocations) {
                fun rela(
                    index: Int,
                    targetVa: Long,
                    addendVa: Long,
                ) {
                    val base = 0x1800 + index * 24
                    buffer.putLong(base, targetVa)
                    buffer.putLong(base + 8, 1027L)
                    buffer.putLong(base + 16, addendVa)
                }

                // Android/AArch64 ET_DYN stores these local pointers through
                // R_AARCH64_RELATIVE relocations rather than absolute bytes.
                rela(0, 0x200108, 0x200200)
                rela(1, 0x200200, 0x200250)
                rela(2, 0x200250, 0x200300)
                rela(3, 0x200260, 0x200380)
                rela(4, 0x200380, 0x100900)
                if (includeMetadataRegistrationRelocations) {
                    rela(5, 0x200538, 0x200600)
                    rela(6, 0x200558, 0x200680)
                    rela(7, 0x200568, 0x200700)
                    rela(8, 0x200638, 0x200780)
                }
            }
        } else {
            buffer.putLong(0x1108, 0x200200)
            buffer.putLong(0x1200, 0x200250)
            buffer.putLong(0x1250, 0x200300)
            buffer.putLong(0x1260, 0x200380)
            buffer.putLong(0x1380, 0x100900)
        }

        if (includeMetadataRegistrationRelocations) {
            // Il2CppMetadataRegistration @ VA 0x200500.
            // Pair #3 = runtime types, #5/#6 both match exact TypeDef count.
            buffer.putInt(0x1530, 8)
            buffer.putInt(0x1550, 1)
            buffer.putInt(0x1560, 1)
            // Minimal file-backed array data used by candidate validation.
            buffer.putLong(0x1680, 0x200800)
            buffer.putLong(0x1700, 0x200880)
            // Il2CppType @ VA 0x200780: type byte at pointerSize + 2.
            bytes[0x178a] = 0x01
        }

        // Il2CppCodeGenModule: methodPointerCount remains scalar data.
        buffer.putInt(0x1258, 1)

        "Assembly-CSharp.dll\u0000"
            .toByteArray(Charsets.US_ASCII)
            .copyInto(bytes, 0x1300)

        // Executable method body sample.
        bytes[0x900] = 0xC0.toByte()
        bytes[0x901] = 0x03
        bytes[0x902] = 0x5F
        bytes[0x903] = 0xD6.toByte()

        return bytes
    }

    private fun androidPackedRelocations(): ByteArray {
        val out = ArrayList<Byte>()

        fun byte(value: Int) {
            out += value.toByte()
        }

        fun sleb(value: Long) {
            var remaining = value
            var more = true
            while (more) {
                var current =
                    (remaining and 0x7f).toInt()
                val signBit = current and 0x40 != 0
                remaining = remaining shr 7
                more = !(
                    (remaining == 0L && !signBit) ||
                        (remaining == -1L && signBit)
                    )
                if (more) current = current or 0x80
                byte(current)
            }
        }

        "APS2".toByteArray(Charsets.US_ASCII).forEach {
            out += it
        }
        sleb(5)
        sleb(0x200100)
        sleb(5)
        sleb(0x09)
        sleb(1027)

        val offsets =
            longArrayOf(
                0x200108,
                0x200200,
                0x200250,
                0x200260,
                0x200380,
            )
        val addends =
            longArrayOf(
                0x200200,
                0x200250,
                0x200300,
                0x200380,
                0x100900,
            )
        var previousOffset = 0x200100L
        var previousAddend = 0L
        offsets.indices.forEach { index ->
            sleb(offsets[index] - previousOffset)
            sleb(addends[index] - previousAddend)
            previousOffset = offsets[index]
            previousAddend = addends[index]
        }
        return ByteArray(out.size) { out[it] }
    }

    private fun neverCancelled() = object : CancellationSignal {
        override fun isCancelled(): Boolean = false
    }
}
