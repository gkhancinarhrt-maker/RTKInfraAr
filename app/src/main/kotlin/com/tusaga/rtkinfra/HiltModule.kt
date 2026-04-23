package com.tusaga.rtkinfra

import android.content.Context
import com.tusaga.rtkinfra.ar.InfrastructureAnchorManager
import com.tusaga.rtkinfra.data.repository.SettingsRepository
import com.tusaga.rtkinfra.gnss.GnssManager
import com.tusaga.rtkinfra.ntrip.NtripClientManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideGnssManager(
        @ApplicationContext context: Context
    ): GnssManager = GnssManager(context)

    @Provides @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context
    ): SettingsRepository = SettingsRepository(context)

    @Provides @Singleton
    fun provideNtripClientManager(
        gnssManager: GnssManager,
        settingsRepository: SettingsRepository
    ): NtripClientManager = NtripClientManager(gnssManager, settingsRepository)

    @Provides @Singleton
    fun provideInfrastructureAnchorManager(): InfrastructureAnchorManager =
        InfrastructureAnchorManager()
}
