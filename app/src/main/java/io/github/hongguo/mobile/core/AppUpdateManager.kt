// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.hongguo.mobile.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

sealed class UpdateStatus {
    data object Idle : UpdateStatus()
    data object Checking : UpdateStatus()
    data class UpToDate(val currentVersion: String) : UpdateStatus()
    data class Available(val info: AppUpdateInfo) : UpdateStatus()
    data class Downloading(
        val info: AppUpdateInfo,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateStatus()
    data class ReadyToInstall(
        val info: AppUpdateInfo,
        val apkFile: File,
        val hint: String? = null
    ) : UpdateStatus()
    data class Error(val message: String, val info: AppUpdateInfo? = null) : UpdateStatus()
}

class AppUpdateManager(
    context: Context,
    private val httpClient: OkHttpClient,
    private val currentVersion: String = BuildConfig.VERSION_NAME
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val _dialogVisible = MutableStateFlow(false)
    val dialogVisible: StateFlow<Boolean> = _dialogVisible.asStateFlow()

    @Volatile
    private var dismissedTagInSession: String? = null
    @Volatile
    private var checkInProgress = false

    private fun apkFileForTag(tagName: String): File {
        val safeTag = tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(updatesDir, "piguo-drama-$safeTag.apk")
    }

    private fun isValidZipMagic(file: File): Boolean {
        if (!file.exists() || file.length() < 4L) return false
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val b0 = raf.read()
                val b1 = raf.read()
                val b2 = raf.read()
                val b3 = raf.read()
                b0 == 0x50 && b1 == 0x4B && b2 == 0x03 && b3 == 0x04
            }
        }.getOrDefault(false)
    }

    private fun isValidDownloadedApk(file: File, expectedSize: Long): Boolean {
        if (!file.exists() || file.length() < 512L * 1024L) return false
        if (expectedSize > 0L && file.length() != expectedSize) return false
        if (!isValidZipMagic(file)) return false
        val archivePkg = runCatching {
            appContext.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.packageName
        }.getOrNull()
        return archivePkg == appContext.packageName
    }

    fun checkForUpdates(silent: Boolean) {
        if (checkInProgress) return
        val cur = _status.value
        if (cur is UpdateStatus.Downloading) {
            if (!silent) _dialogVisible.value = true
            return
        }
        checkInProgress = true
        if (!silent) {
            _status.value = UpdateStatus.Checking
        }
        scope.launch {
            try {
                val info = AppUpdateChecker.fetchLatestRelease(httpClient)
                if (AppUpdateChecker.isNewerVersion(info.versionName, currentVersion)) {
                    val existing = apkFileForTag(info.tagName)
                    _status.value = if (isValidDownloadedApk(existing, info.assetSizeBytes)) {
                        UpdateStatus.ReadyToInstall(info, existing)
                    } else {
                        UpdateStatus.Available(info)
                    }
                    if (!silent || dismissedTagInSession != info.tagName) {
                        _dialogVisible.value = true
                    }
                } else {
                    _status.value = if (silent) UpdateStatus.Idle else UpdateStatus.UpToDate(currentVersion)
                }
            } catch (e: Exception) {
                if (!silent) {
                    _status.value = UpdateStatus.Error(e.message ?: "检查更新失败，请稍后重试")
                } else if (_status.value is UpdateStatus.Checking) {
                    _status.value = UpdateStatus.Idle
                }
            } finally {
                checkInProgress = false
            }
        }
    }

    fun openUpdateDialog() {
        when (_status.value) {
            is UpdateStatus.Available,
            is UpdateStatus.Downloading,
            is UpdateStatus.ReadyToInstall,
            is UpdateStatus.Error -> _dialogVisible.value = true
            else -> checkForUpdates(silent = false)
        }
    }

    fun dismissUpdateDialog(skipInSession: Boolean = false) {
        if (skipInSession) {
            val tag = when (val st = _status.value) {
                is UpdateStatus.Available -> st.info.tagName
                is UpdateStatus.ReadyToInstall -> st.info.tagName
                is UpdateStatus.Error -> st.info?.tagName
                else -> null
            }
            if (!tag.isNullOrBlank()) dismissedTagInSession = tag
        }
        _dialogVisible.value = false
    }

    fun startDownloadAndInstall(info: AppUpdateInfo, activityContext: Context) {
        if (_status.value is UpdateStatus.Downloading) return
        val target = apkFileForTag(info.tagName)
        if (isValidDownloadedApk(target, info.assetSizeBytes)) {
            _status.value = UpdateStatus.ReadyToInstall(info, target)
            triggerInstall(activityContext, target, info)
            return
        }

        _status.value = UpdateStatus.Downloading(info, 0L, info.assetSizeBytes)
        scope.launch {
            val tmpFile = File(updatesDir, "${target.name}.tmp")
            try {
                val dlClient = httpClient.newBuilder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .connectTimeout(12, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .callTimeout(180, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder()
                    .url(info.downloadUrl)
                    .header("User-Agent", "PiguoDrama-Android")
                    .get()
                    .build()
                dlClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        error("HTTP ${resp.code}")
                    }
                    val body = resp.body ?: error("安装包响应为空")
                    val headerLen = body.contentLength().takeIf { it > 0L } ?: info.assetSizeBytes
                    var downloaded = 0L
                    var lastReportMs = 0L
                    body.byteStream().use { input ->
                        tmpFile.outputStream().use { output ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                downloaded += n
                                val now = System.currentTimeMillis()
                                if (now - lastReportMs >= 150L) {
                                    lastReportMs = now
                                    _status.value = UpdateStatus.Downloading(info, downloaded, headerLen)
                                }
                            }
                        }
                    }
                    _status.value = UpdateStatus.Downloading(info, downloaded, maxOf(headerLen, downloaded))
                }

                if (!isValidDownloadedApk(tmpFile, info.assetSizeBytes)) {
                    if (tmpFile.exists()) tmpFile.delete()
                    error("下载文件校验失败")
                }

                // Clean older APKs before finalizing new one
                updatesDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith(".apk") && f.name != target.name) {
                        f.delete()
                    }
                }
                if (target.exists()) target.delete()
                if (!tmpFile.renameTo(target)) {
                    tmpFile.copyTo(target, overwrite = true)
                    tmpFile.delete()
                }
                _status.value = UpdateStatus.ReadyToInstall(info, target)
                triggerInstall(activityContext, target, info)
            } catch (e: Exception) {
                if (tmpFile.exists()) tmpFile.delete()
                _status.value = UpdateStatus.Error(
                    message = "下载失败：${e.message ?: "网络异常"}，可重试或用浏览器下载",
                    info = info
                )
            }
        }
    }

    fun triggerInstall(context: Context, apkFile: File, info: AppUpdateInfo) {
        if (!isValidDownloadedApk(apkFile, info.assetSizeBytes)) {
            _status.value = UpdateStatus.Available(info)
            return
        }
        val canInstall = runCatching { appContext.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        if (!canInstall) {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${appContext.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
            _status.value = UpdateStatus.ReadyToInstall(
                info = info,
                apkFile = apkFile,
                hint = "请先在系统设置中开启「允许来自此来源的应用」，返回后再点「立即安装」"
            )
            return
        }

        runCatching {
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            _status.value = UpdateStatus.ReadyToInstall(info, apkFile, hint = null)
        }.onFailure { e ->
            _status.value = UpdateStatus.Error(
                message = "拉起系统安装器失败：${e.message ?: "未知错误"}",
                info = info
            )
        }
    }

    fun openReleasePageInBrowser(context: Context, url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
