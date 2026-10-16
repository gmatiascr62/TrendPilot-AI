package com.trendpilot.app.di

import android.content.Context
import androidx.room.Room
import com.trendpilot.app.data.AppDatabase
import com.trendpilot.app.engine.EngineHolder
import com.trendpilot.app.security.SecureSettings
import com.trendpilot.app.service.Notifier
import com.trendpilot.app.settings.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** No logging interceptor on purpose: request URLs and headers carry signatures and API keys. */
    @Provides @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    @Provides @Singleton
    fun database(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "trendpilot.db").build()

    @Provides @Singleton fun secure(@ApplicationContext ctx: Context) = SecureSettings(ctx)
    @Provides @Singleton fun settings(@ApplicationContext ctx: Context) = SettingsRepository(ctx)
    @Provides @Singleton fun notifier(@ApplicationContext ctx: Context) = Notifier(ctx)

    @Provides @Singleton
    fun holder(http: OkHttpClient, db: AppDatabase, settings: SettingsRepository, secure: SecureSettings, notifier: Notifier) =
        EngineHolder(http, db, settings, secure, notifier)
}
