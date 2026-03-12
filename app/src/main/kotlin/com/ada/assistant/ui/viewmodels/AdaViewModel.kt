package com.ada.assistant.ui.viewmodels

import android.util.Log
import androidx.lifecycle.*
import com.ada.assistant.ai.agents.AgentManager
import com.ada.assistant.ai.engine.AiEngine
import com.ada.assistant.data.local.ConversationDao
import com.ada.assistant.data.local.MessageDao
import com.ada.assistant.data.models.*
import com.ada.assistant.memory.MemoryManager
import com.ada.assistant.voice.VoiceEvent
import com.ada.assistant.voice.VoiceManager
import com.ada.assistant.voice.VoiceState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AdaViewModel @Inject constructor(
    private val agentManager: AgentManager,
    private val memoryManager: MemoryManager,
    private val aiEngine: AiEngine,
    private val voiceManager: VoiceManager,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao
) : ViewModel() {

    private val TAG = "AdaViewModel"

    // ── Conversation State ─────────────────────────────────────────────────────

    private val _currentConversationId = MutableStateFlow(generateConversationId())
    val currentConversationId: StateFlow<String> = _currentConversationId

    val messages: StateFlow<List<ChatMessage>> = _currentConversationId
        .flatMapLatest { convId ->
            messageDao.getMessagesForConversation(convId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val conversations: StateFlow<List<Conversation>> = conversationDao.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // ── UI State ───────────────────────────────────────────────────────────────

    private val _uiState = MutableStateFlow<UiState<AdaResponse>>(UiState.Empty)
    val uiState: StateFlow<UiState<AdaResponse>> = _uiState

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking

    private val _statusMessage = MutableStateFlow("Ready")
    val statusMessage: StateFlow<String> = _statusMessage

    private val _activeAgent = MutableStateFlow("conversation")
    val activeAgent: StateFlow<String> = _activeAgent

    // ── Voice State ────────────────────────────────────────────────────────────

    val voiceState: StateFlow<VoiceState> = voiceManager.voiceState

    private val _partialSpeech = MutableStateFlow("")
    val partialSpeech: StateFlow<String> = _partialSpeech

    // ── User Profile ───────────────────────────────────────────────────────────

    val userProfile: StateFlow<UserProfile?> = memoryManager.getUserProfile()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // ── Settings ───────────────────────────────────────────────────────────────

    private val _voiceEnabled = MutableStateFlow(false)
    val voiceEnabled: StateFlow<Boolean> = _voiceEnabled

    private val _ttsEnabled = MutableStateFlow(true)
    val ttsEnabled: StateFlow<Boolean> = _ttsEnabled

    init {
        initializeConversation()
        observeVoiceEvents()
    }

    // ── Initialization ─────────────────────────────────────────────────────────

    private fun initializeConversation() {
        viewModelScope.launch {
            // Ensure user profile exists
            val profile = memoryManager.getUserProfileSync()
            if (profile == null) {
                memoryManager.saveUserProfile(UserProfile())
            }

            // Create conversation record
            val convId = _currentConversationId.value
            conversationDao.insert(
                Conversation(
                    id = convId,
                    title = "New Conversation"
                )
            )

            // Send greeting
            sendGreeting()
        }
    }

    private suspend fun sendGreeting() {
        val profile = userProfile.value
        val name = profile?.name?.let { if (it.isNotBlank()) ", $it" else "" } ?: ""
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 12 -> "Good morning$name."
            hour < 17 -> "Good afternoon$name."
            else -> "Good evening$name."
        }

        val memoryContext = memoryManager.buildMemoryContext()
        val intro = if (memoryContext.isBlank()) {
            "$greeting I'm ADA, your Adaptive Digital Assistant. How can I help you today?"
        } else {
            "$greeting I'm ready to assist you."
        }

        insertAssistantMessage(intro)
        if (_ttsEnabled.value) {
            voiceManager.speak(intro)
        }
    }

    private fun observeVoiceEvents() {
        viewModelScope.launch {
            voiceManager.events.collect { event ->
                when (event) {
                    is VoiceEvent.SpeechResult -> {
                        _partialSpeech.value = ""
                        if (event.confidence > 0.3f) {
                            processUserMessage(event.text, isVoice = true)
                        }
                    }
                    is VoiceEvent.PartialResult -> {
                        _partialSpeech.value = event.text
                    }
                    is VoiceEvent.Error -> {
                        _statusMessage.value = "Voice error: ${event.message}"
                        _partialSpeech.value = ""
                    }
                    else -> {}
                }
            }
        }
    }

    // ── Message Processing ─────────────────────────────────────────────────────

    fun sendMessage(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            processUserMessage(text, isVoice = false)
        }
    }

    private suspend fun processUserMessage(text: String, isVoice: Boolean) {
        val convId = _currentConversationId.value

        // 1. Save user message
        val userMsg = ChatMessage(
            conversationId = convId,
            role = MessageRole.USER,
            content = text
        )
        messageDao.insert(userMsg)
        conversationDao.touchConversation(convId)

        // 2. Set thinking state
        _isThinking.value = true
        _uiState.value = UiState.Loading
        _statusMessage.value = "Thinking..."

        try {
            // 3. Get conversation history
            val history = messageDao.getRecentMessages(convId, 20)

            // 4. Record interaction
            memoryManager.recordInteraction()

            // 5. Process through agent system
            val result = withContext(Dispatchers.IO) {
                agentManager.process(
                    userInput = text,
                    conversationHistory = history,
                    userProfile = userProfile.value
                )
            }

            // 6. Update active agent indicator
            _activeAgent.value = result.agentId
            _statusMessage.value = "Response from ${result.agentId} agent"

            // 7. Save assistant response
            val assistantMsg = ChatMessage(
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = result.response,
                agentType = result.agentId
            )
            messageDao.insert(assistantMsg)
            conversationDao.touchConversation(convId)

            // 8. TTS if enabled
            if (_ttsEnabled.value || isVoice) {
                voiceManager.speak(result.response)
            }

            // 9. Update conversation title if first exchange
            val msgCount = messageDao.countMessages(convId)
            if (msgCount <= 3) {
                val title = text.take(40).let { if (text.length > 40) "$it..." else it }
                conversationDao.update(
                    Conversation(
                        id = convId,
                        title = title,
                        updatedAt = System.currentTimeMillis(),
                        messageCount = msgCount
                    )
                )
            }

            _uiState.value = UiState.Success(
                AdaResponse(
                    text = result.response,
                    agentUsed = result.agentId,
                    actions = result.actions.map { it.description }
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error processing message", e)
            val errorMsg = "I encountered an error. Please try again."
            insertAssistantMessage(errorMsg)
            _uiState.value = UiState.Error("Processing failed", e)
            _statusMessage.value = "Error"
        } finally {
            _isThinking.value = false
            _statusMessage.value = "Ready"
        }
    }

    private suspend fun insertAssistantMessage(text: String) {
        messageDao.insert(
            ChatMessage(
                conversationId = _currentConversationId.value,
                role = MessageRole.ASSISTANT,
                content = text,
                agentType = "system"
            )
        )
    }

    // ── Voice Control ──────────────────────────────────────────────────────────

    fun toggleVoiceListening() {
        when (voiceState.value) {
            is VoiceState.Idle -> {
                _voiceEnabled.value = true
                voiceManager.startListening()
            }
            is VoiceState.Listening -> {
                _voiceEnabled.value = false
                voiceManager.stopListening()
            }
            else -> {}
        }
    }

    fun toggleTts() {
        _ttsEnabled.value = !_ttsEnabled.value
        if (!_ttsEnabled.value) voiceManager.stopSpeaking()
    }

    fun stopSpeaking() = voiceManager.stopSpeaking()

    // ── Conversation Management ────────────────────────────────────────────────

    fun startNewConversation() {
        viewModelScope.launch {
            _currentConversationId.value = generateConversationId()
            initializeConversation()
        }
    }

    fun loadConversation(conversationId: String) {
        _currentConversationId.value = conversationId
        _statusMessage.value = "Conversation loaded"
    }

    fun clearCurrentConversation() {
        viewModelScope.launch {
            messageDao.deleteConversation(_currentConversationId.value)
            _currentConversationId.value = generateConversationId()
            initializeConversation()
        }
    }

    // ── Settings ───────────────────────────────────────────────────────────────

    fun configureOllama(baseUrl: String, model: String) {
        aiEngine.configureOllama(baseUrl, model)
        _statusMessage.value = "Connected to Ollama: $model"
    }

    fun configureOpenAI(apiKey: String, model: String = "gpt-4o-mini") {
        aiEngine.configureOpenAI(apiKey, model)
        _statusMessage.value = "Connected to OpenAI: $model"
    }

    fun updateUserName(name: String) {
        viewModelScope.launch {
            memoryManager.updateUserName(name)
        }
    }

    // ── Memory ─────────────────────────────────────────────────────────────────

    fun getAllMemories() = memoryManager.getAllMemories()

    fun deleteMemory(memory: Memory) {
        viewModelScope.launch {
            memoryManager.deleteMemory(memory)
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    private fun generateConversationId() = UUID.randomUUID().toString()

    fun getActiveProviderName() = aiEngine.getActiveProviderName()

    override fun onCleared() {
        super.onCleared()
        voiceManager.stopListening()
    }
}
