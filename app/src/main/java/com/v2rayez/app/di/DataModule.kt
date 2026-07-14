package com.v2rayez.app.di

import android.content.Context
import androidx.room.Room
import com.v2rayez.app.data.local.DailyTrafficDao
import com.v2rayez.app.data.local.ServerDao
import com.v2rayez.app.data.local.SessionDao
import com.v2rayez.app.data.local.SubscriptionDao
import com.v2rayez.app.data.local.V2RayDatabase
import com.v2rayez.app.data.repository.DataStoreSettingsRepository
import com.v2rayez.app.data.repository.RealLogRepository
import com.v2rayez.app.data.repository.RealMitmProxyController
import com.v2rayez.app.data.repository.RealServerRepository
import com.v2rayez.app.data.repository.RealStatsRepository
import com.v2rayez.app.data.repository.RealVpnController
import com.v2rayez.app.domain.repository.LogRepository
import com.v2rayez.app.domain.repository.MitmProxyController
import com.v2rayez.app.domain.repository.ServerRepository
import com.v2rayez.app.domain.repository.SettingsRepository
import com.v2rayez.app.domain.repository.StatsRepository
import com.v2rayez.app.domain.repository.VpnController
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): V2RayDatabase =
        Room.databaseBuilder(context, V2RayDatabase::class.java, "v2rayez.db")
            .addMigrations(
                V2RayDatabase.MIGRATION_3_4,
                V2RayDatabase.MIGRATION_4_5,
                V2RayDatabase.MIGRATION_5_6,
                V2RayDatabase.MIGRATION_6_7,
                V2RayDatabase.MIGRATION_7_8
            )
            // Missing migrations fail closed to preserve stored credentials.
            .build()

    @Provides fun provideServerDao(db: V2RayDatabase): ServerDao = db.serverDao()
    @Provides fun provideSubscriptionDao(db: V2RayDatabase): SubscriptionDao = db.subscriptionDao()
    @Provides fun provideSessionDao(db: V2RayDatabase): SessionDao = db.sessionDao()
    @Provides fun provideDailyTrafficDao(db: V2RayDatabase): DailyTrafficDao = db.dailyTrafficDao()

    @Provides
    @Singleton
    fun provideHttpClient(): OkHttpClient = RealServerRepository.defaultHttpClient()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton abstract fun bindVpnController(impl: RealVpnController): VpnController
    @Binds @Singleton abstract fun bindMitmProxyController(impl: RealMitmProxyController): MitmProxyController
    @Binds @Singleton abstract fun bindServerRepository(impl: RealServerRepository): ServerRepository
    @Binds @Singleton abstract fun bindStatsRepository(impl: RealStatsRepository): StatsRepository
    @Binds @Singleton abstract fun bindLogRepository(impl: RealLogRepository): LogRepository
    @Binds @Singleton abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository
}
