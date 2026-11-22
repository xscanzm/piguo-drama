// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.FileProvider
import io.github.hongguo.mobile.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed class UpdateStatus {
    data object Idle : UpdateStatus()
    data object Checking : UpdateStatus()
    data class UpToDate(val currentVersion: String) : UpdateStatus()
    data class Available(val info: AppUpdateInfo) : UpdateStatus()
    data class Downloading(
        val info: AppUpdateInfo,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val channelLabel: String = "GitHub 直连",
        val speedBytesPerSec: Long = 0L,
        val connecting: Boolean = true
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val updatesDir = File(appContext.cacheDir, "updates").apply { mkdirs() }

    private val _status = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    private val _dialogVisible = MutableStateFlow(false)
    val dialogVisible: StateFlow<Boolean> = _dialogVisible.asStateFlow()

    @Volatile
    private var dismissedTagInSession: String? = null
    @Volatile
    private var checkInProgress = false
    @Volatile
    private var downloadJob: Job? = null
    @Volatile
    private var activeCall: Call? = null
    @Volatile
    private var pendingInstall: Pair<AppUpdateInfo, File>? = null

    private fun apkFileForTag(tagName: String): File {
        val safeTag = tagName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(updatesDir, "piguo-drama-$safeTag.apk")
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private fun isValidApkContainerHeader(file: File): Boolean {
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

    private fun extractSigningDigests(packageInfo: PackageInfo?): Set<String> {
        if (packageInfo == null) return emptySet()
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = packageInfo.signingInfo
            if (info != null) {
                if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        } ?: return emptySet()

        val md = MessageDigest.getInstance("SHA-256")
        return sigs.mapNotNull { sig ->
            runCatching {
                md.reset()
                md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
            }.getOrNull()
        }.toSet()
    }

    private fun installedSigningDigests(): Set<String> = runCatching {
        val pm = appContext.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(appContext.packageName, PackageManager.GET_SIGNATURES)
        }
        extractSigningDigests(info)
    }.getOrDefault(emptySet())

    private fun isValidDownloadedApk(file: File, expectedSize: Long): Boolean {
        if (!file.exists() || file.length() < 512L * 1024L) return false
        if (expectedSize > 0L && file.length() != expectedSize) return false
        if (!isValidApkContainerHeader(file)) return false
        val pm = appContext.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archiveInfo = runCatching { pm.getPackageArchiveInfo(file.absolutePath, flags) }.getOrNull()
            ?: return false
        if (archiveInfo.packageName != appContext.packageName) return false

        val expectedDigests = installedSigningDigests()
        if (expectedDigests.isNotEmpty()) {
            val archiveDigests = extractSigningDigests(archiveInfo)
            if (archiveDigests.isEmpty() || expectedDigests.intersect(archiveDigests).isEmpty()) {
                return false
            }
        }
        return true
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

    fun cancelDownload() {
        activeCall?.cancel()
        activeCall = null
        downloadJob?.cancel()
        downloadJob = null
        updatesDir.listFiles()?.forEach { f ->
            if (f.isFile && f.name.endsWith(".tmp")) f.delete()
        }
        val st = _status.value
        if (st is UpdateStatus.Downloading) {
            _status.value = UpdateStatus.Available(st.info)
        }
    }

    fun startDownloadAndInstall(info: AppUpdateInfo, activityContext: Context) {
        if (_status.value is UpdateStatus.Downloading && downloadJob?.isActive == true) return
        val target = apkFileForTag(info.tagName)
        if (isValidDownloadedApk(target, info.assetSizeBytes)) {
            _status.value = UpdateStatus.ReadyToInstall(info, target)
            triggerInstall(activityContext, target, info)
            return
        }

        _status.value = UpdateStatus.Downloading(
            info = info,
            downloadedBytes = 0L,
            totalBytes = info.assetSizeBytes,
            channelLabel = "正在连接...",
            speedBytesPerSec = 0L,
            connecting = true
        )

        downloadJob = scope.launch {
            val candidates = AppUpdateChecker.buildDownloadCandidates(info.downloadUrl)
            var lastErr = "网络连接超时"
            try {
                for ((channelLabel, url) in candidates) {
                    val tmpFile = File(updatesDir, "${target.name}.tmp")
                    if (tmpFile.exists()) tmpFile.delete()
                    _status.value = UpdateStatus.Downloading(
                        info = info,
                        downloadedBytes = 0L,
                        totalBytes = info.assetSizeBytes,
                        channelLabel = "正在连接 $channelLabel...",
                        speedBytesPerSec = 0L,
                        connecting = true
                    )

                    val channelResult = runCatching {
                        val dlClient = httpClient.newBuilder()
                            .followRedirects(true)
                            .followSslRedirects(true)
                            .connectTimeout(5, TimeUnit.SECONDS)
                            .readTimeout(15, TimeUnit.SECONDS)
                            .callTimeout(120, TimeUnit.SECONDS)
                            .build()
                        val req = Request.Builder()
                            .url(url)
                            .header("User-Agent", "PiguoDrama-Android")
                            .get()
                            .build()
                        val call = dlClient.newCall(req)
                        activeCall = call
                        call.execute().use { resp ->
                            if (!resp.isSuccessful) {
                                error("HTTP ${resp.code}")
                            }
                            val ct = resp.header("Content-Type").orEmpty().lowercase()
                            if (ct.contains("text/html")) {
                                error("节点返回网页而非 APK")
                            }
                            val body = resp.body ?: error("安装包响应为空")
                            val headerLen = body.contentLength().takeIf { it > 0L } ?: info.assetSizeBytes
                            var downloaded = 0L
                            var lastReportMs = System.currentTimeMillis()
                            var lastReportedBytes = 0L
                            var currentSpeed = 0L
                            body.byteStream().use { input ->
                                tmpFile.outputStream().use { output ->
                                    val buf = ByteArray(16 * 1024)
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n <= 0) break
                                        output.write(buf, 0, n)
                                        downloaded += n
                                        val now = System.currentTimeMillis()
                                        val elapsed = now - lastReportMs
                                        if (elapsed >= 180L) {
                                            currentSpeed = ((downloaded - lastReportedBytes) * 1000L) / elapsed.coerceAtLeast(1L)
                                            lastReportMs = now
                                            lastReportedBytes = downloaded
                                            _status.value = UpdateStatus.Downloading(
                                                info = info,
                                                downloadedBytes = downloaded,
                                                totalBytes = headerLen,
                                                channelLabel = channelLabel,
                                                speedBytesPerSec = currentSpeed,
                                                connecting = false
                                            )
                                        }
                                    }
                                }
                            }
                            _status.value = UpdateStatus.Downloading(
                                info = info,
                                downloadedBytes = downloaded,
                                totalBytes = maxOf(headerLen, downloaded),
                                channelLabel = channelLabel,
                                speedBytesPerSec = currentSpeed,
                                connecting = false
                            )
                        }
                        if (!isValidDownloadedApk(tmpFile, info.assetSizeBytes)) {
                            error("安装包签名或完整性校验失败")
                        }
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
                    }

                    activeCall = null
                    if (channelResult.isSuccess) {
                        _status.value = UpdateStatus.ReadyToInstall(info, target)
                        withContext(Dispatchers.Main) {
                            triggerInstall(activityContext, target, info)
                        }
                        return@launch
                    } else {
                        if (tmpFile.exists()) tmpFile.delete()
                        val err = channelResult.exceptionOrNull()
                        if (err is CancellationException) throw err
                        lastErr = "$channelLabel：${err?.message ?: "连接失败"}"
                    }
                }

                _status.value = UpdateStatus.Error(
                    message = "应用内下载失败（已尝试直连与加速线路）：$lastErr",
                    info = info
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                _status.value = UpdateStatus.Error(
                    message = "下载失败：${e.message ?: "网络异常"}",
                    info = info
                )
            } finally {
                activeCall = null
            }
        }
    }

    fun triggerInstall(context: Context, apkFile: File, info: AppUpdateInfo) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { triggerInstall(context, apkFile, info) }
            return
        }
        if (!isValidDownloadedApk(apkFile, info.assetSizeBytes)) {
            if (apkFile.exists()) apkFile.delete()
            _status.value = UpdateStatus.Available(info)
            return
        }
        val launchCtx: Context = context.findActivity() ?: appContext
        val canInstall = runCatching { appContext.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        if (!canInstall) {
            pendingInstall = info to apkFile
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${appContext.packageName}")
            ).apply {
                if (launchCtx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val opened = runCatching { launchCtx.startActivity(settingsIntent) }.isSuccess ||
                runCatching {
                    val appInfoIntent = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${appContext.packageName}")
                    ).apply {
                        if (launchCtx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    launchCtx.startActivity(appInfoIntent)
                }.isSuccess
            _status.value = UpdateStatus.ReadyToInstall(
                info = info,
                apkFile = apkFile,
                hint = if (opened) {
                    "请在系统设置中开启「允许来自此来源的应用」，授权返回后将自动继续安装"
                } else {
                    "未能自动打开权限设置，请到系统设置 → 应用管理 → 皮果刷剧 中允许「安装未知应用」，返回后再点「立即安装」"
                }
            )
            return
        }

        pendingInstall = null
        runCatching {
            val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                clipData = ClipData.newRawUri("apk", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (launchCtx !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            launchCtx.startActivity(intent)
            _status.value = UpdateStatus.ReadyToInstall(info, apkFile, hint = null)
        }.onFailure { e ->
            _status.value = UpdateStatus.Error(
                message = "拉起系统安装器失败：${e.message ?: e.javaClass.simpleName}",
                info = info
            )
        }
    }

    fun resumePendingInstallIfAllowed(activityContext: Context) {
        val pending = pendingInstall ?: return
        val canInstall = runCatching { appContext.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        if (canInstall) {
            pendingInstall = null
            triggerInstall(activityContext, pending.second, pending.first)
        }
    }

    fun openReleasePageInBrowser(context: Context, url: String) {
        val launchCtx = context.findActivity() ?: appContext
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                if (launchCtx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            launchCtx.startActivity(intent)
        }.onFailure { e ->
            val currentInfo = when (val st = _status.value) {
                is UpdateStatus.Available -> st.info
                is UpdateStatus.ReadyToInstall -> st.info
                is UpdateStatus.Error -> st.info
                else -> null
            }
            _status.value = UpdateStatus.Error(
                message = "无法打开浏览器：${e.message ?: "未找到可用浏览器"}",
                info = currentInfo
            )
        }
    }
}
