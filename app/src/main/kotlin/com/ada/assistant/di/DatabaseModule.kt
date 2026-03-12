package com.ada.assistant.di

import android.content.Context
import androidx.room.Room
import com.ada.assistant.data.local.*
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Singleton
    @Provides
    fun provideDatabase(@ApplicationContext context: Context): AdaDatabase {
        return Room.databaseBuilder(
            context,
            AdaDatabase::class.java,
            AdaDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideMessageDao(db: AdaDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideConversationDao(db: AdaDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideMemoryDao(db: AdaDatabase): MemoryDao = db.memoryDao()

    @Provides
    fun provideUserProfileDao(db: AdaDatabase): UserProfileDao = db.userProfileDao()

    @Provides
    fun provideAutomationTaskDao(db: AdaDatabase): AutomationTaskDao = db.automationTaskDao()

    @Provides
    fun provideCommandLogDao(db: AdaDatabase): CommandLogDao = db.commandLogDao()
}
