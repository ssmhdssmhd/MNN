package com.alibaba.mnnllm.android.modelmarket

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Aggregated search across model download sources (HuggingFace / ModelScope / Modelers). */
object OnlineModelSearchRepository {

    data class Result(
        val source: String,
        val modelId: String,
        val downloads: Long,
        val url: String
    )

    private const val TIMEOUT_MS = 8000

    suspend fun searchAll(query: String): List<Result> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        coroutineScope {
            val hf = async { runCatching { searchHuggingFace(query) }.getOrDefault(emptyList()) }
            val ms = async { runCatching { searchModelScope(query) }.getOrDefault(emptyList()) }
            val mo = async { runCatching { searchModelers(query) }.getOrDefault(emptyList()) }
            (hf.await() + ms.await() + mo.await()).sortedByDescending { it.downloads }
        }
    }

    private fun searchHuggingFace(query: String): List<Result> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val hosts = listOf("https://huggingface.co", "https://hf-mirror.com")
        for (host in hosts) {
            val body = get("$host/api/models?search=$encoded&limit=20&full=false") ?: continue
            val arr = runCatching { JSONArray(body) }.getOrNull() ?: continue
            val results = mutableListOf<Result>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                if (id.isBlank()) continue
                results.add(
                    Result(
                        source = "HuggingFace",
                        modelId = id,
                        downloads = obj.optLong("downloads", 0),
                        url = "https://huggingface.co/$id"
                    )
                )
            }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    private fun searchModelScope(query: String): List<Result> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = get("https://modelscope.cn/api/v1/models?PageNumber=1&PageSize=20&Name=$encoded") ?: return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val models = root.optJSONObject("Data")?.optJSONArray("Models")
            ?: root.optJSONArray("Data")
            ?: return emptyList()
        return parseNameBased(models, "ModelScope", "https://modelscope.cn/models")
    }

    private fun searchModelers(query: String): List<Result> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val body = get("https://modelers.cn/api/v1/models?PageSize=20&Name=$encoded") ?: return emptyList()
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val models = root.optJSONArray("data")
            ?: root.optJSONObject("data")?.optJSONArray("models")
            ?: return emptyList()
        return parseNameBased(models, "Modelers", "https://modelers.cn/models")
    }

    private fun parseNameBased(arr: JSONArray, source: String, urlPrefix: String): List<Result> {
        val results = mutableListOf<Result>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("Name").ifBlank { obj.optString("name") }
            if (id.isBlank()) continue
            results.add(
                Result(
                    source = source,
                    modelId = id,
                    downloads = obj.optLong("Downloads", obj.optLong("downloads", 0)),
                    url = "$urlPrefix/$id"
                )
            )
        }
        return results
    }

    private fun get(url: String): String? {
        return runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.connect()
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return@runCatching null
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            text
        }.getOrNull()
    }
}
