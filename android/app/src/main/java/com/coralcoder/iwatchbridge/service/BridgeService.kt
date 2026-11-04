package com.coralcoder.iwatchbridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.coralcoder.iwatchbridge.R
import com.coralcoder.iwatchbridge.bridge
import com.coralcoder.iwatchbridge.ui.MainActivity
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Keeps the watch connection alive while the app is in the background, like the iPhone's
 * always-on companion link. Shows the active watch and link state in a quiet notification.
 */
class BridgeService : LifecycleService() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.service_channel), NotificationManager.IMPORTANCE_MIN))
        ServiceCompat.startForeground(this, 1, build("Starting…"),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0)
        lifecycleScope.launch {
            combine(bridge.registry.state, bridge.radio) { reg, radio ->
                val watch = reg.active?.name ?: "No watch paired"
                "$watch · ${radio.radio?.label ?: "Bluetooth off"} · ${radio.state.name.lowercase()}"
            }.collect { nm.notify(1, build(it)) }
        }
    }

    private fun build(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("Apple Watch")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()

    companion object {
        private const val CHANNEL = "link"
        fun start(context: Context) = context.startForegroundService(Intent(context, BridgeService::class.java))
    }
}
