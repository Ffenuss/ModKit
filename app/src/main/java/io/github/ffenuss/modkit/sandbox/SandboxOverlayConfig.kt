package io.github.ffenuss.modkit.sandbox

import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class SandboxOverlayItem(
    val id: String,
    val title: String,
    val runtimeAddress: Long,
    val originalHex: String,
    val replacementHex: String,
    val enabled: Boolean,
) {
    fun toLiveTarget(): SandboxLiveToggleTarget =
        SandboxLiveToggleTarget(
            id = id,
            title = title,
            runtimeAddress = runtimeAddress,
            originalBytes = decodeHex(originalHex),
            replacementBytes = decodeHex(replacementHex),
        )
}

data class SandboxOverlayConfig(
    val packageName: String,
    val pid: Int,
    val artifactSha256: String,
    val items: List<SandboxOverlayItem>,
)

object SandboxOverlayConfigCodec {
    private const val MAX_ITEMS = 128
    private const val MAX_ENCODED_BYTES = 96 * 1024

    fun fromSession(
        session: SandboxRuntimeActivationSession,
    ): SandboxOverlayConfig {
        val items =
            session.records
                .take(MAX_ITEMS)
                .map { record ->
                    SandboxOverlayItem(
                        id = record.plan.modificationId,
                        title = record.plan.title,
                        runtimeAddress = record.plan.runtimeAddress,
                        originalHex = record.plan.originalBytes.toHex(),
                        replacementHex = record.plan.replacementBytes.toHex(),
                        enabled =
                            record.appliedBySession ||
                                record.alreadyActive,
                    )
                }
        require(items.isNotEmpty()) {
            "Overlay requires at least one live modification."
        }
        return SandboxOverlayConfig(
            packageName = session.packageName,
            pid = session.pid,
            artifactSha256 = session.artifactSha256,
            items = items,
        )
    }

    fun encode(
        config: SandboxOverlayConfig,
    ): String {
        val array = JSONArray()
        config.items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("title", item.title)
                    .put("runtimeAddress", item.runtimeAddress)
                    .put("originalHex", item.originalHex)
                    .put("replacementHex", item.replacementHex)
                    .put("enabled", item.enabled),
            )
        }
        val raw =
            JSONObject()
                .put("schema", "modkit/sandbox-overlay/1")
                .put("packageName", config.packageName)
                .put("pid", config.pid)
                .put("artifactSha256", config.artifactSha256)
                .put("items", array)
                .toString()
                .toByteArray(Charsets.UTF_8)
        require(raw.size <= MAX_ENCODED_BYTES) {
            "Overlay configuration is too large."
        }
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw)
    }

    fun decode(
        encoded: String,
    ): SandboxOverlayConfig {
        require(encoded.length <= MAX_ENCODED_BYTES * 2) {
            "Encoded overlay configuration is too large."
        }
        val raw =
            Base64.getUrlDecoder()
                .decode(encoded)
        require(raw.size <= MAX_ENCODED_BYTES) {
            "Decoded overlay configuration is too large."
        }
        val json =
            JSONObject(
                raw.toString(Charsets.UTF_8),
            )
        require(
            json.getString("schema") ==
                "modkit/sandbox-overlay/1",
        ) {
            "Unsupported overlay configuration schema."
        }
        val packageName =
            json.getString("packageName")
        require(
            packageName.matches(
                Regex(
                    "[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+",
                ),
            ),
        ) {
            "Invalid overlay package name."
        }
        val pid =
            json.getInt("pid")
        require(pid > 0) {
            "Invalid overlay PID."
        }
        val artifactSha =
            json.getString("artifactSha256")
                .lowercase()
        require(
            artifactSha.matches(
                Regex("[0-9a-f]{64}"),
            ),
        ) {
            "Invalid overlay artifact SHA."
        }
        val array =
            json.getJSONArray("items")
        require(array.length() in 1..MAX_ITEMS) {
            "Invalid overlay item count."
        }
        val items =
            buildList {
                repeat(array.length()) { index ->
                    val item =
                        array.getJSONObject(index)
                    val original =
                        item.getString("originalHex")
                    val replacement =
                        item.getString("replacementHex")
                    require(
                        original.length ==
                            replacement.length &&
                            original.length in 2..128 &&
                            original.length % 2 == 0 &&
                            original.matches(
                                Regex("[0-9a-fA-F]+"),
                            ) &&
                            replacement.matches(
                                Regex("[0-9a-fA-F]+"),
                            ),
                    ) {
                        "Invalid overlay patch bytes."
                    }
                    add(
                        SandboxOverlayItem(
                            id = item.getString("id"),
                            title =
                                item.optString("title")
                                    .ifBlank {
                                        item.getString("id")
                                    },
                            runtimeAddress =
                                item.getLong(
                                    "runtimeAddress",
                                ),
                            originalHex =
                                original.lowercase(),
                            replacementHex =
                                replacement.lowercase(),
                            enabled =
                                item.optBoolean(
                                    "enabled",
                                    true,
                                ),
                        ),
                    )
                }
            }
        require(
            items.map { it.id }
                .distinct()
                .size ==
                items.size,
        ) {
            "Overlay item ids must be unique."
        }
        return SandboxOverlayConfig(
            packageName = packageName,
            pid = pid,
            artifactSha256 =
                artifactSha,
            items = items,
        )
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") {
        "%02x".format(it.toInt() and 0xff)
    }

private fun decodeHex(
    value: String,
): ByteArray =
    ByteArray(value.length / 2) { index ->
        value.substring(
            index * 2,
            index * 2 + 2,
        ).toInt(16).toByte()
    }
