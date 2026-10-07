package io.github.ffenuss.modkit.space

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileNotFoundException
import java.security.MessageDigest

/** Read-only bridge: only this app and the separately signed space may read profiles. */
class SpaceMenuProvider : ContentProvider() {
    override fun onCreate() = true

    private fun authorize() {
        val app = requireNotNull(context)
        val caller = Binder.getCallingUid()
        if (caller == app.applicationInfo.uid) return
        val packages = app.packageManager.getPackagesForUid(caller).orEmpty()
        if (TrustedSpace.PACKAGE !in packages || !TrustedSpace.installed(app))
            throw SecurityException("Only the trusted ModKit space may read menus")
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        authorize()
        if (mode != "r" || uri.authority != requireNotNull(context).packageName + ".space-menu")
            throw FileNotFoundException("Read-only menu endpoint")
        val menus = SavedSpaceMenus.load(requireNotNull(context)).take(256)
        val bytes = when {
            uri.pathSegments == listOf("index") -> {
                val entries = JSONArray()
                menus.forEach { menu ->
                    entries.put(JSONObject().put("file", menu.file.name)
                        .put("sha256", sha256(menu.file.readBytes())))
                }
                JSONObject().put("schema", 1).put("profiles", entries).toString().toByteArray(Charsets.UTF_8)
            }
            uri.pathSegments.size == 2 && uri.pathSegments[0] == "profile" -> {
                val file = menus.singleOrNull { it.file.name == uri.pathSegments[1] }?.file
                    ?: throw FileNotFoundException("Prepared profile not found")
                file.readBytes().also { if (it.size > 256 * 1024) throw FileNotFoundException("Profile exceeds limit") }
            }
            else -> throw FileNotFoundException("Unknown menu endpoint")
        }
        return openPipeHelper(uri, "application/json", null, bytes) { output, _, _, _, data ->
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(data!!) }
        }
    }

    override fun getType(uri: Uri): String { authorize(); return "application/json" }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? { authorize(); return null }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException("Read-only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Read-only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException("Read-only")
}

internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

internal object TrustedSpace {
    const val PACKAGE = "com.dualspace.multispace.androidx"
    // Public certificate fingerprint of the user's authorized Aniimo QA space key.
    private const val CERTIFICATE = "03498720af5c326fc3a399d7c96aed5fdea2f378ec1799580bb119e1bcbc4b5f"
    @Suppress("DEPRECATION")
    fun installed(context: Context): Boolean = runCatching {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            context.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners.orEmpty()
        } else context.packageManager.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        signatures.size == 1 && sha256(signatures.single().toByteArray()) == CERTIFICATE
    }.getOrDefault(false)
}
