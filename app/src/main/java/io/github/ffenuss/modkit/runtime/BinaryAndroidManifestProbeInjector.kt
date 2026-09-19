package io.github.ffenuss.modkit.runtime

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

data class BinaryManifestProbeInjectionResult(
    val bytes: ByteArray,
    val packageName: String,
    val providerClassName: String,
    val authority: String,
    val beforeSha256: String,
    val afterSha256: String,
    val alreadyPresent: Boolean,
)

/**
 * Test-only binary AndroidManifest.xml rewriter.
 *
 * The injector adds one explicit ContentProvider under <application>. It does
 * not alter the package name, application class, SDK declarations or existing
 * components. The provider payload is a separate capability and therefore this
 * primitive alone is not enough to make repacked runtime available.
 */
object BinaryAndroidManifestProbeInjector {
    const val PROVIDER_CLASS =
        "io.github.ffenuss.modkit.runtimeprobe.RuntimeEvidenceProvider"
    const val AUTHORITY_SUFFIX = ".modkit.runtimeprobe"

    private const val RES_XML_TYPE = 0x0003
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_START_ELEMENT_TYPE = 0x0102
    private const val RES_XML_END_ELEMENT_TYPE = 0x0103
    private const val UTF8_FLAG = 0x00000100
    private const val SORTED_FLAG = 0x00000001
    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_BOOLEAN = 0x12
    private const val NO_INDEX = -1
    private const val MAX_XML_BYTES = 4 * 1024 * 1024
    private const val MAX_STRINGS = 100_000
    private const val MAX_ATTRIBUTES = 1024
    private const val ANDROID_NS =
        "http://schemas.android.com/apk/res/android"

    fun inject(
        bytes: ByteArray,
    ): BinaryManifestProbeInjectionResult {
        val manifest = BinaryAndroidManifestInspector.inspect(bytes)
        require(manifest.applicationElementPresent) {
            "Manifest has no <application> element for runtime probe injection."
        }
        val applicationEnd = requireNotNull(manifest.applicationEndOffset)
        val authority = manifest.packageName + AUTHORITY_SUFFIX
        val beforeSha = sha256(bytes.copyOf(manifest.manifestSize))

        val document = ParsedDocument.parse(
            bytes = bytes,
            rootSize = manifest.manifestSize,
        )
        val currentProvider = document.findProvider(PROVIDER_CLASS)
        if (currentProvider != null) {
            require(currentProvider.authority == authority) {
                "Runtime probe provider class already exists with another authority."
            }
            return BinaryManifestProbeInjectionResult(
                bytes = bytes.copyOf(manifest.manifestSize),
                packageName = manifest.packageName,
                providerClassName = PROVIDER_CLASS,
                authority = authority,
                beforeSha256 = beforeSha,
                afterSha256 = beforeSha,
                alreadyPresent = true,
            )
        }

        val editor = MutableStringPool(
            strings = document.stringPool.strings.toMutableList(),
            utf8 = document.stringPool.utf8,
            flags = document.stringPool.flags,
            resourceIds = document.resourceIds.toMutableList(),
        )
        val androidNs = editor.ensurePlain(ANDROID_NS)
        val providerTag = editor.ensurePlain("provider")
        val nameAttribute = editor.ensureResource(
            value = "name",
            resourceId = android.R.attr.name,
        )
        val authoritiesAttribute = editor.ensureResource(
            value = "authorities",
            resourceId = android.R.attr.authorities,
        )
        val exportedAttribute = editor.ensureResource(
            value = "exported",
            resourceId = android.R.attr.exported,
        )
        val providerClass = editor.ensurePlain(PROVIDER_CLASS)
        val authorityString = editor.ensurePlain(authority)
        val trueString = editor.ensurePlain("true")

        val providerChunks = providerElement(
            providerTag = providerTag,
            androidNs = androidNs,
            nameAttribute = nameAttribute,
            authoritiesAttribute = authoritiesAttribute,
            exportedAttribute = exportedAttribute,
            providerClass = providerClass,
            authority = authorityString,
            trueString = trueString,
        )

        val newStringPool = editor.buildStringPool()
        val newResourceMap = editor.buildResourceMap()

        val output = ByteArrayOutputStream(
            manifest.manifestSize +
                providerChunks.size +
                newStringPool.size,
        )
        output.write(
            bytes,
            0,
            document.rootHeaderSize,
        )

        var resourceMapWritten = false
        document.chunks.forEach { chunk ->
            when (chunk.type) {
                RES_STRING_POOL_TYPE -> {
                    output.write(newStringPool)
                    if (document.resourceMapChunk == null) {
                        output.write(newResourceMap)
                        resourceMapWritten = true
                    }
                }

                RES_XML_RESOURCE_MAP_TYPE -> {
                    require(!resourceMapWritten) {
                        "Binary XML contains multiple resource maps."
                    }
                    output.write(newResourceMap)
                    resourceMapWritten = true
                }

                else -> {
                    if (chunk.offset == applicationEnd) {
                        output.write(providerChunks)
                    }
                    output.write(
                        bytes,
                        chunk.offset,
                        chunk.size,
                    )
                }
            }
        }

        require(resourceMapWritten) {
            "Could not write binary XML resource map."
        }

        val rewritten = output.toByteArray()
        require(rewritten.size <= MAX_XML_BYTES) {
            "Rewritten AndroidManifest.xml exceeds bounded size."
        }
        putU32(rewritten, 4, rewritten.size)

        val verified = BinaryAndroidManifestInspector.inspect(rewritten)
        require(verified.packageName == manifest.packageName) {
            "Manifest package changed during probe injection."
        }
        require(verified.applicationClassName == manifest.applicationClassName) {
            "Application class changed during probe injection."
        }

        val roundTrip = ParsedDocument.parse(
            bytes = rewritten,
            rootSize = rewritten.size,
        ).findProvider(PROVIDER_CLASS)
        require(roundTrip?.authority == authority) {
            "Injected runtime probe provider did not survive binary XML round-trip."
        }

        return BinaryManifestProbeInjectionResult(
            bytes = rewritten,
            packageName = manifest.packageName,
            providerClassName = PROVIDER_CLASS,
            authority = authority,
            beforeSha256 = beforeSha,
            afterSha256 = sha256(rewritten),
            alreadyPresent = false,
        )
    }

    private data class Chunk(
        val offset: Int,
        val type: Int,
        val headerSize: Int,
        val size: Int,
    )

    private data class StringPoolSnapshot(
        val strings: List<String>,
        val utf8: Boolean,
        val flags: Int,
    )

    private data class ProviderSnapshot(
        val className: String,
        val authority: String?,
    )

    private data class ParsedDocument(
        val rootHeaderSize: Int,
        val chunks: List<Chunk>,
        val stringPool: StringPoolSnapshot,
        val resourceIds: List<Int>,
        val resourceMapChunk: Chunk?,
        val bytes: ByteArray,
    ) {
        fun findProvider(
            className: String,
        ): ProviderSnapshot? {
            chunks.forEach { chunk ->
                if (chunk.type != RES_XML_START_ELEMENT_TYPE) return@forEach
                if (chunk.size < 36) return@forEach
                val tag = stringAt(s32(bytes, chunk.offset + 20))
                if (tag != "provider") return@forEach

                val attributeStart = u16(bytes, chunk.offset + 24)
                val attributeSize = u16(bytes, chunk.offset + 26)
                val attributeCount = u16(bytes, chunk.offset + 28)
                require(attributeCount <= MAX_ATTRIBUTES && attributeSize >= 20) {
                    "Provider attribute table is malformed."
                }
                val start = safeAdd(
                    chunk.offset + 16,
                    attributeStart,
                    "provider attribute start",
                )
                require(
                    start >= chunk.offset + chunk.headerSize &&
                        start <= chunk.offset + chunk.size &&
                        attributeSize * attributeCount <=
                        chunk.offset + chunk.size - start
                ) {
                    "Provider attribute table is outside its chunk."
                }

                var providerName: String? = null
                var authority: String? = null
                repeat(attributeCount) { index ->
                    val base = start + index * attributeSize
                    val ns = s32(bytes, base)
                    val name = stringAt(s32(bytes, base + 4))
                    if (ns != NO_INDEX && stringAt(ns) != ANDROID_NS) {
                        return@repeat
                    }
                    val value = attributeStringValue(base)
                    when (name) {
                        "name" -> providerName = value
                        "authorities" -> authority = value
                    }
                }
                if (providerName == className) {
                    return ProviderSnapshot(
                        className = providerName!!,
                        authority = authority,
                    )
                }
            }
            return null
        }

        private fun attributeStringValue(base: Int): String? {
            val rawIndex = s32(bytes, base + 8)
            if (rawIndex != NO_INDEX) return stringAt(rawIndex)
            val dataType = u8(bytes, base + 15)
            if (dataType != TYPE_STRING) return null
            return stringAt(
                u32(bytes, base + 16)
                    .toIntChecked("attribute string index"),
            )
        }

        private fun stringAt(index: Int): String {
            require(index in stringPool.strings.indices) {
                "Binary XML string index is outside pool."
            }
            return stringPool.strings[index]
        }

        companion object {
            fun parse(
                bytes: ByteArray,
                rootSize: Int,
            ): ParsedDocument {
                require(bytes.size >= rootSize && rootSize >= 8) {
                    "Binary XML root is truncated."
                }
                require(u16(bytes, 0) == RES_XML_TYPE) {
                    "Manifest is not binary XML."
                }
                val rootHeaderSize = u16(bytes, 2)
                require(rootHeaderSize >= 8 && rootHeaderSize <= rootSize) {
                    "Invalid binary XML root header."
                }

                val chunks = mutableListOf<Chunk>()
                var stringPool: StringPoolSnapshot? = null
                var resourceMap: Chunk? = null
                var resourceIds = emptyList<Int>()
                var cursor = rootHeaderSize
                while (cursor < rootSize) {
                    val chunk = readChunk(bytes, cursor, rootSize)
                    chunks += chunk
                    when (chunk.type) {
                        RES_STRING_POOL_TYPE -> {
                            require(stringPool == null) {
                                "Binary XML contains multiple string pools."
                            }
                            stringPool = parseStringPool(bytes, chunk)
                        }
                        RES_XML_RESOURCE_MAP_TYPE -> {
                            require(resourceMap == null) {
                                "Binary XML contains multiple resource maps."
                            }
                            resourceMap = chunk
                            resourceIds = parseResourceMap(bytes, chunk)
                        }
                    }
                    cursor += chunk.size
                }
                require(cursor == rootSize) {
                    "Binary XML child chunks do not fill the root chunk."
                }

                return ParsedDocument(
                    rootHeaderSize = rootHeaderSize,
                    chunks = chunks,
                    stringPool = requireNotNull(stringPool) {
                        "Binary XML string pool is missing."
                    },
                    resourceIds = resourceIds,
                    resourceMapChunk = resourceMap,
                    bytes = bytes,
                )
            }

            private fun parseStringPool(
                bytes: ByteArray,
                chunk: Chunk,
            ): StringPoolSnapshot {
                require(chunk.headerSize >= 28) {
                    "String-pool header is truncated."
                }
                val count = u32(bytes, chunk.offset + 8)
                    .toIntChecked("string count")
                val styleCount = u32(bytes, chunk.offset + 12)
                    .toIntChecked("style count")
                require(count in 1..MAX_STRINGS) {
                    "String count exceeds rewrite limits."
                }
                require(styleCount == 0) {
                    "Styled binary XML string pools are not supported by the current rewriter."
                }
                val flags = u32(bytes, chunk.offset + 16)
                    .toIntChecked("string-pool flags")
                val stringsStart = u32(bytes, chunk.offset + 20)
                    .toIntChecked("strings start")
                val stylesStart = u32(bytes, chunk.offset + 24)
                    .toIntChecked("styles start")
                require(stylesStart == 0) {
                    "Styled binary XML string pools are not supported by the current rewriter."
                }

                val offsetsStart = chunk.offset + chunk.headerSize
                require(
                    count * 4 <= chunk.offset + chunk.size - offsetsStart
                ) {
                    "String offset table is truncated."
                }
                val dataStart = safeAdd(
                    chunk.offset,
                    stringsStart,
                    "string data start",
                )
                require(dataStart in offsetsStart..(chunk.offset + chunk.size)) {
                    "String data is outside string-pool chunk."
                }
                val utf8 = flags and UTF8_FLAG != 0

                val strings = List(count) { index ->
                    val relative = u32(
                        bytes,
                        offsetsStart + index * 4,
                    ).toIntChecked("string offset")
                    val start = safeAdd(
                        dataStart,
                        relative,
                        "string address",
                    )
                    if (utf8) {
                        decodeUtf8(
                            bytes = bytes,
                            start = start,
                            end = chunk.offset + chunk.size,
                        )
                    } else {
                        decodeUtf16(
                            bytes = bytes,
                            start = start,
                            end = chunk.offset + chunk.size,
                        )
                    }
                }

                return StringPoolSnapshot(
                    strings = strings,
                    utf8 = utf8,
                    flags = flags,
                )
            }

            private fun parseResourceMap(
                bytes: ByteArray,
                chunk: Chunk,
            ): List<Int> {
                require(chunk.headerSize >= 8) {
                    "Resource-map header is truncated."
                }
                val payload = chunk.size - chunk.headerSize
                require(payload % 4 == 0) {
                    "Resource-map payload is malformed."
                }
                val count = payload / 4
                return List(count) { index ->
                    u32(
                        bytes,
                        chunk.offset + chunk.headerSize + index * 4,
                    ).toInt()
                }
            }
        }
    }

    private class MutableStringPool(
        val strings: MutableList<String>,
        private val utf8: Boolean,
        private var flags: Int,
        private val resourceIds: MutableList<Int>,
    ) {
        fun ensurePlain(value: String): Int {
            val existing = strings.indexOf(value)
            if (existing >= 0) return existing
            strings += value
            return strings.lastIndex
        }

        fun ensureResource(
            value: String,
            resourceId: Int,
        ): Int {
            strings.indices.forEach { index ->
                if (
                    strings[index] == value &&
                    resourceIds.getOrNull(index) == resourceId
                ) {
                    return index
                }
            }

            strings += value
            val index = strings.lastIndex
            ensureResourceCapacity(index + 1)
            resourceIds[index] = resourceId
            return index
        }

        fun buildStringPool(): ByteArray {
            require(strings.size in 1..MAX_STRINGS) {
                "Rewritten string count exceeds manifest limits."
            }
            flags = flags and SORTED_FLAG.inv()
            val encoded = strings.map { value ->
                if (utf8) encodeUtf8(value) else encodeUtf16(value)
            }
            val offsets = IntArray(encoded.size)
            var cursor = 0
            encoded.forEachIndexed { index, item ->
                offsets[index] = cursor
                cursor = safeAdd(cursor, item.size, "encoded string data")
            }

            val headerSize = 28
            val offsetsBytes = safeMultiply(
                offsets.size,
                4,
                "string offsets",
            )
            val stringsStart = safeAdd(
                headerSize,
                offsetsBytes,
                "strings start",
            )
            val rawSize = safeAdd(
                stringsStart,
                cursor,
                "string-pool size",
            )
            val size = align4(rawSize)
            val buffer = ByteBuffer.allocate(size)
                .order(ByteOrder.LITTLE_ENDIAN)
            buffer.putShort(RES_STRING_POOL_TYPE.toShort())
            buffer.putShort(headerSize.toShort())
            buffer.putInt(size)
            buffer.putInt(strings.size)
            buffer.putInt(0)
            buffer.putInt(flags)
            buffer.putInt(stringsStart)
            buffer.putInt(0)
            offsets.forEach(buffer::putInt)
            encoded.forEach(buffer::put)
            return buffer.array()
        }

        fun buildResourceMap(): ByteArray {
            val lastNonZero = resourceIds.indexOfLast { it != 0 }
            val count = maxOf(
                lastNonZero + 1,
                resourceIds.size,
            )
            require(count > 0) {
                "Runtime probe manifest needs an Android resource map."
            }
            val size = 8 + count * 4
            val buffer = ByteBuffer.allocate(size)
                .order(ByteOrder.LITTLE_ENDIAN)
            buffer.putShort(RES_XML_RESOURCE_MAP_TYPE.toShort())
            buffer.putShort(8)
            buffer.putInt(size)
            repeat(count) { index ->
                buffer.putInt(resourceIds.getOrElse(index) { 0 })
            }
            return buffer.array()
        }

        private fun ensureResourceCapacity(size: Int) {
            while (resourceIds.size < size) {
                resourceIds += 0
            }
        }

        private fun encodeUtf8(value: String): ByteArray {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val utf16Length = value.length
            require(utf16Length <= 0x7fff && bytes.size <= 0x7fff) {
                "Manifest string exceeds UTF-8 binary XML length limit."
            }
            return encodeLength8(utf16Length) +
                encodeLength8(bytes.size) +
                bytes +
                byteArrayOf(0)
        }

        private fun encodeUtf16(value: String): ByteArray {
            val chars = value.length
            val data = value.toByteArray(Charsets.UTF_16LE)
            val output = ByteArrayOutputStream()
            if (chars < 0x8000) {
                writeU16(output, chars)
            } else {
                require(chars <= 0x7fffffff) {
                    "Manifest UTF-16 string is too long."
                }
                writeU16(
                    output,
                    0x8000 or ((chars ushr 16) and 0x7fff),
                )
                writeU16(output, chars and 0xffff)
            }
            output.write(data)
            writeU16(output, 0)
            return output.toByteArray()
        }

        private fun encodeLength8(length: Int): ByteArray =
            if (length < 0x80) {
                byteArrayOf(length.toByte())
            } else {
                byteArrayOf(
                    (0x80 or (length ushr 8)).toByte(),
                    (length and 0xff).toByte(),
                )
            }
    }

    private fun providerElement(
        providerTag: Int,
        androidNs: Int,
        nameAttribute: Int,
        authoritiesAttribute: Int,
        exportedAttribute: Int,
        providerClass: Int,
        authority: Int,
        trueString: Int,
    ): ByteArray {
        val attributes = listOf(
            Attribute(
                namespace = androidNs,
                name = nameAttribute,
                rawValue = providerClass,
                dataType = TYPE_STRING,
                data = providerClass,
            ),
            Attribute(
                namespace = androidNs,
                name = exportedAttribute,
                rawValue = trueString,
                dataType = TYPE_INT_BOOLEAN,
                data = 1,
            ),
            Attribute(
                namespace = androidNs,
                name = authoritiesAttribute,
                rawValue = authority,
                dataType = TYPE_STRING,
                data = authority,
            ),
        )
        val startSize = 36 + attributes.size * 20
        val start = ByteBuffer.allocate(startSize)
            .order(ByteOrder.LITTLE_ENDIAN)
        start.putShort(RES_XML_START_ELEMENT_TYPE.toShort())
        start.putShort(16)
        start.putInt(startSize)
        start.putInt(0)
        start.putInt(NO_INDEX)
        start.putInt(NO_INDEX)
        start.putInt(providerTag)
        start.putShort(20)
        start.putShort(20)
        start.putShort(attributes.size.toShort())
        start.putShort(0)
        start.putShort(0)
        start.putShort(0)
        attributes.forEach { attribute ->
            start.putInt(attribute.namespace)
            start.putInt(attribute.name)
            start.putInt(attribute.rawValue)
            start.putShort(8)
            start.put(0)
            start.put(attribute.dataType.toByte())
            start.putInt(attribute.data)
        }

        val end = ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
        end.putShort(RES_XML_END_ELEMENT_TYPE.toShort())
        end.putShort(16)
        end.putInt(24)
        end.putInt(0)
        end.putInt(NO_INDEX)
        end.putInt(NO_INDEX)
        end.putInt(providerTag)

        return start.array() + end.array()
    }

    private data class Attribute(
        val namespace: Int,
        val name: Int,
        val rawValue: Int,
        val dataType: Int,
        val data: Int,
    )

    private fun readChunk(
        bytes: ByteArray,
        offset: Int,
        parentEnd: Int,
    ): Chunk {
        require(offset >= 0 && offset + 8 <= parentEnd) {
            "Binary XML child header is truncated."
        }
        val type = u16(bytes, offset)
        val headerSize = u16(bytes, offset + 2)
        val size = u32(bytes, offset + 4)
            .toIntChecked("child chunk size")
        require(headerSize >= 8 && size >= headerSize) {
            "Binary XML child chunk size is invalid."
        }
        require(size <= parentEnd - offset) {
            "Binary XML child chunk exceeds root."
        }
        return Chunk(offset, type, headerSize, size)
    }

    private fun decodeUtf8(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): String {
        val (_, afterChars) = decodeLength8(bytes, start, end)
        val (length, dataStart) = decodeLength8(bytes, afterChars, end)
        require(length <= end - dataStart - 1) {
            "UTF-8 string is truncated."
        }
        require(bytes[dataStart + length] == 0.toByte()) {
            "UTF-8 string terminator is missing."
        }
        return String(bytes, dataStart, length, Charsets.UTF_8)
    }

    private fun decodeLength8(
        bytes: ByteArray,
        offset: Int,
        end: Int,
    ): Pair<Int, Int> {
        require(offset in 0 until end) {
            "UTF-8 length is truncated."
        }
        val first = u8(bytes, offset)
        return if (first and 0x80 != 0) {
            require(offset + 1 < end) {
                "UTF-8 extended length is truncated."
            }
            (((first and 0x7f) shl 8) or u8(bytes, offset + 1)) to
                (offset + 2)
        } else {
            first to (offset + 1)
        }
    }

    private fun decodeUtf16(
        bytes: ByteArray,
        start: Int,
        end: Int,
    ): String {
        require(start + 1 < end) {
            "UTF-16 string length is truncated."
        }
        val first = u16(bytes, start)
        val (chars, dataStart) = if (first and 0x8000 != 0) {
            require(start + 3 < end) {
                "UTF-16 extended length is truncated."
            }
            (((first and 0x7fff) shl 16) or u16(bytes, start + 2)) to
                (start + 4)
        } else {
            first to (start + 2)
        }
        val byteLength = safeMultiply(
            chars,
            2,
            "UTF-16 string bytes",
        )
        require(byteLength <= end - dataStart - 2) {
            "UTF-16 string is truncated."
        }
        require(u16(bytes, dataStart + byteLength) == 0) {
            "UTF-16 string terminator is missing."
        }
        return String(
            bytes,
            dataStart,
            byteLength,
            Charsets.UTF_16LE,
        )
    }

    private fun align4(value: Int): Int =
        safeAdd(value, 3, "alignment") and 3.inv()

    private fun writeU16(
        output: ByteArrayOutputStream,
        value: Int,
    ) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun putU32(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        require(value >= 0)
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xff).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xff).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xff).toByte()
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
            "$label exceeds rewrite range."
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
}
