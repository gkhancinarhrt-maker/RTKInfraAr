package com.tusaga.rtkinfra.gnss

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tusaga.rtkinfra.R
import com.tusaga.rtkinfra.ntrip.NtripClientManager
import com.tusaga.rtkinfra.ui.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that keeps GNSS measurement collection and NTRIP
 * correction streaming alive even when the app is backgrounded on-site.
 * Bound to the Activity via [GnssRtkRepository] which shares Flow state.
 */
@AndroidEntryPoint
class GnssRtkService : Service() {

    @Inject lateinit var gnssManager: GnssManager
    @Inject lateinit var ntripClientManager: NtripClientManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    companion object {
        const val CHANNEL_ID   = "rtk_gnss_channel"
        const val NOTIF_ID     = 1001
        const val ACTION_STOP  = "com.tusaga.rtkinfra.STOP_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Initializing GNSS…"))
        Timber.i("GnssRtkService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            // Start GNSS raw measurement listening
            gnssManager.startListening()

            // Start NTRIP corrections streaming – will reconnect automatically
            ntripClientManager.start()

            // Update notification with live RTK state
            gnssManager.rtkStateFlow.collect { state ->
                updateNotification(state)
            }
        }

        return START_STICKY   // Restart if killed by OS
    }

    override fun onBind(intent: Intent?): IBinder? = null   // Not a bound service

    override fun onDestroy() {
        serviceScope.cancel()
        gnssManager.stopListening()
        ntripClientManager.stop()
        Timber.i("GnssRtkService destroyed")
        super.onDestroy()
    }

    // ──────────────────────────────────────────────────────────────────
    // Notification helpers
    // ──────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RTK GNSS Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Continuous GNSS & NTRIP positioning" }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, GnssRtkService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RTK Infra AR")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_satellite)
            .setContentIntent(pendingIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(state: RtkState) {
        val text = "RTK: ${state.fixType.label} | " +
                   "Acc: ${state.accuracyM.format()} | " +
                   "Sats: ${state.usedSatellites}"
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun Double.format(): String =
        if (this < 1.0) "${"%.1f".format(this * 100)} cm"
        else "${"%.2f".format(this)} m"
}
