// SPDX-License-Identifier: GPL-3.0-only
// Port of jackotom/hongguo-drama src/native/playback-device.js.
package io.github.hongguo.mobile.core

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

class DeviceRegistrar(
    private val httpClient: OkHttpClient,
    private val identityFile: File? = null
) {
    companion object {
        private val REGISTER_URLS = listOf(
            "https://log5-applog-lq.fqnovel.com/service/2/device_register/?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1",
            "https://log5-applog-lf.fqnovel.com/service/2/device_register/?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1",
            "https://log5-applog-hl.fqnovel.com/service/2/device_register/?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin&use_store_region_cookie=1"
        )
        private val KDF_SALT = ByteHelpers.hexToBytes(
            "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524" +
                "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
        )
        private const val REGISTER_UA =
            "com.phoenix.read/72232 (Linux; U; Android 16; zh_CN; 25053RT47C; Build/BP2A.250605.031.A3)"
    }

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var cachedIdentity: DeviceIdentity? = null
    private var retryAfterMs = 0L

    init {
        val sourceFile = identityFile
        if (sourceFile != null && sourceFile.exists()) {
            runCatching {
                val loaded = json.decodeFromString<DeviceIdentity>(
                    sourceFile.readText(StandardCharsets.UTF_8)
                )
                if (isValidId(loaded.deviceId) && isValidId(loaded.iid) && loaded.cdid.isNotBlank()) {
                    cachedIdentity = loaded
                }
            }
        }
    }

    private fun isValidId(str: String): Boolean {
        val trimmed = str.trim()
        return Regex("^\\d{1,30}$").matches(trimmed) && trimmed.any { it in '1'..'9' }
    }

    private fun persistIdentity(identity: DeviceIdentity) {
        val target = identityFile ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeText(json.encodeToString(identity), StandardCharsets.UTF_8)
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        }
    }

    private fun responseId(obj: JSONObject, name: String): String {
        val preferred = obj.opt("${name}_str")
        val value = if (preferred == null || preferred == JSONObject.NULL || preferred.toString().isEmpty()) {
            obj.opt(name)
        } else preferred
        if (value == null || value == JSONObject.NULL) return ""
        val str = value.toString().trim()
        return if (isValidId(str)) str else ""
    }

    suspend fun getOrRegister(
        forceFresh: Boolean = false,
        bypassCooldown: Boolean = false
    ): DeviceIdentity = mutex.withLock {
        if (!forceFresh) {
            cachedIdentity?.let { return it }
        }
        val now = System.currentTimeMillis()
        if (!bypassCooldown && now < retryAfterMs) {
            error("匿名播放设备注册冷却中，请稍后重试")
        }
        val identity = registerInternal()
        cachedIdentity = identity
        retryAfterMs = 0L
        persistIdentity(identity)
        return identity
    }

    suspend fun pinIdentity(identity: DeviceIdentity) = mutex.withLock {
        cachedIdentity = identity
        retryAfterMs = 0L
    }

    fun registerFresh(): DeviceIdentity {
        return registerInternal()
    }

    private fun registerInternal(): DeviceIdentity {
        val now = System.currentTimeMillis()
        val cdid = UUID.randomUUID().toString()
        val custom = JSONObject().apply {
            put("account_region_src", "fallback")
            put("account_region", "cn")
            put("host_bit", 64)
            put("dragon_device_type", "phone")
        }
        val header = JSONObject().apply {
            put("display_name", "红果免费短剧")
            put("update_version_code", 73932)
            put("manifest_version_code", 73932)
            put("app_version_minor", "7.3.9.32")
            put("aid", 8662)
            put("channel", "update_64")
            put("package", "com.phoenix.read")
            put("app_version", "7.3.9.32")
            put("version_code", 73932)
            put("sdk_version", "3.7.0-rc.34-fanqie-xiaoshuo")
            put("sdk_target_version", 29)
            put("git_hash", "3a88f4e")
            put("os", "Android")
            put("os_version", "16")
            put("os_api", 36)
            put("device_model", "25053RT47C")
            put("device_brand", "Redmi")
            put("device_manufacturer", "Redmi")
            put("cpu_abi", "arm64-v8a")
            put("release_build", "b5e187f")
            put("density_dpi", 520)
            put("display_density", "mdpi")
            put("resolution", "1280x2772")
            put("language", "zh")
            put("timezone", 8)
            put("access", "wifi")
            put("not_request_sender", 0)
            put("carrier", "CHINA MOBILE")
            put("mcc_mnc", "46000")
            put("rom", "eng.root.20231116.160340")
            put("rom_version", "W528JS release-keys")
            put("cdid", cdid)
            put("sig_hash", "56a962410c494bbaf0b58dba20cae56f")
            put("openudid", ByteHelpers.bytesToHex(ByteHelpers.randomBytes(8)))
            put("clientudid", UUID.randomUUID().toString())
            put("ipv6_list", JSONArray())
            put("region", "CN")
            put("tz_name", "Asia/Shanghai")
            put("tz_offset", 28800)
            put("sim_region", "cn")
            put("sim_serial_number", JSONArray())
            put("oaid_may_support", false)
            put("req_id", UUID.randomUUID().toString())
            put("device_platform", "android")
            put("custom", custom)
            put("pre_installed_channel", "")
            put("apk_first_install_time", now)
            put("is_system_app", 0)
            put("sdk_flavor", "china")
            put("guest_mode", 0)
        }
        val payload = JSONObject().apply {
            put("magic_tag", "ss_app_log")
            put("header", header)
            put("_gen_time", now)
        }.toString()

        val compressed = ByteHelpers.gzip(payload.toByteArray(StandardCharsets.UTF_8))
        val random = ByteHelpers.randomBytes(32)
        val derived = ByteHelpers.sha512(ByteHelpers.concat(ByteHelpers.sha512(random), KDF_SALT))
        val plaintext = ByteHelpers.concat(ByteHelpers.sha512(compressed), compressed)
        val encrypted = ByteHelpers.aesCbcEncrypt(
            key = derived.copyOfRange(0, 16),
            iv = derived.copyOfRange(16, 32),
            data = plaintext,
            padding = true
        )
        val data = ByteHelpers.concat(ByteHelpers.hexToBytes("746305100000"), random, encrypted)
        val stub = ByteHelpers.bytesToHex(ByteHelpers.md5(data), uppercase = true)

        val client = httpClient.newBuilder()
            .callTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()

        var lastErr: Exception? = null
        for (regUrl in REGISTER_URLS) {
            val request = Request.Builder()
                .url(regUrl)
                .post(data.toRequestBody("application/octet-stream;tt-data=a".toMediaType()))
                .header("User-Agent", REGISTER_UA)
                .header("Accept", "application/json")
                .header("log-encode-type", "gzip")
                .header("x-ss-dp", "8662")
                .header("x-ss-req-ticket", now.toString())
                .header("x-vc-bdturing-sdk-version", "4.0.3.cn")
                .header("lc", "101")
                .header("sdk-version", "2")
                .header("passport-sdk-version", "5051452")
                .header("x-neptune", "-8|50:51:59:30:40:47:49:39")
                .header("x-ss-stub", stub)
                .build()
            try {
                client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        error("设备注册失败 (HTTP ${resp.code})")
                    }
                    val raw = resp.body?.string().orEmpty()
                    if (raw.isBlank()) {
                        error("设备注册返回空响应")
                    }
                    val json = JSONObject(raw)
                    val code = json.optInt("code", json.optInt("Code", 0))
                    if (code != 0) {
                        error("设备注册被服务拒绝")
                    }
                    val deviceId = responseId(json, "device_id")
                    val iid = responseId(json, "install_id")
                    if (deviceId.isEmpty() || iid.isEmpty()) {
                        error("设备注册返回无效标识")
                    }
                    return DeviceIdentity(deviceId = deviceId, iid = iid, cdid = cdid)
                }
            } catch (e: Exception) {
                lastErr = e
            }
        }
        retryAfterMs = System.currentTimeMillis() + 3_000L
        throw (lastErr ?: IllegalStateException("设备注册失败"))
    }
}
