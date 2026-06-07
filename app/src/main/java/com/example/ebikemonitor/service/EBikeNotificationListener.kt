package com.example.ebikemonitor.service

import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.ebikemonitor.EBikeApplication
import com.example.ebikemonitor.EBikeBackgroundService
import com.example.ebikemonitor.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class EBikeNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        fun updateFlowConnected(connected: Boolean) {
            com.example.ebikemonitor.BikePresenceManager.updatePresence(connected, "NotificationListener")
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val channelId = sbn.notification.channelId

        if (packageName == "com.bosch.ebike.onebikeapp") {
            if (channelId == "com.bosch.ebike.flow.pocketmode.channel") {
                FileLogger.log("EBikeNotificationListener: Bosch Pocket Mode detected!")
                updateFlowConnected(true)
                
                scope.launch {
                    val settings = (application as EBikeApplication).settingsRepository
                    val enabled = settings.backgroundStartup.first()
                    
                    if (enabled) {
                        FileLogger.log("EBikeNotificationListener: Background Startup is enabled. Starting service...")
                        startMonitoringService()
                    } else {
                        FileLogger.log("EBikeNotificationListener: Background Startup is disabled by user.")
                    }
                }
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val packageName = sbn.packageName
        val channelId = sbn.notification.channelId

        if (packageName == "com.bosch.ebike.onebikeapp") {
            if (channelId == "com.bosch.ebike.flow.pocketmode.channel") {
                FileLogger.log("EBikeNotificationListener: Bosch Pocket Mode notification removed.")
                updateFlowConnected(false)
            }
        }
    }

    private fun checkActiveNotifications() {
        try {
            val active = activeNotifications
            val isPresent = active?.any { sbn ->
                sbn.packageName == "com.bosch.ebike.onebikeapp" &&
                sbn.notification.channelId == "com.bosch.ebike.flow.pocketmode.channel"
            } ?: false
            updateFlowConnected(isPresent)
        } catch (e: Exception) {
            FileLogger.log("EBikeNotificationListener: Error checking active notifications: ${e.message}")
        }
    }

    private fun startMonitoringService() {
        try {
            val intent = Intent(this, EBikeBackgroundService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            FileLogger.log("EBikeNotificationListener: Error starting service: ${e.message}")
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        FileLogger.log("EBikeNotificationListener: Connected and listening...")
        checkActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        FileLogger.log("EBikeNotificationListener: Disconnected.")
        updateFlowConnected(false)
    }
}
