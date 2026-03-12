package com.ada.assistant.data.local

import androidx.lifecycle.LiveData
import androidx.room.*
import com.ada.assistant.data.models.*
import kotlinx.coroutines.flow.Flow

// ─── DAOs ─────────────────────────────────────────────────────────────────────

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY timestamp ASC")
    fun getMessagesForConversation(convId: String): Flow<List<ChatMessage>>

    @Query("SELECT * FROM messages WHERE conversationId = :convId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentMessages(convId: String, limit: Int = 20): List<ChatMessage>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: ChatMessage): Long

    @Update
    suspend fun update(message: ChatMessage)

    @Delete
    suspend fun delete(message: ChatMessage)

    @Query("DELETE FROM messages WHERE conversationId = :convId")
    suspend fun deleteConversation(convId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :convId")
    suspend fun countMessages(convId: String): Int

    @Query("SELECT * FROM messages WHERE content LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    suspend fun searchMessages(query: String): List<ChatMessage>
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE isArchived = 0 ORDER BY updatedAt DESC")
    fun getAllConversations(): Flow<List<Conversation>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversation(id: String): Conversation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: Conversation)

    @Update
    suspend fun update(conversation: Conversation)

    @Query("UPDATE conversations SET updatedAt = :time, messageCount = messageCount + 1 WHERE id = :id")
    suspend fun touchConversation(id: String, time: Long = System.currentTimeMillis())

    @Query("UPDATE conversations SET isArchived = 1 WHERE id = :id")
    suspend fun archiveConversation(id: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteConversation(id: String)
}

@Dao
interface MemoryDao {
    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun getAllMemories(): Flow<List<Memory>>

    @Query("SELECT * FROM memories WHERE type = :type ORDER BY confidence DESC")
    suspend fun getMemoriesByType(type: MemoryType): List<Memory>

    @Query("SELECT * FROM memories WHERE key = :key LIMIT 1")
    suspend fun getMemoryByKey(key: String): Memory?

    @Query("SELECT * FROM memories WHERE tags LIKE '%' || :tag || '%'")
    suspend fun getMemoriesByTag(tag: String): List<Memory>

    @Query("SELECT * FROM memories WHERE confidence >= :minConfidence ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun getTopMemories(minConfidence: Float = 0.7f, limit: Int = 50): List<Memory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: Memory): Long

    @Update
    suspend fun update(memory: Memory)

    @Query("UPDATE memories SET accessCount = accessCount + 1 WHERE id = :id")
    suspend fun incrementAccess(id: Long)

    @Delete
    suspend fun delete(memory: Memory)

    @Query("DELETE FROM memories WHERE type = :type")
    suspend fun deleteByType(type: MemoryType)

    @Query("SELECT * FROM memories WHERE key LIKE '%' || :query || '%' OR value LIKE '%' || :query || '%'")
    suspend fun searchMemories(query: String): List<Memory>
}

@Dao
interface UserProfileDao {
    @Query("SELECT * FROM user_profile WHERE id = 1")
    fun getProfile(): Flow<UserProfile?>

    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun getProfileSync(): UserProfile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(profile: UserProfile)

    @Update
    suspend fun update(profile: UserProfile)

    @Query("UPDATE user_profile SET totalInteractions = totalInteractions + 1, lastActive = :time WHERE id = 1")
    suspend fun recordInteraction(time: Long = System.currentTimeMillis())
}

@Dao
interface AutomationTaskDao {
    @Query("SELECT * FROM automation_tasks ORDER BY createdAt DESC")
    fun getAllTasks(): Flow<List<AutomationTask>>

    @Query("SELECT * FROM automation_tasks WHERE status = :status ORDER BY scheduledAt ASC")
    suspend fun getTasksByStatus(status: TaskStatus): List<AutomationTask>

    @Query("SELECT * FROM automation_tasks WHERE scheduledAt <= :now AND status = 'PENDING'")
    suspend fun getDueTasks(now: Long = System.currentTimeMillis()): List<AutomationTask>

    @Insert
    suspend fun insert(task: AutomationTask): Long

    @Update
    suspend fun update(task: AutomationTask)
}

@Dao
interface CommandLogDao {
    @Insert
    suspend fun insert(log: CommandLog)

    @Query("SELECT * FROM command_log ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<CommandLog>

    @Query("SELECT parsedIntent, COUNT(*) as count FROM command_log GROUP BY parsedIntent ORDER BY count DESC LIMIT :limit")
    suspend fun getTopIntents(limit: Int = 10): List<IntentCount>

    @Query("DELETE FROM command_log WHERE timestamp < :before")
    suspend fun cleanOld(before: Long)
}

data class IntentCount(val parsedIntent: String, val count: Int)

// ─── Database ─────────────────────────────────────────────────────────────────

@Database(
    entities = [
        ChatMessage::class,
        Conversation::class,
        Memory::class,
        UserProfile::class,
        AutomationTask::class,
        PluginRecord::class,
        CommandLog::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AdaDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun memoryDao(): MemoryDao
    abstract fun userProfileDao(): UserProfileDao
    abstract fun automationTaskDao(): AutomationTaskDao
    abstract fun commandLogDao(): CommandLogDao

    companion object {
        const val DATABASE_NAME = "ada_database"
    }
}
