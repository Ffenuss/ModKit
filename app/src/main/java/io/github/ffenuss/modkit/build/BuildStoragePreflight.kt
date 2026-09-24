package io.github.ffenuss.modkit.build

import android.os.StatFs
import java.io.File

/**
 * APK alignment and signing create additional copies beside existing staged
 * files. Check the filesystem before expensive work on large Unity asset
 * splits, rather than discovering ENOSPC after several minutes.
 */
object BuildStoragePreflight {
    private const val RESERVE = 128L * 1024L * 1024L
    private const val MIB = 1024L * 1024L

    fun verify(outputFiles: List<File>, destination: File) {
        require(outputFiles.isNotEmpty()) {
            "Промежуточные APK отсутствуют."
        }
        val totalBytes = outputFiles.sumOf { file ->
            require(file.isFile && file.canRead()) {
                "APK для сборки недоступен: " + file.name
            }
            file.length()
        }
        val available = StatFs(destination.absolutePath).availableBytes
        checkAvailable(totalBytes, available)
    }

    internal fun checkAvailable(inputBytes: Long, availableBytes: Long) {
        require(inputBytes > 0 && availableBytes >= 0)
        require(inputBytes <= (Long.MAX_VALUE - RESERVE) / 2L)
        val needed = inputBytes * 2L + RESERVE
        require(availableBytes >= needed) {
            "Недостаточно места для выравнивания и подписи APK-set: " +
                "требуется ещё " + mib(needed - availableBytes) +
                " МиБ. Для больших игр ModKit создаёт временные " +
                "копии каждого APK. Освободите память и повторите " +
                "сборку; ранее подготовленные изменения сохранены."
        }
    }

    private fun mib(value: Long): Long =
        (value + MIB - 1L) / MIB
}
