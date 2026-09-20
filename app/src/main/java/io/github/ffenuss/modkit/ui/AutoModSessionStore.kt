package io.github.ffenuss.modkit.ui

import android.content.Context
import io.github.ffenuss.modkit.analysis.AnalysisTargetDescriptor
import java.io.File
import org.json.JSONObject

data class AutoModSessionSnapshot(
    val target: AnalysisTargetDescriptor,
    val artifactSha256: String,
)

class AutoModSessionStore(
    context: Context,
) {
    private val file =
        File(
            context.filesDir,
            "automod-session.json",
        )
    private val lock = Any()

    fun load(): AutoModSessionSnapshot? =
        synchronized(lock) {
            if (!file.isFile) {
                return@synchronized null
            }
            runCatching {
                val obj =
                    JSONObject(
                        file.readText(
                            Charsets.UTF_8,
                        ),
                    )
                val sha =
                    obj.getString("artifactSha256")
                        .lowercase()
                require(
                    sha.matches(
                        Regex(
                            "[0-9a-f]{64}",
                        ),
                    ),
                )
                val target =
                    when (
                        obj.getString(
                            "targetType",
                        )
                    ) {
                        "package" ->
                            AnalysisTargetDescriptor
                                .InstalledPackage(
                                    packageName =
                                        obj.getString(
                                            "targetValue",
                                        ),
                                    label =
                                        obj.getString(
                                            "targetLabel",
                                        ),
                                )
                        "uri" ->
                            AnalysisTargetDescriptor
                                .FileUri(
                                    uri =
                                        obj.getString(
                                            "targetValue",
                                        ),
                                    label =
                                        obj.getString(
                                            "targetLabel",
                                        ),
                                )
                        else ->
                            error(
                                "Unknown AutoMod target type",
                            )
                    }
                AutoModSessionSnapshot(
                    target = target,
                    artifactSha256 = sha,
                )
            }.getOrNull()
        }

    fun save(
        target: AnalysisTargetDescriptor,
        artifactSha256: String,
    ) = synchronized(lock) {
        require(
            artifactSha256.matches(
                Regex(
                    "[0-9a-fA-F]{64}",
                ),
            ),
        )

        val obj =
            JSONObject()
                .put(
                    "artifactSha256",
                    artifactSha256.lowercase(),
                )
                .put(
                    "targetLabel",
                    target.label,
                )

        when (target) {
            is AnalysisTargetDescriptor
                .InstalledPackage -> {
                obj.put(
                    "targetType",
                    "package",
                )
                obj.put(
                    "targetValue",
                    target.packageName,
                )
            }
            is AnalysisTargetDescriptor
                .FileUri -> {
                obj.put(
                    "targetType",
                    "uri",
                )
                obj.put(
                    "targetValue",
                    target.uri,
                )
            }
        }

        val temp =
            File(
                file.parentFile,
                file.name + ".tmp",
            )
        temp.writeText(
            obj.toString(),
            Charsets.UTF_8,
        )
        if (file.exists()) {
            file.delete()
        }
        check(temp.renameTo(file)) {
            temp.delete()
            "Could not persist AutoMod session"
        }
    }

    fun clear() =
        synchronized(lock) {
            file.delete()
            File(
                file.parentFile,
                file.name + ".tmp",
            ).delete()
        }
}
