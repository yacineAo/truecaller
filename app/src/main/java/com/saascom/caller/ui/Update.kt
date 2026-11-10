package com.saascom.caller.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.saascom.caller.BuildConfig
import com.saascom.caller.R
import com.saascom.caller.Services
import com.saascom.caller.data.AppVersionDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * In-app update without the Play Store (GET /app-version → APK on the CRM server).
 * Android itself refuses an update that is not signed with the same key, so a tampered APK
 * cannot replace the app; the SHA-256 check also catches broken downloads.
 */
private const val CHECK_EVERY_MS = 24 * 3600 * 1000L

/**
 * Whether to ask the CRM about a newer APK now: at most once a day, but always when asked
 * ([force]) and always while this build is below the minimum the CRM announced, so a required
 * update cannot be dodged by restarting the app.
 */
internal fun shouldCheckForUpdate(now: Long, lastCheck: Long, force: Boolean, currentVersion: Int, knownMinVersion: Int): Boolean =
    force || currentVersion < knownMinVersion || now - lastCheck >= CHECK_EVERY_MS

object AppUpdater {
    class BadFile : IOException("sha256 mismatch")

    /** The newer version, if any. Checks the CRM at most once a day unless [force] (or the update is required). */
    suspend fun check(force: Boolean = false): AppVersionDto? {
        val settings = Services.settings
        val now = System.currentTimeMillis()
        if (!shouldCheckForUpdate(now, settings.lastUpdateCheck, force, BuildConfig.VERSION_CODE, settings.lastKnownMinVersion)) return null
        val latest = Services.api.appVersion()
        settings.lastUpdateCheck = now
        settings.lastKnownMinVersion = latest.minVersionCode
        return latest.takeIf { it.versionCode > BuildConfig.VERSION_CODE && !it.apkUrl.isNullOrBlank() }
    }

    fun isRequired(version: AppVersionDto) = BuildConfig.VERSION_CODE < version.minVersionCode

    suspend fun download(context: Context, version: AppVersionDto, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply {
            mkdirs()
            listFiles()?.forEach(File::delete)
        }
        val file = File(dir, "saascom-caller-${version.versionCode}.apk")
        val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(version.apkUrl!!).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("empty body")
            val total = body.contentLength()
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        read += n
                        if (total > 0) onProgress((read * 100 / total).toInt())
                    }
                }
            }
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            if (!version.sha256.isNullOrBlank() && !version.sha256.equals(sha, ignoreCase = true)) {
                file.delete()
                throw BadFile()
            }
        }
        file
    }

    /** Opens Android's installer. Returns false when the user must first allow installs from this app. */
    fun install(context: Context, file: File): Boolean {
        if (!context.packageManager.canRequestPackageInstalls()) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: ActivityNotFoundException) {
                // Very old or locked-down phone: nothing to open.
            }
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }
}

/** «نسخة جديدة»: offer, download with progress, install. No «لاحقاً» when the update is required. */
@Composable
fun UpdateDialog(version: AppVersionDto, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val required = AppUpdater.isRequired(version)
    var progress by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var file by remember { mutableStateOf<File?>(null) }

    val badFile = stringResource(R.string.update_bad_file)
    val allowInstall = stringResource(R.string.update_allow_install)

    fun start() {
        error = null
        val downloaded = file
        if (downloaded != null) {
            if (!AppUpdater.install(context, downloaded)) error = allowInstall
            return
        }
        progress = 0
        scope.launch {
            try {
                val apk = AppUpdater.download(context, version) { progress = it }
                file = apk
                progress = null
                if (!AppUpdater.install(context, apk)) error = allowInstall
            } catch (e: AppUpdater.BadFile) {
                progress = null
                error = badFile
            } catch (e: IOException) {
                progress = null
                error = context.getString(R.string.update_failed, e.message.orEmpty())
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!required) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !required, dismissOnClickOutside = !required),
        title = { Text(stringResource(R.string.update_title, version.versionName)) },
        text = {
            Column {
                Text(stringResource(if (required) R.string.update_required else R.string.update_text))
                progress?.let {
                    Text(stringResource(R.string.update_downloading, it))
                    LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = ::start, enabled = progress == null) { Text(stringResource(R.string.update_now)) }
        },
        dismissButton = if (required) null else {
            { TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_later)) } }
        },
    )
}
