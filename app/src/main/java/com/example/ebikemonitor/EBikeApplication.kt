package com.example.ebikemonitor

import android.app.Application
import com.example.ebikemonitor.data.ble.BleManager
import com.example.ebikemonitor.data.datasource.SettingsRepository
import com.example.ebikemonitor.data.mqtt.MqttManager

class EBikeApplication : Application() {
    
    // Lazy initialization of dependencies
    val settingsRepository by lazy { SettingsRepository(this) }
    val bleManager by lazy { BleManager(this) }
    val mqttManager by lazy { MqttManager(this) }
    
    companion object {
        // Simple way to access instance if needed, though usually passed via Context
        private lateinit var instance: EBikeApplication
        fun getInstance(): EBikeApplication = instance
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Initialize managers if needed or just let them be lazy
    }
}
