package com.gdhameeja.flighttracker.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.gdhameeja.flighttracker.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import com.gdhameeja.flighttracker.data.json

/**
 * New versions are GitHub releases tagged v1.<commit count> with the APK attached
 * (.github/workflows/release.yml). The version code is the same commit count.
 */
class Updater(private val context: Context) {
    data class Release(val versionCode: Int, val name: String, val notes: String, val apkUrl: String)

    @Serializable
    private data class GhAsset(val name: String, val browser_download_url: String)

    @Serializable
    private data class GhRelease(val tag_name: String, val name: String? = null, val body: String? = null, val assets: List<GhAsset> = emptyList())

    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    /** The newer release, or null when this is the latest. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(req).execute().use { res ->
            if (res.code == 404) return@withContext null // no releases yet
            if (!res.isSuccessful) error("GitHub said ${res.code}")
            val rel = json.decodeFromString(GhRelease.serializer(), res.body!!.string())
            val code = rel.tag_name.substringAfterLast('.').toIntOrNull() ?: return@withContext null
            val apk = rel.assets.firstOrNull { it.name.endsWith(".apk") } ?: return@withContext null
            if (code <= BuildConfig.VERSION_CODE) null
            else Release(code, rel.name ?: rel.tag_name, rel.body.orEmpty(), apk.browser_download_url)
        }
    }

    /** Downloads the APK, reporting progress 0..1. */
    suspend fun download(release: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "flights-${release.versionCode}.apk")
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { res ->
            if (!res.isSuccessful) error("Download failed (${res.code})")
            val body = res.body!!
            val total = body.contentLength().takeIf { it > 0 }
            body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total != null) progress(done.toFloat() / total)
                    }
                }
            }
        }
        out
    }

    /** False until you allow this app to install updates (Android asks once). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
