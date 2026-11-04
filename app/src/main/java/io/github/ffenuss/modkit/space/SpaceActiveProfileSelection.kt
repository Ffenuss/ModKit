package io.github.ffenuss.modkit.space

import android.content.Context
import io.github.ffenuss.modkit.ui.AutoModBuildRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Exact per-game build identity. A newer build must be selected explicitly. */
data class SpaceActiveProfileKey(
    val packageName: String,
    val artifactSha256: String,
) {
    companion object {
        fun of(record: AutoModBuildRecord) =
            SpaceActiveProfileKey(record.plan.packageName, record.plan.artifactSha256.lowercase())
    }
}

/**
 * No implicit fallback to another game or to a newer unsigned/uninstalled APK.
 * In particular, an unavailable previous selection must not select the first
 * READY row in the UI.
 */
object SpaceActiveProfileSelection {
    fun resolve(
        selected: SpaceActiveProfileKey?,
        profiles: List<Pair<AutoModBuildRecord, SpaceLaunchStatus>>,
    ): AutoModBuildRecord? {
        if (selected == null) return null
        return profiles.singleOrNull { (record, status) ->
            status == SpaceLaunchStatus.READY &&
                record.plan.packageName == selected.packageName &&
                record.plan.artifactSha256.equals(selected.artifactSha256, ignoreCase = true) &&
                SpaceBuildProfileIndex.select(listOf(record)).size == 1
        }?.first
    }
}

/**
 * Stores selection only. This is NOT a running guest, virtual app container or
 * live overlay. A future Space handoff must independently authenticate the
 * selected package, artifact SHA, signer, and APK bytes on every use.
 */
object SpaceActiveProfileStore {
    private const val PREFERENCES = "space-active-profile-v1"
    private const val KEY_PACKAGE = "package"
    private const val KEY_ARTIFACT = "artifact"
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val SHA = Regex("[0-9a-fA-F]{64}")

    fun read(context: Context): SpaceActiveProfileKey? {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val pkg = prefs.getString(KEY_PACKAGE, null) ?: return null
        val sha = prefs.getString(KEY_ARTIFACT, null) ?: return null
        if (!PACKAGE.matches(pkg) || !SHA.matches(sha)) return null
        return SpaceActiveProfileKey(pkg, sha.lowercase())
    }

    /**
     * Verifies again immediately before storing. A stale screen, changed APK,
     * or another install cannot silently become the selected profile.
     */
    suspend fun activate(context: Context, record: AutoModBuildRecord): SpaceActiveProfileKey {
        val appContext = context.applicationContext
        return withContext(Dispatchers.IO) {
            require(SpaceBuildProfileIndex.select(listOf(record)).size == 1) {
                "Профиль сборки повреждён или не готов к запуску."
            }
            check(SpaceInstalledPackageLauncher.status(appContext, record) == SpaceLaunchStatus.READY) {
                "Установленная сборка больше не совпадает с проверенным профилем."
            }
            val key = SpaceActiveProfileKey.of(record)
            check(appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(KEY_PACKAGE, key.packageName)
                .putString(KEY_ARTIFACT, key.artifactSha256).commit()) {
                "Не удалось сохранить выбор активной игры."
            }
            SpaceLegacyMenuBridge.announce(appContext)
            key
        }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().clear().apply()
        SpaceLegacyMenuBridge.announce(context)
    }
}
