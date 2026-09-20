package io.github.ffenuss.modkit.analysis

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

data class ElfLoadSegment(
    val virtualAddress: Long,
    val memorySize: Long,
    val fileOffset: Long,
    val fileSize: Long,
    val executable: Boolean,
)

data class ElfDynamicSymbol(
    val name: String,
    val value: Long,
    val size: Long,
    val defined: Boolean,
    val binding: Int,
    val type: Int,
)

class ElfImage private constructor(
    private val raf: RandomAccessFile,
    val is64Bit: Boolean,
    val machine: Int,
    val loadSegments: List<ElfLoadSegment>,
    val dynamicSymbols: List<ElfDynamicSymbol>,
    private val relativeRelocations: Map<Long, Long>,
) : Closeable {
    val relativeRelocationCount: Int
        get() = relativeRelocations.size
    val pointerSize: Int get() = if (is64Bit) 8 else 4

    fun isExecutableVa(virtualAddress: Long): Boolean =
        loadSegments.any { segment ->
            virtualAddress >= segment.virtualAddress &&
                virtualAddress < segment.virtualAddress + segment.memorySize &&
                segment.executable
        }

    fun isFileBackedVa(virtualAddress: Long, size: Long = 1L): Boolean =
        fileOffsetForVa(virtualAddress, size) != null

    fun readU32AtVa(virtualAddress: Long): Long? {
        val offset = fileOffsetForVa(virtualAddress, 4) ?: return null
        return u32(offset)
    }

    fun readPointerAtVa(virtualAddress: Long): Long? {
        relativeRelocations[virtualAddress]?.let { return it }
        val offset =
            fileOffsetForVa(
                virtualAddress,
                pointerSize.toLong(),
            ) ?: return null
        return if (is64Bit) u64(offset) else u32(offset)
    }

    fun readCStringAtVa(virtualAddress: Long, maxBytes: Int = 512): String? {
        val offset = fileOffsetForVa(virtualAddress, 1) ?: return null
        raf.seek(offset)
        val out = ByteArray(maxBytes)
        var count = 0
        while (count < maxBytes) {
            val value = raf.read()
            if (value < 0 || value == 0) break
            if (value !in 0x20..0x7e) return null
            out[count++] = value.toByte()
        }
        if (count == 0 || count == maxBytes) return null
        return out.copyOf(count).toString(Charsets.US_ASCII)
    }

    fun fileOffsetForVa(virtualAddress: Long, size: Long = 1L): Long? {
        if (size < 0L) return null
        for (segment in loadSegments) {
            if (virtualAddress < segment.virtualAddress) continue
            val relative = virtualAddress - segment.virtualAddress
            if (relative < 0L || relative > segment.fileSize) continue
            if (size > segment.fileSize - relative) continue
            return segment.fileOffset + relative
        }
        return null
    }

    override fun close() {
        raf.close()
    }

    private fun u32(offset: Long): Long {
        raf.seek(offset)
        val b0 = raf.readUnsignedByte().toLong()
        val b1 = raf.readUnsignedByte().toLong()
        val b2 = raf.readUnsignedByte().toLong()
        val b3 = raf.readUnsignedByte().toLong()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    private fun u64(offset: Long): Long {
        val lo = u32(offset)
        val hi = u32(offset + 4)
        if ((hi and 0x80000000L) != 0L) return -1L
        return lo or (hi shl 32)
    }

    companion object {
        private const val PT_LOAD = 1L
        private const val PF_X = 1L
        private const val SHT_RELA = 4L
        private const val SHT_NOBITS = 8L
        private const val SHT_REL = 9L
        private const val SHT_DYNSYM = 11L
        private const val SHT_RELR = 19L
        private const val SHT_ANDROID_REL = 0x60000001L
        private const val SHT_ANDROID_RELA = 0x60000002L
        private const val SHT_ANDROID_RELR = 0x6fffff00L

        private const val RELOCATION_GROUPED_BY_INFO_FLAG = 1L
        private const val RELOCATION_GROUPED_BY_OFFSET_DELTA_FLAG = 2L
        private const val RELOCATION_GROUPED_BY_ADDEND_FLAG = 4L
        private const val RELOCATION_GROUP_HAS_ADDEND_FLAG = 8L
        private const val RELOCATION_GROUP_KNOWN_FLAGS = 0x0fL

        private const val MAX_RELATIVE_RELOCATIONS = 1_000_000
        private const val MAX_PACKED_RELOCATION_BYTES =
            64 * 1024 * 1024
        private const val SHN_UNDEF = 0

        private data class Header(
            val is64: Boolean,
            val machine: Int,
            val phoff: Long,
            val phentsize: Int,
            val phnum: Int,
            val shoff: Long,
            val shentsize: Int,
            val shnum: Int,
        )

        private data class Section(
            val type: Long,
            val offset: Long,
            val size: Long,
            val link: Int,
            val entrySize: Long,
        )

        fun open(
            file: File,
            cancellation: CancellationSignal,
        ): ElfImage {
            require(file.isFile && file.canRead()) { "ELF file is not readable" }
            val raf = RandomAccessFile(file, "r")
            try {
                val header = readHeader(raf)
                checkCancelled(cancellation)
                val segments = readLoadSegments(raf, header, cancellation)
                val sections =
                    readSections(
                        raf,
                        header,
                        cancellation,
                    )
                val symbols =
                    readDynamicSymbols(
                        raf,
                        header,
                        sections,
                        cancellation,
                    )
                val relativeRelocations =
                    readRelativeRelocations(
                        raf = raf,
                        h = header,
                        sections = sections,
                        loadSegments = segments,
                        cancellation = cancellation,
                    )
                return ElfImage(
                    raf = raf,
                    is64Bit = header.is64,
                    machine = header.machine,
                    loadSegments = segments,
                    dynamicSymbols = symbols,
                    relativeRelocations =
                        relativeRelocations,
                )
            } catch (failure: Throwable) {
                raf.close()
                throw failure
            }
        }

        private fun readHeader(raf: RandomAccessFile): Header {
            require(raf.length() >= 52L) { "ELF header is truncated" }
            val ident = ByteArray(16)
            raf.seek(0)
            raf.readFully(ident)
            require(
                ident[0] == 0x7f.toByte() &&
                    ident[1] == 'E'.code.toByte() &&
                    ident[2] == 'L'.code.toByte() &&
                    ident[3] == 'F'.code.toByte(),
            ) { "Invalid ELF magic" }
            val is64 = when (ident[4].toInt() and 0xff) {
                1 -> false
                2 -> true
                else -> error("Unsupported ELF class")
            }
            require((ident[5].toInt() and 0xff) == 1) { "Only little-endian ELF is supported" }
            val machine = u16(raf, 18)
            val phoff = if (is64) u64(raf, 32) else u32(raf, 28)
            val shoff = if (is64) u64(raf, 40) else u32(raf, 32)
            val phentsize = u16(raf, if (is64) 54 else 42)
            val phnum = u16(raf, if (is64) 56 else 44)
            val shentsize = u16(raf, if (is64) 58 else 46)
            val shnum = u16(raf, if (is64) 60 else 48)
            require(phnum <= 1024) { "ELF program-header count exceeds limit" }
            require(shnum <= 8192) { "ELF section count exceeds limit" }
            if (phnum > 0) ensureTable(phoff, phentsize, phnum, raf.length(), "program headers")
            if (shnum > 0) ensureTable(shoff, shentsize, shnum, raf.length(), "section headers")
            return Header(is64, machine, phoff, phentsize, phnum, shoff, shentsize, shnum)
        }

        private fun readLoadSegments(
            raf: RandomAccessFile,
            h: Header,
            cancellation: CancellationSignal,
        ): List<ElfLoadSegment> {
            val out = mutableListOf<ElfLoadSegment>()
            repeat(h.phnum) { index ->
                if (index % 64 == 0) checkCancelled(cancellation)
                val base = h.phoff + index.toLong() * h.phentsize
                val type = u32(raf, base)
                if (type != PT_LOAD) return@repeat
                val flags = if (h.is64) u32(raf, base + 4) else u32(raf, base + 24)
                val fileOffset = if (h.is64) u64(raf, base + 8) else u32(raf, base + 4)
                val virtualAddress = if (h.is64) u64(raf, base + 16) else u32(raf, base + 8)
                val fileSize = if (h.is64) u64(raf, base + 32) else u32(raf, base + 16)
                val memorySize = if (h.is64) u64(raf, base + 40) else u32(raf, base + 20)
                require(fileOffset >= 0L && virtualAddress >= 0L && fileSize >= 0L && memorySize >= fileSize) {
                    "Invalid PT_LOAD values"
                }
                require(fileOffset <= raf.length() && fileSize <= raf.length() - fileOffset) {
                    "PT_LOAD file range outside ELF"
                }
                out += ElfLoadSegment(
                    virtualAddress = virtualAddress,
                    memorySize = memorySize,
                    fileOffset = fileOffset,
                    fileSize = fileSize,
                    executable = flags and PF_X != 0L,
                )
            }
            return out.sortedBy { it.virtualAddress }
        }

        private fun readSections(
            raf: RandomAccessFile,
            h: Header,
            cancellation: CancellationSignal,
        ): List<Section> {
            val out = ArrayList<Section>(h.shnum)
            repeat(h.shnum) { index ->
                if (index % 64 == 0) checkCancelled(cancellation)
                val base = h.shoff + index.toLong() * h.shentsize
                val type = u32(raf, base + 4)
                val offset = if (h.is64) u64(raf, base + 24) else u32(raf, base + 16)
                val size = if (h.is64) u64(raf, base + 32) else u32(raf, base + 20)
                val link = if (h.is64) u32(raf, base + 40).toInt() else u32(raf, base + 24).toInt()
                val entrySize = if (h.is64) u64(raf, base + 56) else u32(raf, base + 36)
                require(offset >= 0L && size >= 0L) {
                    "Invalid ELF section range"
                }
                if (type != SHT_NOBITS) {
                    require(
                        offset <= raf.length() &&
                            size <= raf.length() - offset,
                    ) {
                        "ELF section outside file"
                    }
                }
                out += Section(type, offset, size, link, entrySize)
            }
            return out
        }

        private fun readRelativeRelocations(
            raf: RandomAccessFile,
            h: Header,
            sections: List<Section>,
            loadSegments: List<ElfLoadSegment>,
            cancellation: CancellationSignal,
        ): Map<Long, Long> {
            val out = HashMap<Long, Long>()
            for (section in sections) {
                checkCancelled(cancellation)
                when (section.type) {
                    SHT_RELR,
                    SHT_ANDROID_RELR ->
                        readRelrSection(
                            raf = raf,
                            h = h,
                            section = section,
                            loadSegments = loadSegments,
                            cancellation = cancellation,
                            out = out,
                        )

                    SHT_ANDROID_REL,
                    SHT_ANDROID_RELA ->
                        readAndroidPackedRelocations(
                            raf = raf,
                            h = h,
                            section = section,
                            loadSegments = loadSegments,
                            cancellation = cancellation,
                            out = out,
                        )

                    SHT_REL,
                    SHT_RELA ->
                        readClassicRelativeRelocations(
                            raf = raf,
                            h = h,
                            section = section,
                            loadSegments = loadSegments,
                            cancellation = cancellation,
                            out = out,
                        )
                }
            }
            return out
        }

        private fun readClassicRelativeRelocations(
            raf: RandomAccessFile,
            h: Header,
            section: Section,
            loadSegments: List<ElfLoadSegment>,
            cancellation: CancellationSignal,
            out: MutableMap<Long, Long>,
        ) {
            if (section.size == 0L) return
            val minimumEntrySize =
                when {
                    h.is64 && section.type == SHT_RELA -> 24L
                    h.is64 && section.type == SHT_REL -> 16L
                    !h.is64 && section.type == SHT_RELA -> 12L
                    else -> 8L
                }
            val entrySize =
                section.entrySize
                    .takeIf { it >= minimumEntrySize }
                    ?: minimumEntrySize
            val count = section.size / entrySize
            require(count <= MAX_RELATIVE_RELOCATIONS.toLong()) {
                "ELF relocation section exceeds bounded entry limit"
            }

            repeat(count.toInt()) { index ->
                if (index % 1024 == 0) {
                    checkCancelled(cancellation)
                }
                val base =
                    section.offset +
                        index.toLong() * entrySize
                val relocationOffset =
                    if (h.is64) {
                        u64(raf, base)
                    } else {
                        u32(raf, base)
                    }
                val info =
                    if (h.is64) {
                        u64(raf, base + 8)
                    } else {
                        u32(raf, base + 4)
                    }
                val symbolIndex =
                    if (h.is64) {
                        info ushr 32
                    } else {
                        info ushr 8
                    }
                val relocationType =
                    if (h.is64) {
                        (info and 0xffffffffL).toInt()
                    } else {
                        (info and 0xffL).toInt()
                    }
                if (
                    symbolIndex != 0L ||
                    !isRelativeRelocation(
                        machine = h.machine,
                        type = relocationType,
                    )
                ) {
                    return@repeat
                }

                val value =
                    if (section.type == SHT_RELA) {
                        if (h.is64) {
                            u64(raf, base + 16)
                        } else {
                            u32(raf, base + 8)
                        }
                    } else {
                        rawPointerAtVa(
                            raf = raf,
                            h = h,
                            loadSegments = loadSegments,
                            virtualAddress = relocationOffset,
                        ) ?: return@repeat
                    }
                recordRelativeRelocation(
                    out = out,
                    offset = relocationOffset,
                    value = value,
                )
            }
        }

        private fun readRelrSection(
            raf: RandomAccessFile,
            h: Header,
            section: Section,
            loadSegments: List<ElfLoadSegment>,
            cancellation: CancellationSignal,
            out: MutableMap<Long, Long>,
        ) {
            if (section.size == 0L) return
            val pointerSize = if (h.is64) 8L else 4L
            val entrySize =
                section.entrySize
                    .takeIf { it >= pointerSize }
                    ?: pointerSize
            require(section.size % entrySize == 0L) {
                "ELF RELR section size is not entry aligned"
            }
            val count = section.size / entrySize
            require(count <= MAX_RELATIVE_RELOCATIONS.toLong()) {
                "ELF RELR section exceeds bounded entry limit"
            }

            var relocationOffset = 0L
            repeat(count.toInt()) { index ->
                if (index % 1024 == 0) {
                    checkCancelled(cancellation)
                }
                val entryOffset =
                    section.offset +
                        index.toLong() * entrySize
                val entry =
                    if (h.is64) {
                        u64(raf, entryOffset)
                    } else {
                        u32(raf, entryOffset)
                    }

                if ((entry and 1L) == 0L) {
                    val value =
                        rawPointerAtVa(
                            raf = raf,
                            h = h,
                            loadSegments = loadSegments,
                            virtualAddress = entry,
                        )
                    if (value != null) {
                        recordRelativeRelocation(
                            out = out,
                            offset = entry,
                            value = value,
                        )
                    }
                    relocationOffset = safeAdd(
                        entry,
                        pointerSize,
                        "ELF RELR relocation offset overflow",
                    )
                } else {
                    val bitCount = entrySize.toInt() * 8
                    for (bitIndex in 1 until bitCount) {
                        if (
                            (entry and
                                (1L shl bitIndex)) != 0L
                        ) {
                            val value =
                                rawPointerAtVa(
                                    raf = raf,
                                    h = h,
                                    loadSegments = loadSegments,
                                    virtualAddress =
                                        relocationOffset,
                                )
                            if (value != null) {
                                recordRelativeRelocation(
                                    out = out,
                                    offset =
                                        relocationOffset,
                                    value = value,
                                )
                            }
                        }
                        relocationOffset = safeAdd(
                            relocationOffset,
                            pointerSize,
                            "ELF RELR bitmap offset overflow",
                        )
                    }
                }
            }
        }

        private fun readAndroidPackedRelocations(
            raf: RandomAccessFile,
            h: Header,
            section: Section,
            loadSegments: List<ElfLoadSegment>,
            cancellation: CancellationSignal,
            out: MutableMap<Long, Long>,
        ) {
            if (section.size == 0L) return
            require(
                section.size <=
                    MAX_PACKED_RELOCATION_BYTES.toLong(),
            ) {
                "Android packed relocation section exceeds bounded byte limit"
            }
            require(section.size <= Int.MAX_VALUE.toLong()) {
                "Android packed relocation section is too large"
            }

            val bytes = ByteArray(section.size.toInt())
            raf.seek(section.offset)
            raf.readFully(bytes)
            require(
                bytes.size >= 4 &&
                    bytes[0] == 'A'.code.toByte() &&
                    bytes[1] == 'P'.code.toByte() &&
                    bytes[2] == 'S'.code.toByte() &&
                    bytes[3] == '2'.code.toByte(),
            ) {
                "Invalid Android APS2 relocation header"
            }

            val reader = Sleb128Reader(bytes, 4)
            val totalCount = reader.read()
            require(
                totalCount in
                    0..MAX_RELATIVE_RELOCATIONS.toLong(),
            ) {
                "Android packed relocation count exceeds bounded limit"
            }
            var currentCount = 0L
            var relocationOffset = reader.read()
            require(relocationOffset >= 0L) {
                "Android packed relocation offset is negative"
            }
            var addend = 0L

            while (currentCount < totalCount) {
                checkCancelled(cancellation)
                val groupSize = reader.read()
                val groupFlags = reader.read()
                require(
                    groupSize > 0L &&
                        groupSize <= totalCount - currentCount
                ) {
                    "Invalid Android packed relocation group size"
                }
                require(
                    groupFlags >= 0L &&
                        groupFlags and
                            RELOCATION_GROUP_KNOWN_FLAGS.inv() == 0L
                ) {
                    "Unsupported Android packed relocation flags"
                }

                val groupedByInfo =
                    groupFlags and
                        RELOCATION_GROUPED_BY_INFO_FLAG != 0L
                val groupedByOffsetDelta =
                    groupFlags and
                        RELOCATION_GROUPED_BY_OFFSET_DELTA_FLAG != 0L
                val groupedByAddend =
                    groupFlags and
                        RELOCATION_GROUPED_BY_ADDEND_FLAG != 0L
                val hasAddend =
                    groupFlags and
                        RELOCATION_GROUP_HAS_ADDEND_FLAG != 0L

                val groupOffsetDelta =
                    if (groupedByOffsetDelta) {
                        reader.read()
                    } else {
                        0L
                    }
                var info =
                    if (groupedByInfo) {
                        reader.read()
                    } else {
                        0L
                    }
                if (hasAddend && groupedByAddend) {
                    addend = safeSignedAdd(
                        addend,
                        reader.read(),
                        "Android packed relocation addend overflow",
                    )
                }
                if (!hasAddend) {
                    addend = 0L
                }

                repeat(groupSize.toInt()) {
                    relocationOffset = safeSignedAdd(
                        relocationOffset,
                        if (groupedByOffsetDelta) {
                            groupOffsetDelta
                        } else {
                            reader.read()
                        },
                        "Android packed relocation offset overflow",
                    )
                    require(relocationOffset >= 0L) {
                        "Android packed relocation offset is negative"
                    }

                    if (!groupedByInfo) {
                        info = reader.read()
                    }
                    if (hasAddend && !groupedByAddend) {
                        addend = safeSignedAdd(
                            addend,
                            reader.read(),
                            "Android packed relocation addend overflow",
                        )
                    }

                    val symbolIndex =
                        if (h.is64) {
                            info ushr 32
                        } else {
                            info ushr 8
                        }
                    val relocationType =
                        if (h.is64) {
                            (info and
                                0xffffffffL).toInt()
                        } else {
                            (info and 0xffL).toInt()
                        }
                    if (
                        symbolIndex == 0L &&
                        isRelativeRelocation(
                            machine = h.machine,
                            type = relocationType,
                        )
                    ) {
                        val value =
                            if (
                                section.type ==
                                SHT_ANDROID_RELA
                            ) {
                                addend
                            } else {
                                rawPointerAtVa(
                                    raf = raf,
                                    h = h,
                                    loadSegments =
                                        loadSegments,
                                    virtualAddress =
                                        relocationOffset,
                                )
                            }
                        if (value != null) {
                            recordRelativeRelocation(
                                out = out,
                                offset =
                                    relocationOffset,
                                value = value,
                            )
                        }
                    }
                }
                currentCount += groupSize
            }
            require(!reader.hasTrailingNonZeroBytes()) {
                "Unexpected trailing Android packed relocation data"
            }
        }

        private fun recordRelativeRelocation(
            out: MutableMap<Long, Long>,
            offset: Long,
            value: Long,
        ) {
            if (offset < 0L || value <= 0L) return
            require(
                out.size < MAX_RELATIVE_RELOCATIONS ||
                    offset in out,
            ) {
                "ELF relative relocation inventory exceeds bounded limit"
            }
            out[offset] = value
        }

        private fun safeAdd(
            left: Long,
            right: Long,
            message: String,
        ): Long {
            require(left >= 0L && right >= 0L) { message }
            require(left <= Long.MAX_VALUE - right) { message }
            return left + right
        }

        private fun safeSignedAdd(
            left: Long,
            right: Long,
            message: String,
        ): Long {
            if (right > 0L) {
                require(left <= Long.MAX_VALUE - right) {
                    message
                }
            } else if (right < 0L) {
                require(left >= Long.MIN_VALUE - right) {
                    message
                }
            }
            return left + right
        }

        private class Sleb128Reader(
            private val bytes: ByteArray,
            start: Int,
        ) {
            private var offset = start

            fun read(): Long {
                var result = 0L
                var shift = 0
                var last = 0
                while (true) {
                    require(offset < bytes.size) {
                        "Truncated Android APS2 relocation stream"
                    }
                    last = bytes[offset++].toInt() and 0xff
                    val payload = last and 0x7f
                    require(shift < 64) {
                        "Android APS2 SLEB128 value is too wide"
                    }
                    result =
                        result or
                            (payload.toLong() shl shift)
                    shift += 7
                    if (last and 0x80 == 0) break
                }
                if (
                    shift < 64 &&
                    last and 0x40 != 0
                ) {
                    result =
                        result or
                            (-1L shl shift)
                }
                return result
            }

            fun hasTrailingNonZeroBytes(): Boolean {
                while (offset < bytes.size) {
                    if (bytes[offset++].toInt() != 0) {
                        return true
                    }
                }
                return false
            }
        }

        private fun isRelativeRelocation(
            machine: Int,
            type: Int,
        ): Boolean =
            when (machine) {
                183 -> type == 1027 // R_AARCH64_RELATIVE
                40 -> type == 23 // R_ARM_RELATIVE
                62 -> type == 8 // R_X86_64_RELATIVE
                3 -> type == 8 // R_386_RELATIVE
                else -> false
            }

        private fun rawPointerAtVa(
            raf: RandomAccessFile,
            h: Header,
            loadSegments: List<ElfLoadSegment>,
            virtualAddress: Long,
        ): Long? {
            val size = if (h.is64) 8L else 4L
            val segment =
                loadSegments.firstOrNull { candidate ->
                    if (virtualAddress < candidate.virtualAddress) {
                        false
                    } else {
                        val relative =
                            virtualAddress -
                                candidate.virtualAddress
                        relative >= 0L &&
                            relative <= candidate.fileSize &&
                            size <= candidate.fileSize - relative
                    }
                } ?: return null
            val fileOffset =
                segment.fileOffset +
                    (virtualAddress -
                        segment.virtualAddress)
            return if (h.is64) {
                u64(raf, fileOffset)
            } else {
                u32(raf, fileOffset)
            }
        }

        private fun readDynamicSymbols(
            raf: RandomAccessFile,
            h: Header,
            sections: List<Section>,
            cancellation: CancellationSignal,
        ): List<ElfDynamicSymbol> {
            val dynsym =
                sections.firstOrNull { it.type == SHT_DYNSYM }
                    ?: return emptyList()
            val strtab =
                sections.getOrNull(dynsym.link)
                    ?: return emptyList()
            if (
                dynsym.type == SHT_NOBITS ||
                strtab.type == SHT_NOBITS
            ) {
                return emptyList()
            }
            val minEntrySize = if (h.is64) 24L else 16L
            val entrySize = dynsym.entrySize.takeIf { it >= minEntrySize } ?: minEntrySize
            val count = minOf(dynsym.size / entrySize, 100_000L).toInt()
            val out = mutableListOf<ElfDynamicSymbol>()
            repeat(count) { index ->
                if (index % 256 == 0) checkCancelled(cancellation)
                val base = dynsym.offset + index.toLong() * entrySize
                val nameOffset = u32(raf, base)
                val info = if (h.is64) u8(raf, base + 4) else u8(raf, base + 12)
                val sectionIndex = if (h.is64) u16(raf, base + 6) else u16(raf, base + 14)
                val value = if (h.is64) u64(raf, base + 8) else u32(raf, base + 4)
                val size = if (h.is64) u64(raf, base + 16) else u32(raf, base + 8)
                val name = readCString(raf, strtab.offset, strtab.size, nameOffset, 4096)
                if (name.isBlank()) return@repeat
                out += ElfDynamicSymbol(
                    name = name,
                    value = value,
                    size = size,
                    defined = sectionIndex != SHN_UNDEF,
                    binding = info ushr 4,
                    type = info and 0x0f,
                )
            }
            return out
        }

        private fun readCString(
            raf: RandomAccessFile,
            tableOffset: Long,
            tableSize: Long,
            relativeOffset: Long,
            maxBytes: Int,
        ): String {
            if (relativeOffset < 0L || relativeOffset >= tableSize) return ""
            raf.seek(tableOffset + relativeOffset)
            val end = tableOffset + tableSize
            val out = ByteArray(maxBytes)
            var count = 0
            while (raf.filePointer < end && count < maxBytes) {
                val value = raf.read()
                if (value <= 0) break
                out[count++] = value.toByte()
            }
            return String(out, 0, count, Charsets.UTF_8)
        }

        private fun u8(raf: RandomAccessFile, offset: Long): Int {
            raf.seek(offset)
            return raf.readUnsignedByte()
        }

        private fun u16(raf: RandomAccessFile, offset: Long): Int {
            raf.seek(offset)
            return raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)
        }

        private fun u32(raf: RandomAccessFile, offset: Long): Long {
            raf.seek(offset)
            val b0 = raf.readUnsignedByte().toLong()
            val b1 = raf.readUnsignedByte().toLong()
            val b2 = raf.readUnsignedByte().toLong()
            val b3 = raf.readUnsignedByte().toLong()
            return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        }

        private fun u64(raf: RandomAccessFile, offset: Long): Long {
            val lo = u32(raf, offset)
            val hi = u32(raf, offset + 4)
            require(hi and 0x80000000L == 0L) { "ELF 64-bit value exceeds parser range" }
            return lo or (hi shl 32)
        }

        private fun ensureTable(
            offset: Long,
            entrySize: Int,
            count: Int,
            fileSize: Long,
            label: String,
        ) {
            require(offset >= 0L && entrySize > 0 && count >= 0) { "Invalid " + label }
            val bytes = entrySize.toLong() * count.toLong()
            require(offset <= fileSize && bytes <= fileSize - offset) { label + " outside ELF" }
        }

        private fun checkCancelled(cancellation: CancellationSignal) {
            if (cancellation.isCancelled()) throw AnalysisCancelledException()
        }
    }
}
