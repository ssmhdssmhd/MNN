package com.alibaba.mnnllm.api.openai.runtime

import com.alibaba.mnnllm.android.llm.LlmSession
import com.alibaba.mnnllm.android.model.ModelUtils
import com.alibaba.mnnllm.api.openai.di.ServiceLocator
import timber.log.Timber

/**
 * Runtime session loader used by the API server.
 *
 * Two optimizations over the plain runtime controller:
 * 1. Load models with the app's tuned config ([useAppConfig] = true), so API inference uses
 *    the same backend / thread / precision settings as in-app chat. Previously the API used
 *    the bare default config, which is much slower on devices with custom settings.
 * 2. Enable native prompt cache on the session serving API requests. The API always submits
 *    the full conversation history; without prompt cache every request re-prefills the whole
 *    prompt, while prompt cache makes the engine prefill only the new suffix.
 */
object ApiRuntimeSessionHelper {

    @Volatile
    private var promptCacheSessionId: String? = null

    fun ensureSession(modelId: String): EnsureSessionResult {
        val controller = ServiceLocator.getLlmRuntimeController()
        val reused = reuseAliasSession(controller, modelId)
        val result = reused ?: controller.ensureSession(
            modelId = modelId,
            useAppConfig = true
        )
        val session = result.session
        if (result.success && session != null) {
            enablePromptCacheOnce(modelId, session)
        }
        return result
    }

    /**
     * Clients may send a model alias instead of the exact active model id. Reloading the
     * model on every request would make responses extremely slow, so reuse the loaded
     * session when both ids point at the same local model.
     */
    private fun reuseAliasSession(
        controller: LlmRuntimeController,
        requestedModelId: String
    ): EnsureSessionResult? {
        val session = controller.getActiveSession() ?: return null
        val activeModelId = controller.getActiveModelId() ?: return null
        if (activeModelId == requestedModelId || !session.isModelLoaded()) return null

        val sameModel = runCatching {
            val activeConfig = ModelUtils.getConfigPathForModel(activeModelId)
            activeConfig != null &&
                activeConfig == ModelUtils.getConfigPathForModel(requestedModelId)
        }.getOrDefault(false)
        if (!sameModel) return null

        Timber.tag("ApiRuntime").i(
            "Reusing loaded session for alias: requested=%s active=%s",
            requestedModelId,
            activeModelId
        )
        return EnsureSessionResult(success = true, session = session, modelId = activeModelId)
    }

    private fun enablePromptCacheOnce(modelId: String, session: LlmSession) {
        // Avoid repeated native config updates for the same session.
        if (session.sessionId == promptCacheSessionId) return
        if (!session.isModelLoaded()) return
        runCatching {
            session.updateConfig("""{"prompt_cache": true}""")
            promptCacheSessionId = session.sessionId
            Timber.tag("ApiRuntime").i("Prompt cache enabled for API session: %s", modelId)
        }.onFailure { error ->
            Timber.tag("ApiRuntime").w(error, "Failed to enable prompt cache for %s", modelId)
        }
    }
}
