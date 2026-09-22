package io.github.ffenuss.modkit.runtime

import java.util.Base64
import org.json.JSONObject

data class ProcessOverlayConfig(
    val packageName: String,
    val pid: Int,
    val label: String,
)

object ProcessOverlayConfigCodec {
    private const val MAX_BYTES =
        16 * 1024

    fun encode(
        config: ProcessOverlayConfig,
    ): String {
        require(config.pid > 0) {
            "Invalid process overlay PID."
        }
        require(
            config.packageName.matches(
                Regex(
                    "[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+",
                ),
            ),
        ) {
            "Invalid process overlay package."
        }
        val raw =
            JSONObject()
                .put(
                    "schema",
                    "modkit/process-overlay/1",
                )
                .put(
                    "packageName",
                    config.packageName,
                )
                .put(
                    "pid",
                    config.pid,
                )
                .put(
                    "label",
                    config.label,
                )
                .toString()
                .toByteArray(
                    Charsets.UTF_8,
                )
        require(
            raw.size <=
                MAX_BYTES,
        ) {
            "Process overlay config is too large."
        }
        return Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(raw)
    }

    fun decode(
        encoded: String,
    ): ProcessOverlayConfig {
        require(
            encoded.length <=
                MAX_BYTES * 2,
        ) {
            "Encoded process overlay config is too large."
        }
        val raw =
            Base64
                .getUrlDecoder()
                .decode(encoded)
        require(
            raw.size <=
                MAX_BYTES,
        ) {
            "Decoded process overlay config is too large."
        }
        val json =
            JSONObject(
                raw.toString(
                    Charsets.UTF_8,
                ),
            )
        require(
            json.getString(
                "schema",
            ) ==
                "modkit/process-overlay/1",
        ) {
            "Unsupported process overlay schema."
        }
        val packageName =
            json.getString(
                "packageName",
            )
        require(
            packageName.matches(
                Regex(
                    "[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+",
                ),
            ),
        ) {
            "Invalid process overlay package."
        }
        val pid =
            json.getInt(
                "pid",
            )
        require(pid > 0) {
            "Invalid process overlay PID."
        }
        return ProcessOverlayConfig(
            packageName =
                packageName,
            pid = pid,
            label =
                json.optString(
                    "label",
                ).ifBlank {
                    packageName
                },
        )
    }
}
