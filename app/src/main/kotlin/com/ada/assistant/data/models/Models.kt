package com.ada.assistant.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// ─── Chat Message ─────────────────────────────────────────────────────────────

enum class MessageRole { USER, ASSISTANT, SYSTEM }
enum class MessageStatus { SENDING, SENT, ERROR }

@Entity(tableName = "messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = MessageStatus.SENT,
    val tokensUsed: Int = 0,
    val agentType: String? = null,  // which agent produced this
    val metadata: String? = null    // JSON extras
)

// ─── Conversation ─────────────────────────────────────────────────────────────

@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey val id: String,
    val title: String,
    val summary: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messageCount: Int = 0,
    val isArchived: Boolean = false
)

// ─── Long-Term Memory ─────────────────────────────────────────────────────────

enum class MemoryType {
    PREFERENCE,     // User likes/dislikes
    FACT,           // Factual info about user
    HABIT,          // Behavioral patterns
    SKILL,          // What user is good at / learning
    GOAL,           // User goals
    CONTEXT,        // Situational context
    INSTRUCTION     // Explicit user instructions to Ada
}

@Entity(tableName = "memories")
data class Memory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: MemoryType,
    val key: String,           // e.g. "preferred_language", "wake_time"
    val value: String,         // e.g. "Kotlin", "7:00 AM"
    val confidence: Float = 1.0f,  // 0.0 – 1.0
    val source: String = "inferred",  // "user_stated" | "inferred" | "observed"
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    val tags: String = "[]"    // JSON array of tags
)

// ─── User Profile ─────────────────────────────────────────────────────────────

@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: Int = 1,  // singleton
    val name: String = "",
    val preferredLanguage: String = "en",
    val preferredAiModel: String = "local",
    val communicationStyle: String = "balanced",  // concise | balanced | detailed
    val primaryUseCases: String = "[]",  // JSON array
    val timezone: String = "",
    val wakeHour: Int = 7,
    val sleepHour: Int = 23,
    val totalInteractions: Int = 0,
    val firstSeen: Long = System.currentTimeMillis(),
    val lastActive: Long = System.currentTimeMillis()
)

// ─── Automation Task ──────────────────────────────────────────────────────────

enum class TaskStatus { PENDING, RUNNING, COMPLETED, FAILED, CANCELLED }
enum class TaskType {
    OPEN_APP, SEND_MESSAGE, SET_REMINDER, CREATE_NOTE,
    WEB_SEARCH, CALENDAR_EVENT, SYSTEM_SETTING, CUSTOM
}

@Entity(tableName = "automation_tasks")
data class AutomationTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TaskType,
    val description: String,
    val parameters: String = "{}",  // JSON parameters
    val status: TaskStatus = TaskStatus.PENDING,
    val scheduledAt: Long? = null,
    val executedAt: Long? = null,
    val result: String? = null,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

// ─── Plugin Registry ──────────────────────────────────────────────────────────

@Entity(tableName = "plugins")
data class PluginRecord(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val version: String,
    val isEnabled: Boolean = true,
    val capabilities: String = "[]",  // JSON list of what it can do
    val installedAt: Long = System.currentTimeMillis()
)

// ─── Command Log ──────────────────────────────────────────────────────────────

@Entity(tableName = "command_log")
data class CommandLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawInput: String,
    val parsedIntent: String,
    val agentHandled: String,
    val success: Boolean,
    val executionTimeMs: Long,
    val timestamp: Long = System.currentTimeMillis()
)

// ─── Type Converters ──────────────────────────────────────────────────────────

class Converters {
    @TypeConverter
    fun fromMessageRole(role: MessageRole): String = role.name

    @TypeConverter
    fun toMessageRole(name: String): MessageRole = MessageRole.valueOf(name)

    @TypeConverter
    fun fromMessageStatus(status: MessageStatus): String = status.name

    @TypeConverter
    fun toMessageStatus(name: String): MessageStatus = MessageStatus.valueOf(name)

    @TypeConverter
    fun fromMemoryType(type: MemoryType): String = type.name

    @TypeConverter
    fun toMemoryType(name: String): MemoryType = MemoryType.valueOf(name)

    @TypeConverter
    fun fromTaskStatus(status: TaskStatus): String = status.name

    @TypeConverter
    fun toTaskStatus(name: String): TaskStatus = TaskStatus.valueOf(name)

    @TypeConverter
    fun fromTaskType(type: TaskType): String = type.name

    @TypeConverter
    fun toTaskType(name: String): TaskType = TaskType.valueOf(name)
}

// ─── API Models ───────────────────────────────────────────────────────────────

data class OllamaRequest(
    val model: String,
    val messages: List<OllamaMessage>,
    val stream: Boolean = false,
    val options: OllamaOptions = OllamaOptions()
)

data class OllamaMessage(
    val role: String,
    val content: String
)

data class OllamaOptions(
    val temperature: Float = 0.7f,
    val num_predict: Int = 512,
    val top_p: Float = 0.9f
)

data class OllamaResponse(
    val model: String,
    val message: OllamaMessage,
    val done: Boolean,
    val total_duration: Long? = null,
    val prompt_eval_count: Int? = null,
    val eval_count: Int? = null
)

data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val max_tokens: Int = 1024,
    val temperature: Float = 0.7f
)

data class OpenAIMessage(
    val role: String,
    val content: String
)

data class OpenAIResponse(
    val id: String,
    val choices: List<OpenAIChoice>,
    val usage: OpenAIUsage?
)

data class OpenAIChoice(
    val message: OpenAIMessage,
    val finish_reason: String?
)

data class OpenAIUsage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

// ─── UI State ─────────────────────────────────────────────────────────────────

sealed class UiState<out T> {
    object Loading : UiState<Nothing>()
    data class Success<T>(val data: T) : UiState<T>()
    data class Error(val message: String, val throwable: Throwable? = null) : UiState<Nothing>()
    object Empty : UiState<Nothing>()
}

data class AdaResponse(
    val text: String,
    val agentUsed: String = "conversation",
    val thinkingSteps: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
    val memoryUpdates: List<String> = emptyList()
)
