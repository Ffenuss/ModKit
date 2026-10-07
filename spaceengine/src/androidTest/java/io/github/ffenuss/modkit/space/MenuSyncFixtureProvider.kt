package io.github.ffenuss.modkit.space

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Disposable provider fixture installed exclusively by the emulator test APK. */
class MenuSyncFixtureProvider : ContentProvider() {
    private var profiles = emptyMap<String, String>()
    private var corrupt = false
    override fun onCreate() = true
    @Synchronized override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        require(method == "fixture")
        val values = requireNotNull(extras)
        profiles = values.keySet().filter { it.endsWith(".json") }.associateWith { values.getString(it)!! }
        corrupt = values.getBoolean("corrupt")
        return Bundle()
    }
    @Synchronized override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        val text = if (uri.path == "/index") {
            val entries = JSONArray()
            profiles.forEach { (name, body) ->
                val hash = if (corrupt) "0".repeat(64) else MessageDigest.getInstance("SHA-256")
                    .digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
                entries.put(JSONObject().put("file", name).put("sha256", hash))
            }
            JSONObject().put("schema", 1).put("profiles", entries).toString()
        } else profiles.getValue(uri.lastPathSegment!!)
        return openPipeHelper(uri, "application/json", null, text.toByteArray()) { output, _, _, _, data ->
            ParcelFileDescriptor.AutoCloseOutputStream(output).use { it.write(data!!) }
        }
    }
    override fun getType(uri: Uri) = "application/json"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = error("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
