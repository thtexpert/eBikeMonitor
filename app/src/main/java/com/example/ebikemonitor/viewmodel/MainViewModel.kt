package com.example.ebikemonitor.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
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

        // MQTT Re-sync logic
        viewModelScope.launch {
            mqttManager.isConnected.collect { connected ->
                if (connected) {
                    // Publish all current values
                    val status = bleManager.bikeStatus.value
                    publishFullStatus(status)
                    
                    // Publish BLE status
                    val bleConnected = bleManager.isConnected.value
                    mqttManager.publish("ebikemonitor/blestatus", if (bleConnected) "connected" else "disconnected")
                    
                    // Publish Connection Timestamp
                    mqttManager.publish("ebikemonitor/mqttconnecttimestamp", Instant.now().toString());
                }
            }
        }
        
        // BLE Status Reporting to MQTT
        viewModelScope.launch {
            bleManager.isConnected.collect { bleConnected ->
                if (mqttManager.isConnected.value) {
                     val statusPayload = if (bleConnected) "connected" else "disconnected"
                     mqttManager.publish("ebikemonitor/blestatus", statusPayload)
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
                     val topic = "ebikemonitor"
                     
                     status.speed?.let { mqttManager.publish("$topic/speed", it.toString()) }
                     
                     status.batteryLevel?.let { 
                         if (it > 0) mqttManager.publish("$topic/stateofcharge", it.toString()) 
                     }
                     
                     status.assistMode?.let { mqttManager.publish("$topic/assistmode", getAssistModeName(it)) }
                     status.humanPower?.let { mqttManager.publish("$topic/power", it.toString()) }
                 }
             }
        }

        // Auto-launch Flow App logic
        viewModelScope.launch {
            bleManager.isConnected.collect { connected ->
                if (connected) {
                    val autoLaunch = settingsRepository.autoLaunchFlow.first()
                    if (autoLaunch) {
                        delay(1000)
                        launchBoschApp()
                    }
                }
            }
        }
    }
    
    private suspend fun publishFullStatus(status: BikeStatus) {
        val topic = "ebikemonitor"
        
        status.speed?.let { mqttManager.publish("$topic/speed", it.toString()) }
        status.cadence?.let { mqttManager.publish("$topic/cadence", it.toString()) }
        status.humanPower?.let { mqttManager.publish("$topic/power", it.toString()) }
        
        status.batteryLevel?.let {
            if (it > 0) mqttManager.publish("$topic/stateofcharge", it.toString())
        }
        
        status.assistMode?.let { mqttManager.publish("$topic/assistmode", getAssistModeName(it)) }
        
        status.totalDistance?.let {
            if (it > 0) mqttManager.publish("$topic/totaldistance", it.toString())
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
                val topic = "ebikemonitor"
                mqttManager.connect(uri, clientId, user, pass, topic)
            }
        }
    }

    fun toggleMqttConnection() {
        if (isMqttConnected.value) {
            val topic = "ebikemonitor"
            viewModelScope.launch { mqttManager.disconnect(topic) }
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
