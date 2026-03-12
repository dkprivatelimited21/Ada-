package com.ada.assistant.ai.agents

import android.content.Context
import android.util.Log
import com.ada.assistant.ai.engine.AiEngine
import com.ada.assistant.ai.engine.GenerationOptions
import com.ada.assistant.ai.engine.IntentResult
import com.ada.assistant.automation.AutomationEngine
import com.ada.assistant.data.models.*
import com.ada.assistant.memory.MemoryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

// ─── Base Agent ───────────────────────────────────────────────────────────────

abstract class BaseAgent(
    val agentId: String,
    val agentName: String,
    val description: String
) {
    protected val TAG = "Agent:$agentId"

    abstract suspend fun canHandle(intent: IntentResult, input: String): Boolean
    abstract suspend fun execute(
        input: String,
        intent: IntentResult,
        context: AgentContext
    ): AgentResult
}

data class AgentContext(
    val conversationHistory: List<ChatMessage>,
    val userProfile: UserProfile?,
    val relevantMemories: List<Memory>,
    val sessionData: MutableMap<String, Any> = mutableMapOf()
)

data class AgentResult(
    val response: String,
    val agentId: String,
    val actions: List<AgentAction> = emptyList(),
    val memoryUpdates: List<MemoryUpdate> = emptyList(),
    val followUp: String? = null,
    val success: Boolean = true
)

data class AgentAction(
    val type: String,
    val description: String,
    val parameters: Map<String, String> = emptyMap()
)

data class MemoryUpdate(
    val key: String,
    val value: String,
    val type: MemoryType,
    val confidence: Float = 1.0f
)

// ─── Conversation Agent ───────────────────────────────────────────────────────

class ConversationAgent(
    private val aiEngine: AiEngine
) : BaseAgent("conversation", "Conversation Agent", "Handles general dialogue and Q&A") {

    override suspend fun canHandle(intent: IntentResult, input: String) =
        intent.intent in listOf("general_chat", "question")

    override suspend fun execute(
        input: String,
        intent: IntentResult,
        context: AgentContext
    ): AgentResult {
        Log.d(TAG, "Handling conversation: $input")

        val userInfo = context.userProfile?.let {
            if (it.name.isNotBlank()) "The user's name is ${it.name}. " else ""
        } ?: ""

        val memoryContext = if (context.relevantMemories.isNotEmpty()) {
            val memStr = context.relevantMemories.take(5).joinToString("; ") { "${it.key}: ${it.value}" }
            "Relevant info about user: $memStr. "
        } else ""

        val options = GenerationOptions(
            systemPrompt = AiEngine.ADA_SYSTEM_PROMPT + "\n\n$userInfo$memoryContext"
        )

        val response = aiEngine.chat(input, context.conversationHistory, options)

        return AgentResult(
            response = response,
            agentId = agentId
        )
    }
}

// ─── Research Agent ───────────────────────────────────────────────────────────

class ResearchAgent(
    private val aiEngine: AiEngine
) : BaseAgent("research", "Research Agent", "Finds and summarizes information") {

    override suspend fun canHandle(intent: IntentResult, input: String): Boolean {
        val keywords = listOf("search", "find", "what is", "who is", "explain", "research", "look up", "tell me about")
        return intent.intent == "web_search" || keywords.any { input.lowercase().contains(it) }
    }

    override suspend fun execute(
        input: String,
        intent: IntentResult,
        context: AgentContext
    ): AgentResult {
        Log.d(TAG, "Research request: $input")

        val prompt = """
            Research question: $input
            
            Provide a thorough, accurate answer. Structure your response with:
            1. Direct answer to the question
            2. Key details and context
            3. Any relevant caveats or additional info
            
            Be informative but concise.
        """.trimIndent()

        val response = aiEngine.chat(
            prompt,
            context.conversationHistory,
            GenerationOptions(maxTokens = 600, temperature = 0.3f)
        )

        return AgentResult(
            response = response,
            agentId = agentId,
            actions = listOf(AgentAction("research", "Researched: $input"))
        )
    }
}

// ─── Automation Agent ─────────────────────────────────────────────────────────

class AutomationAgent(
    private val aiEngine: AiEngine,
    private val automationEngine: AutomationEngine
) : BaseAgent("automation", "Automation Agent", "Executes device actions and commands") {

    override suspend fun canHandle(intent: IntentResult, input: String): Boolean {
        val actionIntents = listOf("open_app", "set_reminder", "create_note", "automation", "send_message")
        return intent.intent in actionIntents || intent.requires_action
    }

    override suspend fun execute(
        input: String,
        intent: IntentResult,
        context: AgentContext
    ): AgentResult {
        Log.d(TAG, "Automation request: ${intent.intent} | $input")

        return when (intent.intent) {
            "open_app" -> handleOpenApp(intent, input)
            "set_reminder" -> handleReminder(intent, input)
            "create_note" -> handleNote(intent, input)
            else -> handleGenericAutomation(input, intent, context)
        }
    }

    private suspend fun handleOpenApp(intent: IntentResult, input: String): AgentResult {
        val appName = intent.entities["app_name"] ?: extractAppName(input)
        val success = automationEngine.openApp(appName)
        return AgentResult(
            response = if (success) "Opening $appName for you." else "I couldn't find $appName on your device.",
            agentId = agentId,
            actions = listOf(AgentAction("open_app", "Open $appName", mapOf("app" to appName))),
            success = success
        )
    }

    private suspend fun handleReminder(intent: IntentResult, input: String): AgentResult {
        val timeStr = intent.entities["reminder_time"] ?: extractTime(input)
        val message = intent.entities["reminder_content"] ?: input
        val success = automationEngine.setReminder(message, timeStr)
        return AgentResult(
            response = if (success) "Reminder set: '$message' at $timeStr." else "I couldn't set that reminder. Please check your time format.",
            agentId = agentId,
            actions = listOf(AgentAction("set_reminder", "Reminder: $message at $timeStr")),
            success = success
        )
    }

    private suspend fun handleNote(intent: IntentResult, input: String): AgentResult {
        val content = intent.entities["note_content"] ?: input
        val success = automationEngine.createNote(content)
        return AgentResult(
            response = if (success) "Note saved successfully." else "I couldn't save that note.",
            agentId = agentId,
            actions = listOf(AgentAction("create_note", "Note created")),
            success = success
        )
    }

    private suspend fun handleGenericAutomation(
        input: String,
        intent: IntentResult,
        context: AgentContext
    ): AgentResult {
        val steps = aiEngine.planTask(input)
        val stepsText = steps.joinToString("\n") { "• $it" }
        return AgentResult(
            response = "I'll help you with that. Here's my plan:\n\n$stepsText\n\nShall I proceed?",
            agentId = agentId,
            actions = steps.map { AgentAction("planned_step", it) }
        )
    }

    private fun extractAppName(input: String): String {
        val patterns = listOf(
            Regex("open (.+)", RegexOption.IGNORE_CASE),
            Regex("launch (.+)", RegexOption.IGNORE_CASE),
            Regex("start (.+)", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            pattern.find(input)?.groupValues?.get(1)?.let {
                return it.trim().removeSuffix(" app").removeSuffix(" for me")
            }
        }
        return input
    }

    private fun extractTime(input: String): String {
        // Simplified time extraction — production would use NLP
        val patterns = listOf(
            Regex("in (\\d+) (minutes?|hours?|seconds?)", RegexOption.IGNORE_CASE),
            Regex("at (\\d{1,2}(?::\\d{2})? ?(?:am|pm)?)", RegexOption.IGNORE_CASE),
            Regex("(\\d+) (minutes?|hours?) from now", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            pattern.find(input)?.value?.let { return it }
        }
        return "unknown"
    }
}

// ─── Memory Agent ─────────────────────────────────────────────────────────────

class MemoryAgent(
    private val aiEngine: AiEngine,
    private val memoryManager: MemoryManager
) : BaseAgent("memory", "Memory Agent", "Manages long-term memory and user knowledge") {

    override suspend fun canHandle(intent: IntentResult, input: String): Boolean {
        val keywords = listOf("remember", "forget", "recall", "what do you know", "my preferences", "you said")
        return keywords.any { input.lowercase().contains(it) }
    }

    override suspend fun execute(
        input: String,
        intent: IntentResult,
        context: AgentContext
    ): AgentResult {
        Log.d(TAG, "Memory request: $input")

        return when {
            input.lowercase().contains("remember that") -> handleStoreMemory(input)
            input.lowercase().contains("forget") -> handleForgetMemory(input)
            input.lowercase().contains("what do you know") -> handleRecallAll(context)
            input.lowercase().contains("recall") -> handleRecall(input, context)
            else -> handleRecall(input, context)
        }
    }

    private suspend fun handleStoreMemory(input: String): AgentResult {
        val content = input.replace(Regex("remember that", RegexOption.IGNORE_CASE), "").trim()
        memoryManager.storeMemory(
            Memory(
                type = MemoryType.FACT,
                key = "user_stated_${System.currentTimeMillis()}",
                value = content,
                source = "user_stated",
                confidence = 1.0f
            )
        )
        return AgentResult(
            response = "Noted. I'll remember: \"$content\"",
            agentId = agentId,
            memoryUpdates = listOf(MemoryUpdate("user_stated", content, MemoryType.FACT))
        )
    }

    private suspend fun handleForgetMemory(input: String): AgentResult {
        return AgentResult(
            response = "I understand. I'll remove that from my memory. What specifically should I forget?",
            agentId = agentId
        )
    }

    private suspend fun handleRecallAll(context: AgentContext): AgentResult {
        val memories = memoryManager.getTopMemories(20)
        if (memories.isEmpty()) {
            return AgentResult(
                response = "I don't have much stored about you yet. As we interact, I'll learn your preferences and habits.",
                agentId = agentId
            )
        }
        val memoryText = memories.joinToString("\n") { "• ${it.key}: ${it.value}" }
        return AgentResult(
            response = "Here's what I know about you:\n\n$memoryText",
            agentId = agentId
        )
    }

    private suspend fun handleRecall(input: String, context: AgentContext): AgentResult {
        val relevant = context.relevantMemories
        return if (relevant.isNotEmpty()) {
            val response = relevant.joinToString(", ") { "${it.key}: ${it.value}" }
            AgentResult(response = "Based on what I know: $response", agentId = agentId)
        } else {
            AgentResult(response = "I don't have specific information about that stored yet.", agentId = agentId)
        }
    }
}

// ─── Coding Agent ─────────────────────────────────────────────────────────────

class CodingAgent(
    private val aiEngine: AiEngine
) : BaseAgent("coding", "Coding Agent", "Writes, explains, and analyzes code") {

    override suspend fun canHandle(intent: IntentResult, input: String): Boolean {
        val keywords = listOf("code", "function", "class", "bug", "debug", "script", "program", "implement", "write a", "fix this")
        return intent.intent == "coding" || keywords.any { input.lowercase().contains(it) }
    }

    override suspend fun execute(
        input: String,
        intent: IntentResult,
        context: AgentContext
    ): AgentResult {
        Log.d(TAG, "Coding request: $input")

        val isDebug = input.lowercase().let { it.contains("bug") || it.contains("fix") || it.contains("error") }
        val isExplain = input.lowercase().let { it.contains("explain") || it.contains("what does") || it.contains("how does") }

        val systemPrompt = when {
            isDebug -> "You are an expert debugger. Identify the bug, explain it clearly, and provide the corrected code."
            isExplain -> "You are a patient coding teacher. Explain code concepts clearly with examples."
            else -> "You are an expert developer. Write clean, efficient, well-commented code. Include usage examples."
        }

        val response = aiEngine.chat(
            input,
            context.conversationHistory,
            GenerationOptions(maxTokens = 1024, temperature = 0.2f, systemPrompt = systemPrompt)
        )

        // Record that user is interested in coding
        val memoryUpdate = MemoryUpdate(
            key = "interest_coding",
            value = "true",
            type = MemoryType.PREFERENCE,
            confidence = 0.8f
        )

        return AgentResult(
            response = response,
            agentId = agentId,
            memoryUpdates = listOf(memoryUpdate)
        )
    }
}

// ─── Agent Manager ────────────────────────────────────────────────────────────

@Singleton
class AgentManager @Inject constructor(
    private val aiEngine: AiEngine,
    private val memoryManager: MemoryManager,
    private val automationEngine: AutomationEngine
) {
    private val TAG = "AgentManager"

    private val agents: List<BaseAgent> by lazy {
        listOf(
            MemoryAgent(aiEngine, memoryManager),
            AutomationAgent(aiEngine, automationEngine),
            CodingAgent(aiEngine),
            ResearchAgent(aiEngine),
            ConversationAgent(aiEngine)  // Always last — default fallback
        )
    }

    suspend fun process(
        userInput: String,
        conversationHistory: List<ChatMessage>,
        userProfile: UserProfile?
    ): AgentResult = coroutineScope {
        Log.d(TAG, "Processing: $userInput")

        // Step 1: Extract intent
        val intent = aiEngine.extractIntent(userInput)
        Log.d(TAG, "Intent: ${intent.intent} (${intent.confidence})")

        // Step 2: Load relevant memories
        val memories = memoryManager.searchRelevantMemories(userInput)

        // Step 3: Build context
        val context = AgentContext(
            conversationHistory = conversationHistory,
            userProfile = userProfile,
            relevantMemories = memories
        )

        // Step 4: Route to appropriate agent
        val agent = agents.firstOrNull { it.canHandle(intent, userInput) }
            ?: agents.last()  // Fallback to ConversationAgent

        Log.d(TAG, "Routing to: ${agent.agentName}")

        // Step 5: Execute
        val result = agent.execute(userInput, intent, context)

        // Step 6: Background memory update (non-blocking)
        async {
            try {
                result.memoryUpdates.forEach { update ->
                    memoryManager.storeMemory(
                        Memory(
                            type = update.type,
                            key = update.key,
                            value = update.value,
                            confidence = update.confidence,
                            source = "inferred"
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Memory update failed", e)
            }
        }

        result
    }

    fun getAgentList(): List<Pair<String, String>> =
        agents.map { Pair(it.agentName, it.description) }
}
