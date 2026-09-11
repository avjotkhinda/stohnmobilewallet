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

/** Owns the single user-requested Full Mode Core as a visible foreground service. */
class NodeService : Service() {
    private lateinit var controller: NodeController

    override fun onCreate() {
        super.onCreate()
        controller = NodeController.get(applicationContext)
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, 42, notification("Starting Stohn Core…"), type)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        controller.startCoreFromService()
        return START_STICKY
    }

    override fun onDestroy() {
        controller.stopCoreFromService()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(text: String): Notification =
        NotificationCompat.Builder(this, "stohn_node")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Stohn Wallet — Full Mode")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    private fun createChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("stohn_node", "Stohn Full Node", NotificationManager.IMPORTANCE_LOW)
        )
    }
}
