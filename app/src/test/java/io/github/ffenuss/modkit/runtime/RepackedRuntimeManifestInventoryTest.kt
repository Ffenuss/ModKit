package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepackedRuntimeManifestInventoryTest {
    @Test
    fun verifiesOneBaseAndUniqueSplitsForOnePackage() {
        val root = Files.createTempDirectory("modkit-manifest-inventory-").toFile()
        try {
            val base = File(root, "base.apk")
            val split = File(root, "split_config.arm64.apk")
            createApk(base, manifest("com.example.game", null, ".App"))
            createApk(
                split,
                manifest("com.example.game", "config.arm64_v8a", null),
            )

            val inventory = RepackedRuntimeManifestInventoryBuilder.inspect(
                prepared = prepared(base, split),
                cancellation = AtomicCancellationSignal(),
            )

            assertTrue(inventory.verified)
            assertEquals("com.example.game", inventory.packageName)
            assertEquals("base.apk", inventory.baseSourceDisplayName)
            assertEquals(2, inventory.records.size)
            assertTrue(inventory.blockers.isEmpty())
            assertEquals(
                "com.example.game.App",
                inventory.records
                    .single { it.sourceDisplayName == "base.apk" }
                    .applicationClassName,
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun differentPackageNamesFailClosed() {
        val root = Files.createTempDirectory("modkit-manifest-mismatch-").toFile()
        try {
            val base = File(root, "base.apk")
            val split = File(root, "split.apk")
            createApk(base, manifest("com.example.one", null, null))
            createApk(
                split,
                manifest("com.example.two", "config.x86", null),
            )

            val inventory = RepackedRuntimeManifestInventoryBuilder.inspect(
                prepared = prepared(base, split),
                cancellation = AtomicCancellationSignal(),
            )

            assertFalse(inventory.verified)
            assertTrue(
                inventory.blockers.any {
                    "different package names" in it
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun duplicateSplitNamesAreBlocked() {
        val root = Files.createTempDirectory("modkit-manifest-splits-").toFile()
        try {
            val base = File(root, "base.apk")
            val splitA = File(root, "a.apk")
            val splitB = File(root, "b.apk")
            createApk(base, manifest("com.example.game", null, null))
            createApk(
                splitA,
                manifest("com.example.game", "config.en", null),
            )
            createApk(
                splitB,
                manifest("com.example.game", "config.en", null),
            )

            val inventory = RepackedRuntimeManifestInventoryBuilder.inspect(
                prepared = prepared(base, splitA, splitB),
                cancellation = AtomicCancellationSignal(),
            )

            assertFalse(inventory.verified)
            assertTrue(
                inventory.blockers.any {
                    "duplicate split names" in it
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun prepared(vararg files: File): RepackedRuntimePreparedWorkspace =
        RepackedRuntimePreparedWorkspace(
            artifactSha256 = ARTIFACT_SHA,
            rootPath = files.first().parentFile.absolutePath,
            sources = files.map {
                RepackedRuntimePreparedSource(
                    sourceDisplayName = it.name,
                    sourceSha256 = SOURCE_SHA,
                    copiedFilePath = it.absolutePath,
                    copiedSha256 = SOURCE_SHA,
                )
            },
            preparedAtEpochMs = 1,
        )

    private fun createApk(
        file: File,
        manifest: ByteArray,
    ) {
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("AndroidManifest.xml"))
            out.write(manifest)
            out.closeEntry()
        }
    }

    private fun manifest(
        packageName: String,
        splitName: String?,
        applicationName: String?,
    ): ByteArray {
        val strings = linkedSetOf(
            "manifest",
            "package",
            packageName,
            "application",
            ANDROID_NS,
            "name",
            "split",
        )
        splitName?.let(strings::add)
        applicationName?.let(strings::add)
        val table = strings.toList()
        fun index(value: String): Int =
            table.indexOf(value).takeIf { it >= 0 } ?: error(value)

        val chunks = mutableListOf<ByteArray>()
        chunks += stringPool(table)
        val manifestAttrs = mutableListOf(
            attribute(
                namespace = NO_INDEX,
                name = index("package"),
                value = index(packageName),
            ),
        )
        splitName?.let {
            manifestAttrs += attribute(
                namespace = NO_INDEX,
                name = index("split"),
                value = index(it),
            )
        }
        chunks += startElement(index("manifest"), manifestAttrs)
        if (applicationName != null) {
            chunks += startElement(
                index("application"),
                listOf(
                    attribute(
                        namespace = index(ANDROID_NS),
                        name = index("name"),
                        value = index(applicationName),
                    ),
                ),
            )
            chunks += endElement(index("application"))
        } else {
            chunks += startElement(index("application"), emptyList())
            chunks += endElement(index("application"))
        }
        chunks += endElement(index("manifest"))

        val size = 8 + chunks.sumOf { it.size }
        val out = ByteArrayOutputStream(size)
        out.write(
            ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(RES_XML_TYPE.toShort())
                .putShort(8)
                .putInt(size)
                .array(),
        )
        chunks.forEach(out::write)
        return out.toByteArray()
    }

    private data class Attr(
        val namespace: Int,
        val name: Int,
        val value: Int,
    )

    private fun attribute(
        namespace: Int,
        name: Int,
        value: Int,
    ) = Attr(namespace, name, value)

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
            b.putInt(attr.value)
            b.putShort(8)
            b.put(0)
            b.put(TYPE_STRING.toByte())
            b.putInt(attr.value)
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
        encoded.forEachIndexed { index, bytes ->
            offsets[index] = cursor
            cursor += bytes.size
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

    companion object {
        private const val ARTIFACT_SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val SOURCE_SHA =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val ANDROID_NS =
            "http://schemas.android.com/apk/res/android"
        private const val RES_XML_TYPE = 0x0003
        private const val RES_STRING_POOL_TYPE = 0x0001
        private const val RES_XML_START_ELEMENT_TYPE = 0x0102
        private const val RES_XML_END_ELEMENT_TYPE = 0x0103
        private const val UTF8_FLAG = 0x00000100
        private const val TYPE_STRING = 0x03
        private const val NO_INDEX = -1
    }
}
