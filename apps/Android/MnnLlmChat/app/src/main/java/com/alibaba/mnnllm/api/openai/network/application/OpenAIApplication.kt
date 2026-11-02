package com.alibaba.mnnllm.api.openai.network.application


import android.content.Context
import io.ktor.server.application.Application

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

import timber.log.Timber
import com.alibaba.mnnllm.api.openai.service.ApiServerConfig
import com.alibaba.mnnllm.api.openai.manager.ServerEventManager
import com.alibaba.mnnllm.api.openai.manager.ServerEventMonitor
import com.alibaba.mnnllm.api.openai.network.logging.LogCollector
import io.ktor.server.application.ApplicationStarted


class OpenAIApplication(private val lifecycleScope: CoroutineScope, private val context: Context) {
    private val mutex = Mutex()
    private var server: EmbeddedServer<*, *>? = null
    private val serverEventManager = ServerEventManager.getInstance()

    /** Last failure reason, exposed so callers can show an accurate error message. */
    var lastStartError: String? = null
        private set

    init {
        //initializeconfig
        ApiServerConfig.initializeConfig(context)
        //initializelogcollector
        LogCollector.getInstance().initialize()
    }

    /**
     * Start the embedded server.
     *
     * Retries a few times: a restart usually stops the previous Netty instance asynchronously,
     * so the port may still be busy for a short while. Returns false when the server could not
     * reach the running state, instead of silently swallowing the failure.
     */
    fun start(): Boolean {
        if (server != null) {
            Timber.tag("Network").w("Server is already running")
            return true
        }

        lastStartError = null
        val port = ApiServerConfig.getPort(context)
        val ipAddress = ApiServerConfig.getIpAddress(context)

        for (attempt in 1..MAX_START_ATTEMPTS) {
            try {
                if (attempt > 1) Thread.sleep(START_RETRY_DELAY_MS)
                serverEventManager.resetRuntimeState()
                Timber.tag("Network").i("Starting Ktor Server on $ipAddress:$port (attempt $attempt)")

                server = lifecycleScope.embeddedServer(
                    Netty,
                    port = port,
                    host = ipAddress,
                    module = { module(context, ipAddress, port) }
                ).start(wait = false) //notblockcurrentcoroutine

                if (waitUntilRunning(START_VERIFY_TIMEOUT_MS)) {
                    Timber.tag("Network").i("Server started successfully on $ipAddress:$port")
                    return true
                }
                lastStartError = "Server did not reach running state on $ipAddress:$port"
                Timber.tag("Network").e(lastStartError)
                stopInternalQuietly()
            } catch (e: Exception) {
                lastStartError = e.message ?: e.javaClass.simpleName
                Timber.e(e, "Failed to start Ktor server (attempt $attempt)")
                stopInternalQuietly()
            }
        }

        Timber.tag("Network").e("Server start failed after $MAX_START_ATTEMPTS attempts: $lastStartError")
        return false
    }

    private fun waitUntilRunning(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (serverEventManager.isServerRunning()) return true
            Thread.sleep(50)
        }
        return serverEventManager.isServerRunning()
    }

    private fun stopInternalQuietly() {
        runCatching {
            server?.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        }.onFailure { error ->
            Timber.tag("Network").w(error, "Failed to release failed server instance")
        }
        server = null
    }


    /** * stop server * * asynchronousstop serverandprocesspossiblecancelsituation*/
    fun stop() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                mutex.withLock { 
                    Timber.tag("Network").d("Acquired lock for server stop")
                    stopInternal() 
                }
            } catch (e: Exception) {
                Timber.tag("Network").e(e, "Failed to stop server due to lock acquisition failure")
            }
        }.invokeOnCompletion { cause ->
            cause?.let {
                Timber.tag("Network").w("Server stop job completed with exception: ${it.message}")
            }
        }
    }


    fun isRunning(): Boolean = serverEventManager.isServerRunning().also {
        Timber.tag("Network").i("Server is running: $it")
    }
    
    fun isReady(): Boolean = serverEventManager.isServerReady()
    
    fun getServerState() = serverEventManager.getCurrentState()
    
    fun getServerInfo() = serverEventManager.getCurrentInfo()

    fun getPort(): Int = ApiServerConfig.getPort(context)

    /** Block until the current server is fully stopped (call from a background thread only). */
    fun stopBlocking() {
        runCatching {
            runBlocking {
                withTimeoutOrNull(7000L) {
                    mutex.withLock { stopInternal() }
                }
            }
        }.onFailure { error ->
            Timber.tag("Network").w(error, "Blocking stop failed")
        }
        server = null
    }


    /** * internalmethod,forsafestop server * * process serverstopduringvariousexceptionsituation,includingcoroutinecancel*/
    suspend fun stopInternal() {
        try {
            Timber.tag("Network").d("Attempting to stop server...")
            server?.stopSuspend(gracePeriodMillis = 1000, timeoutMillis = 5000)?.let {
                Timber.tag("Network").i("Server stopped gracefully")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            Timber.tag("Network").w("Server stop was cancelled: ${e.message}")
            //even ifbecancelalsotryforcestop
            server?.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        } catch (e: Exception) {
            Timber.tag("Network").e(e, "Error stopping server")
        } finally {
            server = null
            Timber.tag("Network").i("Server stopped completely")
        }
    }

    private companion object {
        private const val MAX_START_ATTEMPTS = 5
        private const val START_RETRY_DELAY_MS = 400L
        private const val START_VERIFY_TIMEOUT_MS = 1500L
    }
}


private fun Application.module(context: Context, host: String, port: Int) {
    //configserviceeventlistener
    ServerEventMonitor(this).startMonitoring()

    //fromcom/alibaba/mnnllm/api/openai/network/application/HTTP.kt
    configureHTTP(context)

    //fromcom/alibaba/mnnllm/api/openai/network/application/Routing.kt
    configureRouting()
}
