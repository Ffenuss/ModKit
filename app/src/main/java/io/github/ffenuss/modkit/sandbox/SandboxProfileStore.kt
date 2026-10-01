package io.github.ffenuss.modkit.sandbox

import android.content.Context
import java.io.File
import java.security.MessageDigest

data class StoredSandboxProfile(
    val id: String,
    val profile: SandboxProfile,
    val rawText: String,
)

class SandboxProfileStore(context: Context) {
    private val root = File(context.filesDir, "sandbox/profiles").apply { mkdirs() }

    fun save(rawText: String): StoredSandboxProfile {
        val profile = SandboxProfileParser.parse(rawText)
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(rawText.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val id = profile.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_") +
            "-" + digest.take(16)
        val output = File(root, id + ".modkit.json")
        val temp = File(root, output.name + ".tmp")
        temp.writeText(rawText, Charsets.UTF_8)
        if (output.exists()) output.delete()
        check(temp.renameTo(output)) {
            temp.delete()
            "Не удалось сохранить sandbox-профиль."
        }
        return StoredSandboxProfile(id, profile, rawText)
    }

    fun list(): List<StoredSandboxProfile> =
        root.listFiles().orEmpty().asSequence()
            .filter { it.isFile && it.name.endsWith(".modkit.json") }
            .mapNotNull { file ->
                runCatching {
                    val text = file.readText(Charsets.UTF_8)
                    StoredSandboxProfile(
                        file.name.removeSuffix(".modkit.json"),
                        SandboxProfileParser.parse(text),
                        text,
                    )
                }.getOrNull()
            }
            .sortedByDescending { it.profile.createdAtEpochMs }
            .toList()

    fun delete(id: String): Boolean {
        require(id.matches(Regex("[A-Za-z0-9._-]+"))) {
            "Некорректный id sandbox-профиля."
        }
        return File(root, id + ".modkit.json").delete()
    }
}
