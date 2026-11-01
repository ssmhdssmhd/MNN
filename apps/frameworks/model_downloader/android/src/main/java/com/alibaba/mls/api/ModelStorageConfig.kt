// Created by ruoyi.sjd on 2026/11/02.
// Copyright (c) 2026 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mls.api

import android.content.Context
import java.io.File

/**
 * Centralized configuration for the model storage root directory.
 *
 * By default models are stored under the app's internal files dir (.mnnmodels).
 * Users can change this path via Storage Management so that downloaded and
 * manually-added models live in a chosen location (e.g. app-specific external storage).
 */
object ModelStorageConfig {

    private const val PREFS_NAME = "model_storage_config"
    private const val KEY_MODEL_ROOT_PATH = "model_root_path"

    /** Default model storage root: <filesDir>/.mnnmodels */
    fun getDefaultModelRootPath(context: Context): String {
        return File(context.filesDir, ".mnnmodels").absolutePath
    }

    /** Get the configured model storage root directory. Falls back to the default. */
    fun getModelRootPath(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val path = prefs.getString(KEY_MODEL_ROOT_PATH, null)
        return if (path.isNullOrBlank()) getDefaultModelRootPath(context) else path
    }

    /** Get the configured model storage root as a File. */
    fun getModelRootDir(context: Context): File {
        return File(getModelRootPath(context))
    }

    /** Persist a custom model storage root path. */
    fun setModelRootPath(context: Context, path: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MODEL_ROOT_PATH, path).apply()
    }

    /** Reset the model storage root to the default location. */
    fun resetToDefault(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_MODEL_ROOT_PATH).apply()
    }

    /** Whether the current configured path equals the default. */
    fun isDefaultPath(context: Context): Boolean {
        return getModelRootPath(context) == getDefaultModelRootPath(context)
    }
}
