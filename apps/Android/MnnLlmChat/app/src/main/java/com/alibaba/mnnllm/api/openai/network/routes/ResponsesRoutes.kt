package com.alibaba.mnnllm.api.openai.network.routes

import MessageContent
import MultiModalContent
import OpenAIGenericMessage
import OpenAIImageContent
import OpenAIMultiModalContent
import OpenAITextContent
import StandardImageUrl
import TextContent
import com.alibaba.mnnllm.android.MnnLlmApplication
import com.alibaba.mnnllm.android.llm.GenerateProgressListener
import com.alibaba.mnnllm.android.llm.LlmSession
import com.alibaba.mnnllm.api.openai.di.ServiceLocator
import com.alibaba.mnnllm.api.openai.network.logging.ChatLogger
import com.alibaba.mnnllm.api.openai.network.processors.MnnImageProcessor
import com.alibaba.mnnllm.api.openai.network.queue.RequestQueueManager
import com.alibaba.mnnllm.api.openai.network.utils.MessageTransformer
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.sse.SSEServerContent
import io.ktor.server.sse.heartbeat
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/**
 * OpenAI Responses API compatible endpoint (/v1/responses).
 * Enabled through ApiServerConfig.isResponsesApiEnabled(context); the flag is read when
 * the Ktor routing is configured (i.e. service start / restart).
 */
fun Route.responsesRoutes() {
    val service = ResponsesService()
    post("/v1/responses") {
        val traceId = UUID.randomUUID().toString()
        service.process(call, traceId)
    }
}

/** Minimal request model for the Responses API. */
private data class ResponsesRequest(
    val model: String?,
    val messages: List<OpenAIGenericMessage>,
    val stream: Boolean
)

private class ResponsesService {

    private val logger = ChatLogger()
    private val messageTransformer = MessageTransformer()
    private val requestQueueManager = RequestQueueManager.getInstance()
    private val runtimeController = ServiceLocator.getLlmRuntimeController()
    private val chatSessionProvider = ServiceLocator.getChatSessionProvider()
    private val imageProcessor = MnnImageProcessor.getInstance(MnnLlmApplication.getAppContext())

    suspend fun process(call: ApplicationCall, traceId: String) {
        logger.logRequestStart(traceId, call)

        val rawBody = try {
            call.receiveText()
        } catch (e: Exception) {
            call.respondError(HttpStatusCode.BadRequest, "Invalid request body: ${e.message}")
            return
        }

        val request = try {
            parseRequest(rawBody)
        } catch (e: Exception) {
            logger.logError(traceId, e, "Failed to parse responses request")
            call.respondError(HttpStatusCode.BadRequest, "Invalid request body: ${e.message}")
            return
        }

        if (request.messages.isEmpty()) {
            call.respondError(HttpStatusCode.BadRequest, "'input' must not be empty")
            return
        }

        logger.logInfo(traceId, "Responses request: model=${request.model}, stream=${request.stream}, messages=${request.messages.size}")

        val responseId = "resp_${UUID.randomUUID().toString().replace("-", "")}"
        val createdAt = System.currentTimeMillis() / 1000
        val modelName = request.model
            ?: runtimeController.getActiveModelId()
            ?: "mnn-local"

        if (request.stream) {
            streamResponse(call, request, responseId, createdAt, modelName, traceId)
        } else {
            val content = StringBuilder()
            var metrics = HashMap<String, Any>()
            try {
                requestQueueManager.submitRequest(
                    requestId = responseId,
                    traceId = traceId,
                    task = {
                        metrics = generate(request, traceId) { content.append(it) }
                    }
                )
                val body = buildResponse(responseId, createdAt, modelName, content.toString(), metrics)
                call.respondText(body, ContentType.Application.Json)
            } catch (e: Exception) {
                logger.logError(traceId, e, "Responses generation failed")
                call.respondError(HttpStatusCode.InternalServerError, e.message ?: "Internal server error")
            }
        }
    }

    // ========================
    // streaming
    // ========================

    private suspend fun streamResponse(
        call: ApplicationCall,
        request: ResponsesRequest,
        responseId: String,
        createdAt: Long,
        modelName: String,
        traceId: String
    ) {
        val itemId = "msg_${UUID.randomUUID().toString().replace("-", "")}"
        val sequence = java.util.concurrent.atomic.AtomicInteger(0)

        call.respond(SSEServerContent(call) {
            heartbeat {
                period = 3L.seconds
                event = ServerSentEvent(comments = "keep-alive")
            }

            val channel = Channel<ServerSentEvent>(Channel.UNLIMITED)

            launch(Dispatchers.IO) {
                val full = StringBuilder()
                var metrics = HashMap<String, Any>()
                try {
                    channel.send(
                        sse(
                            "response.created",
                            sequence,
                            JSONObject().put(
                                "response",
                                responseEnvelope(responseId, createdAt, modelName, "in_progress")
                            )
                        )
                    )

                    requestQueueManager.submitRequest(
                        requestId = responseId,
                        traceId = traceId,
                        task = {
                            metrics = generate(request, traceId) { delta ->
                                full.append(delta)
                                logger.logStreamDelta(traceId, delta)
                                channel.trySend(
                                    sse(
                                        "response.output_text.delta",
                                        sequence,
                                        JSONObject()
                                            .put("item_id", itemId)
                                            .put("output_index", 0)
                                            .put("content_index", 0)
                                            .put("delta", delta)
                                    )
                                )
                            }
                        }
                    )

                    val text = full.toString()
                    channel.send(
                        sse(
                            "response.output_text.done",
                            sequence,
                            JSONObject()
                                .put("item_id", itemId)
                                .put("output_index", 0)
                                .put("content_index", 0)
                                .put("text", text)
                        )
                    )
                    channel.send(
                        sse(
                            "response.completed",
                            sequence,
                            JSONObject().put(
                                "response",
                                completedResponseObject(responseId, createdAt, modelName, text, metrics)
                            )
                        )
                    )
                    logger.logInferenceComplete(traceId)
                } catch (e: Exception) {
                    logger.logError(traceId, e, "Responses streaming failed")
                    channel.trySend(
                        sse(
                            "response.failed",
                            sequence,
                            JSONObject().put(
                                "response",
                                responseEnvelope(responseId, createdAt, modelName, "failed")
                                    .put("error", errorObject(e.message ?: "Internal server error"))
                            )
                        )
                    )
                } finally {
                    channel.close()
                }
            }

            try {
                for (event in channel) {
                    send(event)
                }
            } catch (e: Exception) {
                logger.logWarning(traceId, "Client connection closed during responses stream", e)
            }
        })
    }

    private fun sse(eventName: String, sequence: java.util.concurrent.atomic.AtomicInteger, payload: JSONObject): ServerSentEvent {
        payload.put("type", eventName)
        payload.put("sequence_number", sequence.getAndIncrement())
        return ServerSentEvent(event = eventName, data = payload.toString())
    }

    // ========================
    // generation
    // ========================

    private fun generate(
        request: ResponsesRequest,
        traceId: String,
        onDelta: (String) -> Unit
    ): HashMap<String, Any> {
        val requestedModel = request.model
        if (!requestedModel.isNullOrBlank()) {
            ensureModelSession(requestedModel, traceId)
        }
        val llmSession = getLlmSession()
        val history = kotlinx.coroutines.runBlocking {
            messageTransformer.convertToUnifiedMnnHistory(request.messages, imageProcessor, llmSession)
        }
        logger.logInfo(traceId, "Responses inference start, history size=${history.size}")
        return llmSession.submitFullHistory(history, object : GenerateProgressListener {
            override fun onProgress(progress: String?): Boolean {
                if (progress != null) {
                    onDelta(progress)
                }
                return false
            }
        })
    }

    private fun getLlmSession(): LlmSession {
        return chatSessionProvider.getLlmSession()
            ?: throw IllegalStateException("No active LLM session available")
    }

    private fun ensureModelSession(modelId: String, traceId: String) {
        if (runtimeController.getActiveModelId() == modelId && runtimeController.getActiveSession() != null) {
            return
        }
        logger.logInfo(traceId, "Switching API runtime model to: $modelId")
        val result = runtimeController.ensureSession(modelId)
        if (!result.success || result.session == null) {
            throw IllegalStateException("Failed to load model '$modelId': ${result.reason ?: "unknown"}")
        }
    }

    // ========================
    // response payloads
    // ========================

    private fun buildResponse(
        responseId: String,
        createdAt: Long,
        modelName: String,
        text: String,
        metrics: HashMap<String, Any>
    ): String = completedResponseObject(responseId, createdAt, modelName, text, metrics).toString()

    private fun responseEnvelope(
        responseId: String,
        createdAt: Long,
        modelName: String,
        status: String
    ): JSONObject = JSONObject()
        .put("id", responseId)
        .put("object", "response")
        .put("created_at", createdAt)
        .put("status", status)
        .put("model", modelName)
        .put("output", JSONArray())

    private fun completedResponseObject(
        responseId: String,
        createdAt: Long,
        modelName: String,
        text: String,
        metrics: HashMap<String, Any>
    ): JSONObject = responseEnvelope(responseId, createdAt, modelName, "completed")
        .put(
            "output",
            JSONArray().put(
                JSONObject()
                    .put("id", "msg_${responseId.removePrefix("resp_")}")
                    .put("type", "message")
                    .put("status", "completed")
                    .put("role", "assistant")
                    .put(
                        "content",
                        JSONArray().put(
                            JSONObject()
                                .put("type", "output_text")
                                .put("text", text)
                                .put("annotations", JSONArray())
                        )
                    )
            )
        )
        .put("output_text", text)
        .put("usage", usageObject(metrics))

    private fun usageObject(metrics: HashMap<String, Any>): JSONObject {
        val inputTokens = (metrics["prompt_len"] as? Long)?.toInt() ?: 0
        val outputTokens = (metrics["decode_len"] as? Long)?.toInt() ?: 0
        return JSONObject()
            .put("input_tokens", inputTokens)
            .put("input_tokens_details", JSONObject().put("cached_tokens", 0))
            .put("output_tokens", outputTokens)
            .put("output_tokens_details", JSONObject().put("reasoning_tokens", 0))
            .put("total_tokens", inputTokens + outputTokens)
    }

    private fun errorObject(message: String): JSONObject = JSONObject()
        .put("message", message)
        .put("type", "server_error")
        .put("code", JSONObject.NULL)

    private suspend fun ApplicationCall.respondError(status: HttpStatusCode, message: String) {
        val body = JSONObject().put("error", errorObject(message)).toString()
        respondText(body, ContentType.Application.Json, status = status)
    }

    // ========================
    // request parsing
    // ========================

    private fun parseRequest(body: String): ResponsesRequest {
        val root = JSONObject(body)
        val model = root.optString("model", "").takeIf { it.isNotBlank() }
        val stream = root.optBoolean("stream", false)

        val messages = mutableListOf<OpenAIGenericMessage>()
        val instructions = root.optString("instructions", "")
        if (instructions.isNotBlank()) {
            messages.add(OpenAIGenericMessage("system", TextContent(instructions)))
        }

        when (val input = root.opt("input")) {
            is String -> {
                if (input.isNotBlank()) messages.add(OpenAIGenericMessage("user", TextContent(input)))
            }

            is JSONArray -> {
                for (i in 0 until input.length()) {
                    when (val item = input.opt(i)) {
                        is JSONObject -> {
                            val role = item.optString("role", "user").let {
                                if (it == "developer") "system" else it
                            }
                            messages.add(
                                OpenAIGenericMessage(role, parseContent(item.opt("content")))
                            )
                        }

                        is String -> messages.add(OpenAIGenericMessage("user", TextContent(item)))
                    }
                }
            }

            null -> Unit
        }

        return ResponsesRequest(model, messages, stream)
    }

    private fun parseContent(content: Any?): MessageContent? {
        return when (content) {
            is String -> TextContent(content)
            is JSONArray -> {
                val parts = mutableListOf<OpenAITextContent>()
                val images = mutableListOf<OpenAIImageContent>()
                for (i in 0 until content.length()) {
                    val part = content.optJSONObject(i) ?: continue
                    when (part.optString("type")) {
                        "input_text", "text", "output_text" -> {
                            parts.add(OpenAITextContent(part.optString("text", "")))
                        }

                        "input_image", "image_url" -> {
                            val imageUrl = when (val url = part.opt("image_url")) {
                                is String -> url
                                is JSONObject -> url.optString("url", "")
                                else -> ""
                            }
                            if (imageUrl.isNotBlank()) {
                                images.add(OpenAIImageContent(StandardImageUrl(imageUrl)))
                            }
                        }
                    }
                }
                if (images.isEmpty()) {
                    TextContent(parts.joinToString("") { it.text })
                } else {
                    val content: MutableList<OpenAIMultiModalContent> = mutableListOf()
                    content.addAll(parts)
                    content.addAll(images)
                    MultiModalContent(content)
                }
            }

            null -> null
            else -> {
                Timber.tag("Responses").w("Unsupported input content type: ${content::class}")
                null
            }
        }
    }
}
