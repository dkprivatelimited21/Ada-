package com.ada.assistant.automation

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutomationEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val TAG = "AutomationEngine"

    // ── App Control ────────────────────────────────────────────────────────────

    suspend fun openApp(appName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Try to find by exact package name first
            val packageName = resolvePackageName(appName)
            if (packageName != null) {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Log.d(TAG, "Opened app: $packageName")
                    return@withContext true
                }
            }

            // Try Play Store search as fallback
            val playIntent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("market://search?q=$appName")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(playIntent)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app: $appName", e)
            false
        }
    }

    private fun resolvePackageName(appName: String): String? {
        val normalized = appName.lowercase().trim()

        // Common app name → package mapping
        val commonApps = mapOf(
            "youtube" to "com.google.android.youtube",
            "chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "maps" to "com.google.android.apps.maps",
            "google maps" to "com.google.android.apps.maps",
            "whatsapp" to "com.whatsapp",
            "telegram" to "org.telegram.messenger",
            "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient",
            "twitter" to "com.twitter.android",
            "x" to "com.twitter.android",
            "instagram" to "com.instagram.android",
            "facebook" to "com.facebook.katana",
            "calculator" to "com.android.calculator2",
            "camera" to "android.media.action.IMAGE_CAPTURE",
            "settings" to "com.android.settings",
            "clock" to "com.android.deskclock",
            "calendar" to "com.google.android.calendar",
            "photos" to "com.google.android.apps.photos",
            "drive" to "com.google.android.apps.docs",
            "google drive" to "com.google.android.apps.docs",
            "docs" to "com.google.android.apps.docs.editors.docs",
            "sheets" to "com.google.android.apps.docs.editors.sheets",
            "meet" to "com.google.android.apps.meetings",
            "zoom" to "us.zoom.videomeetings",
            "slack" to "com.Slack",
            "discord" to "com.discord",
            "reddit" to "com.reddit.frontpage",
            "amazon" to "com.amazon.mShop.android.shopping",
            "uber" to "com.ubercab",
            "files" to "com.android.documentsui",
            "phone" to "com.android.dialer",
            "messages" to "com.google.android.apps.messaging",
            "contacts" to "com.android.contacts"
        )

        return commonApps[normalized] ?: searchInstalledApps(normalized)
    }

    private fun searchInstalledApps(name: String): String? {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = pm.queryIntentActivities(intent, 0)
        return apps.firstOrNull {
            it.loadLabel(pm).toString().lowercase().contains(name)
        }?.activityInfo?.packageName
    }

    // ── Reminders ─────────────────────────────────────────────────────────────

    suspend fun setReminder(message: String, timeStr: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val triggerMs = parseTimeString(timeStr)
            if (triggerMs <= 0) return@withContext false

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val intent = Intent(context, ReminderReceiver::class.java).apply {
                putExtra("message", message)
                putExtra("reminder_id", System.currentTimeMillis().toInt())
            }

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                System.currentTimeMillis().toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerMs,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerMs, pendingIntent)
            }

            Log.d(TAG, "Reminder set: '$message' at ${Date(triggerMs)}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set reminder", e)
            false
        }
    }

    private fun parseTimeString(timeStr: String): Long {
        val now = System.currentTimeMillis()
        val lower = timeStr.lowercase()

        // "in X minutes"
        Regex("in (\\d+) minutes?").find(lower)?.let {
            val mins = it.groupValues[1].toLongOrNull() ?: return -1L
            return now + (mins * 60 * 1000)
        }

        // "in X hours"
        Regex("in (\\d+) hours?").find(lower)?.let {
            val hrs = it.groupValues[1].toLongOrNull() ?: return -1L
            return now + (hrs * 60 * 60 * 1000)
        }

        // "in X seconds"
        Regex("in (\\d+) seconds?").find(lower)?.let {
            val secs = it.groupValues[1].toLongOrNull() ?: return -1L
            return now + (secs * 1000)
        }

        // "at HH:mm" or "at H:mm am/pm"
        Regex("at (\\d{1,2}:\\d{2}(?:\\s*[ap]m)?)", RegexOption.IGNORE_CASE).find(timeStr)?.let {
            try {
                val formats = listOf("h:mm a", "H:mm", "hh:mm a")
                for (fmt in formats) {
                    try {
                        val sdf = SimpleDateFormat(fmt, Locale.getDefault())
                        val cal = Calendar.getInstance()
                        val parsed = sdf.parse(it.groupValues[1]) ?: continue
                        val parsedCal = Calendar.getInstance().apply { time = parsed }
                        cal.set(Calendar.HOUR_OF_DAY, parsedCal.get(Calendar.HOUR_OF_DAY))
                        cal.set(Calendar.MINUTE, parsedCal.get(Calendar.MINUTE))
                        cal.set(Calendar.SECOND, 0)
                        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
                        return cal.timeInMillis
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }

        return -1L
    }

    // ── Notes ─────────────────────────────────────────────────────────────────

    suspend fun createNote(content: String, title: String = "ADA Note"): Boolean = withContext(Dispatchers.IO) {
        try {
            // Try to open a notes app with the content pre-filled
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, content)
                putExtra(Intent.EXTRA_SUBJECT, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Try Google Keep specifically
            val keepPackage = "com.google.android.keep"
            if (isAppInstalled(keepPackage)) {
                intent.setPackage(keepPackage)
            }

            context.startActivity(intent)
            Log.d(TAG, "Note created: $title")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create note", e)
            false
        }
    }

    // ── Settings ──────────────────────────────────────────────────────────────

    suspend fun openSettings(setting: String = "main"): Boolean = withContext(Dispatchers.IO) {
        try {
            val intent = when (setting.lowercase()) {
                "wifi", "network" -> Intent(Settings.ACTION_WIFI_SETTINGS)
                "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                "sound", "volume" -> Intent(Settings.ACTION_SOUND_SETTINGS)
                "display", "brightness" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
                "battery", "power" -> Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS)
                "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                "apps" -> Intent(Settings.ACTION_APPLICATION_SETTINGS)
                "location" -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                else -> Intent(Settings.ACTION_SETTINGS)
            }.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings: $setting", e)
            false
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    suspend fun makePhoneCall(number: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun openUrl(url: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val fullUrl = if (!url.startsWith("http")) "https://$url" else url
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(fullUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun searchWeb(query: String): Boolean = withContext(Dispatchers.IO) {
        val encoded = Uri.encode(query)
        openUrl("https://www.google.com/search?q=$encoded")
    }

    fun isAppInstalled(packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    fun getInstalledApps(): List<String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        return pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() }
            .sorted()
    }
}
