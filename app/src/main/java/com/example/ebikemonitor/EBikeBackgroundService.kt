package com.example.ebikemonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class EBikeBackgroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val CHANNEL_ID = "EBikeMonitorChannel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
        
        // Observe connection state to update notification
        val app = application as EBikeApplication
        serviceScope.launch {
            app.bleManager.isConnected.collect { connected ->
                updateNotification(if (connected) "Connected to eBike BLE" else "BLE disconnected")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keeps the service running, but do not restart automatically if killed
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "eBike Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("eBike Monitor")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher) // Ensure this exists or use a drawable
            .setOngoing(true)
            .build()
    }
    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // This is called when the app is swiped away from the recent apps list
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        val app = application as EBikeApplication
        val topic = "ebikemonitor"
        
        // Stop foreground and cancel notifications
        stopForeground(true)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        
        // Disconnect MQTT (this is best effort, process kill follows)
        app.mqttManager.disconnect()
        serviceScope.cancel() 
        
        // Ensure the process is truly terminated after a tiny delay for cleanup
        // Note: Process.killProcess is very aggressive. We only use it if we really want to ensure nothing stays in memory.
        // However, it can cause issues during configuration changes if not handled carefully in the Activity.
        // android.util.Log.d("EBikeService", "Killing process...")
        // val pid = android.os.Process.myPid()
        // android.os.Process.killProcess(pid)
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
