package io.github.ffenuss.modkit.space

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import io.github.ffenuss.modkit.ui.AutoModBuildRecord
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Compatible with the legacy spacehost MenuProfile schema 1 / backend "none".
 * A verified static build is NEVER converted into a live runtime patch.
 */
internal data class SpaceLegacySource(val sha256: String, val size: Long)
internal data class SpaceLegacyMenu(
    val packageName: String,
    val artifactSha256: String,
    val sources: List<SpaceLegacySource>,
) {
    val fileName: String get() = "$packageName-$artifactSha256.json"

    fun toJson(): ByteArray {
        val values = JSONObject()
            .put("schema", 1)
            .put("backend", "none")
            .put("packageName", packageName)
            .put("label", packageName.take(180))
            .put("genre", "unknown")
            .put("artifactSha256", artifactSha256)
            .put("truncated", false)
            .put("engines", JSONArray())
            .put("items", JSONArray()) // No static-to-live recipe promotion.
            .put("sources", JSONArray(sources.map {
                JSONObject().put("sha256", it.sha256).put("size", it.size)
            }))
        return values.toString().toByteArray(Charsets.UTF_8)
    }
}

internal object SpaceLegacyProfileContract {
    fun from(record: AutoModBuildRecord): SpaceLegacyMenu? {
        if (SpaceBuildProfileIndex.select(listOf(record)).size != 1) return null
        val plan = record.plan
        if (plan.packageName.length > 255 || plan.apks.size !in 1..64) return null
        val sources = plan.apks.map {
            SpaceLegacySource(it.expectedSha256.lowercase(Locale.ROOT), it.size)
        }
        if (sources.map { it.sha256 }.distinct().size != sources.size) return null
        return SpaceLegacyMenu(
            packageName = plan.packageName,
            artifactSha256 = plan.artifactSha256.lowercase(Locale.ROOT),
            sources = sources.sortedWith(compareBy({ it.sha256 }, { it.size })),
        )
    }
}

/** The legacy host's two explicitly approved QA certificate SHA-256 pins. */
internal object SpaceTrustedHost {
    const val PACKAGE = "com.dualspace.multispace.androidx"
    private val CERTIFICATE_PINS = setOf(
        "03498720af5c326fc3a399d7c96aed5fdea2f378ec1799580bb119e1bcbc4b5f",
        "b44a6c2b53689c16cb08da3ca22e4aba20d9d0d1ecbc26df13e15c04b6665073",
    )

    fun accepts(signers: Collection<String>): Boolean =
        signers.size == 1 && signers.single().lowercase(Locale.ROOT) in CERTIFICATE_PINS

    @Suppress("DEPRECATION")
    fun installed(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= 33)
            pm.getPackageInfo(PACKAGE, PackageManager.PackageInfoFlags.of(
                PackageManager.GET_SIGNING_CERTIFICATES.toLong(),
            ))
        else pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = if (Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners.orEmpty().toList()
        else info.signatures.orEmpty().toList()
        accepts(signers.map { hexSha256(it.toByteArray()) })
    }.getOrDefault(false)

    fun open(context: Context) {
        check(installed(context)) {
            "Проверенное пространство ModKit не установлено или имеет другую подпись."
        }
        val launcher = requireNotNull(context.packageManager.getLaunchIntentForPackage(PACKAGE)) {
            "У проверенного пространства нет доступного экрана запуска."
        }
        val component = requireNotNull(launcher.component) { "Недоступный компонент пространства." }
        check(component.packageName == PACKAGE) { "Неверный пакет пространства." }
        @Suppress("DEPRECATION")
        val info = context.packageManager.getActivityInfo(component, 0)
        check(info.exported && info.enabled && info.applicationInfo.enabled) {
            "Стартовый экран пространства недоступен."
        }
        val permission = info.permission
        check(permission == null ||
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            "Недостаточно разрешений для запуска пространства."
        }
        context.startActivity(Intent(launcher).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun hexSha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

/**
 * Reads only the explicitly chosen, currently installed and SHA/signer-matching
 * ModKit build. A missing, replaced or untrusted build produces an empty index.
 * Each profile lookup independently rechecks on-device APK bytes.
 */
internal object SpaceLegacyMenuBridge {
    fun verified(context: Context): SpaceLegacyMenu? {
        val selected = SpaceActiveProfileStore.read(context) ?: return null
        val records = SpaceBuildProfileIndex.load(context)
        val record = records.singleOrNull {
            it.plan.packageName == selected.packageName &&
                it.plan.artifactSha256.equals(selected.artifactSha256, ignoreCase = true)
        } ?: return null
        if (SpaceInstalledPackageLauncher.status(context, record) != SpaceLaunchStatus.READY)
            return null
        return SpaceLegacyProfileContract.from(record)
    }

    fun announce(context: Context) {
        val uri = Uri.parse("content://${context.packageName}.space-menu/index")
        context.contentResolver.notifyChange(uri, null)
    }
}

/**
 * The old Space host synchronizes /index and /profile/<filename> on resume.
 * Its UID is authenticated here using the exact installed host signer pins.
 * No APK binaries, commands, or native runtime payloads cross this boundary.
 */
class SpaceReadOnlyMenuProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun authorize() {
        val app = requireNotNull(context)
        val uid = Binder.getCallingUid()
        if (uid == app.applicationInfo.uid) return
        val packages = app.packageManager.getPackagesForUid(uid).orEmpty()
        if (SpaceTrustedHost.PACKAGE !in packages || !SpaceTrustedHost.installed(app))
            throw SecurityException("Only an authenticated ModKit Space host may read profiles")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        authorize()
        val app = requireNotNull(context)
        if (mode != "r" || uri.authority != "${app.packageName}.space-menu")
            throw FileNotFoundException("Read-only Space menu endpoint")
        val menu = SpaceLegacyMenuBridge.verified(app)
        val bytes = when {
            uri.pathSegments == listOf("index") -> {
                val entries = JSONArray()
                if (menu != null) {
                    val payload = menu.toJson()
                    entries.put(JSONObject()
                        .put("file", menu.fileName)
                        .put("sha256", hexSha256(payload)))
                }
                JSONObject().put("schema", 1).put("profiles", entries)
                    .toString().toByteArray(Charsets.UTF_8)
            }
            uri.pathSegments.size == 2 && uri.pathSegments[0] == "profile" &&
                menu != null && uri.pathSegments[1] == menu.fileName -> menu.toJson()
            else -> throw FileNotFoundException("No verified active game profile")
        }
        check(bytes.size <= 256 * 1024) { "Menu payload exceeds host contract" }
        return openPipeHelper(uri, "application/json", null, bytes) { pipe, _, _, _, data ->
            ParcelFileDescriptor.AutoCloseOutputStream(pipe).use { it.write(data!!) }
        }
    }

    override fun getType(uri: Uri): String {
        authorize()
        return "application/json"
    }
    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?,
    ): Cursor? { authorize(); return null }
    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw SecurityException("Read-only")
    override fun update(
        uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?,
    ): Int = throw SecurityException("Read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw SecurityException("Read-only")
}
