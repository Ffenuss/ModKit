package io.github.ffenuss.modkit.sandbox

import org.json.JSONObject

data class SandboxModification(
    val id: String,
    val title: String,
    val category: String,
    val moduleName: String,
    val fileOffset: Long,
    val replacementHex: String,
    val abi: String?,
    val runtimeId: String?,
)

data class SandboxProfile(
    val schema: String,
    val createdAtEpochMs: Long,
    val packageName: String,
    val label: String,
    val versionName: String?,
    val versionCode: Long,
    val artifactSha256: String,
    val modifications: List<SandboxModification>,
)

object SandboxProfileParser {
    const val SCHEMA = "modkit/root-mod-profile/1"

    fun parse(text: String): SandboxProfile {
        val json = JSONObject(text)
        val schema = json.getString("schema")
        require(schema == SCHEMA) {
            "Неподдерживаемая схема sandbox-профиля: " + schema
        }
        val packageName = json.getString("packageName").trim()
        require(packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))) {
            "Некорректный package name в sandbox-профиле."
        }
        val artifactSha256 = json.getString("artifactSha256").lowercase()
        require(artifactSha256.matches(Regex("[0-9a-f]{64}"))) {
            "Некорректный SHA-256 в sandbox-профиле."
        }
        val array = json.getJSONArray("modifications")
        require(array.length() in 1..256) {
            "Sandbox-профиль должен содержать от 1 до 256 модификаций."
        }
        val modifications = buildList {
            repeat(array.length()) { index ->
                val item = array.getJSONObject(index)
                val category = item.getString("category")
                require(category != "SENSITIVE_SURFACE") {
                    "Billing/auth/anti-cheat цели нельзя активировать через sandbox-профиль."
                }
                val replacementHex = item.getString("replacementHex").trim()
                require(
                    replacementHex.length in 2..128 &&
                        replacementHex.length % 2 == 0 &&
                        replacementHex.matches(Regex("[0-9a-fA-F]+"))
                ) {
                    "Некорректный replacementHex у модификации."
                }
                val moduleName = item.getString("moduleName").trim()
                require(moduleName.isNotEmpty() && '/' !in moduleName && '\\' !in moduleName) {
                    "Некорректное имя runtime-модуля."
                }
                val fileOffset = item.getLong("fileOffset")
                require(fileOffset >= 0L) {
                    "Некорректный file offset у модификации."
                }
                add(
                    SandboxModification(
                        id = item.getString("id"),
                        title = item.optString("title").ifBlank { item.getString("id") },
                        category = category,
                        moduleName = moduleName,
                        fileOffset = fileOffset,
                        replacementHex = replacementHex.lowercase(),
                        abi = item.optString("abi").takeIf { it.isNotBlank() && it != "null" },
                        runtimeId = item.optString("runtimeId").takeIf { it.isNotBlank() && it != "null" },
                    ),
                )
            }
        }
        require(modifications.map { it.id }.distinct().size == modifications.size) {
            "Sandbox-профиль содержит повторяющиеся id модификаций."
        }

        return SandboxProfile(
            schema = schema,
            createdAtEpochMs = json.optLong("createdAtEpochMs", 0L),
            packageName = packageName,
            label = json.optString("label").ifBlank { packageName },
            versionName = json.optString("versionName").takeIf { it.isNotBlank() && it != "null" },
            versionCode = json.getLong("versionCode"),
            artifactSha256 = artifactSha256,
            modifications = modifications,
        )
    }
}
