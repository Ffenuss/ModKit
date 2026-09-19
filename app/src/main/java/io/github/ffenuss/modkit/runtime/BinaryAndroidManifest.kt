package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

data class BinaryAndroidManifestInfo(
    val packageName: String,
    val splitName: String?,
    val applicationClassName: String?,
    val applicationElementPresent: Boolean,
    val applicationStartOffset: Int?,
    val applicationEndOffset: Int?,
    val debuggable: Boolean?,
    val manifestSha256: String,
    val manifestSize: Int,
)

/**
 * Bounded reader for compiled Android binary XML manifests.
 *
 * This parser is intentionally narrow: it extracts only identity/runtime
 * prerequisites needed by the repacked-test path. Unknown chunks are skipped.
 * Malformed offsets, oversized pools, unsupported encodings and truncated
 * attributes fail closed instead of returning guessed package/application data.
 */
object BinaryAndroidManifestInspector {
    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_BOOLEAN = 0x12
    private const val UTF8_FLAG = 0x00000100
    private const val NO_INDEX = -1
    private const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
    private const val MAX_STRINGS = 100_000
    private const val MAX_ATTRIBUTES = 1024
    private const val ANDROID_NS =
        "http://schemas.android.com/apk/res/android"

    fun inspectApk(
        apk: File,
        cancellation: CancellationSignal,
    ): BinaryAndroidManifestInfo {
        require(apk.isFile && apk.canRead()) {
            "APK is unavailable for manifest inspection."
        }

        val manifestBytes = ZipFile(apk).use { zip ->
            val entry = zip.getEntry("AndroidManifest.xml")
                ?: error("APK has no AndroidManifest.xml.")
            require(!entry.isDirectory) {
                "AndroidManifest.xml is a directory."
            }
            if (entry.size >= 0L) {
                require(entry.size in 1..MAX_MANIFEST_BYTES.toLong()) {
                    "AndroidManifest.xml exceeds the bounded parser limit."
                }
            }

            zip.getInputStream(entry).use { input ->
                val output = ByteArrayOutputStream(
                    entry.size
                        .takeIf { it in 1..MAX_MANIFEST_BYTES.toLong() }
                        ?.toInt()
                        ?: 16 * 1024,
                )
                val buffer = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    checkCancelled(cancellation)
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    total += read
                    require(total <= MAX_MANIFEST_BYTES) {
                        "AndroidManifest.xml exceeded the bounded parser limit."
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        }

        return inspect(manifestBytes)
    }

    fun inspect(bytes: ByteArray): BinaryAndroidManifestInfo {
        require(bytes.size in 8..MAX_MANIFEST_BYTES) {
            "Binary AndroidManifest.xml size is outside parser limits."
        }
        require(u16(bytes, 0) == RES_XML_TYPE) {
            "AndroidManifest.xml is not compiled binary XML."
        }
        val rootHeaderSize = u16(bytes, 2)
        val rootSize = u32(bytes, 4).toIntChecked("XML chunk size")
        require(rootHeaderSize >= 8 && rootHeaderSize <= rootSize) {
            "Invalid binary XML root header size."
        }
        require(rootSize <= bytes.size) {
            "Binary XML root chunk is truncated."
        }

        val pool = findStringPool(bytes, rootHeaderSize, rootSize)
        val strings = StringPoolReader(bytes, pool)

        var packageName: String? = null
        var splitName: String? = null
        var rawApplicationName: String? = null
        var debuggable: Boolean? = null
        var applicationStart: Int? = null
        var applicationEnd: Int? = null
        var applicationDepth: Int? = null
        var depth = 0

        var cursor = rootHeaderSize
        while (cursor < rootSize) {
            val header = chunkHeader(bytes, cursor, rootSize)
            when (header.type) {
                RES_XML_START_ELEMENT_TYPE -> {
                    val element = parseStartElement(
                        bytes = bytes,
                        offset = cursor,
                        header = header,
                        strings = strings,
                    )
                    when (element.name) {
                        "manifest" -> {
                            packageName = element.attributes
                                .firstOrNull {
                                    it.name == "package" &&
                                        it.namespace == null
                                }
                                ?.stringValue
                                ?.takeIf { it.isNotBlank() }
                                ?: packageName
                            splitName = element.attributes
                                .firstOrNull {
                                    it.name == "split" &&
                                        it.namespace == null
                                }
                                ?.stringValue
                                ?.takeIf { it.isNotBlank() }
                        }

                        "application" -> {
                            require(applicationStart == null) {
                                "Manifest contains multiple <application> elements."
                            }
                            applicationStart = cursor
                            applicationDepth = depth
                            rawApplicationName = element.attributes
                                .firstOrNull {
                                    it.name == "name" &&
                                        it.namespace == ANDROID_NS
                                }
                                ?.stringValue
                                ?.takeIf { it.isNotBlank() }
                            debuggable = element.attributes
                                .firstOrNull {
                                    it.name == "debuggable" &&
                                        it.namespace == ANDROID_NS
                                }
                                ?.booleanValue
                        }
                    }
                    depth++
                }

                RES_XML_END_ELEMENT_TYPE -> {
                    require(depth > 0) {
                        "Binary XML element nesting underflow."
                    }
                    depth--
                    val name = parseEndElementName(
                        bytes = bytes,
                        offset = cursor,
                        header = header,
                        strings = strings,
                    )
                    if (
                        name == "application" &&
                        applicationDepth != null &&
                        depth == applicationDepth
                    ) {
                        require(applicationEnd == null) {
                            "Manifest contains multiple </application> elements."
                        }
                        applicationEnd = cursor
                    }
                }
            }
            cursor += header.size
        }

        require(depth == 0) {
            "Binary XML element nesting is incomplete."
        }
        val resolvedPackage = requireNotNull(packageName) {
            "Manifest package name was not resolved."
        }
        require(isPlausiblePackageName(resolvedPackage)) {
            "Manifest package name is invalid."
        }
        require((applicationStart == null) == (applicationEnd == null)) {
            "Manifest application element is incomplete."
        }

        return BinaryAndroidManifestInfo(
            packageName = resolvedPackage,
            splitName = splitName,
            applicationClassName = normalizeClassName(
                packageName = resolvedPackage,
                rawName = rawApplicationName,
            ),
            applicationElementPresent = applicationStart != null,
            applicationStartOffset = applicationStart,
            applicationEndOffset = applicationEnd,
            debuggable = debuggable,
            manifestSha256 = sha256(bytes.copyOf(rootSize)),
            manifestSize = rootSize,
        )
    }

    private data class ChunkHeader(
        val type: Int,
        val headerSize: Int,
        val size: Int,
    )

    private data class StringPoolChunk(
        val offset: Int,
        val header: ChunkHeader,
    )

    private data class ParsedAttribute(
        val namespace: String?,
        val name: String,
        val stringValue: String?,
        val booleanValue: Boolean?,
    )

    private data class ParsedElement(
        val name: String,
        val attributes: List<ParsedAttribute>,
    )

    private fun findStringPool(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): StringPoolChunk {
        var cursor = start
        var found: StringPoolChunk? = null
        while (cursor < end) {
            val header = chunkHeader(bytes, cursor, end)
            if (header.type == RES_STRING_POOL_TYPE) {
                require(found == null) {
                    "Binary XML contains multiple string pools."
                }
                found = StringPoolChunk(cursor, header)
            }
            cursor += header.size
        }
        return requireNotNull(found) {
            "Binary XML string pool is missing."
        }
    }

    private fun parseStartElement(
        bytes: ByteArray,
        offset: Int,
        header: ChunkHeader,
        strings: StringPoolReader,
    ): ParsedElement {
        require(header.headerSize >= 16 && header.size >= 36) {
            "Start-element chunk is truncated."
        }
        val nameIndex = s32(bytes, offset + 20)
        val name = strings.get(nameIndex)
        require(name.isNotBlank()) {
            "Start-element name is empty."
        }

        val attributeStart = u16(bytes, offset + 24)
        val attributeSize = u16(bytes, offset + 26)
        val attributeCount = u16(bytes, offset + 28)
        require(attributeCount <= MAX_ATTRIBUTES) {
            "Start-element attribute count exceeds parser limit."
        }
        require(attributeSize >= 20) {
            "Binary XML attribute size is unsupported."
        }

        val attributesOffset = safeAdd(
            offset + 16,
            attributeStart,
            "attribute start",
        )
        val attributesBytes = safeMultiply(
            attributeSize,
            attributeCount,
            "attribute table",
        )
        require(
            attributesOffset >= offset + header.headerSize &&
                attributesOffset <= offset + header.size &&
                attributesBytes <= offset + header.size - attributesOffset
        ) {
            "Binary XML attribute table is outside the element chunk."
        }

        val attributes = ArrayList<ParsedAttribute>(attributeCount)
        repeat(attributeCount) { index ->
            val base = attributesOffset + index * attributeSize
            val namespaceIndex = s32(bytes, base)
            val attributeNameIndex = s32(bytes, base + 4)
            val rawValueIndex = s32(bytes, base + 8)
            val typedSize = u16(bytes, base + 12)
            val res0 = u8(bytes, base + 14)
            val dataType = u8(bytes, base + 15)
            val data = u32(bytes, base + 16)

            require(typedSize >= 8 && res0 == 0) {
                "Binary XML typed attribute value is malformed."
            }

            val namespace = if (namespaceIndex == NO_INDEX) {
                null
            } else {
                strings.get(namespaceIndex)
            }
            val attributeName = strings.get(attributeNameIndex)
            require(attributeName.isNotBlank()) {
                "Binary XML attribute name is empty."
            }

            val stringValue = when {
                rawValueIndex != NO_INDEX ->
                    strings.get(rawValueIndex)
                dataType == TYPE_STRING ->
                    strings.get(data.toIntChecked("string value index"))
                else -> null
            }
            val booleanValue = when {
                dataType == TYPE_INT_BOOLEAN -> data != 0L
                stringValue.equals("true", ignoreCase = true) -> true
                stringValue.equals("false", ignoreCase = true) -> false
                else -> null
            }

            attributes += ParsedAttribute(
                namespace = namespace,
                name = attributeName,
                stringValue = stringValue,
                booleanValue = booleanValue,
            )
        }

        return ParsedElement(
            name = name,
            attributes = attributes,
        )
    }

    private fun parseEndElementName(
        bytes: ByteArray,
        offset: Int,
        header: ChunkHeader,
        strings: StringPoolReader,
    ): String {
        require(header.headerSize >= 16 && header.size >= 24) {
            "End-element chunk is truncated."
        }
        return strings.get(s32(bytes, offset + 20))
    }

    private class StringPoolReader(
        private val bytes: ByteArray,
        chunk: StringPoolChunk,
    ) {
        private val chunkOffset = chunk.offset
        private val chunkEnd = chunk.offset + chunk.header.size
        private val stringCount: Int
        private val stringsStart: Int
        private val utf8: Boolean
        private val offsets: IntArray

        init {
            require(chunk.header.headerSize >= 28) {
                "Binary XML string-pool header is truncated."
            }
            stringCount = u32(bytes, chunkOffset + 8)
                .toIntChecked("string count")
            require(stringCount in 1..MAX_STRINGS) {
                "Binary XML string count exceeds parser limits."
            }
            val styleCount = u32(bytes, chunkOffset + 12)
                .toIntChecked("style count")
            val flags = u32(bytes, chunkOffset + 16)
            stringsStart = u32(bytes, chunkOffset + 20)
                .toIntChecked("strings start")
            val stylesStart = u32(bytes, chunkOffset + 24)
                .toIntChecked("styles start")
            utf8 = flags and UTF8_FLAG.toLong() != 0L

            val offsetTableStart = chunkOffset + chunk.header.headerSize
            val offsetBytes = safeMultiply(
                stringCount,
                4,
                "string-offset table",
            )
            val styleOffsetBytes = safeMultiply(
                styleCount,
                4,
                "style-offset table",
            )
            require(
                offsetTableStart <= chunkEnd &&
                    offsetBytes <= chunkEnd - offsetTableStart &&
                    styleOffsetBytes <=
                    chunkEnd - offsetTableStart - offsetBytes
            ) {
                "Binary XML string-pool offset table is truncated."
            }

            val stringDataStart = safeAdd(
                chunkOffset,
                stringsStart,
                "string-data start",
            )
            require(stringDataStart in offsetTableStart..chunkEnd) {
                "Binary XML string data starts outside string-pool chunk."
            }
            if (stylesStart != 0) {
                val styleDataStart = safeAdd(
                    chunkOffset,
                    stylesStart,
                    "style-data start",
                )
                require(styleDataStart in stringDataStart..chunkEnd) {
                    "Binary XML style data starts outside string-pool chunk."
                }
            }

            offsets = IntArray(stringCount) { index ->
                u32(bytes, offsetTableStart + index * 4)
                    .toIntChecked("string offset")
            }
        }

        fun get(index: Int): String {
            require(index in 0 until stringCount) {
                "Binary XML string index is outside the string pool."
            }
            val start = safeAdd(
                chunkOffset + stringsStart,
                offsets[index],
                "string address",
            )
            require(start in chunkOffset until chunkEnd) {
                "Binary XML string starts outside string-pool data."
            }
            return if (utf8) {
                decodeUtf8(start)
            } else {
                decodeUtf16(start)
            }
        }

        private fun decodeUtf8(start: Int): String {
            val (_, afterUtf16Length) = decodeLength8(start)
            val (byteLength, dataStart) = decodeLength8(afterUtf16Length)
            require(byteLength >= 0 && dataStart <= chunkEnd) {
                "Invalid UTF-8 string length."
            }
            require(byteLength <= chunkEnd - dataStart - 1) {
                "UTF-8 string is truncated."
            }
            require(bytes[dataStart + byteLength] == 0.toByte()) {
                "UTF-8 string terminator is missing."
            }
            return String(
                bytes,
                dataStart,
                byteLength,
                Charsets.UTF_8,
            )
        }

        private fun decodeUtf16(start: Int): String {
            val first = u16At(start)
            val (charLength, dataStart) = if (first and 0x8000 != 0) {
                val second = u16At(start + 2)
                val length =
                    ((first and 0x7fff) shl 16) or second
                length to (start + 4)
            } else {
                first to (start + 2)
            }
            val byteLength = safeMultiply(
                charLength,
                2,
                "UTF-16 string bytes",
            )
            require(
                dataStart <= chunkEnd &&
                    byteLength <= chunkEnd - dataStart - 2
            ) {
                "UTF-16 string is truncated."
            }
            require(u16At(dataStart + byteLength) == 0) {
                "UTF-16 string terminator is missing."
            }
            return String(
                bytes,
                dataStart,
                byteLength,
                Charsets.UTF_16LE,
            )
        }

        private fun decodeLength8(offset: Int): Pair<Int, Int> {
            require(offset < chunkEnd) {
                "UTF-8 length is truncated."
            }
            val first = u8(bytes, offset)
            return if (first and 0x80 != 0) {
                require(offset + 1 < chunkEnd) {
                    "UTF-8 extended length is truncated."
                }
                (((first and 0x7f) shl 8) or
                    u8(bytes, offset + 1)) to (offset + 2)
            } else {
                first to (offset + 1)
            }
        }

        private fun u16At(offset: Int): Int {
            require(offset >= chunkOffset && offset + 1 < chunkEnd) {
                "UTF-16 length is outside string-pool chunk."
            }
            return u16(bytes, offset)
        }
    }

    private fun chunkHeader(
        bytes: ByteArray,
        offset: Int,
        parentEnd: Int,
    ): ChunkHeader {
        require(offset >= 0 && offset + 8 <= parentEnd) {
            "Binary XML chunk header is truncated."
        }
        val type = u16(bytes, offset)
        val headerSize = u16(bytes, offset + 2)
        val size = u32(bytes, offset + 4).toIntChecked("chunk size")
        require(headerSize >= 8 && size >= headerSize) {
            "Binary XML chunk size is invalid."
        }
        require(size <= parentEnd - offset) {
            "Binary XML child chunk exceeds its parent."
        }
        return ChunkHeader(type, headerSize, size)
    }

    private fun normalizeClassName(
        packageName: String,
        rawName: String?,
    ): String? {
        val name = rawName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            name.startsWith(".") -> packageName + name
            '.' !in name -> packageName + "." + name
            else -> name
        }
    }

    private fun isPlausiblePackageName(value: String): Boolean =
        value.length in 3..255 &&
            value.contains('.') &&
            value.split('.').all { part ->
                part.isNotEmpty() &&
                    (part[0].isLetter() || part[0] == '_') &&
                    part.all {
                        it.isLetterOrDigit() || it == '_'
                    }
            }

    private fun safeAdd(
        a: Int,
        b: Int,
        label: String,
    ): Int =
        runCatching { Math.addExact(a, b) }
            .getOrElse { error("$label overflow.") }

    private fun safeMultiply(
        a: Int,
        b: Int,
        label: String,
    ): Int =
        runCatching { Math.multiplyExact(a, b) }
            .getOrElse { error("$label overflow.") }

    private fun Long.toIntChecked(label: String): Int {
        require(this in 0..Int.MAX_VALUE.toLong()) {
            "$label exceeds parser range."
        }
        return toInt()
    }

    private fun u8(bytes: ByteArray, offset: Int): Int {
        require(offset in bytes.indices) {
            "Binary XML read is outside buffer."
        }
        return bytes[offset].toInt() and 0xff
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        require(offset >= 0 && offset + 1 < bytes.size) {
            "Binary XML u16 read is outside buffer."
        }
        return (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun s32(bytes: ByteArray, offset: Int): Int =
        u32(bytes, offset).toInt()

    private fun u32(bytes: ByteArray, offset: Int): Long {
        require(offset >= 0 && offset + 3 < bytes.size) {
            "Binary XML u32 read is outside buffer."
        }
        return (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
    }
}
