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
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.example.ebikemonitor.data.model.BikeProfile
import com.example.ebikemonitor.data.model.BatteryProfile

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

const val CURRENT_BIKE_DISCOVERY_VERSION = 2
const val CURRENT_BATTERY_DISCOVERY_VERSION = 1

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
        
        val ASSIST_MODE_NAMES = stringPreferencesKey("assist_mode_names")
        
        val BIKE_PROFILES = stringPreferencesKey("bike_profiles")
        val BATTERY_PROFILES = stringPreferencesKey("battery_profiles")
        val ACTIVE_BIKE_MAC = stringPreferencesKey("active_bike_mac")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val bleMacAddress: Flow<String?> = context.dataStore.data.map { it[BLE_MAC_ADDRESS] }
    val eBikeName: Flow<String> = context.dataStore.data.map { it[EBIKE_NAME] ?: "MyEBike" }
    val autoConnectBle: Flow<Boolean> = context.dataStore.data.map { it[AUTO_CONNECT_BLE] ?: true }
    val autoConnectMqtt: Flow<Boolean> = context.dataStore.data.map { it[AUTO_CONNECT_MQTT] ?: true }
    val autoLaunchFlow: Flow<Boolean> = context.dataStore.data.map { it[AUTO_LAUNCH_FLOW] ?: false }
    
    val mqttBrokerUri: Flow<String> = context.dataStore.data.map { it[MQTT_BROKER_URI] ?: "" }
    val mqttUser: Flow<String> = context.dataStore.data.map { it[MQTT_USER] ?: "" }
    val mqttPassword: Flow<String> = context.dataStore.data.map { it[MQTT_PASSWORD] ?: "" }
    
    val assistModeNames: Flow<List<String>> = context.dataStore.data.map { 
        val str = it[ASSIST_MODE_NAMES] ?: ""
        if (str.isEmpty()) emptyList() else str.split(",")
    }

    val bikeProfiles: Flow<List<BikeProfile>> = context.dataStore.data.map {
        val str = it[BIKE_PROFILES] ?: ""
        if (str.isEmpty()) emptyList() else try { json.decodeFromString(str) } catch (e: Exception) { emptyList() }
    }

    val batteryProfiles: Flow<List<BatteryProfile>> = context.dataStore.data.map {
        val str = it[BATTERY_PROFILES] ?: ""
        if (str.isEmpty()) emptyList() else try { json.decodeFromString(str) } catch (e: Exception) { emptyList() }
    }

    val activeBikeMac: Flow<String?> = context.dataStore.data.map { it[ACTIVE_BIKE_MAC] }

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
    
    suspend fun saveAssistModeNames(names: List<String>) {
        context.dataStore.edit { it[ASSIST_MODE_NAMES] = names.joinToString(",") }
    }

    suspend fun saveBikeProfiles(profiles: List<BikeProfile>) {
        context.dataStore.edit { it[BIKE_PROFILES] = json.encodeToString(profiles) }
    }

    suspend fun saveBatteryProfiles(profiles: List<BatteryProfile>) {
        context.dataStore.edit { it[BATTERY_PROFILES] = json.encodeToString(profiles) }
    }

    suspend fun setActiveBikeMac(mac: String?) {
        context.dataStore.edit { 
            if (mac == null) it.remove(ACTIVE_BIKE_MAC) else it[ACTIVE_BIKE_MAC] = mac 
        }
    }

    suspend fun migrateLegacySettings() {
        val data = context.dataStore.data.first()
        val legacyMac = data[BLE_MAC_ADDRESS]
        val legacyName = data[EBIKE_NAME] ?: "MyEBike"
        
        val profilesStr = data[BIKE_PROFILES] ?: ""
        if (legacyMac != null && profilesStr.isEmpty()) {
            val legacyProfile = BikeProfile(macAddress = legacyMac, name = legacyName, lastDiscoveryVersion = 0)
            saveBikeProfiles(listOf(legacyProfile))
            setActiveBikeMac(legacyMac)
        }
    }

    suspend fun updateBikeDiscoveryVersion(mac: String, version: Int) {
        val profiles = bikeProfiles.first().toMutableList()
        val index = profiles.indexOfFirst { it.macAddress == mac }
        if (index != -1) {
            profiles[index] = profiles[index].copy(lastDiscoveryVersion = version)
            saveBikeProfiles(profiles)
        }
    }

    suspend fun updateBatteryDiscoveryVersion(serial: String, version: Int, model: String? = null) {
        val profiles = batteryProfiles.first().toMutableList()
        val index = profiles.indexOfFirst { it.serialNumber == serial }
        if (index != -1) {
            profiles[index] = profiles[index].copy(
                lastDiscoveryVersion = version,
                model = model ?: profiles[index].model
            )
        } else {
            profiles.add(BatteryProfile(serialNumber = serial, model = model, lastDiscoveryVersion = version))
        }
        saveBatteryProfiles(profiles)
    }
}
