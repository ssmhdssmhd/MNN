package com.alibaba.mnnllm.api.openai.service

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber

/** * APIserviceconfigmanagingclass * responsible for managingserviceconfigparameter,includingport,IPaddress,CORSandauthenticationsettings*/
object ApiServerConfig {
    private const val TAG = "ApiServerConfig"
    private const val PREFS_NAME = "api_settings"

    //configkey names
    private const val KEY_PORT = "port"
    private const val KEY_IP_ADDRESS = "ip_address"
    private const val KEY_CORS_ENABLED = "cors_enabled"
    private const val KEY_CORS_ORIGINS = "cors_origins"
    private const val KEY_AUTH_ENABLED = "auth_enabled"
    private const val KEY_API_KEY = "api_key"
    private const val KEY_USE_HTTPS_URL = "use_https_url"
    private const val KEY_CONFIG_INITIALIZED = "config_initialized"
    private const val KEY_CONFIG_VERSION = "config_version"

    //defaultconfigvalue
    private const val DEFAULT_PORT = 8080
    // Bind all interfaces so the API is reachable from a browser on the LAN.
    private const val DEFAULT_IP_ADDRESS = "0.0.0.0"
    // Enable CORS by default so external web clients (OpenWebUI, LobeChat, ...) can call the API.
    private const val DEFAULT_CORS_ENABLED = true
    private const val DEFAULT_CORS_ORIGINS = ""
    private const val DEFAULT_AUTH_ENABLED = true
    private const val DEFAULT_USE_HTTPS_URL = false

    private const val CURRENT_CONFIG_VERSION = 2

    /** * generaterandomAPI Key * 8-digitdigitlettersymbolcomposite*/
    private fun generateRandomApiKey(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        return (1..16)
            .map { chars.random() }
            .joinToString("")
    }

    /** * initializeconfig * if it'sfirst timerunning,willsavedefaultconfigtoSharedPreferences*/
    fun initializeConfig(context: Context) {
        val prefs = getPreferences(context)
        val isInitialized = prefs.getBoolean(KEY_CONFIG_INITIALIZED, false)

        if (!isInitialized) {
            Timber.Forest.tag(TAG).i("First time initialization, saving default config")
            saveDefaultConfig(prefs)
        } else {
            Timber.Forest.tag(TAG).i("Config already initialized, loading existing settings")
            migrateConfigIfNeeded(prefs)
        }

        logCurrentConfig(context)
    }

    /**
     * Upgrade existing configs to the current version.
     * v1 -> v2: default bind IP moves from 127.0.0.1 to 0.0.0.0 (LAN/browser accessible),
     *           and CORS is enabled by default so external web clients can call the API.
     * Only values still at the old defaults are upgraded, preserving explicit user choices.
     */
    private fun migrateConfigIfNeeded(prefs: SharedPreferences) {
        val version = prefs.getInt(KEY_CONFIG_VERSION, 1)
        if (version >= CURRENT_CONFIG_VERSION) return

        val editor = prefs.edit()
        if (version < 2) {
            val ip = prefs.getString(KEY_IP_ADDRESS, null)
            if (ip == "127.0.0.1") {
                editor.putString(KEY_IP_ADDRESS, DEFAULT_IP_ADDRESS)
            }
            if (!prefs.getBoolean(KEY_CORS_ENABLED, DEFAULT_CORS_ENABLED)) {
                editor.putBoolean(KEY_CORS_ENABLED, DEFAULT_CORS_ENABLED)
            }
        }
        editor.putInt(KEY_CONFIG_VERSION, CURRENT_CONFIG_VERSION)
        editor.apply()
        Timber.Forest.tag(TAG).i("Config migrated to version $CURRENT_CONFIG_VERSION")
    }

    /** * getserviceport*/
    fun getPort(context: Context): Int {
        return getPreferences(context).getInt(KEY_PORT, DEFAULT_PORT)
    }

    /** * getbindIPaddress*/
    fun getIpAddress(context: Context): String {
        return getPreferences(context).getString(KEY_IP_ADDRESS, DEFAULT_IP_ADDRESS) ?: DEFAULT_IP_ADDRESS
    }

    /** * getCORSenablestate*/
    fun isCorsEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_CORS_ENABLED, DEFAULT_CORS_ENABLED)
    }

    /** * getCORSallowedsource*/
    fun getCorsOrigins(context: Context): String {
        return getPreferences(context).getString(KEY_CORS_ORIGINS, DEFAULT_CORS_ORIGINS) ?: DEFAULT_CORS_ORIGINS
    }

    /** * getauthenticationenablestate*/
    fun isAuthEnabled(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_AUTH_ENABLED, DEFAULT_AUTH_ENABLED)
    }

    fun useHttpsUrl(context: Context): Boolean {
        return getPreferences(context).getBoolean(KEY_USE_HTTPS_URL, DEFAULT_USE_HTTPS_URL)
    }

    /**
     * getAPIkey*/
    fun getApiKey(context: Context): String {
        val prefs = getPreferences(context)
        val apiKey = prefs.getString(KEY_API_KEY, "")

        if (apiKey.isNullOrBlank()) {
            initializeConfig(context)
            return prefs.getString(KEY_API_KEY, "") ?: ""
        }

        return apiKey
    }

    /**
     * saveconfig*/
    fun saveConfig(
        context: Context,
        port: Int,
        ipAddress: String,
        corsEnabled: Boolean,
        corsOrigins: String,
        authEnabled: Boolean,
        apiKey: String,
        useHttpsUrl: Boolean
    ) {
        val prefs = getPreferences(context)
        prefs.edit().apply {
            putInt(KEY_PORT, port)
            putString(KEY_IP_ADDRESS, ipAddress)
            putBoolean(KEY_CORS_ENABLED, corsEnabled)
            putString(KEY_CORS_ORIGINS, corsOrigins)
            putBoolean(KEY_AUTH_ENABLED, authEnabled)
            putString(KEY_API_KEY, apiKey)
            putBoolean(KEY_USE_HTTPS_URL, useHttpsUrl)
            putBoolean(KEY_CONFIG_INITIALIZED, true)
        }.apply()

        Timber.Forest.tag(TAG).i("Config saved: port=$port, ip=$ipAddress, cors=$corsEnabled, auth=$authEnabled, useHttpsUrl=$useHttpsUrl")
    }

    /** * resetasdefaultconfig*/
    fun resetToDefault(context: Context) {
        val prefs = getPreferences(context)
        saveDefaultConfig(prefs)
        Timber.Forest.tag(TAG).i("Config reset to default values")
    }

    /**
     * getSharedPreferencesinstance*/
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * savedefaultconfig*/
    private fun saveDefaultConfig(prefs: SharedPreferences) {
        val defaultApiKey = generateRandomApiKey()
        prefs.edit().apply {
            putInt(KEY_PORT, DEFAULT_PORT)
            putString(KEY_IP_ADDRESS, DEFAULT_IP_ADDRESS)
            putBoolean(KEY_CORS_ENABLED, DEFAULT_CORS_ENABLED)
            putString(KEY_CORS_ORIGINS, DEFAULT_CORS_ORIGINS)
            putBoolean(KEY_AUTH_ENABLED, DEFAULT_AUTH_ENABLED)
            putString(KEY_API_KEY, defaultApiKey)
            putBoolean(KEY_USE_HTTPS_URL, DEFAULT_USE_HTTPS_URL)
            putBoolean(KEY_CONFIG_INITIALIZED, true)
            putInt(KEY_CONFIG_VERSION, CURRENT_CONFIG_VERSION)
        }.apply()

        Timber.Forest.tag(TAG).i("Generated new API Key: $defaultApiKey")
    }

    /** * recordcurrentconfigtolog*/
    private fun logCurrentConfig(context: Context) {
        val port = getPort(context)
        val ipAddress = getIpAddress(context)
        val corsEnabled = isCorsEnabled(context)
        val authEnabled = isAuthEnabled(context)
        val httpsUrl = useHttpsUrl(context)

        Timber.Forest.tag(TAG).i("Current config - Port: $port, IP: $ipAddress, CORS: $corsEnabled, Auth: $authEnabled, HTTPS URL: $httpsUrl")
    }
}
