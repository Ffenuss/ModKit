package io.github.ffenuss.modkit.runtime

import io.github.ffenuss.modkit.analysis.CancellationSignal

data class RootRuntimeDirectWriteResult(
    val packageName: String,
    val pid: Int,
    val address: Long,
    val valueType: RuntimeValueType,
    val oldValue: String,
    val newValue: String,
)

/**
 * Verified write for a runtime address already surfaced by ModKit's live
 * scanners. Unlike RootRuntimeValueWriteCoordinator it does not require the
 * whole previous scan snapshot, which makes it suitable for overlay candidates.
 *
 * Exact PID and a fresh writable private mapping are re-proven on every write,
 * followed by read-back verification.
 */
object RootRuntimeDirectValueCoordinator {
    fun writeValue(
        packageName: String,
        pid: Int,
        address: Long,
        valueType: RuntimeValueType,
        valueText: String,
        cancellation: CancellationSignal,
        runner: RootCommandRunner =
            AndroidRootCommandRunner(),
    ): RootRuntimeDirectWriteResult {
        require(pid > 0) {
            "PID должен быть положительным."
        }
        require(address >= 0L) {
            "Некорректный runtime-адрес."
        }
        val bytes =
            valueType.encodeQuery(
                valueText,
            )

        val capture =
            RootRuntimeCaptureCoordinator
                .captureMaps(
                    packageName =
                        packageName,
                    cancellation =
                        cancellation,
                    runner = runner,
                    expectedPid = pid,
                )
        require(capture.pid == pid) {
            "PID процесса изменился. Запись отменена."
        }

        val ranges =
            RootRuntimeValueScanCoordinator
                .candidateRanges(
                    ProcMapsParser.parse(
                        capture.capture.text,
                    ),
                )
        val writable =
            ranges.singleOrNull {
                address >= it.start &&
                    address +
                        bytes.size <=
                    it.endExclusive
            }
        requireNotNull(writable) {
            "Адрес больше не находится в writable private mapping."
        }

        val reader =
            RootProcMemRuntimeMemoryReader(
                pid = pid,
                runner = runner,
            )
        val before =
            reader.read(
                address = address,
                size = valueType.byteWidth,
                cancellation = cancellation,
            ) ?: error(
                "Не удалось прочитать текущее runtime-значение.",
            )
        require(
            before.size ==
                valueType.byteWidth,
        ) {
            "Runtime-значение прочитано не полностью."
        }

        val writer =
            RootProcMemRuntimeMemoryWriter(
                pid = pid,
                runner = runner,
            )
        require(
            writer.write(
                address = address,
                bytes = bytes,
                cancellation = cancellation,
            ),
        ) {
            "Root-запись в память процесса не выполнена."
        }

        val after =
            reader.read(
                address = address,
                size = valueType.byteWidth,
                cancellation = cancellation,
            ) ?: error(
                "Запись выполнена, но read-back недоступен.",
            )
        require(
            after.contentEquals(bytes),
        ) {
            "Read-back не совпал с новым значением."
        }

        return RootRuntimeDirectWriteResult(
            packageName = packageName,
            pid = pid,
            address = address,
            valueType = valueType,
            oldValue =
                valueType.display(
                    valueType.readBits(
                        before,
                        0,
                    ),
                ),
            newValue =
                valueType.display(
                    valueType.readBits(
                        after,
                        0,
                    ),
                ),
        )
    }
}
