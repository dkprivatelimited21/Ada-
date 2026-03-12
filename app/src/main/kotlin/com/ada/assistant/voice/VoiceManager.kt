package com.ada.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

// ─── Voice State ──────────────────────────────────────────────────────────────

sealed class VoiceState {
    object Idle : VoiceState()
    object Listening : VoiceState()
    object Processing : VoiceState()
    object Speaking : VoiceState()
    data class Error(val message: String) : VoiceState()
}

sealed class VoiceEvent {
    data class SpeechResult(val text: String, val confidence: Float) : VoiceEvent()
    data class PartialResult(val text: String) : VoiceEvent()
    object SpeechStart : VoiceEvent()
    object SpeechEnd : VoiceEvent()
    data class Error(val errorCode: Int, val message: String) : VoiceEvent()
    object TtsComplete : VoiceEvent()
}

// ─── Voice Manager ────────────────────────────────────────────────────────────

@Singleton
class VoiceManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "VoiceManager"

    // TTS
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsPitch = 1.05f      // Slightly higher for Ada's voice
    private var ttsSpeechRate = 0.95f  // Slightly slower, more composed

    // STT
    private var speechRecognizer: SpeechRecognizer? = null

    // State flows
    private val _voiceState = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voiceState: StateFlow<VoiceState> = _voiceState

    private val _events = Channel<VoiceEvent>(Channel.BUFFERED)
    val events: Flow<VoiceEvent> = _events.receiveAsFlow()

    // Settings
    private var wakeWord = "ada"
    private var language = Locale.ENGLISH

    init {
        initTts()
    }

    // ── TTS ────────────────────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(language)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "TTS language not supported, falling back to default")
                    tts?.setLanguage(Locale.getDefault())
                }

                // Ada's voice profile
                tts?.setPitch(ttsPitch)
                tts?.setSpeechRate(ttsSpeechRate)

                // Try to set a more feminine voice
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val voices = tts?.voices
                    val femaleVoice = voices?.firstOrNull {
                        it.name.contains("female", ignoreCase = true) ||
                        it.name.contains("en-us-x-sfg", ignoreCase = true) ||
                        it.name.contains("en_US_female", ignoreCase = true)
                    }
                    femaleVoice?.let { tts?.voice = it }
                }

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _voiceState.tryEmit(VoiceState.Speaking)
                    }
                    override fun onDone(utteranceId: String?) {
                        _voiceState.tryEmit(VoiceState.Idle)
                        _events.trySend(VoiceEvent.TtsComplete)
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        _voiceState.tryEmit(VoiceState.Idle)
                    }
                })

                ttsReady = true
                Log.d(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }
    }

    fun speak(text: String, interrupt: Boolean = true) {
        if (!ttsReady) {
            Log.w(TAG, "TTS not ready")
            return
        }

        val cleanText = text
            .replace(Regex("```[\\s\\S]*?```"), "code block omitted")
            .replace(Regex("[*_#`>]"), "")
            .trim()

        val queueMode = if (interrupt) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val utteranceId = "ada_${System.currentTimeMillis()}"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            tts?.speak(cleanText, queueMode, null, utteranceId)
        } else {
            @Suppress("DEPRECATION")
            val params = HashMap<String, String>()
            params[TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID] = utteranceId
            tts?.speak(cleanText, queueMode, params)
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        _voiceState.tryEmit(VoiceState.Idle)
    }

    fun setTtsVoiceParams(pitch: Float, rate: Float) {
        ttsPitch = pitch
        ttsSpeechRate = rate
        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)
    }

    // ── STT ────────────────────────────────────────────────────────────────────

    fun startListening(continuous: Boolean = false) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            _events.trySend(VoiceEvent.Error(-1, "Speech recognition not available on this device"))
            return
        }

        stopSpeaking()  // Silence Ada before listening

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _voiceState.tryEmit(VoiceState.Listening)
                    _events.trySend(VoiceEvent.SpeechStart)
                    Log.d(TAG, "Ready for speech")
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    _voiceState.tryEmit(VoiceState.Processing)
                    _events.trySend(VoiceEvent.SpeechEnd)
                }

                override fun onError(error: Int) {
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client side error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognition service busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                        else -> "Unknown error: $error"
                    }
                    Log.e(TAG, "STT error: $msg")
                    _voiceState.tryEmit(VoiceState.Error(msg))
                    _events.trySend(VoiceEvent.Error(error, msg))
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val text = matches?.firstOrNull() ?: ""
                    val confidence = confidences?.firstOrNull() ?: 0.8f

                    if (text.isNotBlank()) {
                        Log.d(TAG, "STT result: '$text' (${confidence})")
                        _events.trySend(VoiceEvent.SpeechResult(text, confidence))
                    }
                    _voiceState.tryEmit(VoiceState.Idle)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                    if (partial.isNotBlank()) {
                        _events.trySend(VoiceEvent.PartialResult(partial))
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
        }

        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        _voiceState.tryEmit(VoiceState.Idle)
    }

    // ── Wake Word ──────────────────────────────────────────────────────────────

    fun containsWakeWord(text: String): Boolean {
        return text.lowercase().contains(wakeWord.lowercase())
    }

    fun removeWakeWord(text: String): String {
        return text.replace(Regex("\\b$wakeWord\\b", RegexOption.IGNORE_CASE), "").trim()
            .replaceFirst(Regex("^,\\s*"), "")
    }

    fun setWakeWord(word: String) {
        wakeWord = word
    }

    // ── Cleanup ────────────────────────────────────────────────────────────────

    fun destroy() {
        speechRecognizer?.destroy()
        tts?.shutdown()
    }

    fun isTtsReady() = ttsReady
    fun getCurrentState() = _voiceState.value
}
