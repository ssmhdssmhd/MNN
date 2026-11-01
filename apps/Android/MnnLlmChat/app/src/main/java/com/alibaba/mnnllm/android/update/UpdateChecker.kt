// Created by ruoyi.sjd on 2025/2/13.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mnnllm.android.update

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.alibaba.mnnllm.android.BuildConfig
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.utils.AppUtils.getAppVersionName
import com.alibaba.mnnllm.android.utils.PreferenceUtils
import com.alibaba.mnnllm.android.utils.UiUtils
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 从 GitHub Release 检测新版本，用户同意后带断点续传地下载 APK 并拉起安装。
 */
class UpdateChecker(private val context: Context) {

    private var progressDialog: Dialog? = null
    private var downloadDialog: Dialog? = null
    private var downloadProgressBar: ProgressBar? = null
    private var downloadProgressText: TextView? = null

    private var activeCall: Call? = null
    private var downloadThread: Thread? = null

    @Volatile
    private var downloadPaused = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private data class UpdateInfo(
        val version: String,
        val downloadUrl: String,
        val size: Long,
        val timestamp: Long,
        val notes: String
    )

    @SuppressLint("LogNotTimber")
    fun checkForUpdates(context: Context, forceCheck: Boolean) {
        // Do nothing in Google Play build
        if (BuildConfig.IS_GOOGLE_PLAY_BUILD) {
            return
        }

        if (waitingForInstallPermission && (waitingDownloadId > 0 || waitingFileUri != null)) {
            installApk(context, waitingDownloadId, waitingFileUri)
            waitingForInstallPermission = false
            return
        }
        if (!forceCheck) {
            val lastCheckTime = PreferenceUtils.getLong(context, "download_last_show_time", 0)
            if (System.currentTimeMillis() - lastCheckTime < CHECK_INTERVAL_MS) {
                return
            }
        }
        PreferenceUtils.setLong(context, "download_last_show_time", System.currentTimeMillis())
        if (forceCheck) {
            progressDialog = createMaterial3ProgressDialog(context, context.getString(R.string.checking_repo_updates))
        }

        val request = Request.Builder()
            .url(RELEASE_API_URL)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .build()

        httpClient.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "check update failed: ${e.message}")
                mainHandler.post {
                    progressDialog?.dismiss()
                    progressDialog = null
                    if (forceCheck) {
                        UiUtils.showToast(context, context.getString(R.string.get_update_info_failed), Toast.LENGTH_SHORT)
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = try {
                    response.body?.string().orEmpty()
                } catch (e: IOException) {
                    ""
                }
                Log.d(TAG, "release response: ${response.code}")
                if (!response.isSuccessful) {
                    mainHandler.post {
                        progressDialog?.dismiss()
                        progressDialog = null
                        if (forceCheck) {
                            UiUtils.showToast(context, context.getString(R.string.get_update_info_failed), Toast.LENGTH_SHORT)
                        }
                    }
                    return
                }
                val info = try {
                    parseLatestRelease(body)
                } catch (e: Exception) {
                    Log.e(TAG, "parse release failed", e)
                    null
                }
                mainHandler.post {
                    progressDialog?.dismiss()
                    progressDialog = null
                    if (info == null) {
                        if (forceCheck) {
                            UiUtils.showToast(context, context.getString(R.string.get_update_info_failed), Toast.LENGTH_SHORT)
                        }
                        return@post
                    }
                    if (isNewerVersion(info.version, currentReleaseVersion(context))) {
                        showUpdateDialog(context, info)
                    } else if (forceCheck) {
                        UiUtils.showToast(context, context.getString(R.string.no_update), Toast.LENGTH_SHORT)
                    }
                }
            }
        })
    }

    /** 读取构建时注入的发布版本号，便于与 GitHub Release 资产版本保持一致。 */
    private fun currentReleaseVersion(context: Context): String {
        val releaseVersion = BuildConfig.APP_RELEASE_VERSION
        return if (releaseVersion.isNotBlank()) releaseVersion else getAppVersionName(context)
    }

    private fun parseLatestRelease(json: String): UpdateInfo? {
        val root = JSONObject(json)
        val notes = root.optString("body")
        val assets = root.optJSONArray("assets") ?: return null
        var best: UpdateInfo? = null
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.optString("name")
            val match = ASSET_PATTERN.matchEntire(name) ?: continue
            val version = match.groupValues[1]
            val timestamp = match.groupValues[2].toLongOrNull() ?: 0L
            val current = best
            if (current == null ||
                compareVersion(version, current.version) > 0 ||
                (compareVersion(version, current.version) == 0 && timestamp > current.timestamp)
            ) {
                best = UpdateInfo(
                    version = version,
                    downloadUrl = asset.optString("browser_download_url"),
                    size = asset.optLong("size", 0L),
                    timestamp = timestamp,
                    notes = notes
                )
            }
        }
        return best
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        return compareVersion(latest, current) > 0
    }

    private fun compareVersion(left: String, right: String): Int {
        val leftParts = left.split(".")
        val rightParts = right.split(".")
        val length = maxOf(leftParts.size, rightParts.size)
        for (i in 0 until length) {
            val l = leftParts.getOrNull(i)?.toIntOrNull() ?: 0
            val r = rightParts.getOrNull(i)?.toIntOrNull() ?: 0
            if (l != r) {
                return if (l > r) 1 else -1
            }
        }
        return 0
    }

    private fun showUpdateDialog(context: Context, info: UpdateInfo) {
        PreferenceUtils.setLong(context, "download_last_show_time", System.currentTimeMillis())
        val partFile = File(updateDir(context), partName(info))
        val message = buildString {
            append(context.getString(R.string.update_prompt_message, info.version, formatSize(info.size)))
            val notes = cleanNotes(info.notes)
            if (notes.isNotEmpty()) {
                append("\n\n")
                append(notes)
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.download_update_available, info.version))
            .setMessage(message)
            .setPositiveButton(if (partFile.exists()) R.string.update_resume else R.string.update_now) { dialog, _ ->
                dialog.dismiss()
                startDownload(context, info)
            }
            .setNegativeButton(R.string.update_later, null)
            .show()
    }

    private fun cleanNotes(notes: String): String {
        if (TextUtils.isEmpty(notes)) {
            return ""
        }
        val trimmed = notes.replace("\r", "").trim()
        return if (trimmed.length > MAX_NOTES_LENGTH) trimmed.substring(0, MAX_NOTES_LENGTH) + "…" else trimmed
    }

    private fun startDownload(context: Context, info: UpdateInfo) {
        if (downloadThread?.isAlive == true) {
            UiUtils.showToast(context, context.getString(R.string.update_downloading), Toast.LENGTH_SHORT)
            return
        }
        downloadPaused = false
        val appContext = context.applicationContext
        val dir = updateDir(appContext)
        val partFile = File(dir, partName(info))
        val targetFile = File(dir, partFile.name.removeSuffix(PART_SUFFIX))
        if (targetFile.exists() && isValidApk(appContext, targetFile)) {
            installApk(appContext, -1, uriFor(appContext, targetFile))
            return
        }
        showDownloadProgress(context, info)

        val thread = Thread {
            try {
                downloadApkFile(info, partFile, targetFile) { downloaded, total ->
                    mainHandler.post { updateDownloadProgress(downloaded, total) }
                }
                mainHandler.post {
                    dismissDownloadProgress()
                    if (!isValidApk(appContext, targetFile)) {
                        targetFile.delete()
                        UiUtils.showToast(appContext, appContext.getString(R.string.update_verify_failed), Toast.LENGTH_LONG)
                        return@post
                    }
                    UiUtils.showToast(appContext, appContext.getString(R.string.update_download_finished), Toast.LENGTH_SHORT)
                    installApk(appContext, -1, uriFor(appContext, targetFile))
                }
            } catch (e: Exception) {
                Log.e(TAG, "download update failed", e)
                mainHandler.post {
                    dismissDownloadProgress()
                    if (!downloadPaused) {
                        val reason = e.message ?: e.javaClass.simpleName
                        UiUtils.showToast(
                            appContext,
                            appContext.getString(R.string.update_download_failed, reason),
                            Toast.LENGTH_LONG
                        )
                    }
                }
            } finally {
                activeCall = null
            }
        }.also { downloadThread = it }
        thread.start()
    }

    private fun showDownloadProgress(context: Context, info: UpdateInfo) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_update_progress, null)
        downloadProgressBar = view.findViewById(R.id.update_progress_bar)
        downloadProgressText = view.findViewById(R.id.update_progress_text)
        view.findViewById<TextView>(R.id.update_message).text =
            context.getString(R.string.update_downloading_version, info.version)

        downloadDialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.update_downloading)
            .setView(view)
            .setNegativeButton(R.string.update_pause) { dialog, _ ->
                pauseDownload(context)
                dialog.dismiss()
            }
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun dismissDownloadProgress() {
        downloadDialog?.dismiss()
        downloadDialog = null
        downloadProgressBar = null
        downloadProgressText = null
    }

    @SuppressLint("SetTextI18n")
    private fun updateDownloadProgress(downloaded: Long, total: Long) {
        val percent = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else 0
        downloadProgressBar?.progress = percent
        downloadProgressText?.text = if (total > 0) {
            String.format(Locale.getDefault(), "%d%%  %s / %s", percent, formatSize(downloaded), formatSize(total))
        } else {
            formatSize(downloaded)
        }
    }

    private fun pauseDownload(context: Context) {
        downloadPaused = true
        activeCall?.cancel()
        UiUtils.showToast(context, context.getString(R.string.update_paused), Toast.LENGTH_LONG)
    }

    private fun downloadApkFile(
        info: UpdateInfo,
        partFile: File,
        targetFile: File,
        onProgress: (Long, Long) -> Unit
    ) {
        // Build an ordered list of URLs to try: original first, then GitHub mirrors.
        // GitHub releases are often slow or blocked in mainland China, so we fall back
        // to public mirror proxies when the direct download fails.
        val urlsToTry = buildDownloadUrls(info.downloadUrl)
        var lastError: Throwable? = null
        for (url in urlsToTry) {
            try {
                downloadFromUrl(url, info.size, partFile, targetFile, onProgress)
                return
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "Download failed for $url: ${e.message}")
                // Reset partial file before trying the next mirror so we start clean.
                if (partFile.exists()) partFile.delete()
            }
        }
        throw lastError ?: IOException("all download URLs failed")
    }

    private fun downloadFromUrl(
        url: String,
        expectedSize: Long,
        partFile: File,
        targetFile: File,
        onProgress: (Long, Long) -> Unit
    ) {
        val existingLength = if (partFile.exists()) partFile.length() else 0L
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
        if (existingLength > 0) {
            requestBuilder.header("Range", "bytes=$existingLength-")
        }
        val call = httpClient.newCall(requestBuilder.build())
        activeCall = call

        call.execute().use { response ->
            if (response.code == 416 && existingLength > 0) {
                // 本地文件恰好已完整，直接完成
                finishDownload(partFile, targetFile)
                return
            }
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("empty response body")

            val append = response.code == 206 && existingLength > 0
            if (existingLength > 0 && !append) {
                // 服务端不支持 Range，从头开始覆盖
                partFile.delete()
            }
            var total = expectedSize
            val contentRange = response.header("Content-Range")
            if (!contentRange.isNullOrBlank()) {
                val rangeTotal = contentRange.substringAfterLast('/', "").toLongOrNull()
                if (rangeTotal != null && rangeTotal > 0) {
                    total = rangeTotal
                }
            } else {
                total = response.body?.contentLength()?.takeIf { it > 0 }?.let {
                    it + (if (append) existingLength else 0L)
                } ?: expectedSize
            }

            var written = if (append) existingLength else 0L
            val buffer = ByteArray(BUFFER_SIZE)
            var lastReported = -1L
            FileOutputStream(partFile, append).use { output ->
                body.byteStream().use { input ->
                    while (true) {
                        if (downloadPaused) {
                            throw IOException("paused")
                        }
                        val read = input.read(buffer)
                        if (read == -1) {
                            break
                        }
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReported >= PROGRESS_STEP || (total > 0 && written >= total)) {
                            lastReported = written
                            onProgress(written, total)
                        }
                    }
                }
            }
            onProgress(written, total)
            if (total > 0 && written != total) {
                throw IOException("incomplete download $written/$total")
            }
            finishDownload(partFile, targetFile)
        }
    }

    private fun finishDownload(partFile: File, targetFile: File) {
        if (targetFile.exists() && !targetFile.delete()) {
            throw IOException("cannot replace target file")
        }
        if (!partFile.renameTo(targetFile)) {
            throw IOException("cannot rename part file")
        }
    }

    /** Detect whether the device is in a Chinese locale (zh_CN). */
    private fun isChineseLocale(): Boolean {
        val config = context.resources.configuration
        val locale = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            if (config.locales.isEmpty) null else config.locales[0]
        } else {
            @Suppress("DEPRECATION")
            config.locale
        }
        return locale != null && locale.language == "zh" && locale.country == "CN"
    }

    /**
     * Build the ordered list of download URLs to try for a GitHub release asset.
     * For Chinese-locale devices, mirror proxies are prepended so the (often slow/blocked)
     * direct GitHub download is only used as a fallback.
     */
    private fun buildDownloadUrls(originalUrl: String): List<String> {
        val mirrors = listOf(
            "https://ghproxy.com/",
            "https://mirror.ghproxy.com/",
            "https://gh-proxy.com/"
        )
        val urls = mutableListOf<String>()
        if (isChineseLocale()) {
            // Try mirrors first in China; fall back to the original GitHub URL.
            mirrors.forEach { urls.add(it + originalUrl) }
            urls.add(originalUrl)
        } else {
            // Outside China, try the direct URL first; mirrors are a fallback.
            urls.add(originalUrl)
            mirrors.forEach { urls.add(it + originalUrl) }
        }
        return urls
    }

    private fun isValidApk(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() <= 0) {
            return false
        }
        val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return false
        return archive.packageName == context.packageName
    }

    private fun updateDir(context: Context): File {
        val root = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        return File(root, "updates").apply { mkdirs() }
    }

    private fun partName(info: UpdateInfo): String {
        return "MNN.Chat.${info.version}.${info.timestamp}.apk$PART_SUFFIX"
    }

    private fun uriFor(context: Context, file: File): Uri {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else {
            Uri.fromFile(file)
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) {
            return "0 B"
        }
        val kb = bytes / 1024.0
        if (kb < 1024) {
            return String.format(Locale.getDefault(), "%.0f KB", kb)
        }
        val mb = kb / 1024.0
        return String.format(Locale.getDefault(), "%.1f MB", mb)
    }

    private fun createMaterial3ProgressDialog(context: Context, message: String): Dialog {
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.dialog_progress_material3, null)

        val progressIndicator = view.findViewById<CircularProgressIndicator>(R.id.progress_indicator)
        val progressText = view.findViewById<TextView>(R.id.progress_text)

        progressIndicator.isIndeterminate = true
        progressText.text = message

        return MaterialAlertDialogBuilder(context)
            .setView(view)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    companion object {
        private const val TAG = "UpdateChecker"
        private const val RELEASE_API_URL = "https://api.github.com/repos/ssmhdssmhd/MNN/releases/tags/latest"
        private const val USER_AGENT = "MoXi-Local-AI-Updater"
        private const val CHECK_INTERVAL_MS = 60 * 60 * 1000L
        private const val MAX_NOTES_LENGTH = 400
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP = 256 * 1024L
        private const val PART_SUFFIX = ".part"
        private val ASSET_PATTERN = Regex("^MNN\\.Chat\\.(\\d+\\.\\d+\\.\\d+)\\.(\\d{12})\\.apk$")

        private var waitingDownloadId: Long = -9999
        var waitingForInstallPermission = false
        private var waitingFileUri: Uri? = null

        fun getUrlLastName(urlStr: String?): String? {
            try {
                val url = URL(urlStr)
                val path = url.path
                val pathSegments = path.split("/").filter { it.isNotEmpty() }
                return pathSegments.lastOrNull()
            } catch (e: Exception) {
                return null
            }
        }

        fun installApk(context: Context, downloadId: Long, fileUri: Uri? = null) {
            // Do nothing in Google Play build
            if (BuildConfig.IS_GOOGLE_PLAY_BUILD) {
                return
            }

            if (!context.packageManager.canRequestPackageInstalls()) {
                waitingForInstallPermission = true
                waitingDownloadId = downloadId
                waitingFileUri = fileUri
                Toast.makeText(context, context.getString(R.string.please_grant_permission_install_apps), Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                intent.data = Uri.parse("package:${context.packageName}")
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            }
            Log.d(TAG, "installAPK: downloadId=$downloadId uri=$fileUri")
            val uri = fileUri ?: run {
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
                downloadManager.getUriForDownloadedFile(downloadId)
            }
            if (uri == null) {
                return
            }
            val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            installIntent.setDataAndType(uri, "application/vnd.android.package-archive")
            installIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(installIntent)
        }

        fun registerDownloadReceiver(context: Context) {
            // Do nothing in Google Play build
            if (BuildConfig.IS_GOOGLE_PLAY_BUILD) {
                return
            }
            // 保留系统 DownloadManager 兼容入口，更新流程已改为应用内断点续传下载
        }
    }
}
