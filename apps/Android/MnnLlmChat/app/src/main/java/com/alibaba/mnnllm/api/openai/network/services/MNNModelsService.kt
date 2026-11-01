package com.alibaba.mnnllm.api.openai.network.services

import com.alibaba.mnnllm.api.openai.network.logging.ChatLogger
import com.alibaba.mnnllm.api.openai.network.models.ModelData
import com.alibaba.mnnllm.api.openai.network.models.ModelPermission
import com.alibaba.mnnllm.api.openai.network.models.ModelsResponse
import com.alibaba.mnnllm.android.modelist.ModelListManager
import com.alibaba.mnnllm.android.MnnLlmApplication
import com.alibaba.mnnllm.android.mainsettings.MainSettings
import com.alibaba.mnnllm.api.openai.manager.CurrentModelManager
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import timber.log.Timber

/** * MNN modelservice * responsible forprocessmodelrelatedbusinesslogic*/
class MNNModelsService {
    private val logger = ChatLogger()

    suspend fun getAvailableModels(call: io.ktor.server.application.ApplicationCall, traceId: String) {
        try {
            logger.logRequestStart(traceId, call)

            val context = MnnLlmApplication.getAppContext()
            val currentModelId = CurrentModelManager.getCurrentModelId()
            val selectedIds = MainSettings.getApiSelectedModelIds(context)

            // Build the list of model IDs to expose:
            // 1. If user has explicitly selected models in Settings, use those.
            // 2. Otherwise fall back to the currently active model.
            val targetIds: Set<String> = if (selectedIds.isNotEmpty()) {
                selectedIds
            } else {
                setOfNotNull(currentModelId)
            }

            if (targetIds.isEmpty()) {
                Timber.tag("MNNModelsService").w("No model available for API listing")
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "No current model available"))
                return
            }

            val availableModels = ModelListManager.getCurrentModels()
            val modelDataList = mutableListOf<ModelData>()
            val now = System.currentTimeMillis() / 1000

            for (modelId in targetIds) {
                val wrapper = availableModels?.find { it.modelItem.modelId == modelId }
                val id = wrapper?.modelItem?.modelId ?: modelId
                modelDataList.add(
                    ModelData(
                        id = id,
                        created = now,
                        permission = listOf(
                            ModelPermission(
                                id = "modelperm-$id",
                                created = now
                            )
                        )
                    )
                )
            }

            val response = ModelsResponse(data = modelDataList)
            call.respond(response)
            logger.logInfo(traceId, "Models returned successfully: ${modelDataList.map { it.id }}")
        } catch (e: Exception) {
            Timber.tag("MNNModelsService").e(e, "Error getting models")
            logger.logError(traceId, e, "Failed to get models")
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to get models"))
        }
    }
}
