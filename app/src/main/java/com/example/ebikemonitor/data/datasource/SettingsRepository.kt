package com.example.ebikemonitor.data.datasource

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val BLE_MAC_ADDRESS = stringPreferencesKey("ble_mac_address")
        val EBIKE_NAME = stringPreferencesKey("ebike_name")
        val AUTO_CONNECT_BLE = booleanPreferencesKey("auto_connect_ble")
        val AUTO_CONNECT_MQTT = booleanPreferencesKey("auto_connect_mqtt")
        val AUTO_LAUNCH_FLOW = booleanPreferencesKey("auto_launch_flow")
        
        val MQTT_BROKER_URI = stringPreferencesKey("mqtt_broker_uri")
        val MQTT_USER = stringPreferencesKey("mqtt_user")
        val MQTT_PASSWORD = stringPreferencesKey("mqtt_password")
    }

    val bleMacAddress: Flow<String?> = context.dataStore.data.map { it[BLE_MAC_ADDRESS] }
    val eBikeName: Flow<String> = context.dataStore.data.map { it[EBIKE_NAME] ?: "My eBike" }
    val autoConnectBle: Flow<Boolean> = context.dataStore.data.map { it[AUTO_CONNECT_BLE] ?: true }
    val autoConnectMqtt: Flow<Boolean> = context.dataStore.data.map { it[AUTO_CONNECT_MQTT] ?: true }
    val autoLaunchFlow: Flow<Boolean> = context.dataStore.data.map { it[AUTO_LAUNCH_FLOW] ?: false }
    
    val mqttBrokerUri: Flow<String> = context.dataStore.data.map { it[MQTT_BROKER_URI] ?: "" }
    val mqttUser: Flow<String> = context.dataStore.data.map { it[MQTT_USER] ?: "" }
    val mqttPassword: Flow<String> = context.dataStore.data.map { it[MQTT_PASSWORD] ?: "" }

    suspend fun saveBleMacAddress(mac: String) {
        context.dataStore.edit { it[BLE_MAC_ADDRESS] = mac }
    }
    
    suspend fun saveEBikeName(name: String) {
        context.dataStore.edit { it[EBIKE_NAME] = name }
    }

    suspend fun saveAutoConnectBle(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_CONNECT_BLE] = enabled }
    }
    
    suspend fun saveAutoConnectMqtt(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_CONNECT_MQTT] = enabled }
    }
    
    suspend fun saveAutoLaunchFlow(enabled: Boolean) {
        context.dataStore.edit { it[AUTO_LAUNCH_FLOW] = enabled }
    }
    
    suspend fun saveMqttConfig(uri: String, user: String, pass: String) {
        context.dataStore.edit {
            it[MQTT_BROKER_URI] = uri
            it[MQTT_USER] = user
            it[MQTT_PASSWORD] = pass
        }
    }
}
