package io.github.ffenuss.modkit.runtime

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryAndroidManifestInspectorTest {
    @Test
    fun extractsPackageApplicationSplitAndDebuggableFromBinaryXml() {
        val bytes = manifest(
            packageName = "com.example.game",
            splitName = "config.arm64_v8a",
            applicationName = ".GameApp",
            debuggable = true,
        )

        val info = BinaryAndroidManifestInspector.inspect(bytes)

        assertEquals("com.example.game", info.packageName)
        assertEquals("config.arm64_v8a", info.splitName)
        assertEquals(
            "com.example.game.GameApp",
            info.applicationClassName,
        )
        assertTrue(info.applicationElementPresent)
        assertTrue(info.debuggable == true)
        assertNotNull(info.applicationStartOffset)
        assertNotNull(info.applicationEndOffset)
        assertTrue(
            requireNotNull(info.applicationEndOffset) >
                requireNotNull(info.applicationStartOffset),
        )
        assertEquals(bytes.size, info.manifestSize)
        assertEquals(64, info.manifestSha256.length)
    }

    @Test
    fun applicationClassWithoutDotIsResolvedAgainstPackage() {
        val info = BinaryAndroidManifestInspector.inspect(
            manifest(
                packageName = "com.example.target",
                applicationName = "App",
                debuggable = false,
            ),
        )

        assertEquals(
            "com.example.target.App",
            info.applicationClassName,
        )
        assertTrue(info.debuggable == false)
    }

    @Test
    fun manifestWithoutApplicationRemainsExplicitlyAbsent() {
        val bytes = manifest(
            packageName = "com.example.noapp",
            includeApplication = false,
        )

        val info = BinaryAndroidManifestInspector.inspect(bytes)

        assertEquals("com.example.noapp", info.packageName)
        assertFalse(info.applicationElementPresent)
        assertNull(info.applicationClassName)
        assertNull(info.applicationStartOffset)
        assertNull(info.applicationEndOffset)
        assertNull(info.debuggable)
    }

    @Test
    fun truncatedRootChunkFailsClosed() {
        val bytes = manifest(
            packageName = "com.example.bad",
        )
        val truncated = bytes.copyOf(bytes.size - 3)

        val failure = runCatching {
            BinaryAndroidManifestInspector.inspect(truncated)
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "root chunk is truncated",
                ignoreCase = true,
            ),
        )
    }

    @Test
    fun implausiblePackageNameIsRejected() {
        val failure = runCatching {
            BinaryAndroidManifestInspector.inspect(
                manifest(packageName = "not-a-package"),
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(
            failure?.message.orEmpty().contains(
                "package name is invalid",
                ignoreCase = true,
            ),
        )
    }

    private fun manifest(
        packageName: String,
        splitName: String? = null,
        applicationName: String? = null,
        debuggable: Boolean? = null,
        includeApplication: Boolean = true,
    ): ByteArray {
        val strings = linkedSetOf(
            "manifest",
            "package",
            packageName,
            "application",
            ANDROID_NS,
            "name",
            "debuggable",
            "true",
            "false",
            "split",
        )
        splitName?.let(strings::add)
        applicationName?.let(strings::add)
        val table = strings.toList()
        fun index(value: String): Int = table.indexOf(value)
            .takeIf { it >= 0 }
            ?: error("Fixture string missing: $value")

        val chunks = mutableListOf<ByteArray>()
        chunks += stringPool(table)

        val manifestAttributes = mutableListOf<AttributeFixture>()
        manifestAttributes += AttributeFixture(
            namespace = NO_INDEX,
            name = index("package"),
            rawValue = index(packageName),
            dataType = TYPE_STRING,
            data = index(packageName),
        )
        splitName?.let {
            manifestAttributes += AttributeFixture(
                namespace = NO_INDEX,
                name = index("split"),
                rawValue = index(it),
                dataType = TYPE_STRING,
                data = index(it),
            )
        }
        chunks += startElement(
            name = index("manifest"),
            attributes = manifestAttributes,
        )

        if (includeApplication) {
            val applicationAttributes = mutableListOf<AttributeFixture>()
            applicationName?.let {
                applicationAttributes += AttributeFixture(
                    namespace = index(ANDROID_NS),
                    name = index("name"),
                    rawValue = index(it),
                    dataType = TYPE_STRING,
                    data = index(it),
                )
            }
            debuggable?.let {
                applicationAttributes += AttributeFixture(
                    namespace = index(ANDROID_NS),
                    name = index("debuggable"),
                    rawValue = index(if (it) "true" else "false"),
                    dataType = TYPE_INT_BOOLEAN,
                    data = if (it) 1 else 0,
                )
            }
            chunks += startElement(
                name = index("application"),
                attributes = applicationAttributes,
            )
            chunks += endElement(index("application"))
        }

        chunks += endElement(index("manifest"))

        val totalSize = 8 + chunks.sumOf { it.size }
        val output = ByteArrayOutputStream(totalSize)
        output.write(
            ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(RES_XML_TYPE.toShort())
                .putShort(8)
                .putInt(totalSize)
                .array(),
        )
        chunks.forEach(output::write)
        return output.toByteArray()
    }

    private data class AttributeFixture(
        val namespace: Int,
        val name: Int,
        val rawValue: Int,
        val dataType: Int,
        val data: Int,
    )

    private fun startElement(
        name: Int,
        attributes: List<AttributeFixture>,
    ): ByteArray {
        val size = 36 + attributes.size * 20
        val buffer = ByteBuffer.allocate(size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(RES_XML_START_ELEMENT_TYPE.toShort())
        buffer.putShort(16)
        buffer.putInt(size)
        buffer.putInt(1)
        buffer.putInt(NO_INDEX)
        buffer.putInt(NO_INDEX)
        buffer.putInt(name)
        buffer.putShort(20)
        buffer.putShort(20)
        buffer.putShort(attributes.size.toShort())
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(0)

        attributes.forEach { attribute ->
            buffer.putInt(attribute.namespace)
            buffer.putInt(attribute.name)
            buffer.putInt(attribute.rawValue)
            buffer.putShort(8)
            buffer.put(0)
            buffer.put(attribute.dataType.toByte())
            buffer.putInt(attribute.data)
        }
        return buffer.array()
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
        val encoded = strings.map(::encodeUtf8String)
        val offsets = IntArray(strings.size)
        var cursor = 0
        encoded.forEachIndexed { index, bytes ->
            offsets[index] = cursor
            cursor += bytes.size
        }

        val headerSize = 28
        val stringsStart = headerSize + strings.size * 4
        val unpaddedSize = stringsStart + cursor
        val size = (unpaddedSize + 3) and 3.inv()
        val buffer = ByteBuffer.allocate(size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(RES_STRING_POOL_TYPE.toShort())
        buffer.putShort(headerSize.toShort())
        buffer.putInt(size)
        buffer.putInt(strings.size)
        buffer.putInt(0)
        buffer.putInt(UTF8_FLAG)
        buffer.putInt(stringsStart)
        buffer.putInt(0)
        offsets.forEach(buffer::putInt)
        encoded.forEach(buffer::put)
        return buffer.array()
    }

    private fun encodeUtf8String(value: String): ByteArray {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        require(value.length < 0x80)
        require(utf8.size < 0x80)
        return byteArrayOf(
            value.length.toByte(),
            utf8.size.toByte(),
        ) + utf8 + byteArrayOf(0)
    }

    companion object {
        private const val RES_XML_TYPE = 0x0003
        private const val RES_STRING_POOL_TYPE = 0x0001
        private const val RES_XML_START_ELEMENT_TYPE = 0x0102
        private const val RES_XML_END_ELEMENT_TYPE = 0x0103
        private const val UTF8_FLAG = 0x00000100
        private const val TYPE_STRING = 0x03
        private const val TYPE_INT_BOOLEAN = 0x12
        private const val NO_INDEX = -1
        private const val ANDROID_NS =
            "http://schemas.android.com/apk/res/android"
    }
}
