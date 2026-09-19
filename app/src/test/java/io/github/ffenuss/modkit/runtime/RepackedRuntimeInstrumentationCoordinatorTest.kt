package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisWorkspace
import io.github.ffenuss.modkit.analysis.ArtifactIndex
import io.github.ffenuss.modkit.analysis.ArtifactSource
import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import io.github.ffenuss.modkit.analysis.WorkspaceSource
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

class RepackedRuntimeInstrumentationCoordinatorTest {
    @Test
    fun connectsCopyManifestRewriteAndExecutableProbeInjection() {
        val root = Files.createTempDirectory("modkit-repacked-e2e-").toFile()
        try {
            val source = File(root, "target.apk")
            createApk(source)
            val original = source.readBytes()
            val sourceSha = sha256(original)
            val descriptor = ArtifactSource(
                displayName = "base.apk",
                size = source.length(),
                sha256 = sourceSha,
            )
            val workspace = AnalysisWorkspace(
                index = ArtifactIndex(
                    artifactSha256 = ARTIFACT_SHA,
                    sources = listOf(descriptor),
                    entries = emptyList(),
                ),
                sources = listOf(
                    WorkspaceSource(
                        descriptor = descriptor,
                        file = source,
                    ),
                ),
            )
            val payloadBytes =
                RuntimeProbePayloadTest.validProbeDex()
            val payload = RuntimeProbePayload(
                bytes = payloadBytes,
                sha256 = RuntimeProbePayloadTest.sha256(
                    payloadBytes,
                ),
                dexVersion = "035",
                providerDescriptorPresent = true,
            )
            val outputRoot = File(root, "out")

            val result =
                RepackedRuntimeInstrumentationCoordinator
                    .instrumentWithPayload(
                        workspace = workspace,
                        payload = payload,
                        outputRoot = outputRoot,
                        cancellation = AtomicCancellationSignal(),
                    )

            assertArrayEquals(original, source.readBytes())
            assertEquals(PACKAGE, result.packageName)
            assertTrue(result.manifestInventory.verified)
            assertEquals(
                BinaryAndroidManifestProbeInjector.PROVIDER_CLASS,
                result.manifestRewrite.providerClassName,
            )
            assertEquals(
                "classes2.dex",
                result.probeInjection.payloadDexEntry,
            )

            val preflight =
                RepackedRuntimeBuildPreflight
                    .validateProbeInjection(
                        manifestInventory =
                            result.manifestInventory,
                        injection = result.probeInjection,
                    )
            assertTrue(preflight.ready)
            assertTrue(preflight.blockers.isEmpty())

            val injectedBase = File(
                result.probeInjection.sources.single().outputPath,
            )
            ZipFile(injectedBase).use { zip ->
                val dex = zip.getInputStream(
                    requireNotNull(
                        zip.getEntry("classes2.dex"),
                    ),
                ).use { it.readBytes() }
                assertArrayEquals(payloadBytes, dex)
            }

            assertTrue(
                RepackedRuntimeInstrumentationCoordinator.cleanup(
                    result = result,
                    outputRoot = outputRoot,
                ),
            )
            assertFalse(
                File(result.prepared.rootPath).exists(),
            )
            assertFalse(
                File(result.manifestRewrite.outputRootPath)
                    .exists(),
            )
            assertFalse(
                File(result.probeInjection.outputRootPath)
                    .exists(),
            )
            assertArrayEquals(original, source.readBytes())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun buildPreflightDetectsProbeStagingChangedAfterInjection() {
        val root = Files.createTempDirectory("modkit-repacked-stale-").toFile()
        try {
            val source = File(root, "target.apk")
            createApk(source)
            val sourceSha = sha256(source.readBytes())
            val descriptor = ArtifactSource(
                displayName = "base.apk",
                size = source.length(),
                sha256 = sourceSha,
            )
            val workspace = AnalysisWorkspace(
                index = ArtifactIndex(
                    artifactSha256 = ARTIFACT_SHA,
                    sources = listOf(descriptor),
                    entries = emptyList(),
                ),
                sources = listOf(
                    WorkspaceSource(descriptor, source),
                ),
            )
            val payloadBytes =
                RuntimeProbePayloadTest.validProbeDex()
            val result =
                RepackedRuntimeInstrumentationCoordinator
                    .instrumentWithPayload(
                        workspace = workspace,
                        payload = RuntimeProbePayload(
                            bytes = payloadBytes,
                            sha256 =
                                RuntimeProbePayloadTest.sha256(
                                    payloadBytes,
                                ),
                            dexVersion = "035",
                            providerDescriptorPresent = true,
                        ),
                        outputRoot = File(root, "out"),
                        cancellation = AtomicCancellationSignal(),
                    )

            File(
                result.probeInjection.sources.single().outputPath,
            ).appendBytes(byteArrayOf(1, 2, 3))

            val preflight =
                RepackedRuntimeBuildPreflight
                    .validateProbeInjection(
                        manifestInventory =
                            result.manifestInventory,
                        injection = result.probeInjection,
                    )

            assertFalse(preflight.ready)
            assertTrue(
                preflight.blockers.any {
                    "changed before build" in it
                },
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createApk(file: File) {
        ZipOutputStream(
            file.outputStream().buffered(),
        ).use { out ->
            out.putNextEntry(ZipEntry("AndroidManifest.xml"))
            out.write(binaryManifest())
            out.closeEntry()

            out.putNextEntry(ZipEntry("classes.dex"))
            out.write("original-dex".toByteArray())
            out.closeEntry()

            out.putNextEntry(ZipEntry("assets/original.txt"))
            out.write("unchanged".toByteArray())
            out.closeEntry()
        }
    }

    private fun binaryManifest(): ByteArray {
        val strings = listOf(
            "manifest",
            "package",
            PACKAGE,
            "application",
            ANDROID_NS,
            "name",
        )
        fun index(value: String): Int =
            strings.indexOf(value)
                .takeIf { it >= 0 }
                ?: error(value)

        val chunks = mutableListOf<ByteArray>()
        chunks += stringPool(strings)

        val resourceIds = IntArray(strings.size)
        resourceIds[index("name")] = android.R.attr.name
        chunks += resourceMap(resourceIds)

        chunks += startElement(
            name = index("manifest"),
            attributes = listOf(
                Attr(
                    namespace = NO_INDEX,
                    name = index("package"),
                    value = index(PACKAGE),
                ),
            ),
        )
        chunks += startElement(
            name = index("application"),
            attributes = emptyList(),
        )
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
        attributes: List<Attr>,
    ): ByteArray {
        val size = 36 + attributes.size * 20
        val buffer = ByteBuffer.allocate(size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(
            RES_XML_START_ELEMENT_TYPE.toShort(),
        )
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
        attributes.forEach { attr ->
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
            byteArrayOf(
                value.length.toByte(),
                bytes.size.toByte(),
            ) + bytes + byteArrayOf(0)
        }
        val offsets = IntArray(strings.size)
        var cursor = 0
        encoded.forEachIndexed { index, bytes ->
            offsets[index] = cursor
            cursor += bytes.size
        }

        val headerSize = 28
        val stringsStart =
            headerSize + strings.size * 4
        val size = (stringsStart + cursor + 3) and 3.inv()
        val buffer = ByteBuffer.allocate(size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putShort(
            RES_STRING_POOL_TYPE.toShort(),
        )
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
        buffer.putShort(
            RES_XML_RESOURCE_MAP_TYPE.toShort(),
        )
        buffer.putShort(8)
        buffer.putInt(size)
        repeat(count) {
            buffer.putInt(ids[it])
        }
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
