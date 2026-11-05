package io.github.ffenuss.modkit

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

/**
 * CI-only bounded ELF evidence reader. The fixture's native executable
 * symbol/address are extracted from the actual installed APK, not assumed
 * from a hardcoded linker address. Only the exact known 6-byte x86_64 body
 * is patchable by this regression; all mismatches fail closed.
 */
internal object NativeFixtureElfProof {
    data class Patch(val module: String, val address: Long,
                     val original: String, val replacement: String)

    fun readInstalled(apk: File, abi: String): Patch {
        require(abi == "x86_64") { "Native CI proof currently requires x86_64" }
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
        require(bytes.getShort(18).toInt() and 0xffff == 62) // x86_64
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
        val executable = sections.single {
            (it.flags and 0x4L) != 0L && value >= it.address &&
                    value + 6 <= it.address + it.size
        }
        checked(executable)
        val offset = (executable.offset + value - executable.address).toInt()
        val expected = byteArrayOf(0xb8.toByte(), 0x02, 0, 0, 0, 0xc3.toByte())
        require(data.copyOfRange(offset, offset + 6).contentEquals(expected)) {
            "Compiled native fixture function bytes changed: refusing patch"
        }
        // At function entry: mov eax, 2; ret. Replace only imm32 2 -> 7.
        return Patch(module, value + 1, "02000000", "07000000")
    }
}
