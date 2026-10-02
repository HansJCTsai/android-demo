package com.tti.factorydemo

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * 自動更新流程：
 * 1. 讀取 <UPDATE_BASE_URL>/version.json（由 GitHub Actions 每次建置時產生）
 * 2. 比對 versionCode，比目前版本新才更新
 * 3. 下載 APK 直接寫入系統 PackageInstaller，交給系統安裝（覆蓋更新）
 */
object Updater {
    private val running = AtomicBoolean(false)

    fun check(activity: Activity, report: (String) -> Unit) {
        if (!running.compareAndSet(false, true)) return
        report(activity.getString(R.string.update_checking))

        thread {
            try {
                val base = BuildConfig.UPDATE_BASE_URL.trimEnd('/') + "/"
                val info = JSONObject(download(URL(base + "version.json")).use { it.readText() })
                val latestCode = info.getInt("versionCode")
                val latestName = info.getString("versionName")

                if (latestCode <= BuildConfig.VERSION_CODE) {
                    report(activity.getString(R.string.update_latest, BuildConfig.VERSION_NAME))
                    return@thread
                }

                // Android 8 以上：需先允許本 App「安裝不明應用程式」（只需設定一次）
                if (Build.VERSION.SDK_INT >= 26 && !activity.packageManager.canRequestPackageInstalls()) {
                    report(activity.getString(R.string.update_need_permission))
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}"))
                    )
                    return@thread
                }

                report(activity.getString(R.string.update_downloading, latestName))
                // apkUrl 可以是完整網址，或相對於 version.json 的檔名
                val apkUrl = URL(URL(base), info.getString("apkUrl"))
                install(activity, apkUrl)
                report(activity.getString(R.string.update_installing, latestName))
            } catch (e: Exception) {
                report(activity.getString(R.string.update_failed, e.message ?: e.javaClass.simpleName))
            } finally {
                running.set(false)
            }
        }
    }

    private fun download(url: URL) = (url.openConnection() as HttpURLConnection).run {
        connectTimeout = 10_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        if (responseCode !in 200..299) throw IllegalStateException("HTTP $responseCode")
        inputStream.bufferedReader()
    }

    private fun install(activity: Activity, apkUrl: URL) {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            // Android 12 以上：符合系統條件時可不跳確認畫面（例如本 App 已是自己的安裝來源）
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            val conn = apkUrl.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 10_000
            conn.readTimeout = 120_000
            if (conn.responseCode !in 200..299) throw IllegalStateException("APK HTTP ${conn.responseCode}")
            conn.inputStream.use { input ->
                session.openWrite("update.apk", 0, -1).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                activity, sessionId, Intent(activity, InstallReceiver::class.java), flags
            )
            session.commit(callback.intentSender)
        }
    }
}
