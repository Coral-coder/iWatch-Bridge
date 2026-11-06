package com.coralcoder.iwatchbridge.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.coralcoder.iwatchbridge.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Keeps the app up to date without the Play Store. CI publishes each build to a fixed public
 * GitHub release ("latest"), so the app can read a small manifest and, when the build is newer
 * than this one, download the signed APK and hand it to Android's installer.
 *
 * Android never installs silently for an ordinary app — the system shows a one-tap confirmation
 * each time — so this makes updates automatic to find and download, with a single tap to apply.
 */
class Updater(private val context: Context) {
    @Serializable
    data class Manifest(val versionCode: Int = 0, val versionName: String = "", val apk: String = "", val notes: String = "")

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class Available(val versionName: String, val notes: String, val apkUrl: String) : State
        data class Downloading(val percent: Int) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    private val json = Json { ignoreUnknownKeys = true }

    /** Check the release manifest. [auto] = a silent background check (errors don't surface). */
    suspend fun check(auto: Boolean = true) {
        if (_state.value is State.Downloading || _state.value is State.Checking) return
        _state.value = State.Checking
        try {
            val text = withContext(Dispatchers.IO) { fetch(MANIFEST_URL) }
            val m = json.decodeFromString<Manifest>(text)
            _state.value = if (m.versionCode > BuildConfig.VERSION_CODE && m.apk.isNotEmpty())
                State.Available(m.versionName, m.notes, m.apk) else State.Idle
        } catch (e: Exception) {
            _state.value = if (auto) State.Idle else State.Error(e.message ?: "Couldn't check for updates")
        }
    }

    fun dismiss() { if (_state.value is State.Available || _state.value is State.Error) _state.value = State.Idle }

    suspend fun downloadAndInstall(apkUrl: String) {
        try {
            _state.value = State.Downloading(0)
            val file = withContext(Dispatchers.IO) { download(apkUrl) }
            install(file)
            _state.value = State.Idle
        } catch (e: Exception) {
            _state.value = State.Error(e.message ?: "Update download failed")
        }
    }

    private fun fetch(url: String): String {
        val c = open(url, 15_000)
        c.inputStream.use { return it.readBytes().decodeToString() }
    }

    private fun download(url: String): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "update.apk")
        val c = open(url, 30_000)
        val total = c.contentLength
        c.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024); var read = 0L; var n: Int
                while (input.read(buf).also { n = it } >= 0) {
                    output.write(buf, 0, n); read += n
                    if (total > 0) _state.value = State.Downloading((read * 100 / total).toInt().coerceIn(0, 100))
                }
            }
        }
        return out
    }

    private fun open(url: String, readTimeout: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000; this.readTimeout = readTimeout; instanceFollowRedirects = true
            setRequestProperty("Accept", "application/octet-stream")
        }

    private fun install(file: File) {
        // First time, Android needs the user to allow this app to install apps.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    companion object {
        const val MANIFEST_URL = "https://github.com/Coral-coder/iWatch-Bridge/releases/download/latest/latest.json"
    }
}
