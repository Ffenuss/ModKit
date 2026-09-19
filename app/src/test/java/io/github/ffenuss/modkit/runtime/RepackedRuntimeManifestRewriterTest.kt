package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepackedRuntimeManifestRewriterTest {
    @Test
    fun rewritesOnlyBaseManifestAndKeepsPreparedSourcesImmutable() {
        val root = Files.createTempDirectory("modkit-manifest-rewrite-").toFile()
        try {
            val base = File(root, "base.apk")
            val split = File(root, "split.apk")
            createApk(
                base,
                manifest(PACKAGE, null, ".App"),
                "base-payload",
            )
            createApk(
                split,
                manifest(PACKAGE, "config.arm64_v8a", null),
                "split-payload",
            )
            val baseOriginal = base.readBytes()
            val splitOriginal = split.readBytes()
            val prepared = prepared(base, split)
            val inventory = RepackedRuntimeManifestInventoryBuilder.inspect(
                prepared = prepared,
                cancellation = AtomicCancellationSignal(),
            )
            assertTrue(inventory.verified)

            val result = RepackedRuntimeManifestRewriter.rewrite(
                prepared = prepared,
                manifestInventory = inventory,
                outputRoot = File(root, "out"),
                cancellation = AtomicCancellationSignal(),
            )

            assertArrayEquals(baseOriginal, base.readBytes())
            assertArrayEquals(splitOriginal, split.readBytes())
            assertEquals(PACKAGE, result.packageName)
            assertEquals("base.apk", result.baseSourceDisplayName)
            assertEquals(
                BinaryAndroidManifestProbeInjector.PROVIDER_CLASS,
                result.providerClassName,
            )
            assertEquals(
                PACKAGE + BinaryAndroidManifestProbeInjector.AUTHORITY_SUFFIX,
                result.providerAuthority,
            )

            val baseOutput = File(
                result.sources.single {
                    it.sourceDisplayName == "base.apk"
                }.outputPath,
            )
            val splitOutput = File(
                result.sources.single {
                    it.sourceDisplayName == "split.apk"
                }.outputPath,
            )
            assertTrue(baseOutput.isFile)
            assertTrue(splitOutput.isFile)
            assertFalse(baseOutput.readBytes().contentEquals(baseOriginal))
            assertArrayEquals(splitOriginal, splitOutput.readBytes())

            val manifestBytes = ZipFile(baseOutput).use { zip ->
                zip.getInputStream(
                    requireNotNull(zip.getEntry("AndroidManifest.xml")),
                ).use { it.readBytes() }
            }
            val reinjected =
                BinaryAndroidManifestProbeInjector.inject(manifestBytes)
            assertTrue(reinjected.alreadyPresent)
            assertEquals(PACKAGE, reinjected.packageName)

            ZipFile(baseOutput).use { zip ->
                val payload = zip.getInputStream(
                    requireNotNull(zip.getEntry("assets/payload.txt")),
                ).bufferedReader().use { it.readText() }
                assertEquals("base-payload", payload)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun changedPreparedCopyFailsClosedAndRemovesRewriteWorkspace() {
        val root = Files.createTempDirectory("modkit-manifest-stale-").toFile()
        try {
            val base = File(root, "base.apk")
            createApk(base, manifest(PACKAGE, null, null), "payload")
            val prepared = prepared(base)
            val inventory = RepackedRuntimeManifestInventoryBuilder.inspect(
                prepared = prepared,
                cancellation = AtomicCancellationSignal(),
            )
            assertTrue(inventory.verified)

            base.appendBytes(byteArrayOf(1, 2, 3))
            val outputRoot = File(root, "out")
            val failure = runCatching {
                RepackedRuntimeManifestRewriter.rewrite(
                    prepared = prepared,
                    manifestInventory = inventory,
                    outputRoot = outputRoot,
                    cancellation = AtomicCancellationSignal(),
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertFalse(
                File(
                    outputRoot,
                    ARTIFACT_SHA + "/repacked-test/manifest-rewrite",
                ).exists(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun prepared(
        vararg files: File,
    ): RepackedRuntimePreparedWorkspace =
        RepackedRuntimePreparedWorkspace(
            artifactSha256 = ARTIFACT_SHA,
            rootPath = files.first().parentFile.absolutePath,
            sources = files.map { file ->
                val sha = sha256(file.readBytes())
                RepackedRuntimePreparedSource(
                    sourceDisplayName = file.name,
                    sourceSha256 = sha,
                    copiedFilePath = file.absolutePath,
                    copiedSha256 = sha,
                )
            },
            preparedAtEpochMs = 1,
        )

    private fun createApk(
        file: File,
        manifest: ByteArray,
        payload: String,
    ) {
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("AndroidManifest.xml"))
            out.write(manifest)
            out.closeEntry()
            out.putNextEntry(ZipEntry("assets/payload.txt"))
            out.write(payload.toByteArray())
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
        val resourceIds = IntArray(table.size)
        resourceIds[index("name")] = android.R.attr.name
        chunks += resourceMap(resourceIds)

        val manifestAttrs = mutableListOf(
            Attr(
                namespace = NO_INDEX,
                name = index("package"),
                value = index(packageName),
            ),
        )
        splitName?.let {
            manifestAttrs += Attr(
                namespace = NO_INDEX,
                name = index("split"),
                value = index(it),
            )
        }
        chunks += startElement(index("manifest"), manifestAttrs)

        val applicationAttrs = applicationName?.let {
            listOf(
                Attr(
                    namespace = index(ANDROID_NS),
                    name = index("name"),
                    value = index(it),
                ),
            )
        }.orEmpty()
        chunks += startElement(
            index("application"),
            applicationAttrs,
        )
        chunks += endElement(index("application"))
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

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val PACKAGE = "com.example.target"
        private const val ARTIFACT_SHA =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
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
