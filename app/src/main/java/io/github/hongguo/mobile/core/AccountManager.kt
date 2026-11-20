// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import android.webkit.CookieManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class QrCodeSession(
    val token: String,
    val pngBytes: ByteArray,
    val qrcodeIndexUrl: String,
    val expireAtSec: Long,
    val title: String,
    val desc: String
)

sealed interface QrPollResult {
    data object Waiting : QrPollResult
    data object Scanned : QrPollResult
    data object Expired : QrPollResult
    data class Confirmed(val loginResult: LoginResult) : QrPollResult
    data class Error(val message: String) : QrPollResult
}

class AccountManager(
    val httpClient: OkHttpClient,
    private val localStore: LocalStore,
    private val apiClient: HongguoApiClient
) {
    companion object {
        private const val WEB_BASE = "https://hongguoduanju.com"
        private const val WEB_UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
        private const val QR_QUERY =
            "next=https%3A%2F%2Fhongguoduanju.com%2F&aid=513755&passport_jssdk_type=normal&passport_jssdk_version=3.4.10&account_sdk_source=web"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _sessionExpired = MutableStateFlow(false)
    val sessionExpired: StateFlow<Boolean> = _sessionExpired.asStateFlow()

    // Temporary cookie jar between send_code and sms_login
    private val smsCookieMap = ConcurrentHashMap<String, String>()
    private val qrCookieMap = ConcurrentHashMap<String, String>()
    @Volatile
    private var smsCsrfToken: String = ""
    private val lastProgressUploadMs = ConcurrentHashMap<String, Long>()

    init {
        localStore.authSession.value?.let { saved ->
            if (saved.deviceId.isNotBlank() && saved.iid.isNotBlank() && saved.cdid.isNotBlank()) {
                scope.launch {
                    apiClient.registrar.pinIdentity(DeviceIdentity(saved.deviceId, saved.iid, saved.cdid))
                }
            }
        }
    }

    fun currentSession(): AuthSession? = localStore.authSession.value

    private fun normalizePhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.length == 11 && digits.startsWith("1") -> "+86$digits"
            raw.trim().startsWith("+") -> "+$digits"
            else -> "+86$digits"
        }
    }

    private fun maskPhone(raw: String): String {
        val digits = raw.filter { it.isDigit() }.removePrefix("86")
        return if (digits.length == 11) "${digits.take(3)}****${digits.takeLast(4)}" else raw
    }

    private fun absorbCookies(setCookieHeaders: List<String>, jar: MutableMap<String, String>) {
        for (line in setCookieHeaders) {
            val pair = line.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val k = pair.substring(0, eq).trim()
            val v = pair.substring(eq + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty() && !v.equals("deleted", ignoreCase = true)) {
                jar[k] = v
                if (k == "passport_csrf_token" || k == "passport_csrf_token_default") {
                    smsCsrfToken = v
                }
            }
        }
    }

    private fun formatCookieHeader(jar: Map<String, String>): String =
        jar.entries.joinToString("; ") { "${it.key}=${it.value}" }

    private fun isSecurityChallenge(data: JSONObject?, errorCode: Int, desc: String): Boolean {
        if (data != null) {
            if (data.optString("captcha").isNotBlank()) return true
            if (data.optString("verify_center_decision_conf").isNotBlank()) return true
            if (data.optString("verify_ticket").isNotBlank()) return true
            if (data.optString("desc_url").isNotBlank()) return true
        }
        if (errorCode in setOf(7, 1101, 1105, 1106, 4002, 4003, 4012, 4020, 4050, 95152)) return true
        if (desc.contains("账号安全") || desc.contains("暂时不支持") || desc.contains("风险")) return true
        return desc.contains("验证") && !desc.contains("验证码错误") && !desc.contains("已过期")
    }

    suspend fun sendSmsCode(rawPhone: String): SmsSendResult = withContext(Dispatchers.IO) {
        val normalized = normalizePhone(rawPhone)
        val digits = normalized.filter { it.isDigit() }
        if (digits.length < 11) {
            return@withContext SmsSendResult.Error("请输入正确的 11 位手机号")
        }

        val identity = runCatching { apiClient.activeIdentity(bypassCooldown = true) }.getOrNull()
            ?: return@withContext SmsSendResult.NeedsWebVerify("设备初始化受限，请直接用「官网页面登录」")

        val form = linkedMapOf(
            "mobile" to LegacySigner.mixModeXor5Hex(normalized),
            "type" to LegacySigner.mixModeXor5Hex("24"),
            "unbind_exist" to LegacySigner.mixModeXor5Hex("-1"),
            "mix_mode" to "1",
            "account_sdk_source" to "app"
        )
        val formBody = ByteHelpers.encodeQueryParams(form.entries.map { it.key to it.value })
            .toByteArray(StandardCharsets.UTF_8)

        var lastErrorMsg = "发送验证码失败，请尝试官网页面登录"
        for (host in LegacySigner.PASSPORT_HOSTS.take(4)) {
            try {
                val signed = LegacySigner.signedAppRequest(
                    apiPath = "/passport/mobile/send_code/v1/",
                    values = linkedMapOf("account_sdk_source" to "app"),
                    bodyBytes = formBody,
                    contentType = "application/x-www-form-urlencoded; charset=utf-8",
                    identity = identity,
                    host = host,
                    cookieHeader = formatCookieHeader(smsCookieMap).takeIf { it.isNotEmpty() },
                    csrfToken = smsCsrfToken.takeIf { it.isNotEmpty() }
                )
                val req = Request.Builder().url(signed.url).apply {
                    signed.headers.forEach { (k, v) -> header(k, v) }
                    post(formBody.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType()))
                }.build()
                val client = httpClient.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
                client.newCall(req).execute().use { resp ->
                    absorbCookies(resp.headers("Set-Cookie"), smsCookieMap)
                    val raw = resp.body?.string().orEmpty()
                    if (raw.isBlank()) {
                        lastErrorMsg = "短信接口返回空响应"
                        return@use
                    }
                    val json = JSONObject(raw)
                    val msg = json.optString("message", "")
                    val data = json.optJSONObject("data")
                    if (msg.equals("success", ignoreCase = true)) {
                        return@withContext SmsSendResult.Sent("验证码已发送至 ${maskPhone(rawPhone)}")
                    }
                    val errCode = data?.optInt("error_code", json.optInt("error_code", 0)) ?: 0
                    val desc = data?.optString("description").orEmpty().ifEmpty { msg }
                    if (isSecurityChallenge(data, errCode, desc)) {
                        return@withContext SmsSendResult.NeedsWebVerify(
                            desc.ifBlank { "触发官方安全验证，请直接用「官网页面登录」完成" }
                        )
                    }
                    return@withContext SmsSendResult.Error(desc.ifBlank { "发送失败 ($errCode)" })
                }
            } catch (e: Exception) {
                lastErrorMsg = e.message ?: lastErrorMsg
            }
        }
        SmsSendResult.NeedsWebVerify("$lastErrorMsg，建议直接使用「官网页面登录」")
    }

    suspend fun verifySmsLogin(rawPhone: String, rawCode: String): LoginResult = withContext(Dispatchers.IO) {
        val cleanCode = rawCode.trim()
        if (cleanCode.length < 4) {
            return@withContext LoginResult.Error("请输入收到的短信验证码")
        }
        val normalized = normalizePhone(rawPhone)
        val identity = runCatching { apiClient.activeIdentity(bypassCooldown = true) }.getOrNull()
            ?: return@withContext LoginResult.NeedsWebVerify("设备初始化受限，请改用官网页面登录")

        val form = linkedMapOf(
            "mobile" to LegacySigner.mixModeXor5Hex(normalized),
            "code" to LegacySigner.mixModeXor5Hex(cleanCode),
            "mix_mode" to "1",
            "account_sdk_source" to "app"
        )
        val formBody = ByteHelpers.encodeQueryParams(form.entries.map { it.key to it.value })
            .toByteArray(StandardCharsets.UTF_8)

        var lastErrorMsg = "登录失败"
        for (host in LegacySigner.PASSPORT_HOSTS.take(3)) {
            for (path in listOf("/passport/mobile/sms_login/", "/passport/mobile/sms_login_only/")) {
                try {
                    val signed = LegacySigner.signedAppRequest(
                        apiPath = path,
                        values = linkedMapOf("account_sdk_source" to "app"),
                        bodyBytes = formBody,
                        contentType = "application/x-www-form-urlencoded; charset=utf-8",
                        identity = identity,
                        host = host,
                        cookieHeader = formatCookieHeader(smsCookieMap).takeIf { it.isNotEmpty() },
                        csrfToken = smsCsrfToken.takeIf { it.isNotEmpty() }
                    )
                    val req = Request.Builder().url(signed.url).apply {
                        signed.headers.forEach { (k, v) -> header(k, v) }
                        post(formBody.toRequestBody("application/x-www-form-urlencoded; charset=utf-8".toMediaType()))
                    }.build()
                    val client = httpClient.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
                    client.newCall(req).execute().use { resp ->
                        absorbCookies(resp.headers("Set-Cookie"), smsCookieMap)
                        resp.header("x-tt-token")?.takeIf { it.isNotBlank() }?.let {
                            smsCookieMap["x-tt-token"] = it
                        }
                        val raw = resp.body?.string().orEmpty()
                        if (raw.isBlank()) {
                            lastErrorMsg = "登录接口返回空响应"
                            return@use
                        }
                        val json = JSONObject(raw)
                        val msg = json.optString("message", "")
                        val data = json.optJSONObject("data")
                        if (!msg.equals("success", ignoreCase = true) || data == null) {
                            val errCode = data?.optInt("error_code", 0) ?: 0
                            val desc = data?.optString("description").orEmpty().ifEmpty { msg }
                            if (isSecurityChallenge(data, errCode, desc)) {
                                return@withContext LoginResult.NeedsWebVerify(
                                    desc.ifBlank { "触发官方二次校验，请使用「官网页面登录」完成" }
                                )
                            }
                            lastErrorMsg = desc.ifBlank { "验证码登录失败 ($errCode)" }
                            return@use
                        }

                        val uid = data.optString("user_id_str")
                            .ifEmpty { data.opt("user_id")?.toString().orEmpty() }
                            .trim()
                        val cookieHeader = formatCookieHeader(smsCookieMap)
                        if (uid.isEmpty() || uid == "0" || (!cookieHeader.contains("sessionid=") && !cookieHeader.contains("sessionid_ss="))) {
                            return@withContext LoginResult.NeedsWebVerify("未获取到有效登录态，请用「官网页面登录」")
                        }
                        val profile = AccountProfile(
                            userId = uid,
                            nickname = data.optString("name").ifEmpty { data.optString("screen_name") }.ifEmpty { "红果用户" },
                            avatarUrl = data.optString("avatar_url", ""),
                            phoneMasked = maskPhone(rawPhone)
                        )
                        val session = AuthSession(
                            cookieHeader = cookieHeader,
                            csrfToken = smsCsrfToken,
                            profile = profile,
                            deviceId = identity.deviceId,
                            iid = identity.iid,
                            cdid = identity.cdid
                        )
                        localStore.saveAuthSession(session)
                        _sessionExpired.value = false
                        apiClient.registrar.pinIdentity(identity)
                        smsCookieMap.clear()
                        scope.launch { syncCloudLibrary() }
                        return@withContext LoginResult.Success(profile)
                    }
                } catch (e: Exception) {
                    lastErrorMsg = e.message ?: lastErrorMsg
                }
            }
        }
        LoginResult.Error(lastErrorMsg)
    }

    suspend fun completeWebLogin(rawCookies: String): LoginResult = withContext(Dispatchers.IO) {
        val jar = LinkedHashMap<String, String>()
        for (pair in rawCookies.split(';')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val k = pair.substring(0, eq).trim()
            val v = pair.substring(eq + 1).trim()
            if (k.isNotEmpty() && v.isNotEmpty()) jar[k] = v
        }
        if (!jar.containsKey("sessionid") && !jar.containsKey("sessionid_ss")) {
            return@withContext LoginResult.Error("尚未检测到官网登录状态")
        }
        val cookieHeader = formatCookieHeader(jar)
        try {
            val verifyUrl = "$WEB_BASE/passport/account/info/v2/?aid=513755&passport_jssdk_version=3.4.10&passport_jssdk_type=normal&account_sdk_source=web&is_from_ttaccountsdk=1&language=zh"
            val req = Request.Builder()
                .url(verifyUrl)
                .header("User-Agent", WEB_UA)
                .header("Referer", "$WEB_BASE/")
                .header("Origin", WEB_BASE)
                .header("Cookie", cookieHeader)
                .get()
                .build()
            val client = httpClient.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
            val json = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("验证失败 (HTTP ${resp.code})")
                JSONObject(resp.body?.string().orEmpty())
            }
            if (!json.optString("message").equals("success", ignoreCase = true)) {
                val desc = json.optJSONObject("data")?.optString("description").orEmpty()
                return@withContext LoginResult.Error(desc.ifEmpty { "官网登录验证失败，请重试" })
            }
            val data = json.optJSONObject("data") ?: error("账号信息为空")
            val uid = data.optString("user_id_str")
                .ifEmpty { data.opt("user_id")?.toString().orEmpty() }
                .trim()
            if (uid.isEmpty() || uid == "0") {
                return@withContext LoginResult.Error("未能识别红果账号编号")
            }
            val identity = runCatching { apiClient.activeIdentity(bypassCooldown = true) }.getOrNull()
            val profile = AccountProfile(
                userId = uid,
                nickname = data.optString("name").ifEmpty { data.optString("screen_name") }.ifEmpty { "红果用户" },
                avatarUrl = data.optString("avatar_url", ""),
                phoneMasked = data.optString("mobile", "")
            )
            val session = AuthSession(
                cookieHeader = cookieHeader,
                csrfToken = jar["passport_csrf_token"] ?: jar["passport_csrf_token_default"] ?: "",
                profile = profile,
                deviceId = identity?.deviceId.orEmpty(),
                iid = identity?.iid.orEmpty(),
                cdid = identity?.cdid.orEmpty()
            )
            localStore.saveAuthSession(session)
            _sessionExpired.value = false
            if (identity != null) apiClient.registrar.pinIdentity(identity)
            scope.launch { syncCloudLibrary() }
            LoginResult.Success(profile)
        } catch (e: Exception) {
            LoginResult.Error(e.message ?: "官网登录校验失败")
        }
    }

    suspend fun fetchQrLoginCode(): Result<QrCodeSession> = withContext(Dispatchers.IO) {
        runCatching {
            qrCookieMap.clear()
            val url = "$WEB_BASE/passport/web/get_qrcode/?$QR_QUERY"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "$WEB_BASE/")
                .header("Origin", WEB_BASE)
                .get()
                .build()
            val client = httpClient.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
            val json = client.newCall(req).execute().use { resp ->
                absorbCookies(resp.headers("Set-Cookie"), qrCookieMap)
                if (!resp.isSuccessful) error("获取二维码失败 (HTTP ${resp.code})")
                JSONObject(resp.body?.string().orEmpty())
            }
            if (!json.optString("message").equals("success", ignoreCase = true)) {
                val desc = json.optJSONObject("data")?.optString("description").orEmpty()
                error(desc.ifEmpty { "获取二维码失败" })
            }
            val data = json.optJSONObject("data") ?: error("二维码响应为空")
            val token = data.optString("token", "").trim()
            val qrcodeRaw = data.optString("qrcode", "").trim()
            if (token.isEmpty() || qrcodeRaw.isEmpty()) {
                error("二维码数据缺失")
            }
            val base64Part = qrcodeRaw.substringAfter("base64,", qrcodeRaw)
            val pngBytes = Base64.getDecoder().decode(base64Part)
            val cw = data.optJSONObject("copywriting")
            val title = cw?.optString("title").orEmpty().ifEmpty { "请使用已登录账号的设备扫码" }
            val rawDesc = cw?.optString("desc").orEmpty()
            val cleanDesc = rawDesc.replace(Regex("\\[/?color[^\\]]*]"), "")
                .ifEmpty { "请使用红果短剧 App 或抖音 App 扫码登录" }
            QrCodeSession(
                token = token,
                pngBytes = pngBytes,
                qrcodeIndexUrl = data.optString("qrcode_index_url", ""),
                expireAtSec = data.optLong("expire_time", 0L),
                title = title,
                desc = cleanDesc
            )
        }
    }

    suspend fun pollQrLoginStatus(token: String): QrPollResult = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext QrPollResult.Error("二维码令牌无效")
        try {
            val encodedToken = URLEncoder.encode(token, "UTF-8")
            val url = "$WEB_BASE/passport/web/check_qrconnect/?$QR_QUERY&token=$encodedToken"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "$WEB_BASE/")
                .header("Origin", WEB_BASE)
                .apply {
                    val cookieStr = formatCookieHeader(qrCookieMap)
                    if (cookieStr.isNotEmpty()) header("Cookie", cookieStr)
                }
                .get()
                .build()
            val client = httpClient.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
            val json = client.newCall(req).execute().use { resp ->
                absorbCookies(resp.headers("Set-Cookie"), qrCookieMap)
                if (!resp.isSuccessful) {
                    return@withContext QrPollResult.Error("扫码状态查询失败 (HTTP ${resp.code})")
                }
                JSONObject(resp.body?.string().orEmpty())
            }
            val data = json.optJSONObject("data")
                ?: return@withContext QrPollResult.Waiting
            val errCode = data.optInt("error_code", 0)
            val status = data.optString("status", "").lowercase()
            when {
                status == "new" -> QrPollResult.Waiting
                status == "scanned" -> QrPollResult.Scanned
                status == "expired" || errCode == 4001 -> QrPollResult.Expired
                status == "refused" -> QrPollResult.Error("已取消扫码登录")
                status == "confirmed" -> {
                    val redirectUrl = data.optString("redirect_url", "").trim()
                    if (redirectUrl.isNotEmpty() &&
                        !qrCookieMap.containsKey("sessionid") &&
                        !qrCookieMap.containsKey("sessionid_ss")
                    ) {
                        followQrRedirects(redirectUrl)
                    }
                    val loginRes = completeWebLogin(formatCookieHeader(qrCookieMap))
                    QrPollResult.Confirmed(loginRes)
                }
                errCode != 0 -> {
                    val desc = data.optString("description", "").ifEmpty { "扫码异常 ($errCode)" }
                    QrPollResult.Error(desc)
                }
                else -> QrPollResult.Waiting
            }
        } catch (e: Exception) {
            QrPollResult.Error(e.message ?: "扫码状态检测失败")
        }
    }

    private fun followQrRedirects(startUrl: String) {
        val noRedirectClient = httpClient.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
        var currentUrl = startUrl
        repeat(6) {
            if (currentUrl.isBlank()) return
            val req = Request.Builder()
                .url(currentUrl)
                .header("User-Agent", DESKTOP_UA)
                .header("Referer", "$WEB_BASE/")
                .apply {
                    val cookieStr = formatCookieHeader(qrCookieMap)
                    if (cookieStr.isNotEmpty()) header("Cookie", cookieStr)
                }
                .get()
                .build()
            noRedirectClient.newCall(req).execute().use { resp ->
                absorbCookies(resp.headers("Set-Cookie"), qrCookieMap)
                val loc = resp.header("Location")?.trim().orEmpty()
                if (resp.code in 300..399 && loc.isNotEmpty()) {
                    currentUrl = resp.request.url.resolve(loc)?.toString().orEmpty()
                } else {
                    return
                }
            }
        }
    }

    fun verifySessionHealthAsync() {
        if (localStore.authSession.value == null) return
        scope.launch { verifySessionHealth() }
    }

    suspend fun verifySessionHealth(): Boolean = withContext(Dispatchers.IO) {
        val session = localStore.authSession.value
        if (session == null) {
            _sessionExpired.value = false
            return@withContext false
        }
        try {
            val verifyUrl = "$WEB_BASE/passport/account/info/v2/?aid=513755&passport_jssdk_version=3.4.10&passport_jssdk_type=normal&account_sdk_source=web&is_from_ttaccountsdk=1&language=zh"
            val req = Request.Builder()
                .url(verifyUrl)
                .header("User-Agent", WEB_UA)
                .header("Referer", "$WEB_BASE/")
                .header("Origin", WEB_BASE)
                .header("Cookie", session.cookieHeader)
                .get()
                .build()
            val client = httpClient.newBuilder().callTimeout(10, TimeUnit.SECONDS).build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    _sessionExpired.value = true
                    return@withContext false
                }
                if (!resp.isSuccessful) return@withContext true
                val raw = resp.body?.string().orEmpty()
                if (raw.isBlank()) return@withContext true
                val json = JSONObject(raw)
                val msg = json.optString("message", "")
                val data = json.optJSONObject("data")
                val uid = data?.optString("user_id_str")
                    ?.ifEmpty { data.opt("user_id")?.toString().orEmpty() }
                    ?.trim()
                    .orEmpty()
                val valid = msg.equals("success", ignoreCase = true) && uid.isNotEmpty() && uid != "0"
                _sessionExpired.value = !valid
                valid
            }
        } catch (_: Exception) {
            // Do not mark session expired on transient network failures
            true
        }
    }

    fun toggleFavoriteAndSync(summary: DramaSummary): Boolean {
        val added = localStore.toggleFavorite(summary)
        syncFavoriteToggleAsync(summary.seriesId, added)
        return added
    }

    fun syncFavoriteToggleAsync(seriesId: String, added: Boolean) {
        val session = localStore.authSession.value ?: return
        if (!Regex("^\\d{1,30}$").matches(seriesId)) return
        scope.launch {
            runCatching {
                val identity = if (session.deviceId.isNotBlank() && session.iid.isNotBlank() && session.cdid.isNotBlank()) {
                    DeviceIdentity(session.deviceId, session.iid, session.cdid)
                } else {
                    apiClient.activeIdentity()
                }
                val nowSec = System.currentTimeMillis() / 1000L
                val opType = if (added) 0 else 1 // VideoShelfOperateType: AddShelf=0, DelShelf=1
                val videoItem = JSONObject().apply {
                    put("book_id", seriesId)
                    put("modify_time", nowSec)
                    put("video_shelf_operate_type", opType)
                    put("book_type", 2)
                }
                val bodyBytes = JSONObject().apply {
                    put("book_id_list", JSONArray().put(seriesId))
                    put("modify_time", JSONArray().put(nowSec))
                    put("is_cancelled", !added)
                    put("update_bookshelf_video_list", JSONArray().put(videoItem))
                }.toString().toByteArray(StandardCharsets.UTF_8)

                for (path in listOf(
                    "/reading/bookapi/bookshelf/video/update/v",
                    "/reading/bookapi/bookshelf/video/update/v1/"
                )) {
                    val signed = LegacySigner.signedAppRequest(
                        apiPath = path,
                        bodyBytes = bodyBytes,
                        identity = identity,
                        host = "api5-normal-sinfonlineb.fqnovel.com",
                        cookieHeader = session.cookieHeader
                    )
                    val req = Request.Builder().url(signed.url).apply {
                        signed.headers.forEach { (k, v) -> header(k, v) }
                        post(bodyBytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    }.build()
                    val done = httpClient.newCall(req).execute().use { resp ->
                        val raw = resp.body?.string().orEmpty()
                        resp.isSuccessful && raw.isNotBlank()
                    }
                    if (done) break
                }
            }
        }
    }

    suspend fun logout() {
        localStore.clearAuthSession()
        _sessionExpired.value = false
        smsCookieMap.clear()
        qrCookieMap.clear()
        withContext(Dispatchers.Main) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
    }

    suspend fun syncCloudLibrary(): Boolean = withContext(Dispatchers.IO) {
        val session = localStore.authSession.value ?: return@withContext false
        if (_syncing.value) return@withContext false
        _syncing.value = true
        try {
            verifySessionHealth()
            val identity = if (session.deviceId.isNotBlank() && session.iid.isNotBlank() && session.cdid.isNotBlank()) {
                DeviceIdentity(session.deviceId, session.iid, session.cdid)
            } else {
                apiClient.activeIdentity()
            }

            // 1. Sync watch history
            runCatching {
                val signed = LegacySigner.signedAppRequest(
                    apiPath = "/reading/bookapi/read_history/list/v1/",
                    values = linkedMapOf(
                        "book_type" to "2",
                        "limit" to "0",
                        "offset" to "0",
                        "last_min_read_timestamp_ms" to "0",
                        "full_field" to "true",
                        "is_first_load" to "true",
                        "query_soft_deleted" to "false"
                    ),
                    identity = identity,
                    host = "api5-normal-sinfonlinea.fqnovel.com",
                    cookieHeader = session.cookieHeader
                )
                val req = Request.Builder().url(signed.url).apply {
                    signed.headers.forEach { (k, v) -> header(k, v) }
                    get()
                }.build()
                httpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && raw.isNotBlank()) {
                        val json = JSONObject(raw)
                        if (json.optInt("code", -1) == 0) {
                            val list = json.optJSONObject("data")?.optJSONArray("data_list") ?: JSONArray()
                            val entries = ArrayList<WatchHistoryEntry>()
                            for (i in 0 until list.length()) {
                                val row = list.optJSONObject(i) ?: continue
                                if (row.optBoolean("is_delete", false)) continue
                                val sid = row.optString("book_id_str")
                                    .ifEmpty { row.optString("series_id") }
                                    .ifEmpty { row.opt("book_id")?.toString().orEmpty() }
                                    .trim()
                                if (!Regex("^\\d{1,30}$").matches(sid)) continue
                                val title = row.optString("series_title").ifEmpty { row.optString("book_name") }.ifEmpty { "剧集 $sid" }
                                val cover = row.optString("series_cover_url").ifEmpty { row.optString("thumb_url") }.ifEmpty { row.optString("cover") }
                                val epIdx = row.optInt("vid_index", 1).coerceAtLeast(1)
                                val posMs = row.optLong("current_play_position", row.optLong("current_play_time", 0L)).coerceAtLeast(0L)
                                val durMs = row.optLong("duration", row.optLong("current_total_time", 0L)).coerceAtLeast(0L)
                                val rawTime = row.optLong("read_timestamp_ms", row.optLong("last_watch_time", System.currentTimeMillis()))
                                val updatedAt = if (rawTime in 1..9_999_999_999L) rawTime * 1000L else rawTime
                                val totalEps = row.optInt("episode_cnt", row.optInt("episode_count", 0))
                                entries.add(
                                    WatchHistoryEntry(
                                        seriesId = sid,
                                        title = title,
                                        cover = cover,
                                        totalEpisodes = totalEps,
                                        lastEpisodeIndex = epIdx,
                                        positionMs = posMs,
                                        durationMs = durMs,
                                        updatedAt = updatedAt
                                    )
                                )
                            }
                            localStore.mergeRemoteHistory(entries)
                        }
                    }
                }
            }

            // 2. Sync favorites/bookshelf (best-effort)
            runCatching {
                val signed = LegacySigner.signedAppRequest(
                    apiPath = "/reading/bookapi/bookshelf/video/list/v",
                    values = linkedMapOf("offset" to "0", "limit" to "50"),
                    identity = identity,
                    host = "api5-normal-sinfonlineb.fqnovel.com",
                    cookieHeader = session.cookieHeader
                )
                val req = Request.Builder().url(signed.url).apply {
                    signed.headers.forEach { (k, v) -> header(k, v) }
                    get()
                }.build()
                httpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (resp.isSuccessful && raw.isNotBlank()) {
                        val json = JSONObject(raw)
                        if (json.optInt("code", -1) == 0) {
                            val d = json.optJSONObject("data")
                            val arr = d?.optJSONArray("video_list")
                                ?: d?.optJSONArray("data_list")
                                ?: d?.optJSONArray("books")
                                ?: JSONArray()
                            val favs = ArrayList<FavoriteEntry>()
                            for (i in 0 until arr.length()) {
                                val row = arr.optJSONObject(i) ?: continue
                                val sid = row.optString("series_id")
                                    .ifEmpty { row.optString("book_id_str") }
                                    .ifEmpty { row.opt("book_id")?.toString().orEmpty() }
                                    .trim()
                                if (!Regex("^\\d{1,30}$").matches(sid)) continue
                                val title = row.optString("series_title")
                                    .ifEmpty { row.optString("series_name") }
                                    .ifEmpty { row.optString("book_name") }
                                    .ifEmpty { row.optString("title") }
                                if (title.isBlank()) continue
                                favs.add(
                                    FavoriteEntry(
                                        seriesId = sid,
                                        title = title,
                                        cover = row.optString("series_cover").ifEmpty { row.optString("cover") },
                                        totalEpisodes = row.optInt("episode_cnt", 0),
                                        description = row.optString("series_intro", "")
                                    )
                                )
                            }
                            localStore.mergeRemoteFavorites(favs)
                        }
                    }
                }
            }
            true
        } finally {
            _syncing.value = false
        }
    }

    fun reportWatchProgressAsync(
        seriesId: String,
        vid: String,
        episodeIndex: Int,
        positionMs: Long
    ) {
        val session = localStore.authSession.value ?: return
        if (positionMs < 3000L || vid.isBlank()) return
        val now = System.currentTimeMillis()
        val prev = lastProgressUploadMs[seriesId] ?: 0L
        if (now - prev < 10_000L) return
        lastProgressUploadMs[seriesId] = now

        scope.launch {
            runCatching {
                val identity = if (session.deviceId.isNotBlank() && session.iid.isNotBlank() && session.cdid.isNotBlank()) {
                    DeviceIdentity(session.deviceId, session.iid, session.cdid)
                } else {
                    apiClient.activeIdentity()
                }
                val body = JSONObject().apply {
                    put("series_id", seriesId)
                    put("book_id", seriesId)
                    put("vid", vid)
                    put("item_id", vid)
                    put("vid_index", episodeIndex)
                    put("current_play_position", positionMs)
                    put("current_play_time", positionMs)
                    put("read_timestamp_ms", now)
                }.toString().toByteArray(StandardCharsets.UTF_8)
                val signed = LegacySigner.signedAppRequest(
                    apiPath = "/reading/bookapi/read_progress/upload/v",
                    bodyBytes = body,
                    identity = identity,
                    host = "api5-normal-sinfonlineb.fqnovel.com",
                    cookieHeader = session.cookieHeader
                )
                val req = Request.Builder().url(signed.url).apply {
                    signed.headers.forEach { (k, v) -> header(k, v) }
                    post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
                }.build()
                httpClient.newCall(req).execute().close()
            }
        }
    }
}
