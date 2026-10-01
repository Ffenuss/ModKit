package io.github.ffenuss.modkit.runtime

data class AniimoRuntimeProfileResult(
    val packageMatched: Boolean,
    val unityDetected: Boolean,
    val il2CppDetected: Boolean,
    val xLuaDetected: Boolean,
    val eligibleForRuntimeOverlay: Boolean,
    val blockers: List<String>,
)

/**
 * Conservative runtime profile for Aniimo Android.
 *
 * This class only identifies the target and runtime family. It does not
 * disable protection, bypass integrity checks, or hide ModKit from the target.
 */
object AniimoRuntimeProfile {
    const val PACKAGE_NAME = "com.x.aniimos"

    fun inspect(
        packageName: String,
        loadedModules: Collection<String> = emptyList(),
        archiveEntries: Collection<String> = emptyList(),
    ): AniimoRuntimeProfileResult {
        val packageMatched =
            packageName.equals(
                PACKAGE_NAME,
                ignoreCase = true,
            )

        val names =
            (loadedModules.asSequence() +
                archiveEntries.asSequence())
                .map(::baseName)
                .filter { it.isNotEmpty() }
                .map { it.lowercase() }
                .toSet()

        val unityDetected =
            names.any {
                it == "libunity.so" ||
                    it == "unityplayer.so"
            }
        val il2CppDetected =
            names.any {
                it == "libil2cpp.so" ||
                    it == "global-metadata.dat"
            }
        val xLuaDetected =
            names.any {
                "xlua" in it ||
                    it.endsWith("luascripts.xdf")
            }

        val blockers = buildList {
            if (!packageMatched) {
                add("Target package is not Aniimo global Android.")
            }
            if (!unityDetected) {
                add("Unity runtime was not confirmed.")
            }
            if (!il2CppDetected) {
                add("IL2CPP runtime/metadata was not confirmed.")
            }
        }

        return AniimoRuntimeProfileResult(
            packageMatched = packageMatched,
            unityDetected = unityDetected,
            il2CppDetected = il2CppDetected,
            xLuaDetected = xLuaDetected,
            eligibleForRuntimeOverlay =
                packageMatched &&
                    unityDetected &&
                    il2CppDetected,
            blockers = blockers,
        )
    }

    private fun baseName(value: String): String =
        value
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()
}
