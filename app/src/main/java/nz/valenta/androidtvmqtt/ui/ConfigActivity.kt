package nz.valenta.androidtvmqtt.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.core.content.ContextCompat
import nz.valenta.androidtvmqtt.R
import nz.valenta.androidtvmqtt.data.SettingsRepository
import nz.valenta.androidtvmqtt.databinding.ActivityConfigBinding
import nz.valenta.androidtvmqtt.model.MqttSettings
import nz.valenta.androidtvmqtt.service.MqttForegroundService
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class ConfigActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FORCE_CONFIG = "force_config"
    }

    private lateinit var binding: ActivityConfigBinding
    private val viewModel: ConfigViewModel by viewModels()

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updateOverlayPermissionStatus()
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(MqttForegroundService.EXTRA_STATUS) ?: return
            updateServiceStatus(status)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Route to HistoryActivity if settings already configured (unless forced to show config)
        if (!intent.getBooleanExtra(EXTRA_FORCE_CONFIG, false)) {
            val settings = runBlocking { SettingsRepository(applicationContext).getSettings() }
            if (settings.isValid) {
                startActivity(Intent(this, HistoryActivity::class.java))
                finish()
                return
            }
        }

        binding = ActivityConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()
        observeViewModel()
        updateOverlayPermissionStatus()
        updateServiceStatus(if (MqttForegroundService.isRunning) "Running" else "Stopped")
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(MqttForegroundService.BROADCAST_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(statusReceiver, filter)
        }
        updateOverlayPermissionStatus()
        updateServiceStatus(if (MqttForegroundService.isRunning) "Running" else "Stopped")
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    // region Setup

    private fun setupButtons() {
        binding.btnDiscoverBrokers.setOnClickListener { startDiscovery() }
        binding.btnScanTopics.setOnClickListener { startTopicScan() }
        binding.btnGrantOverlay.setOnClickListener { openOverlaySettings() }
        binding.btnSaveStart.setOnClickListener { saveAndStart() }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Populate form with saved settings (once)
                launch {
                    viewModel.settings.collect { s ->
                        if (binding.etBrokerHost.text.isNullOrEmpty() && s.brokerHost.isNotBlank()) {
                            binding.etBrokerHost.setText(s.brokerHost)
                            binding.etBrokerPort.setText(s.brokerPort.toString())
                            binding.etUsername.setText(s.username)
                            binding.etPassword.setText(s.password)
                            binding.etTopic.setText(s.topic)
                            binding.cbAutoStart.isChecked = s.autoStart
                        }
                    }
                }

                // UI state feedback
                launch {
                    viewModel.uiState.collect { state ->
                        when (state) {
                            is UiState.Idle -> hideProgress()
                            is UiState.Discovering -> showProgress("Discovering brokers…")
                            is UiState.ScanningTopics -> showProgress("Scanning topics for 5s…")
                            is UiState.Saving -> showProgress("Saving…")
                            is UiState.Error -> {
                                hideProgress()
                                toast(state.message)
                                viewModel.clearState()
                            }
                            is UiState.Success -> {
                                hideProgress()
                                toast(state.message)
                                viewModel.clearState()
                            }
                        }
                    }
                }

                // Show discovered brokers dialog when results arrive
                launch {
                    viewModel.discoveredBrokers.collect { brokers ->
                        if (brokers.isNotEmpty()) {
                            viewModel.stopDiscovery()
                            showBrokerPickerDialog(brokers.map { it.toString() }) { selected ->
                                brokers.find { it.toString() == selected }?.let {
                                    binding.etBrokerHost.setText(it.host)
                                    binding.etBrokerPort.setText(it.port.toString())
                                }
                            }
                        }
                    }
                }

                // Show scanned topics dialog
                launch {
                    viewModel.scannedTopics.collect { topics ->
                        if (topics.isNotEmpty()) {
                            showTopicPickerDialog(topics) { selected ->
                                binding.etTopic.setText(selected)
                            }
                        }
                    }
                }
            }
        }
    }

    // endregion

    // region Actions

    private fun startDiscovery() {
        viewModel.discoverBrokers()
        toast("Scanning network for MQTT brokers…")
    }

    private fun startTopicScan() {
        val host = binding.etBrokerHost.text?.toString()?.trim() ?: ""
        val port = binding.etBrokerPort.text?.toString()?.toIntOrNull() ?: 1883
        val username = binding.etUsername.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString() ?: ""
        if (host.isBlank()) {
            toast("Enter broker IP first")
            return
        }
        viewModel.scanTopics(host, port, username, password)
    }

    private fun saveAndStart() {
        val host = binding.etBrokerHost.text?.toString()?.trim() ?: ""
        val port = binding.etBrokerPort.text?.toString()?.toIntOrNull() ?: 1883
        val username = binding.etUsername.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString() ?: ""
        val topic = binding.etTopic.text?.toString()?.trim() ?: ""
        val autoStart = binding.cbAutoStart.isChecked

        if (host.isBlank() || topic.isBlank()) {
            toast("Broker IP and Topic are required")
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            AlertDialog.Builder(this)
                .setTitle("Overlay Permission Required")
                .setMessage("Grant 'Display over other apps' permission so notifications appear on top of the TV screen.")
                .setPositiveButton("Grant") { _, _ -> openOverlaySettings() }
                .setNegativeButton("Continue without overlay") { _, _ ->
                    doSaveAndStart(MqttSettings(host, port, topic, username, password, autoStart))
                }
                .show()
            return
        }

        doSaveAndStart(MqttSettings(host, port, topic, username, password, autoStart))
    }

    private fun doSaveAndStart(settings: MqttSettings) {
        viewModel.saveSettings(settings)
        // Stop existing service then restart with new settings
        if (MqttForegroundService.isRunning) {
            startService(Intent(this, MqttForegroundService::class.java).apply {
                action = MqttForegroundService.ACTION_STOP
            })
        }
        ContextCompat.startForegroundService(
            this,
            Intent(this, MqttForegroundService::class.java).apply {
                action = MqttForegroundService.ACTION_START
            }
        )
        val forceConfig = intent.getBooleanExtra(EXTRA_FORCE_CONFIG, false)
        if (forceConfig) {
            // Came from HistoryActivity — go back to it
            finish()
        } else {
            // First-time save — open HistoryActivity
            startActivity(Intent(this, HistoryActivity::class.java))
            finish()
        }
    }

    private fun stopService() {
        val intent = Intent(this, MqttForegroundService::class.java).apply {
            action = MqttForegroundService.ACTION_STOP
        }
        startService(intent)
        updateServiceStatus("Stopping…")
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        overlayPermissionLauncher.launch(intent)
    }

    // endregion

    // region UI helpers

    private fun showBrokerPickerDialog(items: List<String>, onSelected: (String) -> Unit) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Select Broker")
            .setItems(items.toTypedArray()) { _, which -> onSelected(items[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showTopicPickerDialog(topics: List<String>, onSelected: (String) -> Unit) {
        if (isFinishing || isDestroyed) return
        AlertDialog.Builder(this)
            .setTitle("Select Topic")
            .setItems(topics.toTypedArray()) { _, which -> onSelected(topics[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showProgress(msg: String) {
        binding.tvStatus.text = msg
        binding.progressBar.visibility = View.VISIBLE
    }

    private fun hideProgress() {
        binding.progressBar.visibility = View.INVISIBLE
    }

    private fun updateServiceStatus(status: String) {
        binding.tvServiceStatus.text = "Service: $status"
        binding.btnSaveStart.text = getString(R.string.save_connect)
    }

    private fun updateOverlayPermissionStatus() {
        val granted = Settings.canDrawOverlays(this)
        binding.tvOverlayStatus.text = if (granted)
            "✓ Overlay permission granted"
        else
            "⚠ Overlay permission not granted (tap to fix)"
        binding.btnGrantOverlay.isEnabled = !granted
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // endregion
}
