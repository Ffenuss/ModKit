package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AtomicCancellationSignal
import java.io.File
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

class RepackedRuntimeNativeProbeInjectorTest {
    @Test
    fun targetAbiFromSplitInjectsOnlyThatHelperIntoBaseAndKeepsSplitExact() {
        val root = Files.createTempDirectory("modkit-native-probe-abi-").toFile()
        try {
            val base = File(root, "base.apk")
            val split = File(root, "split.apk")
            createApk(
                file = base,
                entries = mapOf(
                    "classes.dex" to "base-dex".toByteArray(),
                ),
            )
            createApk(
                file = split,
                entries = mapOf(
                    "lib/arm64-v8a/libtarget.so" to
                        fakeElf(
                            abi = "arm64-v8a",
                            marker = false,
                        ),
                ),
            )
            val baseBefore = base.readBytes()
            val splitBefore = split.readBytes()
            val dexInjection = dexInjection(base, split)
            val payloads = allPayloads()

            val result = RepackedRuntimeNativeProbeInjector.inject(
                dexInjection = dexInjection,
                payloads = payloads,
                outputRoot = File(root, "out"),
                cancellation = AtomicCancellationSignal(),
            )

            assertEquals(setOf("arm64-v8a"), result.selectedAbis)
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
                val helper = zip.getEntry(
                    "lib/arm64-v8a/" +
                        RuntimeProbeNativePayloadSource.LIBRARY_NAME,
                )
                assertTrue(helper != null)
                assertTrue(
                    zip.getEntry(
                        "lib/x86_64/" +
                            RuntimeProbeNativePayloadSource.LIBRARY_NAME,
                    ) == null,
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun pureJavaTargetGetsAllSupportedHelperAbis() {
        val root = Files.createTempDirectory("modkit-native-probe-java-").toFile()
        try {
            val base = File(root, "base.apk")
            createApk(
                file = base,
                entries = mapOf(
                    "classes.dex" to "base-dex".toByteArray(),
                ),
            )

            val result = RepackedRuntimeNativeProbeInjector.inject(
                dexInjection = dexInjection(base),
                payloads = allPayloads(),
                outputRoot = File(root, "out"),
                cancellation = AtomicCancellationSignal(),
            )

            assertEquals(
                RuntimeProbeNativePayloadSource.supportedAbis.toSet(),
                result.selectedAbis,
            )
            val baseOut = File(result.sources.single().outputPath)
            ZipFile(baseOut).use { zip ->
                RuntimeProbeNativePayloadSource.supportedAbis.forEach { abi ->
                    assertTrue(
                        zip.getEntry(
                            "lib/$abi/" +
                                RuntimeProbeNativePayloadSource.LIBRARY_NAME,
                        ) != null,
                    )
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun exactExistingHelperIsIdempotentButDifferentHelperFailsClosed() {
        val root = Files.createTempDirectory("modkit-native-probe-existing-").toFile()
        try {
            val payload = payload("arm64-v8a")
            val exactBase = File(root, "exact.apk")
            createApk(
                file = exactBase,
                entries = mapOf(
                    "lib/arm64-v8a/libtarget.so" to
                        fakeElf("arm64-v8a", marker = false),
                    "lib/arm64-v8a/" +
                        RuntimeProbeNativePayloadSource.LIBRARY_NAME to
                        payload.bytes,
                ),
            )

            val exact = RepackedRuntimeNativeProbeInjector.inject(
                dexInjection = dexInjection(
                    exactBase,
                    baseDisplayName = "base.apk",
                ),
                payloads = mapOf("arm64-v8a" to payload),
                outputRoot = File(root, "exact-out"),
                cancellation = AtomicCancellationSignal(),
            )
            assertEquals(setOf("arm64-v8a"), exact.selectedAbis)

            val conflictBase = File(root, "conflict.apk")
            createApk(
                file = conflictBase,
                entries = mapOf(
                    "lib/arm64-v8a/libtarget.so" to
                        fakeElf("arm64-v8a", marker = false),
                    "lib/arm64-v8a/" +
                        RuntimeProbeNativePayloadSource.LIBRARY_NAME to
                        "different".toByteArray(),
                ),
            )
            val failure = runCatching {
                RepackedRuntimeNativeProbeInjector.inject(
                    dexInjection = dexInjection(
                        conflictBase,
                        baseDisplayName = "base.apk",
                    ),
                    payloads = mapOf("arm64-v8a" to payload),
                    outputRoot = File(root, "conflict-out"),
                    cancellation = AtomicCancellationSignal(),
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(
                failure?.message.orEmpty().contains(
                    "size is outside limits",
                ) ||
                    failure?.message.orEmpty().contains(
                        "different runtime probe native helper",
                    ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun staleDexInjectionInputFailsClosedAndCleansNativeStage() {
        val root = Files.createTempDirectory("modkit-native-probe-stale-").toFile()
        try {
            val base = File(root, "base.apk")
            createApk(
                file = base,
                entries = mapOf(
                    "lib/arm64-v8a/libtarget.so" to
                        fakeElf("arm64-v8a", marker = false),
                ),
            )
            val dexInjection = dexInjection(base)
            base.appendText("changed")
            val outputRoot = File(root, "out")

            val failure = runCatching {
                RepackedRuntimeNativeProbeInjector.inject(
                    dexInjection = dexInjection,
                    payloads = mapOf(
                        "arm64-v8a" to payload("arm64-v8a"),
                    ),
                    outputRoot = outputRoot,
                    cancellation = AtomicCancellationSignal(),
                )
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertFalse(
                File(
                    outputRoot,
                    ARTIFACT_SHA +
                        "/repacked-test/native-probe-injection",
                ).exists(),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun dexInjection(
        vararg files: File,
        baseDisplayName: String = "base.apk",
    ): RepackedRuntimeProbeInjectionResult {
        val sources = files.mapIndexed { index, file ->
            val displayName = if (files.size == 1) {
                baseDisplayName
            } else if (index == 0) {
                "base.apk"
            } else {
                "split.apk"
            }
            val sha = sha256(file.readBytes())
            RepackedRuntimeProbeInjectedSource(
                sourceDisplayName = displayName,
                inputPath = file.absolutePath + ".previous",
                inputSha256 = sha,
                outputPath = file.absolutePath,
                outputSha256 = sha,
                payloadInjected = displayName == "base.apk",
                payloadDexEntry =
                    if (displayName == "base.apk") "classes2.dex" else null,
                payloadSha256 =
                    if (displayName == "base.apk") DEX_SHA else null,
            )
        }
        return RepackedRuntimeProbeInjectionResult(
            artifactSha256 = ARTIFACT_SHA,
            packageName = PACKAGE,
            baseSourceDisplayName = baseDisplayName,
            payloadSha256 = DEX_SHA,
            payloadDexVersion = "035",
            payloadDexEntry = "classes2.dex",
            alreadyPresent = false,
            sources = sources,
            outputRootPath = files.first().parentFile.absolutePath,
        )
    }

    private fun allPayloads(): Map<String, RuntimeProbeNativePayload> =
        RuntimeProbeNativePayloadSource.supportedAbis.associateWith(::payload)

    private fun payload(
        abi: String,
    ): RuntimeProbeNativePayload {
        val bytes = fakeElf(abi = abi, marker = true)
        val expected = when (abi) {
            "arm64-v8a" -> 183 to true
            "armeabi-v7a" -> 40 to false
            "x86" -> 3 to false
            "x86_64" -> 62 to true
            else -> error(abi)
        }
        return RuntimeProbeNativePayload(
            abi = abi,
            bytes = bytes,
            sha256 = sha256(bytes),
            machine = expected.first,
            is64Bit = expected.second,
        )
    }

    private fun fakeElf(
        abi: String,
        marker: Boolean,
    ): ByteArray {
        val (machine, is64) = when (abi) {
            "arm64-v8a" -> 183 to true
            "armeabi-v7a" -> 40 to false
            "x86" -> 3 to false
            "x86_64" -> 62 to true
            else -> error(abi)
        }
        val bytes = ByteArray(512)
        bytes[0] = 0x7f
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = if (is64) 2 else 1
        bytes[5] = 1
        putU16(bytes, 16, 3)
        putU16(bytes, 18, machine)
        if (marker) {
            var offset = 64
            listOf(
                "RuntimeNativeBridge_nativeResolveLoadedSymbol",
                "RuntimeNativeBridge_nativeStartPassiveDlsymTrace",
                "RuntimeNativeBridge_nativeStopPassiveDlsymTrace",
                "RuntimeNativeBridge_nativeStartPassiveJniTrace",
                "RuntimeNativeBridge_nativeStopPassiveJniTrace",
            "RuntimeNativeBridge_nativePassiveJniOnLoadInvocationReady",
            ).forEach { value ->
                val markerBytes =
                    value.toByteArray(Charsets.US_ASCII)
                markerBytes.copyInto(bytes, offset)
                offset += markerBytes.size + 8
            }
        }
        return bytes
    }

    private fun createApk(
        file: File,
        entries: Map<String, ByteArray>,
    ) {
        ZipOutputStream(file.outputStream().buffered()).use { out ->
            entries.forEach { (name, bytes) ->
                out.putNextEntry(ZipEntry(name))
                out.write(bytes)
                out.closeEntry()
            }
        }
    }

    private fun putU16(
        bytes: ByteArray,
        offset: Int,
        value: Int,
    ) {
        bytes[offset] = (value and 0xff).toByte()
        bytes[offset + 1] =
            ((value ushr 8) and 0xff).toByte()
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
        private const val DEX_SHA =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
