package com.q09009.studytablet.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import com.q09009.studytablet.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class ReleaseInfo(val tag: String, val version: String, val notes: String, val assetUrl: String, val size: Long)

/** Checks GitHub Releases of the public repo and installs newer APKs. */
class Updater(private val context: Context, private val http: OkHttpClient) {

    val currentVersion: String = BuildConfig.VERSION_NAME

    suspend fun latest(): ReleaseInfo = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .build()
        http.newCall(req).execute().use { resp ->
            when (resp.code) {
                403, 429 -> throw IOException("GitHub 요청 한도 초과. 잠시 후 다시 시도하세요.")
                404 -> throw IOException("릴리즈가 없습니다.")
            }
            if (!resp.isSuccessful) throw IOException("업데이트 확인 실패 (${resp.code})")
            val json = JSONObject(resp.body!!.string())
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") }
                ?: throw IOException("릴리즈에 APK가 없습니다.")
            val tag = json.getString("tag_name")
            ReleaseInfo(
                tag = tag,
                version = tag.removePrefix("v"),
                notes = json.optString("body"),
                assetUrl = apk.getString("browser_download_url"),
                size = apk.optLong("size"),
            )
        }
    }

    fun isNewer(release: ReleaseInfo): Boolean = compare(release.version, currentVersion) > 0

    suspend fun download(release: ReleaseInfo, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val out = File(context.cacheDir, "update.apk")
            val req = Request.Builder()
                .url(release.assetUrl)
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("다운로드 실패 (${resp.code})")
                val body = resp.body!!
                val total = body.contentLength().takeIf { it > 0 } ?: release.size
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
            }
            out
        }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context, id, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pi.intentSender)
        }
    }

    companion object {
        const val REPO = "q09009/study-tablet"

        fun compare(a: String, b: String): Int {
            val pa = a.split(".", "-").map { it.toIntOrNull() ?: 0 }
            val pb = b.split(".", "-").map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
                if (d != 0) return d
            }
            return 0
        }
    }
}

/** Receives PackageInstaller results and shows the system confirmation when needed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(context, "설치 실패 ($status): ${msg.orEmpty()}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
