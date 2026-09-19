package io.github.ffenuss.modkit.runtime

import android.app.ActivityManager
import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisCancelledException
import io.github.ffenuss.modkit.analysis.CancellationSignal
import java.io.File
import java.io.FileInputStream

enum class NonRootProcessDiscoverySource {
    ACTIVITY_MANAGER,
    PROC_CMDLINE,
}

data class NonRootProcessHint(
    val pid: Int,
    val processName: String,
)

data class NonRootProcessCandidate(
    val pid: Int,
    val processName: String?,
    val sources: Set<NonRootProcessDiscoverySource>,
    val commandLine: String?,
    val identityConfirmed: Boolean,
    val exactMainProcess: Boolean,
    val mapsReadable: Boolean,
    val blockers: List<String>,
)

data class NonRootProcessDiscoveryResult(
    val packageName: String,
    val candidates: List<NonRootProcessCandidate>,
    val selectedPid: Int?,
    val blockers: List<String>,
) {
    val readyForMapsCapture: Boolean
        get() = selectedPid != null && blockers.isEmpty()
}

interface NonRootProcessProbe {
    fun activityManagerProcesses(): List<NonRootProcessHint>
    fun procPids(): List<Int>
    fun readCmdline(pid: Int): String?
    fun mapsReadable(pid: Int): Boolean
}

class AndroidNonRootProcessProbe(
    context: Context,
    private val procRoot: File = File("/proc"),
) : NonRootProcessProbe {
    private val activityManager =
        context.getSystemService(ActivityManager::class.java)

    override fun activityManagerProcesses(): List<NonRootProcessHint> =
        activityManager
            ?.runningAppProcesses
            .orEmpty()
            .mapNotNull { process ->
                val pid = process.pid
                val name = process.processName
                if (pid > 0 && !name.isNullOrBlank()) {
                    NonRootProcessHint(pid, name)
                } else {
                    null
                }
            }

    override fun procPids(): List<Int> =
        procRoot.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isDirectory }
            .mapNotNull { it.name.toIntOrNull() }
            .filter { it > 0 }
            .sorted()
            .take(MAX_PROC_PID_SCAN)
            .toList()

    override fun readCmdline(pid: Int): String? {
        if (pid <= 0) return null
        val file = File(procRoot, "$pid/cmdline")
        if (!file.isFile || !file.canRead()) return null
        return runCatching {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(MAX_CMDLINE_BYTES)
                val read = input.read(buffer)
                if (read <= 0) return@use null
                val end = buffer
                    .take(read)
                    .indexOf(0)
                    .let { if (it >= 0) it else read }
                buffer.copyOfRange(0, end)
                    .toString(Charsets.UTF_8)
                    .trim()
                    .takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    override fun mapsReadable(pid: Int): Boolean {
        if (pid <= 0) return false
        val file = File(procRoot, "$pid/maps")
        return file.isFile && file.canRead()
    }

    companion object {
        private const val MAX_PROC_PID_SCAN = 4096
        private const val MAX_CMDLINE_BYTES = 4096
    }
}

/**
 * Non-root process discovery is intentionally conservative.
 *
 * ActivityManager is only a hint. Exact process identity requires a readable
 * /proc/<pid>/cmdline matching the requested package. Automatic maps capture
 * is allowed only for one unambiguous exact main process.
 */
object NonRootProcessDiscovery {
    fun discover(
        context: Context,
        packageName: String,
        cancellation: CancellationSignal,
    ): NonRootProcessDiscoveryResult =
        discover(
            packageName = packageName,
            probe = AndroidNonRootProcessProbe(context),
            cancellation = cancellation,
        )

    fun discover(
        packageName: String,
        probe: NonRootProcessProbe,
        cancellation: CancellationSignal,
    ): NonRootProcessDiscoveryResult {
        require(packageName.isNotBlank()) {
            "Package name is required for non-root process discovery."
        }
        checkCancelled(cancellation)

        val activityRows = probe.activityManagerProcesses()
            .filter { row ->
                isPackageProcess(row.processName, packageName)
            }
            .associateBy { it.pid }

        val cmdlineByPid = linkedMapOf<Int, String?>()
        val procMatchedPids = linkedSetOf<Int>()
        probe.procPids().forEach { pid ->
            checkCancelled(cancellation)
            val cmdline = probe.readCmdline(pid)
            cmdlineByPid[pid] = cmdline
            if (cmdline != null && isPackageProcess(cmdline, packageName)) {
                procMatchedPids += pid
            }
        }

        val candidatePids = (activityRows.keys + procMatchedPids)
            .distinct()
            .sorted()

        val candidates = candidatePids.map { pid ->
            checkCancelled(cancellation)
            val activityName = activityRows[pid]?.processName
            val cmdline = if (cmdlineByPid.containsKey(pid)) {
                cmdlineByPid[pid]
            } else {
                probe.readCmdline(pid)
            }
            val identityConfirmed =
                cmdline != null && isPackageProcess(cmdline, packageName)
            val exactMain = cmdline == packageName
            val mapsReadable = probe.mapsReadable(pid)
            val blockers = buildList {
                if (!identityConfirmed) {
                    add(
                        "Process identity is not confirmed by /proc/$pid/cmdline.",
                    )
                }
                if (!mapsReadable) {
                    add(
                        "Process maps are not readable without elevated privileges.",
                    )
                }
            }
            NonRootProcessCandidate(
                pid = pid,
                processName = cmdline ?: activityName,
                sources = buildSet {
                    if (pid in activityRows) {
                        add(NonRootProcessDiscoverySource.ACTIVITY_MANAGER)
                    }
                    if (pid in procMatchedPids) {
                        add(NonRootProcessDiscoverySource.PROC_CMDLINE)
                    }
                },
                commandLine = cmdline,
                identityConfirmed = identityConfirmed,
                exactMainProcess = exactMain,
                mapsReadable = mapsReadable,
                blockers = blockers,
            )
        }

        val exact = candidates.filter {
            it.identityConfirmed &&
                it.exactMainProcess &&
                it.mapsReadable
        }
        val blockers = when {
            exact.size == 1 -> emptyList()
            exact.isEmpty() -> listOf(
                "No unambiguous readable main process was confirmed for $packageName without root.",
            )
            else -> listOf(
                "Multiple readable main-process candidates were confirmed; automatic PID selection is blocked.",
            )
        }

        return NonRootProcessDiscoveryResult(
            packageName = packageName,
            candidates = candidates,
            selectedPid = exact.singleOrNull()?.pid,
            blockers = blockers,
        )
    }

    fun verifyIdentity(
        packageName: String,
        pid: Int,
        probe: NonRootProcessProbe,
    ): Boolean =
        pid > 0 &&
            probe.readCmdline(pid) == packageName

    private fun isPackageProcess(
        processName: String,
        packageName: String,
    ): Boolean =
        processName == packageName ||
            processName.startsWith("$packageName:")

    private fun checkCancelled(cancellation: CancellationSignal) {
        if (cancellation.isCancelled()) {
            throw AnalysisCancelledException()
        }
    }
}
