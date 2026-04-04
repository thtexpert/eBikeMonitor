package com.example.ebikemonitor.viewmodel

import android.app.ActivityManager
import android.app.Application
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ebikemonitor.data.ble.BleManager
import com.example.ebikemonitor.data.datasource.SettingsRepository
import com.example.ebikemonitor.data.model.BikeStatus
import com.example.ebikemonitor.data.model.getAssistModeName
import com.example.ebikemonitor.data.mqtt.MqttManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Date

class MainViewModel(
    application: Application,
    val settingsRepository: SettingsRepository,
    val bleManager: BleManager,
    private val mqttManager: MqttManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(BikeStatus())
    val uiState = bleManager.bikeStatus

    val isBleConnected = bleManager.isConnected
    val isMqttConnected = mqttManager.isConnected
    val mqttError = mqttManager.connectionError
    // State for UI display of last error
    private val _mqttErrorText = MutableStateFlow("")
    val mqttErrorText = _mqttErrorText.asStateFlow()
    
    // Flow App Detection
    private val _isFlowRunning = MutableStateFlow(false)
    val isFlowRunning = _isFlowRunning.asStateFlow()
    
    private val _isUsageAccessGranted = MutableStateFlow(false)
    val isUsageAccessGranted = _isUsageAccessGranted.asStateFlow()
    
    // Combining settings for UI
    val savedBleMac = settingsRepository.bleMacAddress.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val savedEBikeName = settingsRepository.eBikeName.stateIn(viewModelScope, SharingStarted.Lazily, "")
    val autoConnectBle = settingsRepository.autoConnectBle.stateIn(viewModelScope, SharingStarted.Lazily, true)
    
    init {
        // Auto-connect logic
        viewModelScope.launch {
            // Wait for settings to load
            delay(1000) 
            
            val autoBle = settingsRepository.autoConnectBle.first()
            val mac = settingsRepository.bleMacAddress.first()
            
            if (autoBle && !mac.isNullOrEmpty()) {
                bleManager.connect(mac)
            }
            
            val autoMqtt = settingsRepository.autoConnectMqtt.first()
            if (autoMqtt) {
                connectMqtt()
            }
        }
        
        // Synchronize assist mode names from DataStore to BleManager
        viewModelScope.launch {
            settingsRepository.assistModeNames.collect { names ->
                if (names.isNotEmpty()) {
                    bleManager.setAssistModeNames(names)
                }
            }
        }
        
        // Synchronize from BleManager to DataStore when new names are received
        viewModelScope.launch {
            bleManager.bikeStatus.collect { status ->
                val names = status.assistModeNames
                if (names.isNotEmpty()) {
                    val cached = settingsRepository.assistModeNames.first()
                    if (names != cached) {
                        settingsRepository.saveAssistModeNames(names)
                    }
                }
            }
        }

        // MQTT Re-sync logic
        viewModelScope.launch {
            mqttManager.isConnected.collect { connected ->
                if (connected) {
                    // Publish all current values
                    val status = bleManager.bikeStatus.value
                    publishFullStatus(status)
                    
                    val topic = mqttManager.baseTopic
                    
                    // Publish BLE status
                    val bleConnected = bleManager.isConnected.value
                    mqttManager.publish("$topic/blestatus", if (bleConnected) "connected" else "disconnected")
                    
                    // Publish Connection Timestamp
                    mqttManager.publish("$topic/mqttconnecttimestamp", Instant.now().toString());
                }
            }
        }
        
        // BLE Status Reporting to MQTT
        viewModelScope.launch {
            bleManager.isConnected.collect { bleConnected ->
                if (mqttManager.isConnected.value) {
                     val topic = mqttManager.baseTopic
                     val statusPayload = if (bleConnected) "connected" else "disconnected"
                     mqttManager.publish("$topic/blestatus", statusPayload)
                }
            }
        }
        
        // Collect errors for UI text
        viewModelScope.launch {
            mqttManager.connectionError.collect { error ->
                _mqttErrorText.value = error
            }
        }

        // MQTT Live Update logic
        viewModelScope.launch {
             bleManager.bikeStatus.collect { status ->
                 if (mqttManager.isConnected.value) {
                     val topic = mqttManager.baseTopic
                     
                     status.speed?.let { mqttManager.publish("$topic/speed", it.toString()) }
                     status.cadence?.let { mqttManager.publish("$topic/cadence", it.toString()) }
                     
                     status.batteryLevel?.let { 
                         if (it > 0) mqttManager.publish("$topic/stateofcharge", it.toString(), retained = true) 
                     }
                     
                     status.assistMode?.let { mqttManager.publish("$topic/assistmode", getAssistModeName(it, status.assistModeNames)) }
                     status.humanPower?.let { mqttManager.publish("$topic/power", it.toString()) }
                     status.motorPower?.let { mqttManager.publish("$topic/motorpower", it.toString()) }
                     
                     status.totalDistance?.let {
                         if (it > 0) mqttManager.publish("$topic/totaldistance", it.toString(), retained = true)
                     }
                     status.totalBattery?.let {
                         if (it > 0) mqttManager.publish("$topic/totalbattery", it.toString(), retained = true)
                     }
                     
                     status.totalEnergyFromMotor?.let {
                         if (it > 0) mqttManager.publish("$topic/totalenergyfrommotor", it.toString(), retained = true)
                     }
                     
                     status.ebikeLedSoftwareVersion?.let { mqttManager.publish("$topic/ebikeledsoftwareversion", it, retained = true) }

                     // Per-Mode Metrics
                     status.sortedUsageRecordsB.forEachIndexed { index, record ->
                         if (record != null) {
                             val modeName = status.assistModeNames.getOrNull(index) ?: return@forEachIndexed
                             val safeMode = sanitizeForMqtt(modeName)
                             
                             if (record.distance > 0) {
                                 mqttManager.publish("$topic/${safeMode}distance", (record.distance / 1000.0).toString())
                             }
                             if (record.energy > 0) {
                                 mqttManager.publish("$topic/${safeMode}battery", (record.energy / 1000.0).toString())
                             }
                         }
                     }
                 }
             }
        }

        // Auto-launch Flow App logic
        viewModelScope.launch {
            bleManager.isConnected.collect { connected ->
                if (connected) {
                    val autoLaunch = settingsRepository.autoLaunchFlow.first()
                    // Only auto-launch if NOT already running
                    if (autoLaunch && !_isFlowRunning.value) {
                        delay(1000)
                        launchBoschApp()
                    }
                }
            }
        }

        // Periodic Flow App Running Check
        viewModelScope.launch {
            while (true) {
                checkFlowAppState()
                delay(1000) // 1-second interval as requested
            }
        }
    }

    private fun checkFlowAppState() {
        val context = getApplication<Application>()
        val packageName = "com.bosch.ebike.onebikeapp"
        
        // 1. Check if permission is granted
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        val granted = mode == AppOpsManager.MODE_ALLOWED
        _isUsageAccessGranted.value = granted

        if (!granted) {
            _isFlowRunning.value = false
            return
        }

        // 2. Check if running using UsageStatsManager Events (more stable)
        val usageStatsManager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 2000 // Look back 1 minute for events
        
        val events = usageStatsManager.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        
        var isRunning = _isFlowRunning.value // Keep previous state as fallback
        
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.packageName == packageName) {
                when (event.eventType) {
                    UsageEvents.Event.ACTIVITY_RESUMED,
                    UsageEvents.Event.ACTIVITY_PAUSED -> {
                        isRunning = true
                    }
                    UsageEvents.Event.ACTIVITY_STOPPED -> {
                        isRunning = false
                    }
                    // Foreground Service events (API 29+)
                    19 -> isRunning = true // UsageEvents.Event.FOREGROUND_SERVICE_START
                    20 -> isRunning = false // UsageEvents.Event.FOREGROUND_SERVICE_STOP
                }
            }
        }
        
        // Fallback: If no events in the last minute, check aggregate stats
        if (startTime > endTime - 60000 && !isRunning) {
            val stats = usageStatsManager.queryAndAggregateUsageStats(endTime - 300000, endTime)
            val flowStats = stats[packageName]
            if (flowStats != null) {
                isRunning = (endTime - flowStats.lastTimeUsed) < 15000 
            }
        }

        _isFlowRunning.value = isRunning
    }

    fun openUsageAccessSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error opening usage access settings: ${e.message}")
        }
    }

    fun stopBoschApp() {
        val packageName = "com.bosch.ebike.onebikeapp"
        try {
            // 1. Try to kill background processes
            val am = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(packageName)
            
            // 2. Open App Info screen as fallback/helper for manual Force Stop
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.parse("package:$packageName")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error stopping Bosch app: ${e.message}")
        }
    }
    
    private fun sanitizeForMqtt(input: String): String {
        return input.lowercase().replace(Regex("[^a-z0-9]"), "_")
    }

    private suspend fun publishFullStatus(status: BikeStatus) {
        val topic = mqttManager.baseTopic
        
        status.speed?.let { mqttManager.publish("$topic/speed", it.toString()) }
        status.cadence?.let { mqttManager.publish("$topic/cadence", it.toString()) }
        status.humanPower?.let { mqttManager.publish("$topic/power", it.toString()) }
        
        status.batteryLevel?.let {
            if (it > 0) mqttManager.publish("$topic/stateofcharge", it.toString(), retained = true)
        }
        
        status.assistMode?.let { mqttManager.publish("$topic/assistmode", getAssistModeName(it, status.assistModeNames)) }
        
        status.totalDistance?.let {
            if (it > 0) mqttManager.publish("$topic/totaldistance", it.toString(), retained = true)
        }

        status.totalBattery?.let {
            if (it > 0) mqttManager.publish("$topic/totalbattery", it.toString(), retained = true)
        }
        
        status.totalEnergyFromMotor?.let {
            if (it > 0) mqttManager.publish("$topic/totalenergyfrommotor", it.toString(), retained = true)
        }
        
        status.motorPower?.let { mqttManager.publish("$topic/motorpower", it.toString()) }
        status.ebikeLedSoftwareVersion?.let { mqttManager.publish("$topic/ebikeledsoftwareversion", it, retained = true) }

        // Per-Mode Metrics
        status.sortedUsageRecordsB.forEachIndexed { index, record ->
            if (record != null) {
                val modeName = status.assistModeNames.getOrNull(index) ?: return@forEachIndexed
                val safeMode = sanitizeForMqtt(modeName)
                
                if (record.distance > 0) {
                    mqttManager.publish("$topic/${safeMode}distance", (record.distance / 1000.0).toString())
                }
                if (record.energy > 0) {
                    mqttManager.publish("$topic/${safeMode}battery", (record.energy / 1000.0).toString())
                }
            }
        }
    }

    fun connectMqtt() {
        viewModelScope.launch {
            val uri = settingsRepository.mqttBrokerUri.first()
            val user = settingsRepository.mqttUser.first()
            val pass = settingsRepository.mqttPassword.first()
            
            // Client ID logic
            val name = settingsRepository.eBikeName.first()
            val clientId = if (name.isNotEmpty()) name else "MyEbike"
            
            if (uri.isNotEmpty()) {
                val mac = settingsRepository.bleMacAddress.first()
                val deviceId = mac?.lowercase()?.replace(":", "") ?: "unknown"
                val topic = "ebikemonitor/$deviceId"
                mqttManager.connect(uri, clientId, user, pass, topic)
            }
        }
    }

    fun toggleMqttConnection() {
        if (isMqttConnected.value) {
            viewModelScope.launch { mqttManager.disconnect() }
        } else {
            connectMqtt()
        }
    }

    fun toggleBleConnection() {
        if (isBleConnected.value) {
            bleManager.disconnect()
        } else {
            viewModelScope.launch {
                val mac = savedBleMac.value
                if (mac != null) {
                    bleManager.connect(mac)
                }
            }
        }
    }
    
    fun isBluetoothEnabled(): Boolean {
        return bleManager.isBluetoothEnabled()
    }
    
    fun connectToDevice(mac: String) {
        viewModelScope.launch {
            settingsRepository.saveBleMacAddress(mac)
            bleManager.connect(mac)
        }
    }

    fun launchBoschApp() {
        try {
            val packageName = "com.bosch.ebike.onebikeapp"
            val intent = getApplication<Application>().packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                getApplication<Application>().startActivity(intent)
            } else {
                Log.e("MainViewModel", "Bosch Flow app not installed")
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error launching Bosch app: ${e.message}")
        }
    }

    fun sendHomeAssistantDiscovery() {
        viewModelScope.launch {
            val name = savedEBikeName.value
            val mac = savedBleMac.value
            if (name.isBlank() || mac.isNullOrEmpty()) {
                _mqttErrorText.value = "Discovery Failed: Valid eBike Name and Target MAC required"
                return@launch
            }
            val deviceId = mac.lowercase().replace(":", "")
            mqttManager.sendHomeAssistantDiscovery(deviceId, name, uiState.value.assistModeNames)
        }
    }
    
    // Settings updaters
    fun updateMqttConfig(uri: String, user: String, pass: String) {
        viewModelScope.launch {
            settingsRepository.saveMqttConfig(uri, user, pass)
            // Reconnect if auto-connect is on?
        }
    }
    
    fun updateEBikeName(name: String) {
        viewModelScope.launch {
            settingsRepository.saveEBikeName(name)
        }
    }
    
    fun updateAutoConnectBle(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.saveAutoConnectBle(enabled) }
    }
    
    fun updateAutoConnectMqtt(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.saveAutoConnectMqtt(enabled) }
    }
    
    fun updateAutoLaunchFlow(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.saveAutoLaunchFlow(enabled) }
    }

    override fun onCleared() {
        // Disconnect as an extra safety measure when the UI is gone
        // Do this BEFORE super.onCleared() so the viewModelScope is still active
        mqttManager.disconnect()
        bleManager.disconnect()
        super.onCleared()
    }
}

class MainViewModelFactory(
    private val app: Application,
    private val settings: SettingsRepository,
    private val ble: BleManager,
    private val mqtt: MqttManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(app, settings, ble, mqtt) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
