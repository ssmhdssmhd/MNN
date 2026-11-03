package com.alibaba.mnnllm.api.openai.network.routes

import com.alibaba.mls.api.download.DownloadInfo
import com.alibaba.mls.api.download.DownloadListener
import com.alibaba.mls.api.download.ModelDownloadManager
import com.alibaba.mnnllm.android.MnnLlmApplication
import com.alibaba.mnnllm.android.modelist.ModelListManager
import com.alibaba.mnnllm.android.modelmarket.ModelRepository
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val PULL_TIMEOUT_MS = 30 * 60 * 1000L
private val NDJSON = ContentType("application", "x-ndjson")

/**
 * Ollama-compatible endpoints so the API can be inspected in a browser and
 * models can be pulled with the familiar Ollama protocol.
 */
fun Route.ollamaRoutes() {
    registerOllamaEndpoints("")
    // Also expose the same endpoints under /v1, since clients configured with the
    // OpenAI base URL (http://host:port/v1) append /api/... to it.
    registerOllamaEndpoints("/v1")
}

private fun Route.registerOllamaEndpoints(prefix: String) {
    get("$prefix/api/version") {
        call.respond(mapOf("version" to appVersion()))
    }
    if (prefix.isNotEmpty()) {
        // Opening http://host:port/v1 (or /v1/) in a browser shows the model list.
        get(prefix) {
            respondOllamaModels(call)
        }
        get("$prefix/") {
            respondOllamaModels(call)
        }
    }
    get("$prefix/api/tags") {
        respondOllamaModels(call)
    }
    post("$prefix/api/pull") {
        handlePull(call)
    }
    // Browser-friendly variant: /api/pull?name=owner/repo
    get("$prefix/api/pull") {
        handlePull(call)
    }
}

/** Model list in Ollama `/api/tags` shape, also served by GET /v1/responses. */
suspend fun respondOllamaModels(call: ApplicationCall) {
    val models = JSONArray()
    ModelListManager.getCurrentModels().orEmpty().forEach { wrapper ->
        val item = wrapper.modelItem
        val id = item.modelId ?: return@forEach
        val name = item.modelName?.takeIf { it.isNotBlank() } ?: id
        val modifiedAt = wrapper.downloadTime.takeIf { it > 0 }
            ?: item.localPath?.let { File(it).lastModified() }
            ?: 0L
        models.put(
            JSONObject()
                .put("name", name)
                .put("model", name)
                .put("modified_at", formatIsoTime(modifiedAt))
                .put("size", wrapper.downloadSize)
                .put("digest", sha256Digest(id))
                .put(
                    "details",
                    JSONObject()
                        .put("parent_model", "")
                        .put("format", "mnn")
                        .put("family", "mnn")
                        .put("families", JSONArray().put("mnn"))
                        .put("parameter_size", if (item.sizeB > 0) "${item.sizeB}B" else "")
                        .put("quantization_level", "")
                )
        )
    }
    call.respondText(JSONObject().put("models", models).toString(), ContentType.Application.Json)
}

private suspend fun handlePull(call: ApplicationCall) {
    val name = runCatching {
        if (call.request.httpMethod.value == "GET") {
            call.request.queryParameters["name"] ?: call.request.queryParameters["model"]
        } else {
            JSONObject(call.receiveText()).optString("model")
        }
    }.getOrNull()?.trim().orEmpty()

    if (name.isEmpty()) {
        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "model is required"))
        return
    }

    val modelId = resolveModelId(name)
    if (modelId == null) {
        call.respond(
            HttpStatusCode.NotFound,
            mapOf("error" to "model \"$name\" not found, use an MNN model name or owner/repo")
        )
        return
    }

    val manager = ModelDownloadManager.getInstance(MnnLlmApplication.getAppContext())
    if (manager.getDownloadInfo(modelId).isComplete()) {
        call.respondText(jsonLine("status" to "success"), NDJSON)
        return
    }

    call.respondOutputStream(NDJSON) {
        val events = Channel<String>(Channel.UNLIMITED)
        val listener = PullListener(modelId, events)
        manager.addListener(listener)
        try {
            manager.startDownload(modelId)
            withTimeoutOrNull(PULL_TIMEOUT_MS) {
                for (line in events) {
                    write((line + "\n").toByteArray())
                    flush()
                }
            }
        } finally {
            manager.removeListener(listener)
        }
    }
}

private class PullListener(
    private val targetModelId: String,
    private val events: Channel<String>
) : DownloadListener {
    private var lastEmitAt = 0L

    override fun onDownloadStart(modelId: String) {
        if (modelId == targetModelId) events.trySend(jsonLine("status" to "pulling manifest"))
    }

    override fun onDownloadProgress(modelId: String, downloadInfo: DownloadInfo) {
        if (modelId != targetModelId) return
        val now = System.currentTimeMillis()
        if (now - lastEmitAt < 500) return
        lastEmitAt = now
        events.trySend(
            jsonLine(
                "status" to "downloading",
                "total" to downloadInfo.totalSize,
                "completed" to downloadInfo.savedSize
            )
        )
    }

    override fun onDownloadFinished(modelId: String, path: String) {
        if (modelId != targetModelId) return
        events.trySend(jsonLine("status" to "success"))
        events.close()
    }

    override fun onDownloadFailed(modelId: String, e: Exception) {
        if (modelId != targetModelId) return
        events.trySend(jsonLine("error" to (e.message ?: "download failed")))
        events.close()
    }
}

/** Accepts market names, market ids and raw repo ids such as `owner/repo`. */
private fun resolveModelId(name: String): String? {
    val cleaned = name.trim()
    if (cleaned.startsWith("HuggingFace/", true) || cleaned.startsWith("ModelScope/", true)) {
        return cleaned
    }

    val cached = ModelRepository.getModelMarketDataV2()?.llmModels
        .orEmpty()
        .ifEmpty { ModelRepository.getAllCachedModels() }
    cached.firstOrNull {
        it.modelName.equals(cleaned, true) || it.modelId.equals(cleaned, true)
    }?.let { return it.modelId }
    cached.firstOrNull { it.modelName.contains(cleaned, true) }?.let { return it.modelId }

    // The market cache is only populated after browsing the model market, so
    // fall back to the bundled catalog to keep pull working on a fresh install.
    val bundled = loadBundledModels()
    bundled.firstOrNull { it.first.equals(cleaned, true) || it.second.equals(cleaned, true) }
        ?.let { return it.second }
    bundled.firstOrNull { it.first.contains(cleaned, true) }?.let { return it.second }

    if (cleaned.contains("/")) {
        return if (cleaned.startsWith("MNN/", true)) "ModelScope/$cleaned" else "HuggingFace/$cleaned"
    }
    return null
}

/** Reads `assets/model_market.json` into (modelName, modelId) pairs. */
private fun loadBundledModels(): List<Pair<String, String>> {
    if (bundledModels != null) return bundledModels!!
    val models = runCatching {
        val context = MnnLlmApplication.getAppContext()
        val text = context.assets.open("model_market.json").bufferedReader().use { it.readText() }
        val array = JSONObject(text).optJSONArray("models") ?: JSONArray()
        buildList {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val modelName = entry.optString("modelName")
                val sources = entry.optJSONObject("sources")
                val scopeRepo = sources?.optString("ModelScope").orEmpty()
                val hfRepo = sources?.optString("HuggingFace").orEmpty()
                val modelId = when {
                    scopeRepo.isNotBlank() -> "ModelScope/$scopeRepo"
                    hfRepo.isNotBlank() -> "HuggingFace/$hfRepo"
                    else -> null
                } ?: continue
                add(modelName to modelId)
            }
        }
    }.getOrDefault(emptyList())
    bundledModels = models
    return models
}

@Volatile
private var bundledModels: List<Pair<String, String>>? = null

private fun jsonLine(vararg pairs: Pair<String, Any?>): String {
    val json = JSONObject()
    pairs.forEach { (key, value) -> if (value != null) json.put(key, value) }
    return json.toString()
}

private fun formatIsoTime(millis: Long): String {
    val time = if (millis > 0) millis else System.currentTimeMillis()
    val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    format.timeZone = TimeZone.getTimeZone("UTC")
    return format.format(Date(time))
}

private fun sha256Digest(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
    return "sha256:" + bytes.take(16).joinToString("") { "%02x".format(it) }
}

private fun appVersion(): String =
    com.alibaba.mnnllm.android.BuildConfig.APP_RELEASE_VERSION
