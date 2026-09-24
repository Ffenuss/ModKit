package io.github.ffenuss.modkit.analysis

import java.io.*
import java.security.MessageDigest
import java.util.PriorityQueue

data class NativeFunctionSpan(val offset: Long, val references: Int, val nextOffset: Long?)

/** Disk-backed census of every validated CodeGenModule slot, independent of UI binding limits. */
data class NativeFunctionIndex(
    val path: String,
    val sha256: String,
    val records: Int,
    val slotsExamined: Long,
    val complete: Boolean,
) : Serializable {
    fun verify(): Boolean = File(path).let {
        it.isFile && it.length() == records.toLong() * RECORD_BYTES && digest(it) == sha256
    }

    fun lookup(offset: Long): NativeFunctionSpan? {
        if (!complete) return null
        RandomAccessFile(path, "r").use { input ->
            require(input.length() == records.toLong() * RECORD_BYTES) { "Native pointer index is incomplete." }
            var low = 0
            var high = records - 1
            while (low <= high) {
                val mid = (low + high).ushr(1)
                input.seek(mid.toLong() * RECORD_BYTES)
                val found = input.readLong()
                val refs = input.readInt()
                when {
                    found < offset -> low = mid + 1
                    found > offset -> high = mid - 1
                    else -> return NativeFunctionSpan(found, refs,
                        if (mid + 1 < records) input.readLong() else null)
                }
            }
        }
        return null
    }

    companion object {
        private const val RECORD_BYTES = 12L
        internal fun digest(file: File): String {
            val hash = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    hash.update(buffer, 0, count)
                }
            }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }
}

/** External merge sort: 64K primitive offsets per run, no boxed per-method map. */
class NativeFunctionIndexWriter(
    private val output: File,
    private val cancellation: CancellationSignal,
    private val chunkSize: Int = 65_536,
) : Closeable {
    private val directory = File(output.parentFile, output.name + ".runs-" + System.nanoTime())
        .apply { mkdirs() }
    private val values = LongArray(chunkSize)
    private var used = 0
    private val runs = mutableListOf<File>()

    init { require(chunkSize > 0) }

    fun add(offset: Long) {
        checkCancelled()
        require(offset >= 0)
        values[used++] = offset
        if (used == values.size) flush()
    }

    private fun flush() {
        if (used == 0) return
        java.util.Arrays.sort(values, 0, used)
        val run = File(directory, "${runs.size}.bin")
        DataOutputStream(run.outputStream().buffered()).use { target ->
            for (i in 0 until used) target.writeLong(values[i])
        }
        runs += run
        used = 0
    }

    fun finish(slotsExamined: Long, complete: Boolean): NativeFunctionIndex {
        flush()
        data class Head(val value: Long, val run: Int)
        val readers = mutableListOf<DataInputStream>()
        val remaining = runs.map { it.length() / 8 }.toLongArray()
        val queue = PriorityQueue<Head>(compareBy<Head> { it.value }.thenBy { it.run })
        val temp = File(directory, "merged")
        var count = 0
        try {
            runs.forEachIndexed { i, file ->
                val reader = DataInputStream(file.inputStream().buffered(8 * 1024))
                readers += reader
                if (remaining[i] > 0) {
                    queue.add(Head(reader.readLong(), i)); remaining[i]--
                }
            }
            DataOutputStream(temp.outputStream().buffered()).use { target ->
                var last = -1L
                var refs = 0
                fun emit() {
                    if (last >= 0) { target.writeLong(last); target.writeInt(refs); count++ }
                }
                while (queue.isNotEmpty()) {
                    checkCancelled()
                    val head = queue.remove()
                    if (head.value != last) { emit(); last = head.value; refs = 0 }
                    refs = Math.addExact(refs, 1)
                    if (remaining[head.run] > 0) {
                        queue.add(Head(readers[head.run].readLong(), head.run))
                        remaining[head.run]--
                    }
                }
                emit()
            }
            val hash = NativeFunctionIndex.digest(temp)
            require(temp.renameTo(output)) { "Cannot finalize native function index." }
            return NativeFunctionIndex(output.absolutePath, hash, count, slotsExamined, complete)
        } finally { readers.forEach { it.close() } }
    }

    private fun checkCancelled() {
        if (cancellation.isCancelled()) throw AnalysisCancelledException()
    }

    override fun close() { directory.deleteRecursively() }
}
