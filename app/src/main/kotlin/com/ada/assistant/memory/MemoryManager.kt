package com.ada.assistant.memory

import android.util.Log
import com.ada.assistant.data.local.MemoryDao
import com.ada.assistant.data.local.UserProfileDao
import com.ada.assistant.data.models.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MemoryManager @Inject constructor(
    private val memoryDao: MemoryDao,
    private val userProfileDao: UserProfileDao
) {
    private val TAG = "MemoryManager"

    // ── Storage ────────────────────────────────────────────────────────────────

    suspend fun storeMemory(memory: Memory) = withContext(Dispatchers.IO) {
        try {
            // Check if key already exists — update or create
            val existing = memoryDao.getMemoryByKey(memory.key)
            if (existing != null) {
                // Merge: update value, boost confidence if repeated
                val updated = existing.copy(
                    value = memory.value,
                    confidence = minOf(1.0f, existing.confidence + 0.05f),
                    updatedAt = System.currentTimeMillis(),
                    source = memory.source
                )
                memoryDao.update(updated)
                Log.d(TAG, "Updated memory: ${memory.key}")
            } else {
                memoryDao.insert(memory)
                Log.d(TAG, "Stored new memory: ${memory.key} = ${memory.value}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to store memory", e)
        }
    }

    suspend fun storePreference(key: String, value: String, confidence: Float = 1.0f) {
        storeMemory(Memory(
            type = MemoryType.PREFERENCE,
            key = key,
            value = value,
            confidence = confidence,
            source = "user_stated"
        ))
    }

    suspend fun storeFact(key: String, value: String, confidence: Float = 0.9f) {
        storeMemory(Memory(
            type = MemoryType.FACT,
            key = key,
            value = value,
            confidence = confidence,
            source = "inferred"
        ))
    }

    suspend fun storeHabit(key: String, value: String) {
        storeMemory(Memory(
            type = MemoryType.HABIT,
            key = key,
            value = value,
            confidence = 0.7f,
            source = "observed"
        ))
    }

    // ── Retrieval ──────────────────────────────────────────────────────────────

    fun getAllMemories(): Flow<List<Memory>> = memoryDao.getAllMemories()

    suspend fun getTopMemories(limit: Int = 20): List<Memory> =
        memoryDao.getTopMemories(limit = limit)

    suspend fun getMemoriesByType(type: MemoryType): List<Memory> =
        memoryDao.getMemoriesByType(type)

    suspend fun getMemory(key: String): Memory? = memoryDao.getMemoryByKey(key)

    suspend fun searchRelevantMemories(query: String): List<Memory> =
        withContext(Dispatchers.IO) {
            val results = memoryDao.searchMemories(query)
            results.forEach { memoryDao.incrementAccess(it.id) }
            results.take(10)
        }

    suspend fun buildMemoryContext(): String {
        val memories = getTopMemories(15)
        if (memories.isEmpty()) return ""

        return memories.groupBy { it.type }.map { (type, mems) ->
            val typeName = when (type) {
                MemoryType.PREFERENCE -> "Preferences"
                MemoryType.FACT -> "Known facts"
                MemoryType.HABIT -> "Observed habits"
                MemoryType.GOAL -> "Goals"
                MemoryType.SKILL -> "Skills"
                MemoryType.CONTEXT -> "Context"
                MemoryType.INSTRUCTION -> "Instructions"
            }
            "$typeName:\n" + mems.joinToString("\n") { "  - ${it.key}: ${it.value}" }
        }.joinToString("\n\n")
    }

    // ── Deletion ───────────────────────────────────────────────────────────────

    suspend fun deleteMemory(memory: Memory) = memoryDao.delete(memory)

    suspend fun clearMemoriesOfType(type: MemoryType) = memoryDao.deleteByType(type)

    // ── User Profile ───────────────────────────────────────────────────────────

    fun getUserProfile(): Flow<UserProfile?> = userProfileDao.getProfile()

    suspend fun getUserProfileSync(): UserProfile? = userProfileDao.getProfileSync()

    suspend fun saveUserProfile(profile: UserProfile) {
        val existing = userProfileDao.getProfileSync()
        if (existing == null) {
            userProfileDao.insert(profile)
        } else {
            userProfileDao.update(profile)
        }
    }

    suspend fun recordInteraction() {
        try {
            val profile = userProfileDao.getProfileSync()
            if (profile == null) {
                userProfileDao.insert(UserProfile())
            } else {
                userProfileDao.recordInteraction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record interaction", e)
        }
    }

    suspend fun updateUserName(name: String) {
        val profile = userProfileDao.getProfileSync() ?: UserProfile()
        userProfileDao.update(profile.copy(name = name))
        storeFact("user_name", name, 1.0f)
    }

    // ── Learning System ────────────────────────────────────────────────────────

    suspend fun observeCommandPattern(intent: String) {
        val habitKey = "frequent_intent_$intent"
        val existing = getMemory(habitKey)
        val count = existing?.value?.toIntOrNull() ?: 0
        val newCount = count + 1

        storeMemory(Memory(
            type = MemoryType.HABIT,
            key = habitKey,
            value = newCount.toString(),
            confidence = minOf(1.0f, newCount * 0.1f),
            source = "observed"
        ))

        // If they use coding > 5 times, set as primary use case
        if (newCount >= 5) {
            val ucKey = "primary_use_$intent"
            val existing2 = getMemory(ucKey)
            if (existing2 == null) {
                storePreference(ucKey, "true", 0.9f)
                Log.d(TAG, "Learning: User is a frequent $intent user")
            }
        }
    }

    suspend fun getPersonalityInsights(): Map<String, String> {
        val habits = getMemoriesByType(MemoryType.HABIT)
        val prefs = getMemoriesByType(MemoryType.PREFERENCE)

        return mapOf(
            "total_memories" to (habits.size + prefs.size).toString(),
            "top_habit" to (habits.maxByOrNull { it.value.toIntOrNull() ?: 0 }?.key ?: "unknown"),
            "preferences_count" to prefs.size.toString()
        )
    }
}
