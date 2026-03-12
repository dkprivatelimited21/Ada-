package com.ada.assistant.plugins

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

// ─── Plugin Interface ─────────────────────────────────────────────────────────

interface AdaPlugin {
    val pluginId: String
    val pluginName: String
    val version: String
    val description: String
    val capabilities: List<String>

    suspend fun initialize(context: Context): Boolean
    suspend fun canHandle(intent: String, input: String): Boolean
    suspend fun execute(intent: String, input: String, params: Map<String, String>): PluginResult
    fun destroy()
}

data class PluginResult(
    val success: Boolean,
    val response: String,
    val data: Map<String, Any> = emptyMap(),
    val error: String? = null
)

// ─── Built-in Plugins ─────────────────────────────────────────────────────────

class WebSearchPlugin : AdaPlugin {
    override val pluginId = "web_search"
    override val pluginName = "Web Search"
    override val version = "1.0"
    override val description = "Search the web using Google"
    override val capabilities = listOf("web_search", "open_url", "google_search")

    private lateinit var context: Context

    override suspend fun initialize(ctx: Context): Boolean {
        context = ctx
        return true
    }

    override suspend fun canHandle(intent: String, input: String): Boolean {
        return intent == "web_search" ||
            input.lowercase().contains("search for") ||
            input.lowercase().contains("google")
    }

    override suspend fun execute(intent: String, input: String, params: Map<String, String>): PluginResult {
        val query = params["query"] ?: input
        val url = "https://www.google.com/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        return PluginResult(true, "Searching for: $query", mapOf("url" to url))
    }

    override fun destroy() {}
}

class CalendarPlugin : AdaPlugin {
    override val pluginId = "calendar"
    override val pluginName = "Calendar Plugin"
    override val version = "1.0"
    override val description = "Create and manage calendar events"
    override val capabilities = listOf("create_event", "view_events", "calendar")

    private lateinit var context: Context

    override suspend fun initialize(ctx: Context): Boolean {
        context = ctx
        return true
    }

    override suspend fun canHandle(intent: String, input: String): Boolean {
        val keywords = listOf("calendar", "event", "schedule", "meeting", "appointment")
        return keywords.any { input.lowercase().contains(it) }
    }

    override suspend fun execute(intent: String, input: String, params: Map<String, String>): PluginResult {
        return try {
            val calIntent = android.content.Intent(android.provider.CalendarContract.Events.CONTENT_URI).apply {
                action = android.content.Intent.ACTION_INSERT
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(android.provider.CalendarContract.Events.TITLE, params["title"] ?: input)
                params["description"]?.let {
                    putExtra(android.provider.CalendarContract.Events.DESCRIPTION, it)
                }
            }
            context.startActivity(calIntent)
            PluginResult(true, "Opening calendar to create event.")
        } catch (e: Exception) {
            PluginResult(false, "Couldn't open calendar.", error = e.message)
        }
    }

    override fun destroy() {}
}

class FileManagerPlugin : AdaPlugin {
    override val pluginId = "file_manager"
    override val pluginName = "File Manager"
    override val version = "1.0"
    override val description = "Browse and manage files"
    override val capabilities = listOf("open_files", "browse_storage", "file_manager")

    private lateinit var context: Context

    override suspend fun initialize(ctx: Context): Boolean {
        context = ctx
        return true
    }

    override suspend fun canHandle(intent: String, input: String): Boolean {
        val keywords = listOf("file", "folder", "document", "storage", "download")
        return keywords.any { input.lowercase().contains(it) }
    }

    override suspend fun execute(intent: String, input: String, params: Map<String, String>): PluginResult {
        return try {
            val fileIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                addCategory(android.content.Intent.CATEGORY_DEFAULT)
                type = "resource/folder"
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fileIntent)
            PluginResult(true, "Opening file manager.")
        } catch (e: Exception) {
            PluginResult(false, "Couldn't open file manager.", error = e.message)
        }
    }

    override fun destroy() {}
}

class TimerPlugin : AdaPlugin {
    override val pluginId = "timer"
    override val pluginName = "Timer & Stopwatch"
    override val version = "1.0"
    override val description = "Set timers and use stopwatch"
    override val capabilities = listOf("timer", "stopwatch", "countdown")

    private lateinit var context: Context

    override suspend fun initialize(ctx: Context): Boolean {
        context = ctx
        return true
    }

    override suspend fun canHandle(intent: String, input: String): Boolean {
        val keywords = listOf("timer", "stopwatch", "countdown", "set timer")
        return keywords.any { input.lowercase().contains(it) }
    }

    override suspend fun execute(intent: String, input: String, params: Map<String, String>): PluginResult {
        return try {
            val timerIntent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                params["seconds"]?.let {
                    putExtra(android.provider.AlarmClock.EXTRA_LENGTH, it.toIntOrNull() ?: 0)
                }
            }
            context.startActivity(timerIntent)
            PluginResult(true, "Timer set!")
        } catch (e: Exception) {
            PluginResult(false, "Couldn't set timer.", error = e.message)
        }
    }

    override fun destroy() {}
}

// ─── Plugin Manager ───────────────────────────────────────────────────────────

@Singleton
class PluginManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "PluginManager"
    private val plugins = mutableMapOf<String, AdaPlugin>()

    // ── Initialization ─────────────────────────────────────────────────────────

    suspend fun initialize() {
        // Register built-in plugins
        registerPlugin(WebSearchPlugin())
        registerPlugin(CalendarPlugin())
        registerPlugin(FileManagerPlugin())
        registerPlugin(TimerPlugin())
        Log.d(TAG, "Plugin system initialized with ${plugins.size} plugins")
    }

    suspend fun registerPlugin(plugin: AdaPlugin): Boolean {
        return try {
            val success = plugin.initialize(context)
            if (success) {
                plugins[plugin.pluginId] = plugin
                Log.d(TAG, "Plugin registered: ${plugin.pluginName}")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register plugin: ${plugin.pluginName}", e)
            false
        }
    }

    fun unregisterPlugin(pluginId: String) {
        plugins[pluginId]?.destroy()
        plugins.remove(pluginId)
        Log.d(TAG, "Plugin unregistered: $pluginId")
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    suspend fun findAndExecute(
        intent: String,
        input: String,
        params: Map<String, String> = emptyMap()
    ): PluginResult? {
        val plugin = plugins.values.firstOrNull { it.canHandle(intent, input) }
        return plugin?.let {
            Log.d(TAG, "Executing plugin: ${it.pluginName}")
            it.execute(intent, input, params)
        }
    }

    // ── Queries ────────────────────────────────────────────────────────────────

    fun getAllPlugins(): List<AdaPlugin> = plugins.values.toList()

    fun getPlugin(pluginId: String): AdaPlugin? = plugins[pluginId]

    fun getCapabilities(): List<String> = plugins.values.flatMap { it.capabilities }

    fun getPluginCount() = plugins.size

    fun isPluginEnabled(pluginId: String): Boolean = plugins.containsKey(pluginId)

    // ── Cleanup ────────────────────────────────────────────────────────────────

    fun destroyAll() {
        plugins.values.forEach { it.destroy() }
        plugins.clear()
    }
}
