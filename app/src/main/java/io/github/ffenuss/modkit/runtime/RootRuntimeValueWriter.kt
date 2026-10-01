package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal

data class RootRuntimeValueWriteResult(
    val packageName: String,
    val pid: Int,
    val address: Long,
    val oldValue: String,
    val newValue: String,
    val verified: Boolean,
    val updatedScan: RootRuntimeValueScanResult,
)

fun interface RuntimeMemoryWriter {
    fun write(
        address: Long,
        bytes: ByteArray,
        cancellation: CancellationSignal,
    ): Boolean
}

/**
 * Privileged bounded writer for /proc/<pid>/mem.
 *
 * The payload is rendered as POSIX printf octal escapes, so no user text is
 * ever interpolated into the shell command. Callers must still prove the
 * address belongs to the selected process and a writable mapping.
 */
class RootProcMemRuntimeMemoryWriter(
    private val pid: Int,
    private val runner: RootCommandRunner =
        AndroidRootCommandRunner(),
) : RuntimeMemoryWriter {
    override fun write(
        address: Long,
        bytes: ByteArray,
        cancellation: CancellationSignal,
    ): Boolean {
        if (
            pid <= 0 ||
            address < 0L ||
            bytes.isEmpty() ||
            bytes.size >
                ProcMemRuntimeMemoryReader
                    .MAX_READ_BYTES
        ) {
            return false
        }
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }

        val escaped =
            bytes.joinToString(
                separator = "",
            ) {
                byte ->
                "\\" +
                    (byte.toInt() and 0xff)
                        .toString(8)
                        .padStart(3, '0')
            }
        val command =
            "printf '" +
                escaped +
                "' | dd of=/proc/" +
                pid +
                "/mem bs=1 seek=" +
                address +
                " count=" +
                bytes.size +
                " conv=notrunc status=none 2>/dev/null"

        val result =
            try {
                runner.run(
                    command = command,
                    maxOutputBytes = 256,
                    cancellation = cancellation,
                )
            } catch (
                failure: AnalysisCancelledException,
            ) {
                throw failure
            } catch (_: Throwable) {
                return false
            }

        return result.exitCode == 0 &&
            !result.truncated
    }
}

/**
 * Explicit one-shot write for an address that came from the active value scan.
 *
 * Safety properties:
 * - exact package/PID is re-proven immediately before the write;
 * - the address must still be one of the scan's hits;
 * - the full value must remain inside a fresh readable+writable private map;
 * - bytes are read before and after the write;
 * - success is returned only when read-back equals the requested bytes.
 */
object RootRuntimeValueWriteCoordinator {
    fun writeHit(
        previous: RootRuntimeValueScanResult,
        address: Long,
        valueText: String,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): RootRuntimeValueWriteResult {
        val hit =
            previous.snapshot.hits
                .singleOrNull {
                    it.address == address
                }
                ?: error(
                    "Адрес отсутствует в текущем наборе найденных значений.",
                )
        val type =
            previous.snapshot.valueType
        val bytes =
            type.encodeQuery(
                valueText,
            )

        val capture =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        previous.packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                )
        require(
            capture.pid ==
                previous.pid,
        ) {
            "PID процесса изменился. Запись отменена."
        }

        val ranges =
            RootRuntimeValueScanCoordinator
                .candidateRanges(
                    ProcMapsParser.parse(
                        capture.capture.text,
                    ),
                )
        val writableRange =
            ranges.singleOrNull {
                address >= it.start &&
                    address +
                        bytes.size <=
                    it.endExclusive
            }
        requireNotNull(
            writableRange,
        ) {
            "Адрес больше не находится в подтверждённом writable private mapping."
        }

        val reader =
            RootProcMemRuntimeMemoryReader(
                pid = capture.pid,
                runner = runner,
            )
        val before =
            reader.read(
                address = address,
                size = type.byteWidth,
                cancellation =
                    cancellation,
            ) ?: error(
                "Не удалось перечитать исходное runtime-значение.",
            )
        require(
            before.size ==
                type.byteWidth
        ) {
            "Исходное runtime-значение прочитано не полностью."
        }

        val writer =
            RootProcMemRuntimeMemoryWriter(
                pid = capture.pid,
                runner = runner,
            )
        require(
            writer.write(
                address = address,
                bytes = bytes,
                cancellation =
                    cancellation,
            ),
        ) {
            "Root-запись в память процесса не выполнена."
        }

        val after =
            reader.read(
                address = address,
                size = type.byteWidth,
                cancellation =
                    cancellation,
            ) ?: error(
                "Запись выполнена, но read-back недоступен.",
            )
        require(
            after.contentEquals(bytes)
        ) {
            "Read-back не совпал с записанным значением."
        }

        val oldBits =
            type.readBits(
                before,
                0,
            )
        val newBits =
            type.readBits(
                after,
                0,
            )
        val updatedSnapshot =
            previous.snapshot.copy(
                hits =
                    previous.snapshot.hits
                        .map {
                            current ->
                            if (
                                current.address ==
                                address
                            ) {
                                current.copy(
                                    bits = newBits,
                                )
                            } else {
                                current
                            }
                        },
            )
        val updated =
            previous.copy(
                capturedAtEpochMs =
                    System.currentTimeMillis(),
                snapshot =
                    updatedSnapshot,
            )

        return RootRuntimeValueWriteResult(
            packageName =
                previous.packageName,
            pid = previous.pid,
            address = hit.address,
            oldValue =
                type.display(oldBits),
            newValue =
                type.display(newBits),
            verified = true,
            updatedScan = updated,
        )
    }
}
