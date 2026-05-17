package nz.valenta.androidtvmqtt.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import nz.valenta.androidtvmqtt.databinding.ActivityHistoryBinding
import nz.valenta.androidtvmqtt.service.MqttForegroundService
import kotlinx.coroutines.launch

class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHistoryBinding
    private val viewModel: HistoryViewModel by viewModels()
    private lateinit var adapter: MessageAdapter

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(MqttForegroundService.EXTRA_STATUS) ?: return
            updateServiceStatus(status)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHistoryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupButtons()
        observeMessages()
        autoConnectIfNeeded()
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
        updateServiceStatus(if (MqttForegroundService.isRunning) "Running" else "Stopped")
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(statusReceiver)
    }

    private fun setupRecyclerView() {
        adapter = MessageAdapter()
        // Newest at the top
        binding.rvMessages.layoutManager = LinearLayoutManager(this)
        binding.rvMessages.adapter = adapter
    }

    private fun setupButtons() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, ConfigActivity::class.java).apply {
                putExtra(ConfigActivity.EXTRA_FORCE_CONFIG, true)
            })
        }
        binding.btnReconnect.setOnClickListener { reconnect() }
    }

    private fun observeMessages() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.messages.collect { messages ->
                    // Show newest first
                    adapter.submitList(messages.reversed())
                    binding.tvEmpty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun autoConnectIfNeeded() {
        if (!MqttForegroundService.isRunning) {
            ContextCompat.startForegroundService(
                this,
                Intent(this, MqttForegroundService::class.java).apply {
                    action = MqttForegroundService.ACTION_START
                }
            )
        }
    }

    private fun reconnect() {
        startService(Intent(this, MqttForegroundService::class.java).apply {
            action = MqttForegroundService.ACTION_STOP
        })
        ContextCompat.startForegroundService(
            this,
            Intent(this, MqttForegroundService::class.java).apply {
                action = MqttForegroundService.ACTION_START
            }
        )
        updateServiceStatus("Connecting…")
    }

    private fun updateServiceStatus(status: String) {
        binding.tvServiceStatus.text = "● $status"
    }
}
