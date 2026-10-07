package io.github.ffenuss.modkit.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

/** One distribution channel for every game; only explicitly published host assets qualify. */
@Composable
fun SpaceDownloadButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    Button(enabled = !busy, modifier = modifier, onClick = {
        busy = true
        message = null
        scope.launch {
            try {
                val download = withContext(Dispatchers.IO) { publishedSpaceAsset() }
                if (download == null) {
                    message = "Сборка пространства ещё не опубликована. Повторите проверку после выпуска версии."
                } else {
                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    manager.enqueue(DownloadManager.Request(Uri.parse(download))
                        .setTitle("ModKit · пространство")
                        .setDescription("Единое пространство для оригинальных игр и приложений")
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "modkit-space-${System.currentTimeMillis()}.apk"))
                    message = "Загрузка началась. После установки добавьте оригинальное приложение в пространстве. Для всех игр используется одна версия пространства."
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                message = "Не удалось скачать пространство. Проверьте соединение и повторите."
            } finally { busy = false }
        }
    }) { Text(if (busy) "Проверяем версию…" else "Скачать пространство") }
    message?.let { Text(it) }
}

private fun publishedSpaceAsset(): String? {
    val connection = URL("https://api.github.com/repos/Ffenuss/ModKit/releases?per_page=20")
        .openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("Accept", "application/vnd.github+json")
    connection.setRequestProperty("User-Agent", "ModKit-Space")
    try {
        check(connection.responseCode == 200)
        val content = connection.inputStream.use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                check(output.size() + count <= 2_000_000)
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            check(bytes.size <= 2_000_000)
            bytes.toString(Charsets.UTF_8)
        }
        val releases = JSONArray(content)
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val assets = release.getJSONArray("assets")
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                if (asset.optString("name") != "modkit-space.apk" || asset.optLong("size") <= 0) continue
                val url = asset.getString("browser_download_url")
                val uri = Uri.parse(url)
                check(uri.scheme == "https" && uri.host == "github.com" &&
                    uri.path.orEmpty().startsWith("/Ffenuss/ModKit/releases/download/") &&
                    uri.lastPathSegment == "modkit-space.apk")
                return url
            }
        }
        return null
    } finally { connection.disconnect() }
}
