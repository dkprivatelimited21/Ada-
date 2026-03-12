package com.ada.assistant.ai.engine

import android.content.Context
import android.util.Log
import com.ada.assistant.data.models.*
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import javax.inject.Inject
import javax.inject.Singleton

// ─── AI Provider Interface ────────────────────────────────────────────────────

interface AiProvider {
    val name: String
    suspend fun chat(messages: List<OllamaMessage>, options: GenerationOptions): String
    suspend fun isAvailable(): Boolean
}

data class GenerationOptions(
    val temperature: Float = 0.7f,
    val maxTokens: Int = 512,
    val topP: Float = 0.9f,
    val systemPrompt: String = AiEngine.ADA_SYSTEM_PROMPT
)

// ─── Ollama Provider ──────────────────────────────────────────────────────────

interface OllamaApi {
    @POST("api/chat")
    suspend fun chat(@Body request: OllamaRequest): OllamaResponse
}

class OllamaProvider(
    private val baseUrl: String,
    private val model: String
) : AiProvider {
    override val name = "Ollama ($model)"

    private val retrofit = Retrofit.Builder()
        .baseUrl(baseUrl)
        .addConverterFactory(GsonConverterFactory.create())
        .client(
            OkHttpClient.Builder()
                .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )
        .build()

    private val api = retrofit.create(OllamaApi::class.java)

    override suspend fun chat(messages: List<OllamaMessage>, options: GenerationOptions): String {
        val allMessages = mutableListOf(
            OllamaMessage("system", options.systemPrompt)
        ) + messages

        val response = api.chat(
            OllamaRequest(
                model = model,
                messages = allMessages,
                stream = false,
                options = OllamaOptions(
                    temperature = options.temperature,
                    num_predict = options.maxTokens,
                    top_p = options.topP
                )
            )
        )
        return response.message.content
    }

    override suspend fun isAvailable(): Boolean = try {
        val client = OkHttpClient.Builder()
            .connectTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
            .build()
        val req = Request.Builder().url("${baseUrl}api/tags").get().build()
        client.newCall(req).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }
}

// ─── OpenAI-Compatible Provider ───────────────────────────────────────────────

class OpenAIProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String
) : AiProvider {
    override val name = "OpenAI ($model)"

    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    override suspend fun chat(messages: List<OllamaMessage>, options: GenerationOptions): String {
        val allMessages = mutableListOf(
            OpenAIMessage("system", options.systemPrompt)
        ) + messages.map { OpenAIMessage(it.role, it.content) }

        val requestBody = gson.toJson(
            OpenAIRequest(model = model, messages = allMessages, max_tokens = options.maxTokens)
        )

        val request = Request.Builder()
            .url("${baseUrl}v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaType()))
            .build()

        val responseStr = client.newCall(request).execute().use { it.body?.string() ?: "" }
        val response = gson.fromJson(responseStr, OpenAIResponse::class.java)
        return response.choices.firstOrNull()?.message?.content ?: "No response."
    }

    override suspend fun isAvailable(): Boolean = apiKey.isNotBlank()
}

// ─── Mock / Offline Provider ──────────────────────────────────────────────────

class MockProvider : AiProvider {
    override val name = "Mock (Offline Demo)"

    override suspend fun chat(messages: List<OllamaMessage>, options: GenerationOptions): String {
        val lastUser = messages.lastOrNull { it.role == "user" }?.content ?: ""
        return when {
            lastUser.contains("hello", ignoreCase = true) ||
            lastUser.contains("hi", ignoreCase = true) ->
                "Hello! I'm ADA, your Adaptive Digital Assistant. I'm currently running in offline demo mode. How can I help you today?"

            lastUser.contains("time", ignoreCase = true) ->
                "It's ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())}. Is there something you'd like me to schedule?"

            lastUser.contains("remind", ignoreCase = true) ->
                "I'd be happy to set a reminder for you. Please connect me to your Ollama server or API for full functionality."

            lastUser.contains("note", ignoreCase = true) ->
                "Note-taking is ready. I've saved your request. For full AI capabilities, please configure your AI server in Settings."

            else ->
                "I'm ADA, running in offline demo mode. To unlock my full capabilities including reasoning, research, and automation, please configure an AI server in Settings → AI Engine."
        }
    }

    override suspend fun isAvailable() = true
}

// ─── AI Engine (Main) ─────────────────────────────────────────────────────────

@Singleton
class AiEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "AiEngine"
    private val gson = Gson()

    // Provider priority: Ollama local → OpenAI → Mock
    private var providers: MutableList<AiProvider> = mutableListOf(MockProvider())
    private var activeProvider: AiProvider = providers[0]

    // Conversation history cache
    private val conversationCache = mutableListOf<OllamaMessage>()
    private val maxCacheSize = 20

    // ── Configuration ──────────────────────────────────────────────────────────

    fun configureOllama(baseUrl: String, model: String) {
        val provider = OllamaProvider(baseUrl, model)
        providers.add(0, provider)
        Log.d(TAG, "Ollama provider added: $baseUrl model=$model")
    }

    fun configureOpenAI(apiKey: String, model: String = "gpt-4o-mini", baseUrl: String = "https://api.openai.com/") {
        val provider = OpenAIProvider(baseUrl, apiKey, model)
        providers.add(0, provider)
        Log.d(TAG, "OpenAI provider added: $model")
    }

    suspend fun selectBestProvider(): AiProvider {
        for (provider in providers) {
            if (provider.isAvailable()) {
                activeProvider = provider
                Log.d(TAG, "Using provider: ${provider.name}")
                return provider
            }
        }
        activeProvider = MockProvider()
        return activeProvider
    }

    // ── Core AI Functions ──────────────────────────────────────────────────────

    suspend fun chat(
        userMessage: String,
        conversationHistory: List<ChatMessage> = emptyList(),
        options: GenerationOptions = GenerationOptions()
    ): String = withContext(Dispatchers.IO) {
        try {
            val provider = selectBestProvider()

            // Build message list from history
            val messages = buildMessageList(userMessage, conversationHistory)

            val response = provider.chat(messages, options)

            // Update cache
            conversationCache.add(OllamaMessage("user", userMessage))
            conversationCache.add(OllamaMessage("assistant", response))
            if (conversationCache.size > maxCacheSize) {
                conversationCache.removeAt(0)
                conversationCache.removeAt(0)
            }

            response
        } catch (e: Exception) {
            Log.e(TAG, "Chat error", e)
            "I encountered an error processing your request. Please check your AI server connection in Settings."
        }
    }

    suspend fun summarize(text: String): String = withContext(Dispatchers.IO) {
        val prompt = "Summarize the following text concisely in 2-3 sentences:\n\n$text"
        chat(prompt, options = GenerationOptions(maxTokens = 200, systemPrompt = "You are a concise summarizer. Return only the summary."))
    }

    suspend fun reason(problem: String): String = withContext(Dispatchers.IO) {
        val prompt = """
            Problem: $problem
            
            Think through this step by step:
            1. What is being asked?
            2. What information do I have?
            3. What is the logical solution?
            
            Provide your reasoning and conclusion.
        """.trimIndent()
        chat(prompt, options = GenerationOptions(maxTokens = 800, temperature = 0.5f))
    }

    suspend fun planTask(goal: String, context: String = ""): List<String> = withContext(Dispatchers.IO) {
        val prompt = """
            Goal: $goal
            ${if (context.isNotBlank()) "Context: $context" else ""}
            
            Create a step-by-step action plan. Return ONLY a numbered list of steps, one per line.
            Keep steps concrete and actionable.
        """.trimIndent()

        val response = chat(prompt, options = GenerationOptions(maxTokens = 400, temperature = 0.3f))

        response.lines()
            .filter { it.matches(Regex("^\\d+\\..*")) }
            .map { it.replace(Regex("^\\d+\\.\\s*"), "") }
            .filter { it.isNotBlank() }
    }

    suspend fun extractIntent(userInput: String): IntentResult = withContext(Dispatchers.IO) {
        val prompt = """
            Analyze this user command and extract the intent.
            User: "$userInput"
            
            Return JSON only:
            {
              "intent": "one of: open_app|set_reminder|create_note|web_search|send_message|general_chat|question|automation|coding",
              "confidence": 0.0-1.0,
              "entities": {"app_name": "...", "reminder_time": "...", "note_content": "..."},
              "requires_action": true/false
            }
        """.trimIndent()

        return@withContext try {
            val response = chat(prompt, options = GenerationOptions(
                maxTokens = 200,
                temperature = 0.1f,
                systemPrompt = "You are an intent classifier. Return only valid JSON."
            ))
            val cleaned = response.trim().removePrefix("```json").removeSuffix("```").trim()
            gson.fromJson(cleaned, IntentResult::class.java) ?: IntentResult("general_chat", 0.5f)
        } catch (e: Exception) {
            IntentResult("general_chat", 0.5f)
        }
    }

    suspend fun generateCode(
        task: String,
        language: String = "Kotlin",
        context: String = ""
    ): String = withContext(Dispatchers.IO) {
        val prompt = """
            Write $language code for: $task
            ${if (context.isNotBlank()) "Additional context: $context" else ""}
            
            Provide clean, well-commented code.
        """.trimIndent()
        chat(prompt, options = GenerationOptions(
            maxTokens = 1024,
            temperature = 0.2f,
            systemPrompt = "You are an expert $language developer. Write clean, efficient, well-commented code."
        ))
    }

    suspend fun updateMemoryFromConversation(
        conversation: String,
        existingMemories: String
    ): List<MemoryExtract> = withContext(Dispatchers.IO) {
        val prompt = """
            Based on this conversation, extract NEW facts to remember about the user.
            Existing memories: $existingMemories
            
            Conversation:
            $conversation
            
            Return JSON array only:
            [{"type": "PREFERENCE|FACT|HABIT|GOAL", "key": "...", "value": "...", "confidence": 0.0-1.0}]
            
            Only include genuinely new, useful information. Return [] if nothing new.
        """.trimIndent()

        return@withContext try {
            val response = chat(prompt, options = GenerationOptions(
                maxTokens = 300,
                temperature = 0.1f,
                systemPrompt = "You are a memory extraction system. Return only valid JSON."
            ))
            val cleaned = response.trim().removePrefix("```json").removeSuffix("```").trim()
            val type = object : com.google.gson.reflect.TypeToken<List<MemoryExtract>>() {}.type
            gson.fromJson(cleaned, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun buildMessageList(
        userMessage: String,
        history: List<ChatMessage>
    ): List<OllamaMessage> {
        val messages = mutableListOf<OllamaMessage>()

        // Add last N turns from history (exclude system messages)
        val recentHistory = history
            .filter { it.role != MessageRole.SYSTEM }
            .takeLast(16)

        recentHistory.forEach { msg ->
            messages.add(OllamaMessage(
                role = if (msg.role == MessageRole.USER) "user" else "assistant",
                content = msg.content
            ))
        }

        messages.add(OllamaMessage("user", userMessage))
        return messages
    }

    fun clearCache() {
        conversationCache.clear()
    }

    fun getActiveProviderName() = activeProvider.name

    companion object {
        const val ADA_SYSTEM_PROMPT = """You are ADA (Adaptive Digital Assistant), an intelligent and calm female AI assistant inspired by Jarvis.

You are:
• Professional and composed
• Helpful and proactive  
• Slightly witty when appropriate
• Analytical and precise
• Efficient — you avoid unnecessary verbosity

Your goal is to help the user think, learn, automate tasks, and organize information.

You remember user preferences and improve your assistance over time.

When executing device commands or automation, clearly state what you are doing.
When you don't know something, say so honestly.
Keep responses concise unless depth is requested."""
    }
}

// ─── Supporting Data Classes ──────────────────────────────────────────────────

data class IntentResult(
    val intent: String,
    val confidence: Float,
    val entities: Map<String, String> = emptyMap(),
    val requires_action: Boolean = false
)

data class MemoryExtract(
    val type: String,
    val key: String,
    val value: String,
    val confidence: Float
)
