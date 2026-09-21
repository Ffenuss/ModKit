package io.github.ffenuss.modkit.sandbox

import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.analysis.ElfImage
import io.github.ffenuss.modkit.analysis.ElfLoadSegment
import io.github.ffenuss.modkit.analysis.EngineResultCache
import io.github.ffenuss.modkit.analysis.FastArtifactIndexer
import io.github.ffenuss.modkit.analysis.ProgressSink
import io.github.ffenuss.modkit.data.InstalledAppTarget
import io.github.ffenuss.modkit.runtime.AndroidRootCommandRunner
import io.github.ffenuss.modkit.runtime.ProcMapRegion
import io.github.ffenuss.modkit.runtime.ProcMapsParser
import io.github.ffenuss.modkit.runtime.RootCommandRunner
import io.github.ffenuss.modkit.runtime.RootProcMemRuntimeMemoryReader
import io.github.ffenuss.modkit.runtime.RootRuntimeCaptureCoordinator
import io.github.ffenuss.modkit.runtime.RuntimeModuleMappingResolver
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipFile

data class SandboxRuntimePatchPlan(
    val modificationId: String,
    val title: String,
    val moduleName: String,
    val binaryVirtualAddress: Long,
    val runtimeAddress: Long,
    val originalBytes: ByteArray,
    val replacementBytes: ByteArray,
)

data class SandboxRuntimePatchRecord(
    val plan: SandboxRuntimePatchPlan,
    val appliedBySession: Boolean,
    val alreadyActive: Boolean,
)

data class SandboxRuntimeActivationSession(
    val packageName: String,
    val pid: Int,
    val artifactSha256: String,
    val records: List<SandboxRuntimePatchRecord>,
) {
    val appliedCount: Int
        get() = records.count { it.appliedBySession }

    val alreadyActiveCount: Int
        get() = records.count { it.alreadyActive }
}

/**
 * Resolves a SHA-bound sandbox profile into exact live addresses and applies
 * native code changes only after static bytes and live bytes agree.
 *
 * The write transport is /proc/<pid>/mem. Android/Linux routes remote writes
 * through access_remote_vm/copy_to_user_page; executable mappings therefore
 * receive the architecture's ptrace/cache-coherency handling. Every write is
 * still treated as fallible: target identity, ELF mapping, static bytes and
 * read-back are revalidated, and partial profile application is rolled back.
 */
object RootSandboxRuntimePatchCoordinator {
    private const val MAX_PATCH_BYTES = 64
    private const val MODULE_BUFFER_BYTES = 128 * 1024
    private const val MAX_MODULE_BYTES = 1024L * 1024L * 1024L

    fun activateProfile(
        context: Context,
        app: InstalledAppTarget,
        profile: SandboxProfile,
        expectedPid: Int,
        cancellation: CancellationSignal,
        runner: RootCommandRunner = AndroidRootCommandRunner(),
    ): SandboxRuntimeActivationSession {
        require(expectedPid > 0) {
            "Sandbox PID должен быть положительным."
        }
        require(app.packageName == profile.packageName) {
            "Sandbox-профиль относится к другому package."
        }
        require(app.versionCode == profile.versionCode) {
            "Версия приложения изменилась: профиль нужно пересобрать."
        }
        checkCancelled(cancellation)

        val verifiedIndex =
            FastArtifactIndexer.index(
                files = app.apkFiles,
                cancellation = cancellation,
                progress = ProgressSink { },
                cache =
                    EngineResultCache(
                        File(
                            context.filesDir,
                            "analysis-cache",
                        ),
                    ),
            )
        require(
            verifiedIndex.index.artifactSha256.equals(
                profile.artifactSha256,
                ignoreCase = true,
            ),
        ) {
            "SHA установленного APK не совпадает с sandbox-профилем."
        }

        val moduleRoot =
            File(
                context.cacheDir,
                "sandbox-runtime-modules/" +
                    profile.artifactSha256.take(24),
            ).apply {
                mkdirs()
            }
        val moduleFiles =
            profile.modifications
                .map {
                    it.moduleName to it.abi
                }
                .distinct()
                .associateWith {
                    key ->
                    materializeModule(
                        app = app,
                        moduleName = key.first,
                        abi = key.second,
                        outputRoot = moduleRoot,
                        cancellation = cancellation,
                    )
                }

        var capture =
            RootRuntimeCaptureCoordinator.captureMaps(
                packageName = profile.packageName,
                cancellation = cancellation,
                runner = runner,
                expectedPid = expectedPid,
            )
        require(capture.pid == expectedPid) {
            "Sandbox PID изменился до активации."
        }
        requireProcessNotStopped(
            pid = expectedPid,
            runner = runner,
            cancellation = cancellation,
        )

        stopProcess(
            pid = expectedPid,
            runner = runner,
            cancellation = cancellation,
        )
        val applied =
            mutableListOf<SandboxRuntimePatchRecord>()
        try {
            capture =
                RootRuntimeCaptureCoordinator.captureMaps(
                    packageName = profile.packageName,
                    cancellation = cancellation,
                    runner = runner,
                    expectedPid = expectedPid,
                )
            val regions =
                ProcMapsParser.parse(
                    capture.capture.text,
                )
            require(regions.isNotEmpty()) {
                "У sandbox-процесса пустой /proc maps."
            }

            val reader =
                RootProcMemRuntimeMemoryReader(
                    pid = expectedPid,
                    runner = runner,
                )
            val plans =
                profile.modifications.map {
                    modification ->
                    val moduleFile =
                        moduleFiles[
                            modification.moduleName to
                                modification.abi
                        ] ?: error(
                            "Не материализован модуль " +
                                modification.moduleName,
                        )
                    buildPlan(
                        modification = modification,
                        moduleFile = moduleFile,
                        regions = regions,
                        cancellation = cancellation,
                    )
                }

            plans.forEach {
                plan ->
                checkCancelled(cancellation)
                val current =
                    reader.read(
                        address = plan.runtimeAddress,
                        size = plan.replacementBytes.size,
                        cancellation = cancellation,
                    ) ?: error(
                        "Не удалось прочитать runtime-байты " +
                            plan.title,
                    )

                when {
                    current.contentEquals(
                        plan.replacementBytes,
                    ) -> {
                        applied +=
                            SandboxRuntimePatchRecord(
                                plan = plan,
                                appliedBySession = false,
                                alreadyActive = true,
                            )
                    }

                    current.contentEquals(
                        plan.originalBytes,
                    ) -> {
                        writeExecutableBytes(
                            pid = expectedPid,
                            address = plan.runtimeAddress,
                            bytes = plan.replacementBytes,
                            runner = runner,
                            cancellation = cancellation,
                        )
                        val after =
                            reader.read(
                                address = plan.runtimeAddress,
                                size = plan.replacementBytes.size,
                                cancellation = cancellation,
                            ) ?: error(
                                "Патч записан, но read-back недоступен: " +
                                    plan.title,
                            )
                        if (
                            !after.contentEquals(
                                plan.replacementBytes,
                            )
                        ) {
                            runCatching {
                                writeExecutableBytes(
                                    pid = expectedPid,
                                    address = plan.runtimeAddress,
                                    bytes = plan.originalBytes,
                                    runner = runner,
                                    cancellation =
                                        NeverCancelled,
                                )
                            }
                            error(
                                "Read-back не совпал с replacement bytes: " +
                                    plan.title,
                            )
                        }
                        applied +=
                            SandboxRuntimePatchRecord(
                                plan = plan,
                                appliedBySession = true,
                                alreadyActive = false,
                            )
                    }

                    else ->
                        error(
                            "Runtime-байты цели не совпадают ни с исходным APK, " +
                                "ни с выбранным патчем: " +
                                plan.title +
                                ". Возможна другая модификация или self-modifying code.",
                        )
                }
            }

            return SandboxRuntimeActivationSession(
                packageName = profile.packageName,
                pid = expectedPid,
                artifactSha256 =
                    verifiedIndex.index
                        .artifactSha256,
                records = applied.toList(),
            )
        } catch (
            failure: Throwable,
        ) {
            val rollbackErrors =
                rollbackAppliedRecords(
                    pid = expectedPid,
                    records = applied,
                    runner = runner,
                )
            if (rollbackErrors.isNotEmpty()) {
                throw IllegalStateException(
                    (
                        failure.message
                            ?: failure.javaClass.simpleName
                        ) +
                        " · rollback не подтверждён: " +
                        rollbackErrors.joinToString(),
                    failure,
                )
            }
            throw failure
        } finally {
            resumeProcess(
                pid = expectedPid,
                runner = runner,
            )
        }
    }

    fun rollbackSession(
        session: SandboxRuntimeActivationSession,
        cancellation: CancellationSignal,
        runner: RootCommandRunner = AndroidRootCommandRunner(),
    ) {
        val capture =
            RootRuntimeCaptureCoordinator.captureMaps(
                packageName = session.packageName,
                cancellation = cancellation,
                runner = runner,
                expectedPid = session.pid,
            )
        require(
            capture.pid ==
                session.pid
        ) {
            "Sandbox PID изменился; rollback отменён."
        }
        requireProcessNotStopped(
            pid = session.pid,
            runner = runner,
            cancellation = cancellation,
        )
        stopProcess(
            pid = session.pid,
            runner = runner,
            cancellation = cancellation,
        )
        try {
            val errors =
                rollbackAppliedRecords(
                    pid = session.pid,
                    records = session.records,
                    runner = runner,
                )
            require(errors.isEmpty()) {
                "Rollback не подтверждён: " +
                    errors.joinToString()
            }
        } finally {
            resumeProcess(
                pid = session.pid,
                runner = runner,
            )
        }
    }

    internal fun buildPlanFromSegments(
        modification: SandboxModification,
        loadSegments: List<ElfLoadSegment>,
        regions: List<ProcMapRegion>,
        originalBytes: ByteArray,
        replacementBytes: ByteArray,
    ): SandboxRuntimePatchPlan {
        require(
            replacementBytes.isNotEmpty() &&
                replacementBytes.size <=
                MAX_PATCH_BYTES
        ) {
            "Размер sandbox code patch вне допустимого диапазона."
        }
        require(
            originalBytes.size ==
                replacementBytes.size
        ) {
            "Original/replacement patch sizes differ."
        }

        val size =
            replacementBytes.size.toLong()
        val segment =
            loadSegments.singleOrNull {
                candidate ->
                candidate.executable &&
                    modification.fileOffset >=
                    candidate.fileOffset &&
                    modification.fileOffset <=
                    candidate.fileOffset +
                        candidate.fileSize &&
                    size <=
                    candidate.fileOffset +
                        candidate.fileSize -
                        modification.fileOffset
            } ?: error(
                "File offset не разрешается однозначно в executable PT_LOAD: " +
                    modification.title,
            )
        val delta =
            modification.fileOffset -
                segment.fileOffset
        val binaryVirtualAddress =
            Math.addExact(
                segment.virtualAddress,
                delta,
            )

        val mapping =
            RuntimeModuleMappingResolver.resolve(
                moduleName =
                    modification.moduleName,
                loadSegments = loadSegments,
                regions = regions,
            )
        require(mapping.confirmed) {
            mapping.blockers.firstOrNull()
                ?: (
                    "Не подтверждён runtime mapping " +
                        modification.moduleName
                    )
        }
        val loadBias =
            requireNotNull(
                mapping.loadBias,
            ) {
                "У подтверждённого mapping отсутствует load bias."
            }
        val runtimeAddress =
            Math.addExact(
                loadBias,
                binaryVirtualAddress,
            )
        val runtimeEnd =
            Math.addExact(
                runtimeAddress,
                size,
            )
        val exactExecutableRegion =
            ProcMapsParser.matchingModule(
                regions,
                modification.moduleName,
            ).singleOrNull {
                region ->
                region.executable &&
                    runtimeAddress >=
                    region.start &&
                    runtimeEnd <=
                    region.endExclusive
            }
        requireNotNull(
            exactExecutableRegion,
        ) {
            "Вычисленный runtime address не попал в единственный executable mapping."
        }

        return SandboxRuntimePatchPlan(
            modificationId = modification.id,
            title = modification.title,
            moduleName = modification.moduleName,
            binaryVirtualAddress =
                binaryVirtualAddress,
            runtimeAddress = runtimeAddress,
            originalBytes =
                originalBytes,
            replacementBytes =
                replacementBytes,
        )
    }

    private fun buildPlan(
        modification: SandboxModification,
        moduleFile: File,
        regions: List<ProcMapRegion>,
        cancellation: CancellationSignal,
    ): SandboxRuntimePatchPlan {
        val replacement =
            decodeHex(
                modification.replacementHex,
            )
        val original =
            readStaticBytes(
                file = moduleFile,
                offset =
                    modification.fileOffset,
                size =
                    replacement.size,
            )
        ElfImage.open(
            file = moduleFile,
            cancellation = cancellation,
        ).use {
            elf ->
            return buildPlanFromSegments(
                modification = modification,
                loadSegments =
                    elf.loadSegments,
                regions = regions,
                originalBytes = original,
                replacementBytes =
                    replacement,
            )
        }
    }

    private fun materializeModule(
        app: InstalledAppTarget,
        moduleName: String,
        abi: String?,
        outputRoot: File,
        cancellation: CancellationSignal,
    ): File {
        val candidates =
            mutableListOf<
                Triple<File, String, Long>
            >()
        app.apkFiles.forEach {
            apk ->
            checkCancelled(cancellation)
            ZipFile(apk).use {
                zip ->
                val entries =
                    zip.entries()
                while (
                    entries.hasMoreElements()
                ) {
                    checkCancelled(
                        cancellation,
                    )
                    val entry =
                        entries.nextElement()
                    if (
                        entry.isDirectory ||
                        entry.name
                            .substringAfterLast(
                                '/',
                            ) !=
                        moduleName
                    ) {
                        continue
                    }
                    if (
                        abi != null &&
                        !entry.name.contains(
                            "/" + abi + "/",
                        )
                    ) {
                        continue
                    }
                    val size =
                        entry.size
                    require(
                        size in
                            1..MAX_MODULE_BYTES
                    ) {
                        "Размер runtime-модуля вне безопасного лимита: " +
                            moduleName
                    }
                    candidates +=
                        Triple(
                            apk,
                            entry.name,
                            size,
                        )
                }
            }
        }
        require(
            candidates.size == 1
        ) {
            if (
                candidates.isEmpty()
            ) {
                "Не найден ELF-модуль " +
                    moduleName +
                    (
                        abi
                            ?.let {
                                " (" + it + ")"
                            }
                            ?: ""
                        )
            } else {
                "ELF-модуль " +
                    moduleName +
                    " найден неоднозначно в APK-set."
            }
        }

        val candidate =
            candidates.single()
        val safeName =
            (
                (abi ?: "abi") +
                    "-" +
                    moduleName
                )
                .replace(
                    Regex(
                        "[^A-Za-z0-9._-]",
                    ),
                    "_",
                )
        val output =
            File(
                outputRoot,
                safeName,
            )
        val temp =
            File(
                outputRoot,
                safeName + ".tmp",
            )
        temp.delete()

        ZipFile(candidate.first).use {
            zip ->
            val entry =
                requireNotNull(
                    zip.getEntry(
                        candidate.second,
                    ),
                ) {
                    "APK entry исчез между проверкой и чтением."
                }
            BufferedInputStream(
                zip.getInputStream(
                    entry,
                ),
                MODULE_BUFFER_BYTES,
            ).use {
                input ->
                BufferedOutputStream(
                    FileOutputStream(
                        temp,
                    ),
                    MODULE_BUFFER_BYTES,
                ).use {
                    out ->
                    val buffer =
                        ByteArray(
                            MODULE_BUFFER_BYTES,
                        )
                    var total =
                        0L
                    while (true) {
                        checkCancelled(
                            cancellation,
                        )
                        val read =
                            input.read(
                                buffer,
                            )
                        if (
                            read < 0
                        ) {
                            break
                        }
                        if (
                            read == 0
                        ) {
                            continue
                        }
                        total =
                            Math.addExact(
                                total,
                                read.toLong(),
                            )
                        require(
                            total <=
                                candidate.third
                        ) {
                            "Runtime module expanded beyond declared ZIP size."
                        }
                        out.write(
                            buffer,
                            0,
                            read,
                        )
                    }
                    require(
                        total ==
                            candidate.third
                    ) {
                        "Runtime module was extracted incompletely."
                    }
                }
            }
        }

        if (
            output.exists()
        ) {
            output.delete()
        }
        check(
            temp.renameTo(
                output,
            ),
        ) {
            temp.delete()
            "Не удалось завершить materialization runtime-модуля."
        }
        return output
    }

    private fun readStaticBytes(
        file: File,
        offset: Long,
        size: Int,
    ): ByteArray {
        require(
            offset >= 0L &&
                size in
                1..MAX_PATCH_BYTES
        ) {
            "Некорректный static patch range."
        }
        val end =
            Math.addExact(
                offset,
                size.toLong(),
            )
        require(
            end <=
                file.length()
        ) {
            "Static patch range выходит за ELF-файл."
        }
        return RandomAccessFile(
            file,
            "r",
        ).use {
            raf ->
            val bytes =
                ByteArray(
                    size,
                )
            raf.seek(
                offset,
            )
            raf.readFully(
                bytes,
            )
            bytes
        }
    }

    private fun rollbackAppliedRecords(
        pid: Int,
        records: List<SandboxRuntimePatchRecord>,
        runner: RootCommandRunner,
    ): List<String> {
        if (
            records.none {
                it.appliedBySession
            }
        ) {
            return emptyList()
        }
        val errors =
            mutableListOf<String>()
        val reader =
            RootProcMemRuntimeMemoryReader(
                pid = pid,
                runner = runner,
            )
        records
            .asReversed()
            .filter {
                it.appliedBySession
            }
            .forEach {
                record ->
                val current =
                    runCatching {
                        reader.read(
                            address =
                                record.plan
                                    .runtimeAddress,
                            size =
                                record.plan
                                    .replacementBytes
                                    .size,
                            cancellation =
                                NeverCancelled,
                        )
                    }.getOrNull()
                if (
                    current == null
                ) {
                    errors +=
                        record.plan.title +
                            ": read-back unavailable"
                    return@forEach
                }
                if (
                    current.contentEquals(
                        record.plan
                            .originalBytes,
                    )
                ) {
                    return@forEach
                }
                if (
                    !current.contentEquals(
                        record.plan
                            .replacementBytes,
                    )
                ) {
                    errors +=
                        record.plan.title +
                            ": runtime bytes changed after apply"
                    return@forEach
                }
                val written =
                    runCatching {
                        writeExecutableBytes(
                            pid = pid,
                            address =
                                record.plan
                                    .runtimeAddress,
                            bytes =
                                record.plan
                                    .originalBytes,
                            runner = runner,
                            cancellation =
                                NeverCancelled,
                        )
                        true
                    }.getOrElse {
                        false
                    }
                if (
                    !written
                ) {
                    errors +=
                        record.plan.title +
                            ": rollback write failed"
                    return@forEach
                }
                val after =
                    runCatching {
                        reader.read(
                            address =
                                record.plan
                                    .runtimeAddress,
                            size =
                                record.plan
                                    .originalBytes
                                    .size,
                            cancellation =
                                NeverCancelled,
                        )
                    }.getOrNull()
                if (
                    after == null ||
                    !after.contentEquals(
                        record.plan
                            .originalBytes,
                    )
                ) {
                    errors +=
                        record.plan.title +
                            ": rollback read-back mismatch"
                }
            }
        return errors
    }

    private fun writeExecutableBytes(
        pid: Int,
        address: Long,
        bytes: ByteArray,
        runner: RootCommandRunner,
        cancellation: CancellationSignal,
    ) {
        require(
            pid > 0 &&
                address >= 0L &&
                bytes.size in
                1..MAX_PATCH_BYTES
        ) {
            "Некорректная runtime code write."
        }
        checkCancelled(
            cancellation,
        )
        val escaped =
            bytes.joinToString(
                separator = "",
            ) {
                byte ->
                "\\" +
                    (byte.toInt() and 0xff)
                        .toString(8)
                        .padStart(
                            3,
                            '0',
                        )
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
            runner.run(
                command = command,
                maxOutputBytes = 512,
                cancellation =
                    cancellation,
            )
        require(
            result.exitCode == 0 &&
                !result.truncated
        ) {
            "Root kernel write в executable mapping не выполнена."
        }
    }

    private fun requireProcessNotStopped(
        pid: Int,
        runner: RootCommandRunner,
        cancellation: CancellationSignal,
    ) {
        val state =
            processState(
                pid = pid,
                runner = runner,
                cancellation =
                    cancellation,
            )
        require(
            state != 'T' &&
                state != 't'
        ) {
            "Процесс уже остановлен другим debugger/job-control; ModKit не будет менять его состояние."
        }
    }

    private fun stopProcess(
        pid: Int,
        runner: RootCommandRunner,
        cancellation: CancellationSignal,
    ) {
        val stop =
            runner.run(
                command =
                    "kill -STOP " +
                        pid,
                maxOutputBytes = 512,
                cancellation =
                    cancellation,
            )
        require(
            stop.exitCode == 0
        ) {
            "Не удалось безопасно остановить sandbox-процесс перед code patch."
        }

        repeat(20) {
            checkCancelled(
                cancellation,
            )
            val state =
                processState(
                    pid = pid,
                    runner = runner,
                    cancellation =
                        cancellation,
                )
            if (
                state == 'T' ||
                state == 't'
            ) {
                return
            }
            Thread.sleep(
                25L,
            )
        }
        resumeProcess(
            pid = pid,
            runner = runner,
        )
        error(
            "Sandbox-процесс не подтвердил stopped state; patch отменён.",
        )
    }

    private fun resumeProcess(
        pid: Int,
        runner: RootCommandRunner,
    ) {
        runCatching {
            runner.run(
                command =
                    "kill -CONT " +
                        pid,
                maxOutputBytes = 512,
                cancellation =
                    NeverCancelled,
            )
        }
    }

    private fun processState(
        pid: Int,
        runner: RootCommandRunner,
        cancellation: CancellationSignal,
    ): Char? {
        val status =
            runner.run(
                command =
                    "cat /proc/" +
                        pid +
                        "/status",
                maxOutputBytes =
                    64 * 1024,
                cancellation =
                    cancellation,
            )
        require(
            status.exitCode == 0 &&
                !status.truncated
        ) {
            "Не удалось перечитать состояние sandbox-процесса."
        }
        return status.output
            .toString(
                Charsets.UTF_8,
            )
            .lineSequence()
            .firstOrNull {
                it.startsWith(
                    "State:",
                )
            }
            ?.substringAfter(
                "State:",
            )
            ?.trim()
            ?.firstOrNull()
    }

    private fun decodeHex(
        hex: String,
    ): ByteArray {
        val normalized =
            hex.trim()
        require(
            normalized.length % 2 ==
                0 &&
                normalized.length in
                2..MAX_PATCH_BYTES * 2 &&
                normalized.matches(
                    Regex(
                        "[0-9a-fA-F]+",
                    ),
                )
        ) {
            "Некорректные replacement bytes."
        }
        return ByteArray(
            normalized.length / 2,
        ) {
            index ->
            normalized
                .substring(
                    index * 2,
                    index * 2 + 2,
                )
                .toInt(
                    16,
                )
                .toByte()
        }
    }

    private fun checkCancelled(
        cancellation: CancellationSignal,
    ) {
        if (
            cancellation.isCancelled()
        ) {
            throw AnalysisCancelledException()
        }
    }

    private object NeverCancelled :
        CancellationSignal {
        override fun isCancelled(): Boolean =
            false
    }
}
