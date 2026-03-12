package com.ada.assistant.ui.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.ada.assistant.R
import com.ada.assistant.databinding.ActivityMainBinding
import com.ada.assistant.ui.adapters.MessageAdapter
import com.ada.assistant.ui.viewmodels.AdaViewModel
import com.ada.assistant.voice.VoiceState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: AdaViewModel by viewModels()
    private lateinit var messageAdapter: MessageAdapter

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val audioGranted = permissions[Manifest.permission.RECORD_AUDIO] == true
        if (!audioGranted) {
            Toast.makeText(this, "Microphone permission needed for voice commands", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestPermissions()
        setupRecyclerView()
        setupInputControls()
        observeViewModel()
    }

    private fun requestPermissions() {
        val needed = arrayOf(Manifest.permission.RECORD_AUDIO)
        val notGranted = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (notGranted.isNotEmpty()) {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter()
        binding.recyclerMessages.apply {
            adapter = messageAdapter
            layoutManager = LinearLayoutManager(this@MainActivity).apply {
                stackFromEnd = true
            }
        }
    }

    private fun setupInputControls() {
        binding.apply {
            // Send button
            btnSend.setOnClickListener {
                val text = etMessage.text?.toString()?.trim() ?: ""
                if (text.isNotEmpty()) {
                    viewModel.sendMessage(text)
                    etMessage.text?.clear()
                }
            }

            // Voice button
            btnVoice.setOnClickListener {
                viewModel.toggleVoiceListening()
            }

            // TTS toggle
            btnTts.setOnClickListener {
                viewModel.toggleTts()
            }

            // Stop speaking
            btnStop.setOnClickListener {
                viewModel.stopSpeaking()
            }

            // Settings
            btnSettings.setOnClickListener {
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }

            // New conversation
            btnNewChat.setOnClickListener {
                viewModel.startNewConversation()
            }

            // Memory viewer
            btnMemory.setOnClickListener {
                startActivity(Intent(this@MainActivity, MemoryViewerActivity::class.java))
            }
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            // Messages
            viewModel.messages.collectLatest { messages ->
                messageAdapter.submitList(messages)
                if (messages.isNotEmpty()) {
                    binding.recyclerMessages.smoothScrollToPosition(messages.size - 1)
                }
            }
        }

        lifecycleScope.launch {
            // Thinking indicator
            viewModel.isThinking.collectLatest { thinking ->
                binding.thinkingIndicator.visibility = if (thinking) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            // Status message
            viewModel.statusMessage.collectLatest { status ->
                binding.tvStatus.text = status
            }
        }

        lifecycleScope.launch {
            // Voice state
            viewModel.voiceState.collectLatest { state ->
                when (state) {
                    is VoiceState.Listening -> {
                        binding.btnVoice.setImageResource(R.drawable.ic_mic_active)
                        binding.tvStatus.text = "Listening..."
                        binding.voiceWaveform.visibility = View.VISIBLE
                    }
                    is VoiceState.Processing -> {
                        binding.tvStatus.text = "Processing speech..."
                        binding.voiceWaveform.visibility = View.GONE
                    }
                    is VoiceState.Speaking -> {
                        binding.btnVoice.setImageResource(R.drawable.ic_mic)
                        binding.tvStatus.text = "ADA is speaking..."
                    }
                    is VoiceState.Idle -> {
                        binding.btnVoice.setImageResource(R.drawable.ic_mic)
                        binding.voiceWaveform.visibility = View.GONE
                    }
                    is VoiceState.Error -> {
                        binding.tvStatus.text = "Voice error"
                        binding.voiceWaveform.visibility = View.GONE
                    }
                }
            }
        }

        lifecycleScope.launch {
            // Partial speech
            viewModel.partialSpeech.collectLatest { partial ->
                if (partial.isNotBlank()) {
                    binding.etMessage.hint = partial
                } else {
                    binding.etMessage.hint = getString(R.string.hint_message)
                }
            }
        }

        lifecycleScope.launch {
            // TTS toggle button indicator
            viewModel.ttsEnabled.collectLatest { enabled ->
                binding.btnTts.alpha = if (enabled) 1.0f else 0.4f
            }
        }

        lifecycleScope.launch {
            // Active agent display
            viewModel.activeAgent.collectLatest { agent ->
                binding.tvAgent.text = "[$agent]"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopSpeaking()
    }
}

// ─── Splash Activity ──────────────────────────────────────────────────────────

@AndroidEntryPoint
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Brief splash then launch main
        android.os.Handler(mainLooper).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }, 1500)
    }
}

// ─── Settings Activity ────────────────────────────────────────────────────────

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {
    private val viewModel: AdaViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.title = "ADA Settings"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // Settings fragment would be loaded here
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}

// ─── Memory Viewer Activity ───────────────────────────────────────────────────

@AndroidEntryPoint
class MemoryViewerActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_memory_viewer)
        supportActionBar?.title = "ADA Memory"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
