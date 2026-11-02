package com.alibaba.mnnllm.android.modelmarket

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Search for MNN-format models that can actually run on device.
 *
 * Sources:
 *  - The bundled model market catalogue (assets/model_market.json), which lists both
 *    HuggingFace (taobao-mnn) and ModelScope (MNN) repo ids for each MNN model.
 *  - Live HuggingFace search restricted to the `taobao-mnn` author.
 *  - A directly entered `owner/repo` id, validated against ModelScope / HuggingFace.
 *
 * Raw HF weights (safetensors/bin) are excluded on purpose: only MNN converted repos work.
 */
object OnlineModelSearchRepository {

    data class Result(
        val modelName: String,
        val source: String,
        val repoId: String,
        val downloadId: String,
        val downloads: Long,
        val sizeGb: Double,
        val url: String
    )

    private const val TIMEOUT_MS = 8000
    private const val HF_REPO_PREFIX = "HuggingFace/"
    private const val MS_REPO_PREFIX = "ModelScope/"
    private const val HF_ORG = "taobao-mnn"
    private const val MS_ORG = "MNN"

    suspend fun searchAll(context: Context, query: String): List<Result> = withContext(Dispatchers.IO) {
        val keyword = query.trim()
        if (keyword.isEmpty()) return@withContext emptyList()

        coroutineScope {
            val catalog = async { runCatching { searchCatalog(context, keyword) }.getOrDefault(emptyList()) }
            val hf = async { runCatching { searchHuggingFace(keyword) }.getOrDefault(emptyList()) }
            val direct = async { runCatching { searchDirectId(keyword) }.getOrDefault(emptyList()) }

            val merged = mutableListOf<Result>()
            merged.addAll(direct.await())
            merged.addAll(catalog.await())
            for (item in hf.await()) {
                if (merged.none { it.repoId.equals(item.repoId, ignoreCase = true) }) {
                    merged.add(item)
                }
            }
            merged.distinctBy { it.repoId.lowercase() }
                .sortedByDescending { score(it, keyword) }
        }
    }

    /** Rank exact name matches first, then official MNN repos, then shorter names. */
    private fun score(result: Result, keyword: String): Double {
        val name = result.modelName.lowercase()
        val query = keyword.lowercase()
        var value = 0.0
        if (name == query) value += 100.0
        else if (name.contains(query)) value += 60.0
        else if (query.contains(name)) value += 20.0
        if (result.repoId.startsWith("$HF_ORG/")) value += 15.0 // official taobao-mnn conversions
        value += result.sizeGb * 2
        return value
    }

    private fun searchCatalog(context: Context, keyword: String): List<Result> {
        val body = context.assets.open("model_market.json").bufferedReader().use { it.readText() }
        val root = JSONObject(body)
        val models = root.optJSONArray("models") ?: return emptyList()
        val query = keyword.lowercase()
        val results = mutableListOf<Result>()

        for (i in 0 until models.length()) {
            val item = models.optJSONObject(i) ?: continue
            val name = item.optString("modelName")
            val vendor = item.optString("vendor")
            val categories = item.optJSONArray("categories")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it) }
            }.orEmpty()

            val searchable = (name + " " + vendor + " " + categories.joinToString(" ")).lowercase()
            if (!searchable.contains(query)) continue

            val sources = item.optJSONObject("sources") ?: continue
            val msId = sources.optString("ModelScope").takeIf { it.isNotBlank() }
            val hfId = sources.optString("HuggingFace").takeIf { it.isNotBlank() }
            val repoId = msId ?: hfId ?: continue
            val isModelScope = msId != null

            results.add(
                Result(
                    modelName = name,
                    source = if (isModelScope) "ModelScope" else "HuggingFace",
                    repoId = repoId,
                    downloadId = if (isModelScope) MS_REPO_PREFIX + repoId else HF_REPO_PREFIX + repoId,
                    downloads = 0,
                    sizeGb = item.optDouble("size_gb", 0.0),
                    url = if (isModelScope) "https://modelscope.cn/models/$repoId"
                    else "https://huggingface.co/$repoId"
                )
            )
        }
        return results.take(30)
    }

    private fun searchHuggingFace(keyword: String): List<Result> {
        val encoded = URLEncoder.encode(keyword, "UTF-8")
        val hosts = listOf("https://hf-mirror.com", "https://huggingface.co")
        for (host in hosts) {
            // Search broadly on HF, then keep only MNN-converted repos (name contains "mnn").
            // taobao-mnn author repos are preferred (official MNN conversions).
            val body = get("$host/api/models?search=$encoded&limit=30&full=false") ?: continue
            val arr = runCatching { JSONArray(body) }.getOrNull() ?: continue
            val results = mutableListOf<Result>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val repoId = obj.optString("id")
                if (repoId.isBlank()) continue
                val name = repoId.substringAfterLast('/')
                if (!name.contains("mnn", ignoreCase = true)) continue
                results.add(
                    Result(
                        modelName = name,
                        source = "HuggingFace",
                        repoId = repoId,
                        downloadId = HF_REPO_PREFIX + repoId,
                        downloads = obj.optLong("downloads", 0),
                        sizeGb = 0.0,
                        url = "https://huggingface.co/$repoId"
                    )
                )
            }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    /**
     * Support pasting a repo id directly: `owner/repo`, `MNN/xxx`, `taobao-mnn/xxx`,
     * optionally prefixed with `HuggingFace/` or `ModelScope/`.
     */
    private suspend fun searchDirectId(raw: String): List<Result> {
        var id = raw.removePrefix(HF_REPO_PREFIX).removePrefix(MS_REPO_PREFIX)
        if (!id.contains("/")) return emptyList()
        // Only MNN-converted repos are runnable on device; reject raw weight repos.
        if (!id.substringAfterLast('/').contains("mnn", ignoreCase = true)) return emptyList()
        val results = mutableListOf<Result>()

        val msAvailable = validateModelScope(id)
        if (msAvailable) {
            results.add(fromRepoId(id, "ModelScope", 0L, 0.0))
        }
        val hfAvailable = validateHuggingFace(id)
        if (hfAvailable) {
            results.add(fromRepoId(id, "HuggingFace", 0L, 0.0))
        }
        if (results.isEmpty() && id.startsWith("$MS_ORG/")) {
            results.add(fromRepoId(id, "ModelScope", 0L, 0.0))
        }
        if (results.isEmpty() && id.startsWith("$HF_ORG/")) {
            results.add(fromRepoId(id, "HuggingFace", 0L, 0.0))
        }
        return results
    }

    private fun fromRepoId(repoId: String, source: String, downloads: Long, sizeGb: Double): Result {
        return Result(
            modelName = repoId.substringAfterLast('/'),
            source = source,
            repoId = repoId,
            downloadId = (if (source == "ModelScope") MS_REPO_PREFIX else HF_REPO_PREFIX) + repoId,
            downloads = downloads,
            sizeGb = sizeGb,
            url = if (source == "ModelScope") "https://modelscope.cn/models/$repoId"
            else "https://huggingface.co/$repoId"
        )
    }

    private fun validateModelScope(repoId: String): Boolean {
        val body = get("https://modelscope.cn/api/v1/models/$repoId") ?: return false
        return runCatching { JSONObject(body).optInt("Code") == 200 }.getOrDefault(false)
    }

    private fun validateHuggingFace(repoId: String): Boolean {
        for (host in listOf("https://hf-mirror.com", "https://huggingface.co")) {
            val body = get("$host/api/models/$repoId") ?: continue
            if (runCatching { JSONObject(body).has("id") }.getOrDefault(false)) return true
        }
        return false
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
