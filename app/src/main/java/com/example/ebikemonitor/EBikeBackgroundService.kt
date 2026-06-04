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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample

class EBikeBackgroundService : Service() {

    // Cache for delta-publishing
    private var lastPublishedStatus: com.example.ebikemonitor.data.model.BikeStatus? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val CHANNEL_ID = "EBikeMonitorChannel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        updateNotification("Waiting for Bosch Flow connection...")
        FileLogger.log("EBikeBackgroundService onCreate")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
        
        // 1. Initial Setup
        val app = application as EBikeApplication
        val bleManager = app.bleManager
        val mqttManager = app.mqttManager
        val settings = app.settingsRepository
        
        // 2. Pre-load Baselines so we are ready the moment we connect
        serviceScope.launch {
            try {
                // Use activeBikeMac if available, otherwise fallback to legacy bleMacAddress
                val mac = settings.activeBikeMac.first() ?: settings.bleMacAddress.first()
                if (!mac.isNullOrEmpty()) {
                    val profiles = settings.bikeProfiles.first()
                    val profile = profiles.find { it.macAddress == mac }
                    
                    if (profile != null && profile.lastUsageRecords.isNotEmpty()) {
                        val baselines = profile.lastUsageRecords.mapIndexed { i, usage ->
                            val trip = profile.lastTripDistPerMode.getOrNull(i) ?: 0
                            (usage?.distance ?: 0) - trip
                        }
                        if (baselines.size == profile.lastUsageRecords.size) {
                            FileLogger.log("EBikeBackgroundService: Pre-loading ${baselines.size} persistent baselines for $mac")
                            bleManager.setPersistentBaselines(baselines)
                        }
                    }
                }
            } catch (e: Exception) {
                FileLogger.log("EBikeBackgroundService: Baseline pre-load error: ${e.message}")
            }
        }

        // 3. Observe connection state to update notification
        serviceScope.launch {
            bleManager.isConnected.collect { connected ->
                FileLogger.log("EBikeBackgroundService: BLE connection changed to: $connected")
                updateNotification(if (connected) "Connected to eBike BLE" else "BLE disconnected")
            }
        }

        // 4. Monitoring and Publishing Logic
        serviceScope.launch {
            bleManager.bikeStatus
                .sample(1000)
                .collect { status ->
                    if (mqttManager.isConnected.value) {
                        syncToMqtt(status)
                    }
                    
                    // Periodic history saving (Crucial for next start!)
                    val mac = settings.activeBikeMac.first() ?: settings.bleMacAddress.first() ?: return@collect
                    val expectedCount = status.assistModeNames.size
                    if (expectedCount > 0 && 
                        status.confirmedModeIndices.size == expectedCount && 
                        status.sortedUsageRecordsB.size == expectedCount) {
                        
                        settings.updateBikeUsageHistory(mac, status.sortedUsageRecordsB, status.tripDistPerMode)
                    }
                }
        }

        // 5. Connect!
        serviceScope.launch {
            try {
                val savedMac = settings.activeBikeMac.first() ?: settings.bleMacAddress.first()
                if (!savedMac.isNullOrEmpty()) {
                    FileLogger.log("EBikeBackgroundService: Starting auto-connect to $savedMac")
                    bleManager.connect(savedMac)
                } else {
                    FileLogger.log("EBikeBackgroundService: No saved MAC found.")
                }
            } catch (e: Exception) {
                FileLogger.log("EBikeBackgroundService: Connection error: ${e.message}")
            }
        }

        // Auto-connect to MQTT if enabled
        serviceScope.launch {
            try {
                val autoMqtt = settings.autoConnectMqtt.first()
                if (autoMqtt) {
                    val uri = settings.mqttBrokerUri.first()
                    val user = settings.mqttUser.first()
                    val pass = settings.mqttPassword.first()
                    val eBikeName = settings.eBikeName.first()
                    val mac = settings.bleMacAddress.first()
                    
                    if (uri.isNotEmpty()) {
                        val clientId = if (eBikeName.isNotEmpty()) eBikeName else "MyEbike"
                        val deviceId = mac?.lowercase()?.replace(":", "") ?: "unknown"
                        val topic = "ebikemonitor/$deviceId"
                        
                        FileLogger.log("EBikeBackgroundService: Attempting MQTT auto-connect to $uri")
                        app.mqttManager.connect(uri, clientId, user, pass, topic)
                    }
                }
            } catch (e: Exception) {
                FileLogger.log("EBikeBackgroundService: MQTT auto-connect error: ${e.message}")
            }
        }

        // MQTT Re-sync logic: Trigger full sync when connection established
        serviceScope.launch {
            app.mqttManager.isConnected.collect { connected ->
                if (connected) {
                    FileLogger.log("EBikeBackgroundService: MQTT Connected. Flagging for full sync.")
                    // Resetting lastPublishedStatus triggers a full sync on the next BLE update
                    lastPublishedStatus = null
                    // If we already have a status, sync it immediately
                    val currentStatus = app.bleManager.bikeStatus.value
                    if (currentStatus.lastUpdateTimestamp > 0) {
                        syncToMqtt(currentStatus)
                    }
                }
            }
        }
        
        // Heartbeat for resource monitoring
        serviceScope.launch {
            while (true) {
                val runtime = Runtime.getRuntime()
                val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                val totalMem = runtime.totalMemory() / 1024 / 1024
                
                FileLogger.log("[HEARTBEAT] Mem: ${usedMem}MB / ${totalMem}MB, BLE: ${app.bleManager.isConnected.value}, MQTT: ${app.mqttManager.isConnected.value}")
                kotlinx.coroutines.delay(60000) // 1 minute
            }
        }
    }

    private suspend fun syncToMqtt(status: com.example.ebikemonitor.data.model.BikeStatus) {
        val app = application as EBikeApplication
        val mac = app.settingsRepository.activeBikeMac.first() ?: app.settingsRepository.bleMacAddress.first()
        val deviceId = mac?.lowercase()?.replace(":", "") ?: "unknown"
        val bikeTopic = "ebikemonitor/$deviceId"
        val last = lastPublishedStatus

        fun pubIfDiff(topicPath: String, current: Any?, lastVal: Any?, retained: Boolean = false) {
            if (current != null && current != lastVal) {
                app.mqttManager.publish(topicPath, current.toString(), retained)
            }
        }

        // Log the sync with correct prefix
        if (last == null) {
            FileLogger.log("EBikeBackgroundService: Performing full sync for $deviceId")
        }

        // Essential live metrics
        pubIfDiff("$bikeTopic/speed", status.speed, last?.speed)
        pubIfDiff("$bikeTopic/cadence", status.cadence, last?.cadence)
        pubIfDiff("$bikeTopic/power", status.humanPower, last?.humanPower)
        pubIfDiff("$bikeTopic/motorpower", status.motorPower, last?.motorPower)
        
        if (status.assistMode != last?.assistMode) {
             app.mqttManager.publish("$bikeTopic/assistmode", com.example.ebikemonitor.data.model.getAssistModeName(status.assistMode, status.assistModeNames))
        }

        // Totals & Per-mode (Retained)
        pubIfDiff("$bikeTopic/stateofcharge", status.batteryLevel, last?.batteryLevel, true)
        pubIfDiff("$bikeTopic/totaldistance", status.totalDistance, last?.totalDistance, true)
        pubIfDiff("$bikeTopic/totalenergy", status.totalEnergyFromMotor, last?.totalEnergyFromMotor, true)
        pubIfDiff("$bikeTopic/totalbattery", status.totalBattery, last?.totalBattery, true)

        // Metadata & Versions (Retained)
        pubIfDiff("$bikeTopic/ebikeledsoftwareversion", status.ebikeLedSoftwareVersion, last?.ebikeLedSoftwareVersion, true)
        pubIfDiff("$bikeTopic/batteryserialnumber", status.batterySerialNumber, last?.batterySerialNumber, true)
        pubIfDiff("$bikeTopic/totalhours", status.driveUnitHours, last?.driveUnitHours, true)

        // Per-Mode Metrics
        status.sortedUsageRecordsB.forEachIndexed { index, record ->
            if (record != null) {
                val modeName = status.assistModeNames.getOrNull(index) ?: return@forEachIndexed
                val safeMode = sanitizeForMqtt(modeName)
                val lastRecord = last?.sortedUsageRecordsB?.getOrNull(index)
                
                if (record.distance != lastRecord?.distance && record.distance > 0) {
                    app.mqttManager.publish("$bikeTopic/${safeMode}distance", (record.distance / 1000.0).toString(), true)
                }
                if (record.energy != lastRecord?.energy && record.energy > 0) {
                    app.mqttManager.publish("$bikeTopic/${safeMode}energy", (record.energy / 1000.0).toString(), true)
                }
            }
        }

        // PowerTube Specific Topics
        val batterySerial = status.batterySerialNumber
        if (!batterySerial.isNullOrEmpty()) {
            val battTopic = "powertube/$batterySerial"
            pubIfDiff("$battTopic/stateofcharge", status.batteryLevel, last?.batteryLevel, true)
            pubIfDiff("$battTopic/totalbattery", status.totalBattery, last?.totalBattery, true)
            pubIfDiff("$battTopic/chargecycles", status.chargeCycles, last?.chargeCycles, true)
            pubIfDiff("$battTopic/serial", batterySerial, last?.batterySerialNumber, true)
        }

        lastPublishedStatus = status
    }

    private fun sanitizeForMqtt(input: String): String {
        return input.lowercase().replace(Regex("[^a-z0-9]"), "_")
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

    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // This is called when the app is swiped away from the recent apps list
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        FileLogger.log("EBikeBackgroundService: onDestroy")
        val app = application as EBikeApplication
        
        // Stop foreground and cancel notifications
        stopForeground(true)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        
        // Disconnect MQTT (best effort)
        app.mqttManager.disconnect()
        serviceScope.cancel() 
        
        // WE REMOVED THE AGGRESSIVE KILLPROCESS LOGIC HERE
        // This allows the app to stay in memory normally.
    }

    private fun createNotification(text: String): Notification {
        // PendingIntent to open the app on click
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, 
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("eBike Background Monitoring")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher) // Use app icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent) // CLICK TO OPEN APP
            .build()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun updateNotification(text: String) {
        // Don't show 'disconnected' notification as a persistent one if we are cleaning up
        if (text.contains("disconnected", ignoreCase = true)) {
             // Let onDestroy handle it, or just don't update to a negative state
             return
        }
        
        val notification = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
