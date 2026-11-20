// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.ui.components

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import okhttp3.Request
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.hongguo.mobile.core.AccountManager
import io.github.hongguo.mobile.core.LoginResult
import io.github.hongguo.mobile.core.QrCodeSession
import io.github.hongguo.mobile.core.QrPollResult
import io.github.hongguo.mobile.core.SmsSendResult
import io.github.hongguo.mobile.ui.theme.BgBlack
import io.github.hongguo.mobile.ui.theme.CoralRed
import io.github.hongguo.mobile.ui.theme.SurfaceDark
import io.github.hongguo.mobile.ui.theme.SurfaceElevated
import io.github.hongguo.mobile.ui.theme.TextPrimary
import io.github.hongguo.mobile.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhoneLoginSheet(
    accountManager: AccountManager,
    onDismiss: () -> Unit,
    onSwitchToQrLogin: () -> Unit = {},
    onSwitchToWebLogin: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var countdown by remember { mutableIntStateOf(0) }
    var sending by remember { mutableStateOf(false) }
    var loggingIn by remember { mutableStateOf(false) }
    var statusMsg by remember { mutableStateOf<String?>(null) }
    var highlightWebFallback by remember { mutableStateOf(false) }

    LaunchedEffect(countdown) {
        while (countdown > 0) {
            delay(1000L)
            countdown -= 1
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        contentColor = TextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "手机号验证码登录",
                color = TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "登录后自动开启官方个性化推荐流与云端追剧同步",
                color = TextSecondary,
                fontSize = 13.sp
            )

            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CoralRed,
                unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = CoralRed
            )

            OutlinedTextField(
                value = phone,
                onValueChange = { if (it.length <= 15) phone = it },
                label = { Text("手机号 (+86)", color = TextSecondary) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                colors = fieldColors,
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { if (it.length <= 8) code = it },
                    label = { Text("短信验证码", color = TextSecondary) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = fieldColors,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    enabled = !sending && countdown == 0 && phone.filter { it.isDigit() }.length >= 11,
                    onClick = {
                        scope.launch {
                            sending = true
                            statusMsg = null
                            when (val res = accountManager.sendSmsCode(phone)) {
                                is SmsSendResult.Sent -> {
                                    countdown = 60
                                    statusMsg = res.message
                                    highlightWebFallback = false
                                }
                                is SmsSendResult.NeedsWebVerify -> {
                                    statusMsg = res.reason
                                    highlightWebFallback = true
                                }
                                is SmsSendResult.Error -> {
                                    statusMsg = res.message
                                }
                            }
                            sending = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated),
                    modifier = Modifier.height(52.dp)
                ) {
                    if (sending) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = CoralRed, strokeWidth = 2.dp)
                    } else {
                        Text(
                            text = if (countdown > 0) "${countdown}s" else "获取验证码",
                            color = TextPrimary,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            if (!statusMsg.isNullOrBlank()) {
                Text(
                    text = statusMsg.orEmpty(),
                    color = if (highlightWebFallback) CoralRed else TextSecondary,
                    fontSize = 13.sp
                )
            }

            Button(
                enabled = !loggingIn && phone.filter { it.isDigit() }.length >= 11 && code.trim().length >= 4,
                onClick = {
                    scope.launch {
                        loggingIn = true
                        statusMsg = null
                        when (val res = accountManager.verifySmsLogin(phone, code)) {
                            is LoginResult.Success -> {
                                onLoginSuccess()
                            }
                            is LoginResult.NeedsWebVerify -> {
                                statusMsg = res.reason
                                highlightWebFallback = true
                            }
                            is LoginResult.Error -> {
                                statusMsg = res.message
                            }
                        }
                        loggingIn = false
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = CoralRed),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                if (loggingIn) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Text("登录", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(CoralRed.copy(alpha = 0.14f))
                        .border(
                            width = 1.dp,
                            color = CoralRed.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            onDismiss()
                            onSwitchToQrLogin()
                        }
                        .padding(vertical = 11.dp, horizontal = 8.dp)
                ) {
                    Text(
                        text = "红果/抖音扫码登录 (推荐)",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (highlightWebFallback) CoralRed.copy(alpha = 0.16f) else Color.Transparent)
                        .border(
                            width = 1.dp,
                            color = if (highlightWebFallback) CoralRed else Color.White.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            onDismiss()
                            onSwitchToWebLogin()
                        }
                        .padding(vertical = 11.dp, horizontal = 8.dp)
                ) {
                    Text(
                        text = if (highlightWebFallback) "官网登录窗(兜底)" else "官网页面登录",
                        color = if (highlightWebFallback) Color.White else TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = if (highlightWebFallback) FontWeight.SemiBold else FontWeight.Normal,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrLoginSheet(
    accountManager: AccountManager,
    onDismiss: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var refreshKey by remember { mutableIntStateOf(0) }
    var loadingQr by remember { mutableStateOf(true) }
    var qrSession by remember { mutableStateOf<QrCodeSession?>(null) }
    var qrImage by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var statusText by remember { mutableStateOf("正在获取红果官方登录二维码...") }
    var isExpired by remember { mutableStateOf(false) }
    var savedOnce by remember { mutableStateOf(false) }
    var saveFeedback by remember { mutableStateOf<String?>(null) }

    val hasHongguoApp = remember {
        context.packageManager.getLaunchIntentForPackage("com.phoenix.read") != null
    }
    val hasDouyinApp = remember {
        context.packageManager.getLaunchIntentForPackage("com.ss.android.ugc.aweme") != null
    }

    LaunchedEffect(refreshKey) {
        loadingQr = true
        isExpired = false
        savedOnce = false
        saveFeedback = null
        statusText = "正在获取红果官方登录二维码..."

        val fetched = accountManager.fetchQrLoginCode()
        if (fetched.isFailure) {
            loadingQr = false
            isExpired = true
            qrSession = null
            qrImage = null
            statusText = fetched.exceptionOrNull()?.message ?: "获取二维码失败，请点刷新"
            return@LaunchedEffect
        }

        val session = fetched.getOrThrow()
        val bmp = withContext(Dispatchers.Default) {
            runCatching {
                BitmapFactory.decodeByteArray(session.pngBytes, 0, session.pngBytes.size)
                    ?.asImageBitmap()
            }.getOrNull()
        }
        qrSession = session
        qrImage = bmp
        loadingQr = false
        statusText = session.desc

        var transientFailures = 0
        while (true) {
            delay(1800L)
            if (session.expireAtSec > 0L && System.currentTimeMillis() / 1000L >= session.expireAtSec) {
                isExpired = true
                statusText = "二维码已过期，请点击下方「刷新二维码」"
                break
            }
            when (val res = accountManager.pollQrLoginStatus(session.token)) {
                QrPollResult.Waiting -> {
                    transientFailures = 0
                }
                QrPollResult.Scanned -> {
                    transientFailures = 0
                    statusText = "已扫描二维码，请在红果短剧 / 抖音内点击「确认登录」"
                }
                QrPollResult.Expired -> {
                    isExpired = true
                    statusText = "二维码已过期，请点击下方「刷新二维码」"
                    break
                }
                is QrPollResult.Confirmed -> {
                    when (val loginRes = res.loginResult) {
                        is LoginResult.Success -> {
                            onLoginSuccess()
                            return@LaunchedEffect
                        }
                        is LoginResult.NeedsWebVerify -> {
                            isExpired = true
                            statusText = loginRes.reason
                            break
                        }
                        is LoginResult.Error -> {
                            isExpired = true
                            statusText = loginRes.message
                            break
                        }
                    }
                }
                is QrPollResult.Error -> {
                    transientFailures += 1
                    statusText = res.message
                    if (transientFailures >= 4) {
                        isExpired = true
                        break
                    }
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        contentColor = TextPrimary
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "红果扫码登录",
                        color = TextPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "与官方 PC / TV 同源通道，免短信安全风控",
                        color = TextSecondary,
                        fontSize = 12.sp
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = TextSecondary)
                }
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(214.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White)
                    .padding(10.dp)
            ) {
                when {
                    loadingQr -> {
                        CircularProgressIndicator(color = CoralRed, modifier = Modifier.size(34.dp))
                    }
                    qrImage != null -> {
                        Image(
                            bitmap = qrImage!!,
                            contentDescription = "红果登录二维码",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    else -> {
                        Text(
                            text = "加载失败",
                            color = Color.DarkGray,
                            fontSize = 13.sp
                        )
                    }
                }

                if (isExpired && !loadingQr) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.76f))
                            .clickable { refreshKey += 1 }
                    ) {
                        Text(
                            text = "二维码已失效\n点此刷新",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Text(
                text = statusText,
                color = if (isExpired) Color(0xFFFF8E8E) else TextPrimary,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "单机操作：先点「保存二维码到相册」，再打开红果短剧或抖音 → 扫一扫 → 右/左下角选相册图片确认登录（切回本页即自动完成）。",
                color = TextSecondary,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceElevated)
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            )

            if (!saveFeedback.isNullOrBlank()) {
                Text(
                    text = saveFeedback!!,
                    color = Color(0xFF8BE28B),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    enabled = qrSession != null && !loadingQr && !isExpired,
                    onClick = {
                        val bytes = qrSession?.pngBytes ?: return@Button
                        scope.launch {
                            val ok = saveQrPngToGallery(context, bytes)
                            savedOnce = ok
                            saveFeedback = if (ok) {
                                "二维码已保存到系统相册（Pictures/Hongguo）"
                            } else {
                                "保存相册失败，请直接截屏后去扫一扫选图"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CoralRed),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (savedOnce) "已保存相册" else "保存二维码到相册",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                OutlinedButton(
                    onClick = { refreshKey += 1 },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("刷新", color = TextPrimary, fontSize = 13.sp)
                }
            }

            if (hasHongguoApp || hasDouyinApp) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (hasHongguoApp) {
                        OutlinedButton(
                            enabled = !loadingQr,
                            onClick = {
                                val bytes = qrSession?.pngBytes
                                scope.launch {
                                    if (!savedOnce && bytes != null) {
                                        savedOnce = saveQrPngToGallery(context, bytes)
                                        if (savedOnce) {
                                            saveFeedback = "已保存二维码到相册，正在打开红果短剧..."
                                        }
                                    }
                                    launchPackageSafe(context, "com.phoenix.read")
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("保存并打开红果短剧", color = TextPrimary, fontSize = 12.sp)
                        }
                    }
                    if (hasDouyinApp) {
                        OutlinedButton(
                            enabled = !loadingQr,
                            onClick = {
                                val bytes = qrSession?.pngBytes
                                scope.launch {
                                    if (!savedOnce && bytes != null) {
                                        savedOnce = saveQrPngToGallery(context, bytes)
                                        if (savedOnce) {
                                            saveFeedback = "已保存二维码到相册，正在打开抖音..."
                                        }
                                    }
                                    launchPackageSafe(context, "com.ss.android.ugc.aweme")
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("保存并打开抖音", color = TextPrimary, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private suspend fun saveQrPngToGallery(context: Context, pngBytes: ByteArray): Boolean =
    withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
        runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "hongguo_qr_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Hongguo")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext false
            resolver.openOutputStream(uri)?.use { out ->
                out.write(pngBytes)
                out.flush()
            } ?: return@withContext false
            val done = ContentValues().apply {
                put(MediaStore.Images.Media.IS_PENDING, 0)
            }
            resolver.update(uri, done, null, null)
            true
        }.getOrDefault(false)
    }

private fun launchPackageSafe(context: Context, packageName: String) {
    runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginDialog(
    accountManager: AccountManager,
    onDismiss: () -> Unit,
    onLoginSuccess: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var pageLoading by remember { mutableStateOf(true) }
    var verifying by remember { mutableStateOf(false) }
    var hintText by remember { mutableStateOf("正在打开红果官网登录窗（支持手机号验证码）...") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    val desktopEnhanceJs = remember {
        """
        (function() {
          try {
            window.dispatchEvent(new Event('resize'));
            if (!document.getElementById('hg-mobile-login-style')) {
              var st = document.createElement('style');
              st.id = 'hg-mobile-login-style';
              st.textContent =
                '[class*="pc-backdrop"]{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;display:flex!important;align-items:center!important;justify-content:center!important;z-index:99999!important;background:rgba(0,0,0,0.72)!important;}' +
                '[class*="pc-login-dialog"]{width:400px!important;height:486px!important;transform:scale(1.9)!important;transform-origin:center center!important;border-radius:18px!important;overflow:hidden!important;}' +
                '[class*="pc-login-dialog"]::after,.web-login-union__login__scan-code,[class*="pc-dialog-decoration"]{display:none!important;}' +
                '.web-login-union__login__form{flex:none!important;width:400px!important;}';
              (document.head || document.documentElement).appendChild(st);
            }
            window.scrollTo(0, 0);
            function tryOpen() {
              if (document.querySelector('[class*="pc-login-dialog"]')) return true;
              try { window.dispatchEvent(new Event('hongguo:pc-open-login')); } catch (e) {}
              if (document.querySelector('[class*="pc-login-dialog"]')) return true;
              var btn = document.querySelector('button[class*="pc-entry"]');
              if (!btn) {
                var btns = document.querySelectorAll('button');
                for (var i = 0; i < btns.length; i++) {
                  if ((btns[i].textContent || '').trim() === '登录') { btn = btns[i]; break; }
                }
              }
              if (btn) { btn.click(); }
              return !!document.querySelector('[class*="pc-login-dialog"]');
            }
            if (!tryOpen() && !window.__hgLoginPoll) {
              var tries = 0;
              window.__hgLoginPoll = setInterval(function() {
                tries++;
                if (tryOpen() || tries >= 25) {
                  clearInterval(window.__hgLoginPoll);
                  window.__hgLoginPoll = null;
                }
              }, 350);
            }
          } catch (e) {}
        })();
        """.trimIndent()
    }

    fun inspectCookies() {
        if (verifying) return
        val cm = CookieManager.getInstance()
        val c1 = cm.getCookie("https://hongguoduanju.com/").orEmpty()
        val c2 = cm.getCookie("https://.hongguoduanju.com/").orEmpty()
        val combined = listOf(c1, c2).filter { it.isNotBlank() }.joinToString("; ")
        if (!combined.contains("sessionid=") && !combined.contains("sessionid_ss=")) return
        verifying = true
        hintText = "已检测到登录状态，正在验证..."
        scope.launch {
            when (val res = accountManager.completeWebLogin(combined)) {
                is LoginResult.Success -> {
                    verifying = false
                    onLoginSuccess()
                }
                is LoginResult.NeedsWebVerify -> {
                    verifying = false
                    hintText = res.reason
                }
                is LoginResult.Error -> {
                    verifying = false
                    hintText = res.message
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1200L)
            inspectCookies()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BgBlack)
                .statusBarsPadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SurfaceDark)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = TextPrimary)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("红果官网页面登录", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(hintText, color = TextSecondary, fontSize = 11.sp, maxLines = 2)
                }
                TextButton(
                    onClick = { webViewRef?.reload() }
                ) {
                    Text("刷新", color = TextSecondary, fontSize = 12.sp)
                }
                TextButton(
                    onClick = { webViewRef?.evaluateJavascript(desktopEnhanceJs, null) }
                ) {
                    Text("登录窗", color = TextPrimary, fontSize = 12.sp)
                }
                TextButton(
                    enabled = !verifying,
                    onClick = { inspectCookies() }
                ) {
                    Text("已完成", color = CoralRed, fontWeight = FontWeight.SemiBold)
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { ctx ->
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        WebView(ctx).apply {
                            webViewRef = this
                            cm.setAcceptThirdPartyCookies(this, true)
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = true
                            settings.setSupportZoom(true)
                            settings.builtInZoomControls = true
                            settings.displayZoomControls = false
                            settings.userAgentString = desktopUa
                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun shouldInterceptRequest(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    val url = request?.url ?: return null
                                    val host = url.host?.lowercase().orEmpty()
                                    if (request.isForMainFrame &&
                                        request.method.equals("GET", ignoreCase = true) &&
                                        (host == "hongguoduanju.com" || host == "www.hongguoduanju.com")
                                    ) {
                                        for (attempt in 0..1) {
                                            val result = runCatching {
                                                val cookieStr = cm.getCookie(url.toString()).orEmpty()
                                                val req = Request.Builder()
                                                    .url(url.toString())
                                                    .header("User-Agent", desktopUa)
                                                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                                                    .header("Accept-Language", "zh-CN,zh;q=0.9")
                                                    .apply { if (cookieStr.isNotEmpty()) header("Cookie", cookieStr) }
                                                    .get()
                                                    .build()
                                                accountManager.httpClient.newCall(req).execute().use { resp ->
                                                    if (!resp.isSuccessful) return@runCatching null
                                                    val rawHtml = resp.body?.string() ?: return@runCatching null
                                                    for (sc in resp.headers("Set-Cookie")) {
                                                        cm.setCookie(url.toString(), sc)
                                                    }
                                                    // Force desktop layout viewport >= 1024px BEFORE 3v.js hydrates so likeMobile stays false
                                                    val widthPatch =
                                                        "<meta name=\"viewport\" content=\"width=1024\">" +
                                                            "<script>(function(){" +
                                                            "function f(){try{Object.defineProperty(window,'innerWidth',{get:function(){return 1280;},configurable:true});Object.defineProperty(window,'outerWidth',{get:function(){return 1280;},configurable:true});if(document.documentElement){Object.defineProperty(document.documentElement,'clientWidth',{get:function(){return 1280;},configurable:true});}}catch(e){}}" +
                                                            "f();window.addEventListener('DOMContentLoaded',function(){f();window.dispatchEvent(new Event('resize'));});window.addEventListener('load',function(){f();window.dispatchEvent(new Event('resize'));});" +
                                                            "})();</script>"
                                                    val patched = if (Regex("<meta[^>]*name=[\"']viewport[\"'][^>]*>", RegexOption.IGNORE_CASE).containsMatchIn(rawHtml)) {
                                                        rawHtml.replace(Regex("<meta[^>]*name=[\"']viewport[\"'][^>]*>", RegexOption.IGNORE_CASE), widthPatch)
                                                    } else {
                                                        rawHtml.replaceFirst("<head>", "<head>$widthPatch", ignoreCase = true)
                                                    }
                                                    WebResourceResponse(
                                                        "text/html",
                                                        "UTF-8",
                                                        200,
                                                        "OK",
                                                        mapOf("Cache-Control" to "no-store"),
                                                        ByteArrayInputStream(patched.toByteArray(StandardCharsets.UTF_8))
                                                    )
                                                }
                                            }.getOrNull()
                                            if (result != null) return result
                                        }
                                    }
                                    return null
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val url = request?.url ?: return false
                                    val host = url.host?.lowercase().orEmpty()
                                    val allowed = url.scheme == "https" && (
                                        host == "hongguoduanju.com" || host.endsWith(".hongguoduanju.com") ||
                                            host.endsWith(".bytedance.com") || host.endsWith(".snssdk.com") ||
                                            host.endsWith(".zijieapi.com") || host.endsWith(".douyin.com") ||
                                            host.endsWith(".fqnovel.com") || host.endsWith(".bytetos.com") ||
                                            host.endsWith(".yhgfb-cn-static.com")
                                        )
                                    return !allowed
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    pageLoading = true
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    pageLoading = false
                                    hintText = "请在弹窗输入手机号与验证码登录（如未弹出可点右上角「登录窗」）"
                                    view?.evaluateJavascript(desktopEnhanceJs, null)
                                    inspectCookies()
                                }
                            }
                            clearCache(true)
                            loadUrl("https://hongguoduanju.com/")
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (pageLoading || verifying) {
                    CircularProgressIndicator(
                        color = CoralRed,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(36.dp)
                    )
                }
            }
        }
    }
}
