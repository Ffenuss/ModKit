package io.github.ffenuss.modkit.analysis

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Complete disk-backed method index. `Il2CppBinaryEvidence.bindings` is a small,
 * project-first UI window; it must never define the scanner's coverage.
 *
 * Fixed-size sparse records are addressed by MethodDef index. Empty entries
 * have slotPlusOne=0, so a zero-valued native pointer can never be confused
 * with a resolved binding. No per-method Kotlin objects are retained here.
 */
data class Il2CppIndexedMethod(
    val slotIndex: Int,
    val moduleIndex: Int,
    val functionVirtualAddress: Long,
    val functionFileOffset: Long?,
    val returnKind: Il2CppNativeReturnKind,
)

data class Il2CppDiskBindingIndex(
    val path: String,
    val sha256: String,
    val methodCount: Int,
    val boundCount: Int,
) : Serializable {
    fun verify(): Boolean {
        val file = File(path)
        return methodCount >= 0 && boundCount in 0..methodCount &&
            file.isFile && file.length() == methodCount.toLong() * RECORD_BYTES &&
            hash(file) == sha256
    }

    fun lookup(methodIndex: Int): Il2CppIndexedMethod? {
        if (methodIndex !in 0 until methodCount) return null
        RandomAccessFile(path, "r").use { raf ->
            require(raf.length() == methodCount.toLong() * RECORD_BYTES) {
                "IL2CPP disk binding index is truncated."
            }
            raf.seek(methodIndex.toLong() * RECORD_BYTES)
            val slotPlusOne = raf.readInt()
            if (slotPlusOne == 0) return null
            val moduleIndex = raf.readInt()
            val functionVa = raf.readLong()
            val rawOffset = raf.readLong()
            val kind = raf.readInt()
            val reserved = raf.readInt()
            require(slotPlusOne > 0 && moduleIndex >= 0 && functionVa > 0L &&
                rawOffset >= -1L && reserved == 0 && kind in Il2CppNativeReturnKind.entries.indices
            ) { "Corrupt IL2CPP disk binding record." }
            return Il2CppIndexedMethod(
                slotIndex = slotPlusOne - 1,
                moduleIndex = moduleIndex,
                functionVirtualAddress = functionVa,
                functionFileOffset = rawOffset.takeIf { it >= 0L },
                returnKind = Il2CppNativeReturnKind.entries[kind],
            )
        }
    }

    companion object {
        internal const val RECORD_BYTES = 32L

        internal fun hash(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

/** Atomic, bounded-RAM writer. One record per metadata MethodDef, no 30k stop. */
class Il2CppDiskBindingIndexWriter(
    private val output: File,
    private val methodCount: Int,
    private val cancellation: CancellationSignal,
) : Closeable {
    private val staging = File(output.parentFile, output.name + ".part-" + System.nanoTime())
    private val raf: RandomAccessFile
    private var count = 0
    private var completed = false

    init {
        require(methodCount in 0..300_000) { "MethodDef count exceeds validated metadata bound." }
        output.parentFile?.mkdirs()
        raf = RandomAccessFile(staging, "rw")
        raf.setLength(methodCount.toLong() * Il2CppDiskBindingIndex.RECORD_BYTES)
    }

    fun add(
        methodIndex: Int,
        slotIndex: Int,
        moduleIndex: Int,
        functionVa: Long,
        fileOffset: Long?,
        returnKind: Il2CppNativeReturnKind,
    ) {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
        require(methodIndex in 0 until methodCount && slotIndex >= 0 &&
            moduleIndex >= 0 && functionVa > 0L && (fileOffset == null || fileOffset >= 0L)
        )
        val pos = methodIndex.toLong() * Il2CppDiskBindingIndex.RECORD_BYTES
        raf.seek(pos)
        check(raf.readInt() == 0) { "Duplicate MethodDef index in binary binding." }
        raf.seek(pos)
        raf.writeInt(slotIndex + 1)
        raf.writeInt(moduleIndex)
        raf.writeLong(functionVa)
        raf.writeLong(fileOffset ?: -1L)
        raf.writeInt(returnKind.ordinal)
        raf.writeInt(0)
        count++
    }

    fun finish(): Il2CppDiskBindingIndex {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
        raf.fd.sync()
        raf.close()
        val digest = Il2CppDiskBindingIndex.hash(staging)
        Files.move(
            staging.toPath(), output.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        completed = true
        return Il2CppDiskBindingIndex(output.absolutePath, digest, methodCount, count)
    }

    override fun close() {
        if (!completed) {
            runCatching { raf.close() }
            staging.delete()
        }
    }
}
