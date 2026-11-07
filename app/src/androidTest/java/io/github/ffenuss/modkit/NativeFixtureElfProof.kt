package io.github.ffenuss.modkit

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * CI-only bounded ELF evidence reader. The fixture's native executable
 * symbol/address are extracted from the actually installed APK, not assumed
 * from a hardcoded linker address. Only the exact known x86_64 or ARM64
 * fixture instruction body is patchable; all mismatches fail closed.
 * This permits the same Android end-to-end test on an ARM64 device, but
 * does not by itself establish live ARM64 write-back on an emulator.
 */
internal object NativeFixtureElfProof {
    data class Patch(val module: String, val address: Long,
                     val original: String, val replacement: String)

    fun readInstalled(apk: File, abi: String): Patch {
        require(abi == "x86_64" || abi == "arm64-v8a") {
            "Native positive proof supports only x86_64 and arm64-v8a"
        }
        val module = "libmodkit_native_fixture.so"
        val data = ZipFile(apk).use { zip ->
            val entry = requireNotNull(zip.getEntry("lib/$abi/$module")) {
                "Fixture's $abi ELF is not present in the installed APK"
            }
            require(entry.size in 1..2_000_000)
            zip.getInputStream(entry).use { it.readBytes() }
        }
        require(data.size in 64..2_000_000)
        require(data[0] == 0x7f.toByte() &&
                data[1] == 'E'.code.toByte() &&
                data[2] == 'L'.code.toByte() &&
                data[3] == 'F'.code.toByte() &&
                data[4] == 2.toByte() && data[5] == 1.toByte())
        val bytes = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val expectedMachine = if (abi == "x86_64") 62 else 183 // EM_X86_64 / EM_AARCH64
        require(bytes.getShort(18).toInt() and 0xffff == expectedMachine) {
            "ELF machine does not match requested native fixture ABI"
        }
        val headers = bytes.getLong(40)
        val entrySize = bytes.getShort(58).toInt() and 0xffff
        val count = bytes.getShort(60).toInt() and 0xffff
        require(headers in 64L..(data.size - 64).toLong())
        require(entrySize == 64 && count in 1..512)
        require(headers + count.toLong() * entrySize <= data.size)

        data class Section(val type: Int, val flags: Long, val address: Long,
                           val offset: Long, val size: Long, val link: Int, val sizeOfEntry: Long)
        val sections = (0 until count).map { n ->
            val base = (headers + entrySize.toLong() * n).toInt()
            Section(bytes.getInt(base + 4), bytes.getLong(base + 8),
                bytes.getLong(base + 16), bytes.getLong(base + 24),
                bytes.getLong(base + 32), bytes.getInt(base + 40),
                bytes.getLong(base + 56))
        }
        fun checked(section: Section) {
            require(section.offset >= 0 && section.size >= 0 &&
                    section.offset <= data.size &&
                    section.size <= data.size - section.offset)
        }
        val symtab = sections.single { it.type == 11 } // .dynsym
        checked(symtab)
        require(symtab.link in sections.indices && symtab.sizeOfEntry == 24L)
        val strings = sections[symtab.link]
        checked(strings)
        fun symbolName(offset: Int): String {
            require(offset >= 0 && offset.toLong() < strings.size)
            val start = strings.offset.toInt() + offset
            val end = (start until (strings.offset + strings.size).toInt())
                .firstOrNull { data[it] == 0.toByte() }
                ?: error("Unterminated ELF symbol name")
            return String(data, start, end - start, Charsets.UTF_8)
        }
        val matches = (0 until (symtab.size / symtab.sizeOfEntry).toInt())
            .filter { i ->
                val at = (symtab.offset + i.toLong() * symtab.sizeOfEntry).toInt()
                symbolName(bytes.getInt(at)) == "native_fixture_step"
            }
        require(matches.size == 1) { "Expected unique native fixture step symbol" }
        val at = (symtab.offset + matches.single() * symtab.sizeOfEntry).toInt()
        val value = bytes.getLong(at + 8)
        require(value > 0)
        val bodyLength = if (abi == "x86_64") 6 else 8
        val executable = sections.single {
            (it.flags and 0x4L) != 0L && value >= it.address &&
                    value + bodyLength <= it.address + it.size
        }
        checked(executable)
        val offset = (executable.offset + value - executable.address).toInt()
        val expected = if (abi == "x86_64") {
            byteArrayOf(0xb8.toByte(), 0x02, 0, 0, 0, 0xc3.toByte())
        } else {
            // mov w0,#2; ret in little-endian ARM64 machine instructions.
            byteArrayOf(0x40, 0x00, 0x80.toByte(), 0x52,
                0xc0.toByte(), 0x03, 0x5f, 0xd6.toByte())
        }
        require(data.copyOfRange(offset, offset + bodyLength).contentEquals(expected)) {
            "Compiled native fixture function bytes changed: refusing patch"
        }
        return if (abi == "x86_64") {
            // mov eax,2; ret: replace the 4-byte immediate, never the opcode.
            Patch(module, value + 1, "02000000", "07000000")
        } else {
            // ARM64 instructions are naturally aligned and 4 bytes wide.
            require(value % 4L == 0L) { "Unaligned ARM64 function entry" }
            // mov w0,#2 -> mov w0,#7; retain the original ret instruction.
            Patch(module, value, "40008052", "e0008052")
        }
    }
}
