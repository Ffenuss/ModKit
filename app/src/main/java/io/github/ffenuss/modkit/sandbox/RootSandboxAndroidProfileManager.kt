package io.github.ffenuss.modkit.sandbox

import io.github.ffenuss.modkit.analysis.CancellationSignal
import io.github.ffenuss.modkit.runtime.AndroidRootCommandRunner
import io.github.ffenuss.modkit.runtime.RootCommandRunner

data class RootSandboxAndroidProfile(
    val userId: Int,
    val name: String,
    val running: Boolean,
)

data class RootSandboxLaunchResult(
    val userId: Int,
    val packageName: String,
    val launcherComponent: String,
)

object RootSandboxAndroidProfileManager {
    const val PROFILE_NAME = "ModKit Sandbox"

    fun list(
        cancellation: CancellationSignal,
        runner: RootCommandRunner = AndroidRootCommandRunner(),
    ): List<RootSandboxAndroidProfile> {
        val result = runner.run(
            command = "pm list users",
            maxOutputBytes = 128 * 1024,
            cancellation = cancellation,
        )
        require(result.exitCode == 0 && !result.truncated) {
            "Android не вернул список пользователей/профилей."
        }
        return result.output.toString(Charsets.UTF_8)
            .lineSequence()
            .mapNotNull { line ->
                val match = Regex("UserInfo\\{(\\d+):([^:}]+):[^}]*}").find(line)
                    ?: return@mapNotNull null
                val id = match.groupValues[1].toIntOrNull()
                    ?: return@mapNotNull null
                RootSandboxAndroidProfile(
                    userId = id,
                    name = match.groupValues[2],
                    running = line.contains("running", ignoreCase = true),
                )
            }
            .toList()
    }

    fun ensureManagedProfile(
        cancellation: CancellationSignal,
        runner: RootCommandRunner = AndroidRootCommandRunner(),
    ): RootSandboxAndroidProfile {
        val existing = list(cancellation, runner).singleOrNull {
            it.name == PROFILE_NAME
        }
        val userId = existing?.userId ?: run {
            val create = runner.run(
                command = "pm create-user --profileOf 0 --managed 'ModKit Sandbox'",
                maxOutputBytes = 16 * 1024,
                cancellation = cancellation,
            )
            require(create.exitCode == 0 && !create.truncated) {
                "Не удалось создать отдельный Android managed-profile для ModKit Sandbox: " +
                    create.output.toString(Charsets.UTF_8).trim()
            }
            Regex("(?:id|user)\\s+(\\d+)", RegexOption.IGNORE_CASE)
                .find(create.output.toString(Charsets.UTF_8))
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: error("Android создал профиль, но его userId не удалось определить.")
        }
        require(userId > 0) {
            "Sandbox не может использовать основной Android user 0."
        }
        val start = runner.run(
            command = "am start-user -w " + userId,
            maxOutputBytes = 16 * 1024,
            cancellation = cancellation,
        )
        require(start.exitCode == 0) {
            "Не удалось запустить Android sandbox-профиль user " + userId + "."
        }
        return RootSandboxAndroidProfile(userId, PROFILE_NAME, true)
    }

    fun installExistingAndLaunch(
        packageName: String,
        cancellation: CancellationSignal,
        runner: RootCommandRunner = AndroidRootCommandRunner(),
    ): RootSandboxLaunchResult {
        require(packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) {
            "Некорректный package name."
        }
        val profile = ensureManagedProfile(cancellation, runner)
        val install = runner.run(
            command = "cmd package install-existing --user " + profile.userId + " " + packageName,
            maxOutputBytes = 32 * 1024,
            cancellation = cancellation,
        )
        if (install.exitCode != 0) {
            val fallback = runner.run(
                command = "pm install-existing --user " + profile.userId + " " + packageName,
                maxOutputBytes = 32 * 1024,
                cancellation = cancellation,
            )
            require(fallback.exitCode == 0) {
                "Не удалось добавить установленное приложение в отдельный sandbox-профиль."
            }
        }
        val resolve = runner.run(
            command = "cmd package resolve-activity --brief --user " + profile.userId +
                " -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " + packageName,
            maxOutputBytes = 32 * 1024,
            cancellation = cancellation,
        )
        require(resolve.exitCode == 0 && !resolve.truncated) {
            "Не удалось определить launcher activity sandbox-копии."
        }
        val component = resolve.output.toString(Charsets.UTF_8)
            .lineSequence().map { it.trim() }
            .lastOrNull { it.contains('/') && !it.startsWith("priority=") }
            ?: error("Launcher activity sandbox-копии не найдена.")
        val launch = runner.run(
            command = "am start --user " + profile.userId + " -n " + component,
            maxOutputBytes = 32 * 1024,
            cancellation = cancellation,
        )
        require(launch.exitCode == 0) {
            "Android не запустил sandbox-копию приложения."
        }
        return RootSandboxLaunchResult(profile.userId, packageName, component)
    }
}
