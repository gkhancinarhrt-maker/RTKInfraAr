package com.tusaga.rtkinfra.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.tusaga.rtkinfra.BuildConfig
import com.tusaga.rtkinfra.ntrip.NtripConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores user-configured TUSAGA-Aktif credentials and preferences
 * in encrypted SharedPreferences.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("rtk_infra_settings", Context.MODE_PRIVATE)

    fun getNtripConfig(): NtripConfig = NtripConfig(
        host       = prefs.getString("ntrip_host", BuildConfig.TUSAGA_HOST) ?: BuildConfig.TUSAGA_HOST,
        port       = prefs.getInt("ntrip_port", BuildConfig.TUSAGA_PORT),
        mountpoint = prefs.getString("ntrip_mount", BuildConfig.TUSAGA_MOUNT) ?: BuildConfig.TUSAGA_MOUNT,
        username   = prefs.getString("ntrip_user", "") ?: "",
        password   = prefs.getString("ntrip_pass", "") ?: ""
    )

    fun saveNtripConfig(config: NtripConfig) {
        prefs.edit()
            .putString("ntrip_host",  config.host)
            .putInt   ("ntrip_port",  config.port)
            .putString("ntrip_mount", config.mountpoint)
            .putString("ntrip_user",  config.username)
            .putString("ntrip_pass",  config.password)
            .apply()
    }

    fun getLastMountpoint(): String =
        prefs.getString("last_mount", BuildConfig.TUSAGA_MOUNT) ?: BuildConfig.TUSAGA_MOUNT

    fun saveLastMountpoint(mount: String) {
        prefs.edit().putString("last_mount", mount).apply()
    }
}
