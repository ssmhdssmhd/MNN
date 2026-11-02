package com.alibaba.mnnllm.api.openai.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import com.alibaba.mnnllm.android.R
import com.alibaba.mnnllm.android.databinding.FragmentApiSettingsSheetBinding
import com.alibaba.mnnllm.android.mainsettings.MainSettings
import com.alibaba.mnnllm.api.openai.manager.ApiServiceManager
import com.alibaba.mnnllm.api.openai.service.ApiServerConfig
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.net.NetworkInterface
import java.util.*

class ApiSettingsBottomSheetFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentApiSettingsSheetBinding? = null
    private val binding get() = _binding!!

    //API settings configuration
    private var currentPort: Int = 8080
    private var currentIpAddress: String = "127.0.0.1"
    private var corsEnabled: Boolean = false
    private var corsOrigins: String = ""
    private var authEnabled: Boolean = false
    private var apiKey: String = ""
    private var useHttpsUrl: Boolean = false
    private var responsesApiEnabled: Boolean = false
    private var promptCacheKeyEnabled: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentApiSettingsSheetBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onStart() {
        super.onStart()
        val dialog: Dialog? = dialog
        if (dialog != null) {
            val bottomSheet: FrameLayout? = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                bottomSheet.post {
                    behavior.state = BottomSheetBehavior.STATE_EXPANDED
                }
                behavior.skipCollapsed = false
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadSettings()
        setupNetworkSettings()
        setupCorsSettings()
        setupAuthSettings()
        setupServiceControl()
        setupActionButtons()
    }

    private fun refreshServiceStatus() {
        val running = ApiServiceManager.isApiServiceRunning()
        binding.tvServiceStatus.text = getString(
            if (running) R.string.api_service_status_running else R.string.api_service_stopped
        )
        binding.btnServiceStart.isEnabled = !running
        binding.btnServiceStop.isEnabled = running
        binding.btnServiceRestart.isEnabled = running
    }

    private fun setupServiceControl() {
        refreshServiceStatus()
        // Poll status: model loading on start can take a while, so keep the label in sync
        lifecycleScope.launch {
            while (true) {
                delay(1000)
                if (!isAdded) break
                refreshServiceStatus()
            }
        }

        binding.btnServiceStart.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    ApiServiceManager.startApiService(requireContext())
                }
                Toast.makeText(requireContext(), R.string.api_service_starting, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnServiceStop.setOnClickListener {
            lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    ApiServiceManager.stopApiService(requireContext())
                }
                delay(300)
                refreshServiceStatus()
                Toast.makeText(requireContext(), R.string.api_service_stopped_msg, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnServiceRestart.setOnClickListener {
            ApiServiceManager.restartApiService(requireContext())
            lifecycleScope.launch {
                delay(800)
                if (!isAdded) return@launch
                refreshServiceStatus()
                Toast.makeText(requireContext(), R.string.api_service_restarted, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadSettings() {
        //Load settings using ApiServerConfig
        val context = requireContext()

        currentPort = ApiServerConfig.getPort(context)
        currentIpAddress = ApiServerConfig.getIpAddress(context)
        corsEnabled = ApiServerConfig.isCorsEnabled(context)
        corsOrigins = ApiServerConfig.getCorsOrigins(context)
        authEnabled = ApiServerConfig.isAuthEnabled(context)
        apiKey = ApiServerConfig.getApiKey(context)
        useHttpsUrl = ApiServerConfig.useHttpsUrl(context)
        responsesApiEnabled = ApiServerConfig.isResponsesApiEnabled(context)
        promptCacheKeyEnabled = ApiServerConfig.isPromptCacheKeyEnabled(context)

        //Update UI
        binding.editPort.setText(currentPort.toString())
        binding.editIpAddress.setText(currentIpAddress)
        binding.switchCors.isChecked = corsEnabled
        binding.editCorsOrigins.setText(corsOrigins)
        binding.switchAuth.isChecked = authEnabled
        binding.editApiKey.setText(apiKey)
        binding.switchUseHttpsUrl.isChecked = useHttpsUrl
        binding.switchResponsesApi.isChecked = responsesApiEnabled
        binding.switchPromptCacheKey.isChecked = promptCacheKeyEnabled

        updateCorsVisibility()
        updateAuthVisibility()
        updateAccessUrl()
    }

    /** Ensure the service is running, then open the built-in test page in a browser. */
    private fun openServiceInBrowser() {
        if (binding.textAccessUrl.text.isNullOrBlank()) {
            updateAccessUrl()
        }
        if (ApiServiceManager.isApiServiceRunning()) {
            launchBrowser()
            return
        }
        Toast.makeText(requireContext(), R.string.api_service_starting, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                ApiServiceManager.startApiService(requireContext())
            }
            delay(1500)
            if (isAdded) launchBrowser()
        }
    }

    private fun launchBrowser() {
        val base = binding.textAccessUrl.text.toString().removeSuffix("/v1")
        val url = if (base.isBlank()) "http://127.0.0.1:$currentPort" else base
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$url/")))
        }.onFailure {
            Toast.makeText(requireContext(), R.string.browser_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** 显示当前配置的完整访问地址。监听所有网卡(0.0.0.0)时用设备局域网IP，便于浏览器直接打开。 */
    private fun updateAccessUrl() {
        val host = when {
            currentIpAddress.isBlank() -> "127.0.0.1"
            currentIpAddress == "0.0.0.0" -> {
                // 绑定到所有网卡时，展示设备实际局域网IP，浏览器才能访问
                val deviceIp = try {
                    getDeviceIpAddress()
                } catch (e: Exception) {
                    "127.0.0.1"
                }
                if (deviceIp.isBlank() || deviceIp == "0.0.0.0") "127.0.0.1" else deviceIp
            }
            else -> currentIpAddress
        }
        val scheme = if (useHttpsUrl) "https" else "http"
        binding.textAccessUrl.text = "$scheme://$host:$currentPort/v1"
    }

    private fun setupNetworkSettings() {
        binding.editPort.addTextChangedListener { text ->
            if (!text.isNullOrEmpty()) {
                try {
                    currentPort = text.toString().toInt()
                } catch (e: NumberFormatException) {
                    //Ignore invalid input
                }
            }
            updateAccessUrl()
        }

        binding.editIpAddress.addTextChangedListener { text ->
            if (!text.isNullOrEmpty()) {
                currentIpAddress = text.toString()
            }
            updateAccessUrl()
        }

        binding.switchUseHttpsUrl.setOnCheckedChangeListener { _, isChecked ->
            useHttpsUrl = isChecked
            updateAccessUrl()
        }

        binding.switchResponsesApi.setOnCheckedChangeListener { _, isChecked ->
            responsesApiEnabled = isChecked
        }

        binding.switchPromptCacheKey.setOnCheckedChangeListener { _, isChecked ->
            promptCacheKeyEnabled = isChecked
        }

        binding.btnOpenInBrowser.setOnClickListener {
            openServiceInBrowser()
        }

        binding.textAccessUrl.setOnClickListener {
            val clipboard = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("API URL", binding.textAccessUrl.text)
            )
            Toast.makeText(requireContext(), getString(R.string.access_url_copied), Toast.LENGTH_SHORT).show()
        }

        //IP address quick setup button
        binding.btnIpLocalhost.setOnClickListener {
            binding.editIpAddress.setText("127.0.0.1")
            currentIpAddress = "127.0.0.1"
        }

        binding.btnIpLan.setOnClickListener {
            binding.editIpAddress.setText("0.0.0.0")
            currentIpAddress = "0.0.0.0"
        }

        binding.btnIpAuto.setOnClickListener {
            val deviceIp = getDeviceIpAddress()
            binding.editIpAddress.setText(deviceIp)
            currentIpAddress = deviceIp
        }
    }

    private fun setupCorsSettings() {
        binding.switchCors.setOnCheckedChangeListener { _, isChecked ->
            corsEnabled = isChecked
            updateCorsVisibility()
        }

        binding.editCorsOrigins.addTextChangedListener { text ->
            corsOrigins = text.toString()
        }
    }

    private fun setupAuthSettings() {
        binding.switchAuth.setOnCheckedChangeListener { _, isChecked ->
            authEnabled = isChecked
            updateAuthVisibility()
        }

        binding.editApiKey.addTextChangedListener { text ->
            apiKey = text.toString()
        }
    }

    private fun updateCorsVisibility() {
        binding.layoutCorsOrigins.visibility = if (corsEnabled) View.VISIBLE else View.GONE
    }

    private fun updateAuthVisibility() {
        binding.layoutApiKey.visibility = if (authEnabled) View.VISIBLE else View.GONE
    }

    private fun setupActionButtons() {
        binding.buttonCancel.setOnClickListener {
            dismiss()
        }

        binding.buttonSave.setOnClickListener {
            saveSettings()
            dismiss()
        }

        binding.buttonReset.setOnClickListener {
            resetToDefaults()
        }
    }

    private fun saveSettings() {
        val context = requireContext()

        //Save configuration using ApiServerConfig
        ApiServerConfig.saveConfig(
            context = context,
            port = currentPort,
            ipAddress = currentIpAddress,
            corsEnabled = corsEnabled,
            corsOrigins = corsOrigins,
            authEnabled = authEnabled,
            apiKey = apiKey,
            useHttpsUrl = useHttpsUrl,
            responsesApiEnabled = responsesApiEnabled,
            promptCacheKeyEnabled = promptCacheKeyEnabled
        )

        //Commented out but keep these two Toasts, can be used for testing
        //  Toast.makeText(context, getString(R.string.api_settings_saved), Toast.LENGTH_SHORT).show()

        //Apply the new configuration to a running service (even if auto-start is off)
        val serviceShouldRun = MainSettings.isApiServiceEnabled(context) || ApiServiceManager.isApiServiceRunning()
        if (serviceShouldRun) {
            if (ApiServiceManager.isApiServiceRunning()) {
                //Print isApiServiceRunning value
                Timber.d("saveSettings中isApiServiceRunning: ${ApiServiceManager.isApiServiceRunning()}")


                //  Toast.makeText(context, getString(R.string.restarting_api_service_new_config), Toast.LENGTH_SHORT).show()

                //Restart in place. Call it directly: this fragment may be dismissed right after
                //saving, and dismiss cancels the fragment lifecycleScope before it can run.
                ApiServiceManager.restartApiService(context)
                Toast.makeText(context, getString(R.string.api_settings_saved), Toast.LENGTH_SHORT).show()

                //Print new configuration，
                Timber.d("API服务已启用并New API settings: Port=$currentPort, IP=$currentIpAddress, CORS=$corsEnabled, Auth=$authEnabled")

            } else {
                //Start service (using new configuration)
                ApiServiceManager.startApiService(context)
                Toast.makeText(context, getString(R.string.api_settings_saved), Toast.LENGTH_SHORT).show()

                //Print new configuration
                Timber.d("API服务未启用并New API settings: Port=$currentPort, IP=$currentIpAddress, CORS=$corsEnabled, Auth=$authEnabled")
            }
        }
    }

    private fun resetToDefaults() {
        val context = requireContext()
        ApiServerConfig.resetToDefault(context) //Reset configuration to default values
        Toast.makeText(context, getString(R.string.config_restored_default_processing_service), Toast.LENGTH_SHORT).show()

        val serviceShouldRun = MainSettings.isApiServiceEnabled(context) //Check if service should be running

        if (ApiServiceManager.isApiServiceRunning()) {
            if (serviceShouldRun) {
                ApiServiceManager.restartApiService(context)
            } else {
                ApiServiceManager.stopApiService(context)
            }
            lifecycleScope.launch {
                delay(800)
                if (!isAdded) return@launch
                loadSettings() //Update UI in coroutine, ensure execution after service operations
                refreshServiceStatus()
            }
        } else {
            //If service is not running
            //ServerEventManager.getInstance().resetRuntimeState() // Also reset state just in case
            if (serviceShouldRun) {
                //If service was previously enabled but not running, start with new default configuration
                Timber.d("ApiService is enabled but was not running, starting with default settings.")
                ApiServiceManager.startApiService(context)
            }
            loadSettings() //Update UI
            refreshServiceStatus()
        }
    }

    /** * getdeviceIPaddress * Prioritize getting WiFi IPaddress，if not availablethengetmobiledataIPaddress*/
    private fun getDeviceIpAddress(): String {
        try {
            val context = requireContext()
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            //Get current active network
            val activeNetwork = connectivityManager.activeNetwork
            val networkCapabilities = connectivityManager.getNetworkCapabilities(activeNetwork)

            //Prioritize getting WiFi IP
            if (networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val wifiInfo = wifiManager.connectionInfo
                val ipInt = wifiInfo.ipAddress
                if (ipInt != 0) {
                    return String.format(
                        Locale.getDefault(),
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                }
            }

            //If WiFi unavailable, try getting IP from other network interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (networkInterface in Collections.list(interfaces)) {
                if (!networkInterface.isLoopback && networkInterface.isUp) {
                    val addresses = networkInterface.inetAddresses
                    for (address in Collections.list(addresses)) {
                        if (!address.isLoopbackAddress && address.hostAddress?.contains(":") == false) {
                            return address.hostAddress ?: "0.0.0.0"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        //If unable to get IP address, return 0.0.0.0
        return "0.0.0.0"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "ApiSettingsBottomSheetFragment"
    }
}