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
import com.example.ebikemonitor.data.model.UsageRecord
import com.example.ebikemonitor.data.mqtt.MqttManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import com.example.ebikemonitor.FileLogger
import java.time.Instant
import java.util.Date
import com.example.ebikemonitor.data.model.BikeProfile
import com.example.ebikemonitor.data.model.BatteryProfile
import com.example.ebikemonitor.data.datasource.CURRENT_BIKE_DISCOVERY_VERSION
import com.example.ebikemonitor.data.datasource.CURRENT_BATTERY_DISCOVERY_VERSION

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
    
    private val _mqttSessionConnectTime = MutableStateFlow<String?>(null)
    val mqttSessionConnectTime = _mqttSessionConnectTime.asStateFlow()
    private var lastActiveBatterySerial: String? = null
    
    private val _isNotificationAccessGranted = MutableStateFlow(false)
    val isNotificationAccessGranted = _isNotificationAccessGranted.asStateFlow()

    private val _companionDeviceIntentSender = kotlinx.coroutines.flow.MutableSharedFlow<android.content.IntentSender>()
    val companionDeviceIntentSender: kotlinx.coroutines.flow.SharedFlow<android.content.IntentSender> = _companionDeviceIntentSender
    
    // Combining settings for UI
    val savedBleMac = settingsRepository.bleMacAddress.stateIn(viewModelScope, SharingStarted.Lazily, null)
    val savedEBikeName = settingsRepository.eBikeName.stateIn(viewModelScope, SharingStarted.Lazily, "")
    val backgroundStartup = settingsRepository.backgroundStartup.stateIn(viewModelScope, SharingStarted.Lazily, true)
    val homeSyncDurationMins = settingsRepository.homeSyncDurationMins.stateIn(viewModelScope, SharingStarted.Lazily, 2)
    
    val bikeProfiles = settingsRepository.bikeProfiles.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val batteryProfiles = settingsRepository.batteryProfiles.stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    val activeBikeMac = settingsRepository.activeBikeMac.stateIn(viewModelScope, SharingStarted.Lazily, null)

    private val _isBikeDiscoveryOutdated = MutableStateFlow(false)
    val isBikeDiscoveryOutdated = _isBikeDiscoveryOutdated.asStateFlow()

    private val _isBatteryDiscoveryOutdated = MutableStateFlow(false)
    val isBatteryDiscoveryOutdated = _isBatteryDiscoveryOutdated.asStateFlow()

    init {
        // Legacy Migration
        viewModelScope.launch {
            settingsRepository.migrateLegacySettings()
        }

        // Auto-connect MQTT logic (Triggered by UI only if needed, Service handles background auto-connect)
        viewModelScope.launch {
            // Wait for settings to load
            delay(500) 
            
            val autoMqtt = settingsRepository.autoConnectMqtt.first()
            if (autoMqtt && !mqttManager.isConnected.value && !mqttManager.isConnecting.value) {
                FileLogger.log("MainViewModel: Auto-connect on UI start (MQTT not yet connected)")
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

        // MQTT Session Tracking for UI
        viewModelScope.launch {
            mqttManager.isConnected.collect { connected ->
                if (connected) {
                    _mqttSessionConnectTime.value = java.time.Instant.now().toString()
                } else {
                    _mqttSessionConnectTime.value = null
                }
            }
        }
        
        // Collect errors for UI text
        viewModelScope.launch {
            mqttManager.connectionError.collect { error ->
                _mqttErrorText.value = error
            }
        }

        // Discovery Version Monitoring
        viewModelScope.launch {
            combine(bikeProfiles, batteryProfiles, activeBikeMac, uiState) { profiles, battProfs, activeMac, status ->
                val activeProfile = profiles.find { it.macAddress == activeMac }
                val bikeOutdated = (activeProfile?.lastDiscoveryVersion ?: 0) < CURRENT_BIKE_DISCOVERY_VERSION
                
                val serial = status.batterySerialNumber
                val batteryOutdated = if (serial != null) {
                    val battProfile = battProfs.find { it.hardwareSerial == serial }
                    (battProfile?.lastDiscoveryVersion ?: 0) < CURRENT_BATTERY_DISCOVERY_VERSION
                } else {
                    false
                }
                
                Pair(bikeOutdated, batteryOutdated)
            }.collect { (bikeOutdated, batteryOutdated) ->
                _isBikeDiscoveryOutdated.value = bikeOutdated
                _isBatteryDiscoveryOutdated.value = batteryOutdated
            }
        }

        // --- NEW: Persistent Usage Baseline Loading ---
        viewModelScope.launch {
            combine(activeBikeMac, bikeProfiles) { mac, profiles ->
                profiles.find { it.macAddress == mac }
            }.collect { profile ->
                if (profile != null && profile.lastUsageRecords.isNotEmpty() && profile.lastTripDistPerMode.isNotEmpty()) {
                    // Calculate B_i = Usage_stored,i - Trip_stored,i
                    val baselines = profile.lastUsageRecords.mapIndexed { i, usage ->
                        val trip = profile.lastTripDistPerMode.getOrNull(i) ?: 0
                        (usage?.distance ?: 0) - trip
                    }
                    if (baselines.size == profile.lastUsageRecords.size) {
                        bleManager.setPersistentBaselines(baselines)
                    }
                }
            }
        }

        // --- NEW: Persistent Usage Saving ---
        viewModelScope.launch {
            bleManager.bikeStatus.collect { status ->
                val mac = activeBikeMac.value ?: return@collect
                val expectedCount = status.assistModeNames.size
                
                if (expectedCount > 0 && 
                    status.confirmedModeIndices.size == expectedCount && 
                    status.sortedUsageRecordsB.size == expectedCount &&
                    status.tripDistPerMode.size == expectedCount) {
                    
                    val cachedProfile = bikeProfiles.value.find { it.macAddress == mac }
                    val currentRecords = status.sortedUsageRecordsB
                    val currentTrip = status.tripDistPerMode
                    
                    // Basic debounce: only save if data has changed
                    if (cachedProfile != null && (cachedProfile.lastUsageRecords != currentRecords || cachedProfile.lastTripDistPerMode != currentTrip)) {
                        settingsRepository.updateBikeUsageHistory(mac, currentRecords, currentTrip)
                    }
                }
            }
        }

        // Periodic Permission Check
        viewModelScope.launch {
            while (true) {
                checkPermissions()
                delay(2000)
            }
        }
    }

    private fun checkPermissions() {
        val context = getApplication<Application>()
        val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        val packageName = context.packageName
        val isGranted = enabledListeners != null && enabledListeners.contains(packageName)
        _isNotificationAccessGranted.value = isGranted
    }

    fun openNotificationAccessSettings() {
        try {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
        } catch (e: Exception) {
            Log.e("MainViewModel", "Error opening notification access settings: ${e.message}")
        }
    }
    
    private fun sanitizeForMqtt(input: String): String {
        return input.lowercase().replace(Regex("[^a-z0-9]"), "_")
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
                val mac = activeBikeMac.value
                val deviceId = mac?.lowercase()?.replace(":", "") ?: "unknown"
                val topic = "ebikemonitor/$deviceId"
                mqttManager.connect(uri, clientId, user, pass, topic)
            }
        }
    }

    fun toggleMqttConnection() {
        if (isMqttConnected.value) {
            FileLogger.log("MainViewModel: [USER ACTION] Manual MQTT disconnect via UI")
            viewModelScope.launch { mqttManager.disconnect() }
        } else {
            FileLogger.log("MainViewModel: [USER ACTION] Manual MQTT connect via UI")
            connectMqtt()
        }
    }

    fun toggleBleConnection() {
        if (isBleConnected.value) {
            FileLogger.log("MainViewModel: [USER ACTION] Manual BLE disconnect via UI")
            bleManager.disconnect()
        } else {
            viewModelScope.launch {
                val mac = activeBikeMac.value
                if (mac != null) {
                    FileLogger.log("MainViewModel: [USER ACTION] Manual BLE connect via UI to $mac")
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
            settingsRepository.setActiveBikeMac(mac)
            
            // If bike doesn't exist in profiles, add it
            val profiles = bikeProfiles.value
            if (profiles.none { it.macAddress == mac }) {
                val newProfile = BikeProfile(macAddress = mac, name = "New Bike", lastDiscoveryVersion = 0)
                settingsRepository.saveBikeProfiles(profiles + newProfile)
            }
            
            bleManager.connect(mac)
        }
    }

    fun selectBike(mac: String) {
        viewModelScope.launch {
            settingsRepository.setActiveBikeMac(mac)
            if (isBleConnected.value) {
                bleManager.disconnect()
                delay(500)
                bleManager.connect(mac)
            }
        }
    }


    fun updateBikeDiscovery() {
        viewModelScope.launch {
            val mac = activeBikeMac.value ?: return@launch
            // Use the user-editable eBike Name (Settings) for the HA device label,
            // not BikeProfile.name which is never set from the UI.
            val name = settingsRepository.eBikeName.first()

            val deviceId = mac.lowercase().replace(":", "")
            mqttManager.sendBikeDiscovery(deviceId, name, uiState.value.assistModeNames)
            
            settingsRepository.updateBikeDiscoveryVersion(mac, CURRENT_BIKE_DISCOVERY_VERSION)
        }
    }

    fun updateBatteryDiscovery() {
        viewModelScope.launch {
            val serial = uiState.value.batterySerialNumber ?: return@launch
            val model = uiState.value.batteryModel
            
            mqttManager.sendPowerTubeDiscovery(serial, model)
            
            settingsRepository.updateBatteryDiscoveryVersion(serial, CURRENT_BATTERY_DISCOVERY_VERSION, model)
        }
    }

    fun sendHomeAssistantDiscovery() {
        // Legacy call redirected to new Bike Discovery
        updateBikeDiscovery()
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
    
    fun updateAutoConnectMqtt(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveAutoConnectMqtt(enabled)
        }
    }

    fun updateBackgroundStartup(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveBackgroundStartup(enabled)
        }
    }

    fun updateUseHardwareConnectionTrigger(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.saveUseHardwareConnectionTrigger(enabled)
        }
    }

    fun updateHomeSyncDuration(mins: Int) {
        viewModelScope.launch {
            FileLogger.log("MainViewModel: [USER ACTION] Home Sync Window set to ${mins}min")
            settingsRepository.saveHomeSyncDuration(mins)
        }
    }

    fun toggleDirectDetection(enabled: Boolean, context: Context) {
        viewModelScope.launch {
            settingsRepository.saveUseDirectDetection(enabled)
            if (enabled) {
                val deviceManager = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as android.companion.CompanionDeviceManager
                val mac = activeBikeMac.value ?: settingsRepository.bleMacAddress.first()
                
                val filterBuilder = android.companion.BluetoothDeviceFilter.Builder()
                if (mac != null) {
                    filterBuilder.setAddress(mac)
                }
                
                val associationRequest = android.companion.AssociationRequest.Builder()
                    .addDeviceFilter(filterBuilder.build())
                    .setSingleDevice(true)
                    .build()

                deviceManager.associate(associationRequest, object : android.companion.CompanionDeviceManager.Callback() {
                    override fun onDeviceFound(chooserLauncher: android.content.IntentSender) {
                        viewModelScope.launch {
                            _companionDeviceIntentSender.emit(chooserLauncher)
                        }
                    }
                    override fun onFailure(error: CharSequence?) {
                        Log.e("MainViewModel", "CDM Association Failed: $error")
                    }
                }, null)
            } else {
                val deviceManager = context.getSystemService(Context.COMPANION_DEVICE_SERVICE) as android.companion.CompanionDeviceManager
                val mac = activeBikeMac.value ?: settingsRepository.bleMacAddress.first()
                if (mac != null) {
                    try {
                        deviceManager.stopObservingDevicePresence(mac)
                    } catch (e: Exception) {}
                }
            }
        }
    }

    override fun onCleared() {
        // Disconnect as an extra safety measure when the UI is gone
        // Do this BEFORE super.onCleared() so the viewModelScope is still active
        FileLogger.log("MainViewModel: onCleared — disconnecting BLE and MQTT as safety measure")
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
