// SPDX-License-Identifier: GPL-3.0-only
package io.github.hongguo.mobile.core

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okio.Buffer
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DataProbeTest {

    private data class CapturedResponse(
        val seq: String,
        val method: String,
        val url: String,
        val host: String,
        val path: String,
        val code: Int,
        val bytes: Int,
        val file: File,
        val isJson: Boolean
    )

    private data class FieldStats(
        var count: Int = 0,
        var sample: String = ""
    )

    @Test
    fun probeLiveApiSchemasAndFields() {
        Assume.assumeTrue("Set HG_PROBE=1 to run live probe", System.getenv("HG_PROBE") == "1")

        val outPath = System.getenv("HG_PROBE_OUT")?.takeIf { it.isNotBlank() }
            ?: File(System.getProperty("java.io.tmpdir"), "hongguo-probe").absolutePath
        val outDir = File(outPath)
        outDir.mkdirs()
        outDir.listFiles()?.forEach { it.deleteRecursively() }

        val indexFile = File(outDir, "index.txt")
        val errorsFile = File(outDir, "errors.txt")
        val counter = AtomicInteger(0)
        val captured = java.util.Collections.synchronizedList(ArrayList<CapturedResponse>())

        val probeInterceptor = Interceptor { chain ->
            val request = chain.request()
            val seq = String.format(Locale.US, "%03d", counter.incrementAndGet())
            val url = request.url
            val host = url.host
            val path = url.encodedPath
            val safePath = path.replace(Regex("[^A-Za-z0-9]"), "_").trim('_').ifEmpty { "root" }

            runCatching {
                val reqBody = request.body
                if (request.method == "POST" && reqBody != null) {
                    val len = reqBody.contentLength()
                    if (len in 0..20_480) {
                        val buf = Buffer()
                        reqBody.writeTo(buf)
                        val reqText = buf.readString(StandardCharsets.UTF_8)
                        File(outDir, "${seq}_req.txt").writeText(
                            "${request.method} $url\n\n$reqText",
                            StandardCharsets.UTF_8
                        )
                    }
                }
            }

            val response = try {
                chain.proceed(request)
            } catch (t: Throwable) {
                runCatching {
                    synchronized(indexFile) {
                        indexFile.appendText(
                            "$seq | ${request.method} | $url | ERR | 0 | ${t.javaClass.simpleName}: ${t.message}\n",
                            StandardCharsets.UTF_8
                        )
                    }
                }
                throw t
            }

            runCatching {
                val peek = response.peekBody(Long.MAX_VALUE)
                val bodyBytes = peek.bytes()
                val bodyText = String(bodyBytes, StandardCharsets.UTF_8)
                val ct = response.header("Content-Type").orEmpty().lowercase(Locale.US)
                val trimmed = bodyText.trimStart()
                val isJson = ct.contains("json") || trimmed.startsWith("{") || trimmed.startsWith("[")
                val ext = if (isJson) "json" else "html"
                val outFile = File(outDir, "${seq}_${host}_${safePath}.$ext")
                outFile.writeBytes(bodyBytes)

                synchronized(indexFile) {
                    indexFile.appendText(
                        "$seq | ${request.method} | $url | ${response.code} | ${bodyBytes.size} | ${outFile.name}\n",
                        StandardCharsets.UTF_8
                    )
                }
                captured.add(
                    CapturedResponse(
                        seq = seq,
                        method = request.method,
                        url = url.toString(),
                        host = host,
                        path = path,
                        code = response.code,
                        bytes = bodyBytes.size,
                        file = outFile,
                        isJson = isJson
                    )
                )
            }

            response
        }

        val httpClient = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(probeInterceptor)
            .build()

        val apiClient = HongguoApiClient(httpClient)
        val errors = StringBuilder()

        fun logError(step: String, t: Throwable) {
            errors.append("[$step] ${t.javaClass.name}: ${t.message}\n")
            errors.append(t.stackTraceToString().lines().take(8).joinToString("\n")).append("\n\n")
        }

        val collectedItems = ArrayList<DramaSummary>()
        val realCatalogGenres = ArrayList<GenreItem>()

        runBlocking {
            // 1. Recommend feeds
            runCatching {
                val res = apiClient.fetchRecommendFeed("hot-drama", 1, forceRefresh = true)
                println("PROBE hot-drama feed items=${res.items.size}")
                collectedItems.addAll(res.items)
            }.onFailure { logError("fetchRecommendFeed(hot-drama)", it) }

            runCatching {
                val res = apiClient.fetchRecommendFeed("real-drama", 1, forceRefresh = true)
                println("PROBE real-drama feed items=${res.items.size}")
                collectedItems.addAll(res.items)
            }.onFailure { logError("fetchRecommendFeed(real-drama)", it) }

            // 2. Catalog REAL_DRAMA + first 2 genres
            runCatching {
                val res = apiClient.fetchCatalog(CatalogCategory.REAL_DRAMA, "", 1, forceRefresh = true)
                println("PROBE catalog REAL_DRAMA items=${res.items.size} genres=${res.genres.size}")
                collectedItems.addAll(res.items)
                realCatalogGenres.addAll(res.genres)
            }.onFailure { logError("fetchCatalog(REAL_DRAMA, all)", it) }

            for (g in realCatalogGenres.take(2)) {
                runCatching {
                    val res = apiClient.fetchCatalog(CatalogCategory.REAL_DRAMA, g.slug, 1, forceRefresh = true)
                    println("PROBE catalog REAL_DRAMA(${g.slug}=${g.label}) items=${res.items.size}")
                    collectedItems.addAll(res.items)
                }.onFailure { logError("fetchCatalog(REAL_DRAMA, ${g.slug})", it) }
            }

            // 3. Catalog HOT_REAL ranking
            runCatching {
                val res = apiClient.fetchCatalog(CatalogCategory.HOT_REAL, "", 1, forceRefresh = true)
                println("PROBE catalog HOT_REAL items=${res.items.size}")
                collectedItems.addAll(res.items)
            }.onFailure { logError("fetchCatalog(HOT_REAL)", it) }

            // 4. Pick up to 8 diverse seriesIds (mix of low-followed/non-hot & top/hot) and fetch details
            val distinct = collectedItems.distinctBy { it.seriesId }
            val pickedIds = LinkedHashSet<String>()
            // Add up to 3 from lower followedCount / newer-looking items (exclude 0 only if non-zero exist, also allow 0 from catalog)
            val withStats = distinct.filter { it.followedCount > 0L }.sortedBy { it.followedCount }
            withStats.take(3).forEach { pickedIds.add(it.seriesId) }
            // Add up to 3 from highest followedCount / ranked items
            distinct.filter { it.rank != null }.take(2).forEach { pickedIds.add(it.seriesId) }
            withStats.reversed().take(2).forEach { pickedIds.add(it.seriesId) }
            // Fill up to 8 from remaining distinct items
            for (item in distinct) {
                if (pickedIds.size >= 8) break
                pickedIds.add(item.seriesId)
            }

            println("PROBE fetching details for ${pickedIds.size} series: $pickedIds")
            for ((idx, sid) in pickedIds.withIndex()) {
                if (idx > 0) delay(800)
                runCatching {
                    val detail = apiClient.fetchSeriesDetail(sid, forceRefresh = true)
                    println("PROBE detail($sid): title=${detail.title} eps=${detail.episodes.size} followed=${detail.followedCount}")
                }.onFailure { logError("fetchSeriesDetail($sid)", it) }
            }
        }

        if (errors.isNotEmpty()) {
            errorsFile.writeText(errors.toString(), StandardCharsets.UTF_8)
        }

        // Analyze captured JSON files
        val jsonFiles = captured.filter { it.isJson }
        val apiFields = TreeMap<String, TreeMap<String, FieldStats>>()

        for (entry in jsonFiles) {
            val apiName = entry.path.replace(Regex("[^A-Za-z0-9]"), "_").trim('_').ifEmpty { "root" }
            val fieldMap = apiFields.getOrPut(apiName) { TreeMap() }
            val rawText = entry.file.readText(StandardCharsets.UTF_8).trim()
            runCatching {
                when {
                    rawText.startsWith("{") -> walkJsonObject(JSONObject(rawText), "", fieldMap)
                    rawText.startsWith("[") -> walkJsonArray(JSONArray(rawText), "[]", fieldMap)
                }
            }.onFailure {
                errorsFile.appendText("[parse ${entry.file.name}] ${it.message}\n", StandardCharsets.UTF_8)
            }
        }

        // Write fields_<api>.txt
        for ((apiName, map) in apiFields) {
            val sb = StringBuilder()
            sb.append("# API: $apiName (${map.size} leaf paths)\n\n")
            for ((path, st) in map) {
                sb.append("$path  [count=${st.count}]  sample=${st.sample}\n")
            }
            File(outDir, "fields_${apiName}.txt").writeText(sb.toString(), StandardCharsets.UTF_8)
        }

        // Keyword hits -> hits.txt
        val keywords = listOf(
            "time", "date", "online", "publish", "create", "update",
            "status", "serial", "finish", "end", "complete",
            "play", "view", "watch",
            "category", "genre", "theme", "role", "epoch", "tag",
            "gender"
        )
        val hitsSb = StringBuilder()
        hitsSb.append("# Keyword hits across all captured JSON APIs\n")
        hitsSb.append("# Keywords: ${keywords.joinToString(", ")}\n\n")
        for ((apiName, map) in apiFields) {
            val matched = map.entries.filter { (path, _) ->
                val lower = path.lowercase(Locale.US)
                keywords.any { kw -> lower.contains(kw) }
            }
            if (matched.isNotEmpty()) {
                hitsSb.append("=== $apiName (${matched.size} hits) ===\n")
                for ((path, st) in matched) {
                    hitsSb.append("  $path  [count=${st.count}]  sample=${st.sample}\n")
                }
                hitsSb.append("\n")
            }
        }
        File(outDir, "hits.txt").writeText(hitsSb.toString(), StandardCharsets.UTF_8)

        // Selectors -> selectors.txt
        writeSelectorsReport(jsonFiles, File(outDir, "selectors.txt"))

        // Sample dramas -> sample_dramas.txt
        writeSampleDramasReport(jsonFiles, File(outDir, "sample_dramas.txt"))

        println("PROBE DONE: totalRequests=${captured.size}, jsonFiles=${jsonFiles.size}, outDir=${outDir.absolutePath}")
    }

    private fun recordField(map: TreeMap<String, FieldStats>, path: String, value: String) {
        val clean = value.replace(Regex("[\\r\\n]+"), " ").trim()
        val truncated = if (clean.length > 100) clean.take(100) + "..." else clean
        val st = map.getOrPut(path) { FieldStats() }
        st.count += 1
        if (st.sample.isEmpty() && truncated.isNotEmpty() && truncated != "null") {
            st.sample = truncated
        }
    }

    private fun walkJsonObject(obj: JSONObject, prefix: String, map: TreeMap<String, FieldStats>) {
        val keys = obj.keys()
        if (!keys.hasNext()) {
            if (prefix.isNotEmpty()) recordField(map, prefix, "{}")
            return
        }
        while (keys.hasNext()) {
            val key = keys.next()
            val nextPath = if (prefix.isEmpty()) key else "$prefix.$key"
            walkValue(obj.opt(key), nextPath, map)
        }
    }

    private fun walkJsonArray(arr: JSONArray, prefix: String, map: TreeMap<String, FieldStats>) {
        if (arr.length() == 0) {
            recordField(map, prefix, "[]")
            return
        }
        val itemPath = "$prefix[]"
        for (i in 0 until arr.length()) {
            walkValue(arr.opt(i), itemPath, map)
        }
    }

    private fun walkValue(value: Any?, path: String, map: TreeMap<String, FieldStats>) {
        when (value) {
            null, JSONObject.NULL -> recordField(map, path, "null")
            is JSONObject -> walkJsonObject(value, path, map)
            is JSONArray -> walkJsonArray(value, path, map)
            is String -> {
                val trimmed = value.trim()
                if (trimmed.startsWith("{")) {
                    try {
                        val sub = JSONObject(trimmed)
                        walkJsonObject(sub, "$path<json>", map)
                        return
                    } catch (_: JSONException) {
                    }
                } else if (trimmed.startsWith("[")) {
                    try {
                        val sub = JSONArray(trimmed)
                        walkJsonArray(sub, "$path<json>", map)
                        return
                    } catch (_: JSONException) {
                    }
                }
                recordField(map, path, value)
            }
            else -> recordField(map, path, value.toString())
        }
    }

    private fun writeSelectorsReport(jsonFiles: List<CapturedResponse>, outFile: File) {
        val sb = StringBuilder()
        sb.append("# Selector rows from catalog APIs\n\n")
        for (entry in jsonFiles) {
            val raw = entry.file.readText(StandardCharsets.UTF_8).trim()
            if (!raw.startsWith("{")) continue
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            val data = root.optJSONObject("data") ?: continue
            val selectorRows = data.optJSONArray("selector_rows") ?: continue
            sb.append("=== ${entry.file.name} (${entry.url}) ===\n")
            for (i in 0 until selectorRows.length()) {
                val row = selectorRows.optJSONObject(i) ?: continue
                val type = row.optString("type", "")
                val name = row.optString("name", row.optString("show_name", row.optString("title", "")))
                val extraKeys = row.keys().asSequence()
                    .filter { it !in setOf("items", "type", "name", "show_name", "title") }
                    .joinToString(", ") { k -> "$k=${row.opt(k)}" }
                sb.append("Row[$i]: type=$type | name=$name | $extraKeys\n")
                val items = row.optJSONArray("items") ?: continue
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val id = item.optString("selector_item_id", "")
                    val showName = item.optString("show_name", "")
                    val itemExtra = item.keys().asSequence()
                        .filter { it !in setOf("selector_item_id", "show_name") }
                        .joinToString(", ") { k -> "$k=${item.opt(k)}" }
                    sb.append("    - id=$id | show_name=$showName${if (itemExtra.isNotEmpty()) " | $itemExtra" else ""}\n")
                }
            }
            sb.append("\n")
        }
        outFile.writeText(sb.toString(), StandardCharsets.UTF_8)
    }

    private fun writeSampleDramasReport(jsonFiles: List<CapturedResponse>, outFile: File) {
        val sb = StringBuilder()
        sb.append("# Sample Dramas Raw Metadata & Episodes\n\n")
        val kwRegex = Regex("(time|date|online|publish|create|update|status|serial|finish|end|complete|gender|category|genre|theme|role|epoch|tag|play|digg|follow|episode)", RegexOption.IGNORE_CASE)

        fun dumpMatchingFields(obj: JSONObject, prefix: String, out: StringBuilder) {
            for (key in obj.keys().asSequence().sorted()) {
                if (key == "video_list" || key == "video_model" || key == "episode_list") continue
                val v = obj.opt(key)
                if (v is JSONObject) {
                    dumpMatchingFields(v, "$prefix.$key", out)
                } else if (kwRegex.containsMatchIn(key)) {
                    val text = v?.toString().orEmpty().replace(Regex("[\\r\\n]+"), " ")
                    out.append("    $prefix.$key = ${if (text.length > 300) text.take(300) + "..." else text}\n")
                }
            }
        }

        // 1. Feed / Catalog items (limit to first few per file to keep readable)
        for (entry in jsonFiles) {
            val raw = entry.file.readText(StandardCharsets.UTF_8).trim()
            if (!raw.startsWith("{")) continue
            val root = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            val data = root.optJSONObject("data") ?: continue

            if (entry.path.contains("multi_video_detail")) {
                sb.append("=== DETAIL: ${entry.file.name} ===\n")
                for (sid in data.keys()) {
                    val sObj = data.optJSONObject(sid) ?: continue
                    val vd = sObj.optJSONObject("video_data") ?: sObj
                    val title = vd.optString("series_title", vd.optString("title", ""))
                    sb.append("Series $sid: $title\n")
                    dumpMatchingFields(sObj, "data.$sid", sb)
                    val vl = vd.optJSONArray("video_list")
                    sb.append("  video_list.length = ${vl?.length() ?: 0}\n")
                    if (vl != null && vl.length() > 0) {
                        sb.append("  Episode[0]: ${vl.optJSONObject(0)?.toString()}\n")
                        if (vl.length() > 1) {
                            sb.append("  Episode[${vl.length() - 1}]: ${vl.optJSONObject(vl.length() - 1)?.toString()}\n")
                        }
                    }
                    sb.append("\n")
                }
            } else {
                val cellData = data.optJSONObject("cell_view")?.optJSONArray("cell_data")
                val directArr = data.optJSONArray("video_data")
                val list = ArrayList<JSONObject>()
                if (cellData != null) {
                    for (i in 0 until cellData.length()) {
                        val cell = cellData.optJSONObject(i) ?: continue
                        val vd = cell.opt("video_data")
                        if (vd is JSONArray) {
                            for (j in 0 until vd.length()) vd.optJSONObject(j)?.let { list.add(it) }
                        } else if (vd is JSONObject) {
                            list.add(vd)
                        }
                    }
                } else if (directArr != null) {
                    for (i in 0 until directArr.length()) directArr.optJSONObject(i)?.let { list.add(it) }
                }
                if (list.isNotEmpty()) {
                    sb.append("=== FEED/CATALOG: ${entry.file.name} (showing ${minOf(list.size, 4)} of ${list.size}) ===\n")
                    for (v in list.take(4)) {
                        val det = v.optJSONObject("video_detail")
                        val sid = v.optString("series_id", det?.optString("series_id_str", det?.optString("series_id", "")) ?: "")
                        val title = v.optString("title", v.optString("series_title", det?.optString("series_title", "")))
                        sb.append("Series $sid: $title\n")
                        dumpMatchingFields(v, "item", sb)
                        val vl = det?.optJSONArray("video_list")
                        if (vl != null && vl.length() > 0) {
                            sb.append("  embedded video_list.length = ${vl.length()}\n")
                            sb.append("  embedded Episode[0]: ${vl.optJSONObject(0)?.toString()}\n")
                            if (vl.length() > 1) {
                                sb.append("  embedded Episode[${vl.length() - 1}]: ${vl.optJSONObject(vl.length() - 1)?.toString()}\n")
                            }
                        }
                        sb.append("\n")
                    }
                }
            }
        }
        outFile.writeText(sb.toString(), StandardCharsets.UTF_8)
    }
}
