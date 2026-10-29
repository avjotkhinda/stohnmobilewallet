package org.stohncoin.wallet.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Owns the single user-requested Full Mode Core as a visible foreground service. */
class NodeService : Service() {
    private lateinit var controller: NodeController
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        controller = NodeController.get(applicationContext)
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(NodeController.Status.STARTING), type)
        serviceScope.launch {
            controller.state.collect { state ->
                getSystemService(NotificationManager::class.java)
                    .notify(NOTIFICATION_ID, notification(state.status))
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        controller.startCoreFromService()
        return START_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        controller.stopCoreFromService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Notification content is deliberately coarse. Block heights, peer counts and anything
     * wallet-related stay out of the shade and off the lock screen, and the notification is
     * hidden entirely on secure lock screens.
     */
    private fun notification(status: NodeController.Status): Notification {
        val text = publicText(status)
        val publicVersion = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Stohn Wallet")
            .setContentText("Background service active")
            .setOngoing(true)
            .build()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Stohn Wallet")
            .setContentText(text)
            .setPublicVersion(publicVersion)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun publicText(status: NodeController.Status): String = when (status) {
        NodeController.Status.STARTING -> "Preparing the local node"
        NodeController.Status.SYNCING -> "Local node synchronizing"
        NodeController.Status.READY -> "Local node is running"
        NodeController.Status.ERROR -> "Local node stopped"
        NodeController.Status.STOPPED -> "Local node is stopped"
    }

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Stohn Full Node", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private companion object {
        const val CHANNEL_ID = "stohn_node"
        const val NOTIFICATION_ID = 42
    }
}
