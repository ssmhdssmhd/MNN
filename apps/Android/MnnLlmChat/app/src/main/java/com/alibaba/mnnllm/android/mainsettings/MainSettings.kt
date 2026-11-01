// Created by ruoyi.sjd on 2025/2/28.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mnnllm.android.mainsettings

import android.content.Context
import androidx.preference.PreferenceManager
import com.alibaba.mls.api.source.ModelSources
import com.alibaba.mnnllm.android.utils.DeviceUtils
import org.json.JSONArray


object MainSettings {

    fun getDownloadProvider(context: Context): ModelSources.ModelSourceType {
        val result = getDownloadProviderString(context)
        return when (result) {
            ModelSources.sourceHuffingFace -> ModelSources.ModelSourceType.HUGGING_FACE
            ModelSources.sourceModelScope -> ModelSources.ModelSourceType.MODEL_SCOPE
            else -> ModelSources.ModelSourceType.MODELERS
         }
    }

    fun setDownloadProvider(context: Context, source:String) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        sharedPreferences.edit().putString("download_provider", source)
        .apply()
    }

    fun getDownloadProviderString(context: Context):String {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        return sharedPreferences.getString("download_provider", getDefaultDownloadProvider())!!
    }

    private fun getDefaultDownloadProvider():String {
        return if (DeviceUtils.isChinese) ModelSources.sourceModelScope else ModelSources.sourceHuffingFace
    }

    fun isStopDownloadOnChatEnabled(context: Context): Boolean {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        return sharedPreferences.getBoolean("stop_download_on_chat", true)
    }

    fun isApiServiceEnabled(context: Context): Boolean {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        return sharedPreferences.getBoolean("enable_api_service", false)
    }

    /**
     * Get the default TTS model ID
     */
    fun getDefaultTtsModel(context: Context): String? {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        return sharedPreferences.getString("default_tts_model", null)
    }

    /**
     * Set the default TTS model ID
     */
    fun setDefaultTtsModel(context: Context, modelId: String) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        sharedPreferences.edit()
            .putString("default_tts_model", modelId)
            .apply()
    }

    /**
     * Check if the given model is the current default TTS model
     */
    fun isDefaultTtsModel(context: Context, modelId: String): Boolean {
        return getDefaultTtsModel(context) == modelId
    }

    /**
     * Get the default ASR model ID
     */
    fun getDefaultAsrModel(context: Context): String? {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        return sharedPreferences.getString("default_asr_model", null)
    }

    /**
     * Set the default ASR model ID
     */
    fun setDefaultAsrModel(context: Context, modelId: String) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        sharedPreferences.edit()
            .putString("default_asr_model", modelId)
            .apply()
    }

    /**
     * Check if the given model is the current default ASR model
     */
    fun isDefaultAsrModel(context: Context, modelId: String): Boolean {
        return getDefaultAsrModel(context) == modelId
    }

    /**
     * Get the model IDs selected for the API service (multi-model support).
     * Returns an empty set if no selection has been made.
     */
    fun getApiSelectedModelIds(context: Context): Set<String> {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val raw = sharedPreferences.getString(KEY_API_SELECTED_MODEL_IDS, null) ?: return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            val set = LinkedHashSet<String>()
            for (i in 0 until arr.length()) {
                set.add(arr.getString(i))
            }
            set
        }.getOrDefault(emptySet())
    }

    /**
     * Save the model IDs selected for the API service.
     */
    fun setApiSelectedModelIds(context: Context, modelIds: Set<String>) {
        val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        val arr = JSONArray()
        modelIds.forEach { arr.put(it) }
        sharedPreferences.edit().putString(KEY_API_SELECTED_MODEL_IDS, arr.toString()).apply()
    }

    private const val KEY_API_SELECTED_MODEL_IDS = "api_selected_model_ids"

}
