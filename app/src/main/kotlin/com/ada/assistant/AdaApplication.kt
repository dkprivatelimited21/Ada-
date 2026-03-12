package com.ada.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class AdaApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)

            // Voice service channel
            val voiceChannel = NotificationChannel(
                CHANNEL_VOICE,
                "ADA Voice Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "ADA is listening for your commands"
                setShowBadge(false)
            }

            // Reminder channel
            val reminderChannel = NotificationChannel(
                CHANNEL_REMINDERS,
                "ADA Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders set through ADA"
            }

            // General notifications
            val generalChannel = NotificationChannel(
                CHANNEL_GENERAL,
                "ADA Notifications",
                NotificationManager.IMPORTANCE_DEFAULT
            )

            manager.createNotificationChannels(listOf(voiceChannel, reminderChannel, generalChannel))
        }
    }

    companion object {
        const val CHANNEL_VOICE = "ada_voice_channel"
        const val CHANNEL_REMINDERS = "ada_reminders_channel"
        const val CHANNEL_GENERAL = "ada_general_channel"
    }
}
