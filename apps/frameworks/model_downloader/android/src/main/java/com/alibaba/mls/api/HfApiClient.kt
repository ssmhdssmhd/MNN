// Created by ruoyi.sjd on 2024/12/25.
// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.
package com.alibaba.mls.api

import android.content.res.Configuration
import android.os.Build
import okhttp3.OkHttpClient
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Minimal HuggingFace API client for the framework
 * Only includes repo info fetching, no model search functionality
 */
class HfApiClient(val host: String) {
    private val apiService: HfApiService
    var okHttpClient: OkHttpClient? = null
        private set

    init {
        // Initialize Retrofit
        val retrofit = Retrofit.Builder()
            .baseUrl("https://$host")
            .addConverterFactory(GsonConverterFactory.create())
            .client(createOkHttpClient())
            .build()
        apiService = retrofit.create(HfApiService::class.java)
    }

    private fun createOkHttpClient(): OkHttpClient? {
        val builder: OkHttpClient.Builder = OkHttpClient.Builder()
        builder.connectTimeout(30, TimeUnit.SECONDS)
        builder.readTimeout(30, TimeUnit.SECONDS)
        okHttpClient = builder.build()
        return okHttpClient
    }

    // Get repo file tree
    fun getRepoTree(repoId: String, revision: String = "main"): Call<List<HfTreeItem>> {
        return apiService.getRepoTree(repoId, revision)
    }

    companion object {
        const val HOST_DEFAULT = "huggingface.co"
        const val HOST_CN = "hf-mirror.com"

        /** Detect whether the device is in a Chinese locale (zh_CN). */
        private fun isChineseLocale(): Boolean {
            val app = ApplicationProvider.application ?: return false
            val config: Configuration = app.resources.configuration
            val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (config.locales.isEmpty) null else config.locales[0]
            } else {
                @Suppress("DEPRECATION")
                config.locale
            }
            return locale != null && locale.language == "zh" && locale.country == "CN"
        }

        /**
         * Pick the best HuggingFace host for the current locale.
         * Chinese users default to the mirror (hf-mirror.com) because huggingface.co
         * is often slow or blocked in mainland China; others use huggingface.co.
         */
        val bestHost: String
            get() = if (isChineseLocale()) HOST_CN else HOST_DEFAULT

        /** Client bound to [bestHost]. */
        val bestClient: HfApiClient
            get() = HfApiClient(bestHost)

        /** The fallback host to try when [bestHost] fails. */
        val fallbackHost: String
            get() = if (bestHost == HOST_DEFAULT) HOST_CN else HOST_DEFAULT
    }
}
