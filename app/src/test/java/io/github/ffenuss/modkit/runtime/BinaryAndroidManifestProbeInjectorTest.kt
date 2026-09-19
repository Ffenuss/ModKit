package io.github.ffenuss.modkit.runtime

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryAndroidManifestProbeInjectorTest {
    @Test
    fun injectsProviderWithoutChangingTargetIdentity() {
        val original = manifest(
            packageName = PACKAGE,
            applicationName = ".App",
            includeApplication = true,
        )
        val before = BinaryAndroidManifestInspector.inspect(original)

        val injected = BinaryAndroidManifestProbeInjector.inject(original)
        val after = BinaryAndroidManifestInspector.inspect(injected.bytes)

        assertFalse(injected.alreadyPresent)
        assertEquals(PACKAGE, injected.packageName)
        assertEquals(
            BinaryAndroidManifestProbeInjector.PROVIDER_CLASS,
            injected.providerClassName,
        )
        assertEquals(
            PACKAGE + BinaryAndroidManifestProbeInjector.AUTHORITY_SUFFIX,
            injected.authority,
        )
        assertNotEquals(injected.beforeSha256, injected.afterSha256)
        assertEquals(before.packageName, after.packageName)
        assertEquals(before.applicationClassName, after.applicationClassName)
        assertTrue(after.applicationElementPresent)

        val resourceIds = resourceMapIds(injected.bytes)
        assertTrue(android.R.attr.name in resourceIds)
        assertTrue(android.R.attr.authorities in resourceIds)
        assertTrue(android.R.attr.exported in resourceIds)
    }

    @Test
    fun reinjectionIsIdempotentAndKeepsManifestBytes() {
        val first = BinaryAndroidManifestProbeInjector.inject(
            manifest(
                packageName = PACKAGE,
                applicationName = null,
                includeApplication = true,
            ),
        )
        val second = BinaryAndroidManifestProbeInjector.inject(first.bytes)

        assertTrue(second.alreadyPresent)
        assertEquals(first.afterSha256, second.afterSha256)
        assertTrue(first.bytes.contentEquals(second.bytes))
    }

    @Test
    fun manifestWithoutApplicationIsRejected() {
        val failure = runCatching {
            BinaryAndroidManifestProbeInjector.inject(
                manifest(
                    packageName = PACKAGE,
                    applicationName = null,
                    includeApplication = false,
                ),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "no <application>",
                ignoreCase = true,
            ),
        )
    }

    private fun resourceMapIds(bytes: ByteArray): List<Int> {
        val rootSize = u32(bytes, 4)
        var cursor = u16(bytes, 2)
        while (cursor < rootSize) {
            val type = u16(bytes, cursor)
            val headerSize = u16(bytes, cursor + 2)
            val size = u32(bytes, cursor + 4)
            if (type == RES_XML_RESOURCE_MAP_TYPE) {
                val count = (size - headerSize) / 4
                return List(count) { index ->
                    u32(
                        bytes,
                        cursor + headerSize + index * 4,
                    )
                }
            }
            cursor += size
        }
        return emptyList()
    }

    private fun manifest(
        packageName: String,
        applicationName: String?,
        includeApplication: Boolean,
    ): ByteArray {
        val strings = linkedSetOf(
            "manifest",
            "package",
            packageName,
            "application",
            ANDROID_NS,
            "name",
        )
        applicationName?.let(strings::add)
        val table = strings.toList()
        fun index(value: String): Int =
            table.indexOf(value).takeIf { it >= 0 } ?: error(value)

        val chunks = mutableListOf<ByteArray>()
        chunks += stringPool(table)

        val resourceMap = IntArray(table.size)
        resourceMap[index("name")] = android.R.attr.name
        chunks += resourceMap(resourceMap)

        chunks += startElement(
            name = index("manifest"),
            attrs = listOf(
                Attr(
                    namespace = NO_INDEX,
                    name = index("package"),
                    rawValue = index(packageName),
                    type = TYPE_STRING,
                    data = index(packageName),
                ),
            ),
        )

        if (includeApplication) {
            val attrs = applicationName?.let {
                listOf(
                    Attr(
                        namespace = index(ANDROID_NS),
                        name = index("name"),
                        rawValue = index(it),
                        type = TYPE_STRING,
                        data = index(it),
                    ),
                )
            }.orEmpty()
            chunks += startElement(
                name = index("application"),
                attrs = attrs,
            )
            chunks += endElement(index("application"))
        }

        chunks += endElement(index("manifest"))

        val size = 8 + chunks.sumOf { it.size }
        val output = ByteArrayOutputStream(size)
        output.write(
            ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(RES_XML_TYPE.toShort())
                .putShort(8)
                .putInt(size)
                .array(),
        )
        chunks.forEach(output::write)
        return output.toByteArray()
    }

    private data class Attr(
        val namespace: Int,
        val name: Int,
        val rawValue: Int,
        val type: Int,
        val data: Int,
    )

    private fun startElement(
        name: Int,
        attrs: List<Attr>,
    ): ByteArray {
        val size = 36 + attrs.size * 20
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(RES_XML_START_ELEMENT_TYPE.toShort())
        b.putShort(16)
        b.putInt(size)
        b.putInt(1)
        b.putInt(NO_INDEX)
        b.putInt(NO_INDEX)
        b.putInt(name)
        b.putShort(20)
        b.putShort(20)
        b.putShort(attrs.size.toShort())
        b.putShort(0)
        b.putShort(0)
        b.putShort(0)
        attrs.forEach { attr ->
            b.putInt(attr.namespace)
            b.putInt(attr.name)
            b.putInt(attr.rawValue)
            b.putShort(8)
            b.put(0)
            b.put(attr.type.toByte())
            b.putInt(attr.data)
        }
        return b.array()
    }

    private fun endElement(name: Int): ByteArray =
        ByteBuffer.allocate(24)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putShort(RES_XML_END_ELEMENT_TYPE.toShort())
            .putShort(16)
            .putInt(24)
            .putInt(1)
            .putInt(NO_INDEX)
            .putInt(NO_INDEX)
            .putInt(name)
            .array()

    private fun stringPool(strings: List<String>): ByteArray {
        val encoded = strings.map { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            require(value.length < 0x80 && bytes.size < 0x80)
            byteArrayOf(value.length.toByte(), bytes.size.toByte()) +
                bytes + byteArrayOf(0)
        }
        val offsets = IntArray(strings.size)
        var cursor = 0
        encoded.forEachIndexed { index, item ->
            offsets[index] = cursor
            cursor += item.size
        }
        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val size = (stringsStart + cursor + 3) and 3.inv()
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(RES_STRING_POOL_TYPE.toShort())
        b.putShort(headerSize.toShort())
        b.putInt(size)
        b.putInt(strings.size)
        b.putInt(0)
        b.putInt(UTF8_FLAG)
        b.putInt(stringsStart)
        b.putInt(0)
        offsets.forEach(b::putInt)
        encoded.forEach(b::put)
        return b.array()
    }

    private fun resourceMap(ids: IntArray): ByteArray {
        val last = ids.indexOfLast { it != 0 }
        val count = maxOf(last + 1, 1)
        val size = 8 + count * 4
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(RES_XML_RESOURCE_MAP_TYPE.toShort())
        b.putShort(8)
        b.putInt(size)
        repeat(count) { b.putInt(ids[it]) }
        return b.array()
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    companion object {
        private const val PACKAGE = "com.example.target"
        private const val ANDROID_NS =
            "http://schemas.android.com/apk/res/android"
        private const val RES_XML_TYPE = 0x0003
        private const val RES_STRING_POOL_TYPE = 0x0001
        private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
        private const val RES_XML_START_ELEMENT_TYPE = 0x0102
        private const val RES_XML_END_ELEMENT_TYPE = 0x0103
        private const val UTF8_FLAG = 0x00000100
        private const val TYPE_STRING = 0x03
        private const val NO_INDEX = -1
    }
}
