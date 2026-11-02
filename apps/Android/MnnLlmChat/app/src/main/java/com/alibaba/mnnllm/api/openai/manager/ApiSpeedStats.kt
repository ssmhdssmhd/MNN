// Copyright (c) 2024 Alibaba Group Holding Limited All rights reserved.

package com.alibaba.mnnllm.api.openai.manager

/**
 * Latency of the most recent inference served by the API endpoints.
 * Surfaced in the API settings sheet as "endpoint speed" so the user can see
 * how fast the current model answers.
 */
object ApiSpeedStats {

    data class Snapshot(
        val firstTokenMs: Long,
        val totalMs: Long,
        val generatedTokens: Int
    ) {
        val tokensPerSecond: Float
            get() = if (totalMs > 0 && generatedTokens > 0) {
                generatedTokens * 1000f / totalMs
            } else {
                0f
            }
    }

    @Volatile
    private var firstTokenMs: Long = -1L

    @Volatile
    private var totalMs: Long = -1L

    @Volatile
    private var generatedTokens: Int = 0

    fun onRequestStart() {
        firstTokenMs = -1L
        totalMs = -1L
        generatedTokens = 0
    }

    fun onFirstToken(elapsedMs: Long) {
        firstTokenMs = elapsedMs
    }

    fun onComplete(elapsedMs: Long, metrics: Map<String, Any>) {
        totalMs = elapsedMs
        generatedTokens = (metrics["decode_len"] as? Number)?.toInt() ?: 0
    }

    fun onComplete(elapsedMs: Long, tokens: Int) {
        totalMs = elapsedMs
        generatedTokens = tokens
    }

    fun snapshot(): Snapshot? {
        val elapsed = totalMs
        return if (elapsed > 0) Snapshot(firstTokenMs, elapsed, generatedTokens) else null
    }
}
