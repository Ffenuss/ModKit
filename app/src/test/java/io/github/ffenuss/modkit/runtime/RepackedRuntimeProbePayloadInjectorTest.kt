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

class RepackedRuntimeProbePayloadInjectorTest {
    @Test
    fun injectsProbeIntoFirstFreeDexSlotAndKeepsSplitByteExact() {
        val root = Files.createTempDirectory("modkit-probe-inject-").toFile()
        try {
            val base = File(root, "base.apk")
            val split = File(root, "split.apk")
            createApk(
                file = base,
                manifest = probeManifest(PACKAGE),
                dexEntries = linkedMapOf(
                    "classes.dex" to "primary".toByteArray(),
                    "classes2.dex" to "secondary".toByteArray(),
                ),
                payload = "base",
            )
            createApk(
                file = split,
                manifest = basicManifest(
                    packageName = PACKAGE,
                    splitName = "config.arm64_v8a",
                ),
                dexEntries = emptyMap(),
                payload = "split",
            )
            val baseBefore = base.readBytes()
            val splitBefore = split.readBytes()
            val payloadBytes = RuntimeProbePayloadTest.validProbeDex()
            val payload = RuntimeProbePayload(
                bytes = payloadBytes,
                sha256 = RuntimeProbePayloadTest.sha256(payloadBytes),
                dexVersion = "035",
                providerDescriptorPresent = true,
            )

            val result = RepackedRuntimeProbePayloadInjector.inject(
                manifestRewrite = manifestRewrite(base, split),
                payload = payload,
                outputRoot = File(root, "out"),
                cancellation = AtomicCancellationSignal(),
            )

            assertEquals("classes3.dex", result.payloadDexEntry)
            assertFalse(result.alreadyPresent)
            assertArrayEquals(baseBefore, base.readBytes())
            assertArrayEquals(splitBefore, split.readBytes())

            val baseOut = File(
                result.sources.single {
                    it.sourceDisplayName == "base.apk"
                }.outputPath,
            )
            val splitOut = File(
                result.sources.single {
                    it.sourceDisplayName == "split.apk"
                }.outputPath,
            )
            assertArrayEquals(splitBefore, splitOut.readBytes())

            ZipFile(baseOut).use { zip ->
                val injected = zip.getInputStream(
                    requireNotNull(zip.getEntry("classes3.dex")),
                ).use { it.readBytes() }
                assertArrayEquals(payloadBytes, injected)
                assertEquals(
                    payload.sha256,
                    RuntimeProbePayloadTest.sha256(injected),
                )
                assertTrue(zip.getEntry("classes.dex") != null)
                assertTrue(zip.getEntry("classes2.dex") != null)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exactPayloadReinjectionIsIdempotentBySha() {
        val root = Files.createTempDirectory("modkit-probe-idempotent-").toFile()
        try {
            val base = File(root, "base.apk")
            createApk(
                file = base,
                manifest = probeManifest(PACKAGE),
                dexEntries = mapOf(
                    "classes.dex" to "primary".toByteArray(),
                ),
                payload = "base",
            )
            val payloadBytes = RuntimeProbePayloadTest.validProbeDex()
            val payload = RuntimeProbePayload(
                bytes = payloadBytes,
                sha256 = RuntimeProbePayloadTest.sha256(payloadBytes),
                dexVersion = "035",
                providerDescriptorPresent = true,
            )
            val first = RepackedRuntimeProbePayloadInjector.inject(
                manifestRewrite = manifestRewrite(base),
                payload = payload,
                outputRoot = File(root, "first"),
                cancellation = AtomicCancellationSignal(),
            )
            val firstBase = File(first.sources.single().outputPath)
            val firstBytes = firstBase.readBytes()

            val secondInput = manifestRewrite(firstBase).copy(
                sources = listOf(
                    manifestRewrite(firstBase).sources.single().copy(
                        sourceDisplayName = "base.apk",
                    ),
                ),
                baseSourceDisplayName = "base.apk",
            )
            val second = RepackedRuntimeProbePayloadInjector.inject(
                manifestRewrite = secondInput,
                payload = payload,
                outputRoot = File(root, "second"),
                cancellation = AtomicCancellationSignal(),
            )

            assertTrue(second.alreadyPresent)
            assertEquals(first.payloadDexEntry, second.payloadDexEntry)
            assertArrayEquals(
                firstBytes,
                File(second.sources.single().outputPath).readBytes(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun payloadInjectionRequiresVerifiedProbeManifestDeclaration() {
        val root = Files.createTempDirectory("modkit-probe-manifest-").toFile()
        try {
            val base = File(root, "base.apk")
            createApk(
                file = base,
                manifest = basicManifest(PACKAGE, null),
                dexEntries = mapOf(
                    "classes.dex" to "primary".toByteArray(),
                ),
                payload = "base",
            )
            val payloadBytes = RuntimeProbePayloadTest.validProbeDex()
            val payload = RuntimeProbePayload(
                bytes = payloadBytes,
                sha256 = RuntimeProbePayloadTest.sha256(payloadBytes),
                dexVersion = "035",
                providerDescriptorPresent = true,
            )

            val failure = runCatching {
                RepackedRuntimeProbePayloadInjector.inject(
                    manifestRewrite = manifestRewrite(base),
                    payload = payload,
                    outputRoot = File(root, "out"),
                    cancellation = AtomicCancellationSignal(),
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(
                failure?.message.orEmpty()
                    .contains("manifest declaration", ignoreCase = true),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun differentDexContainingProviderDescriptorIsConflictNotIdempotence() {
        val root = Files.createTempDirectory("modkit-probe-conflict-").toFile()
        try {
            val base = File(root, "base.apk")
            val conflict =
                ("prefix-" +
                    RuntimeProbePayloadSource.PROVIDER_DESCRIPTOR +
                    "-suffix").toByteArray()
            createApk(
                file = base,
                manifest = probeManifest(PACKAGE),
                dexEntries = mapOf("classes.dex" to conflict),
                payload = "base",
            )
            val payloadBytes = RuntimeProbePayloadTest.validProbeDex()
            val payload = RuntimeProbePayload(
                bytes = payloadBytes,
                sha256 = RuntimeProbePayloadTest.sha256(payloadBytes),
                dexVersion = "035",
                providerDescriptorPresent = true,
            )

            val failure = runCatching {
                RepackedRuntimeProbePayloadInjector.inject(
                    manifestRewrite = manifestRewrite(base),
                    payload = payload,
                    outputRoot = File(root, "out"),
                    cancellation = AtomicCancellationSignal(),
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(
                failure?.message.orEmpty()
                    .contains("different DEX payload"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun manifestRewrite(
        vararg files: File,
    ): RepackedRuntimeManifestRewriteResult {
        val sources = files.map { file ->
            val displayName = if (files.size == 1) {
                "base.apk"
            } else {
                file.name
            }
            val sha = sha256(file.readBytes())
            RepackedRuntimeManifestRewriteSource(
                sourceDisplayName = displayName,
                preparedInputPath = file.absolutePath + ".prepared",
                preparedInputSha256 = sha,
                outputPath = file.absolutePath,
                outputSha256 = sha,
                manifestChanged = displayName == "base.apk",
                manifestBeforeSha256 = null,
                manifestAfterSha256 = null,
            )
        }
        return RepackedRuntimeManifestRewriteResult(
            artifactSha256 = ARTIFACT_SHA,
            packageName = PACKAGE,
            baseSourceDisplayName = "base.apk",
            sources = sources,
            providerClassName =
                BinaryAndroidManifestProbeInjector.PROVIDER_CLASS,
            providerAuthority =
                PACKAGE +
                    BinaryAndroidManifestProbeInjector.AUTHORITY_SUFFIX,
            outputRootPath = files.first().parentFile.absolutePath,
        )
    }

    private fun createApk(
        file: File,
        manifest: ByteArray,
        dexEntries: Map<String, ByteArray>,
        payload: String,
    ) {
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            out.putNextEntry(ZipEntry("AndroidManifest.xml"))
            out.write(manifest)
            out.closeEntry()

            dexEntries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }

            out.putNextEntry(ZipEntry("assets/payload.txt"))
            out.write(payload.toByteArray())
            out.closeEntry()
        }
    }

    private fun probeManifest(
        packageName: String,
    ): ByteArray =
        BinaryAndroidManifestProbeInjector.inject(
            basicManifest(packageName, null),
        ).bytes

    private fun basicManifest(
        packageName: String,
        splitName: String?,
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
        chunks += startElement(index("application"), emptyList())
        chunks += endElement(index("application"))
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
        val value: Int,
    )

    private fun startElement(
        name: Int,
        attrs: List<Attr>,
    ): ByteArray {
        val size = 36 + attrs.size * 20
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
        buffer.putShort(attrs.size.toShort())
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.putShort(0)
        attrs.forEach { attr ->
            buffer.putInt(attr.namespace)
            buffer.putInt(attr.name)
            buffer.putInt(attr.value)
            buffer.putShort(8)
            buffer.put(0)
            buffer.put(TYPE_STRING.toByte())
            buffer.putInt(attr.value)
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

    private fun stringPool(
        strings: List<String>,
    ): ByteArray {
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

    private fun resourceMap(
        ids: IntArray,
    ): ByteArray {
        val last = ids.indexOfLast { it != 0 }
        val count = maxOf(last + 1, 1)
        val size = 8 + count * 4
        val buffer = ByteBuffer.allocate(size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(RES_XML_RESOURCE_MAP_TYPE.toShort())
        buffer.putShort(8)
        buffer.putInt(size)
        repeat(count) { buffer.putInt(ids[it]) }
        return buffer.array()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }

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
