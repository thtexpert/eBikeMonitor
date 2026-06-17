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

const val CURRENT_BIKE_DISCOVERY_VERSION = 3
const val CURRENT_BATTERY_DISCOVERY_VERSION = 2

class SettingsRepository(private val context: Context) {

    companion object {
        val BLE_MAC_ADDRESS = stringPreferencesKey("ble_mac_address")
        val EBIKE_NAME = stringPreferencesKey("ebike_name")
        val AUTO_CONNECT_BLE = booleanPreferencesKey("auto_connect_ble")
        val AUTO_CONNECT_MQTT = booleanPreferencesKey("auto_connect_mqtt")
        val AUTO_LAUNCH_FLOW = booleanPreferencesKey("auto_launch_flow")
        val BACKGROUND_STARTUP = booleanPreferencesKey("background_startup")
        val USE_DIRECT_DETECTION = booleanPreferencesKey("use_direct_detection")
        val USE_HARDWARE_CONNECTION_TRIGGER = booleanPreferencesKey("use_hardware_connection_trigger")
        
        val MQTT_BROKER_URI = stringPreferencesKey("mqtt_broker_uri")
        val MQTT_USER = stringPreferencesKey("mqtt_user")
        val MQTT_PASSWORD = stringPreferencesKey("mqtt_password")
        
        val ASSIST_MODE_NAMES = stringPreferencesKey("assist_mode_names")
        
        val BIKE_PROFILES = stringPreferencesKey("bike_profiles")
        val BATTERY_PROFILES = stringPreferencesKey("battery_profiles")
        val ACTIVE_BIKE_MAC = stringPreferencesKey("active_bike_mac")
        val HOME_SYNC_DURATION_MINS = intPreferencesKey("home_sync_duration_mins")
    }

    private val json = Json { ignoreUnknownKeys = true }

    val bleMacAddress: Flow<String?> = context.dataStore.data.map { it[BLE_MAC_ADDRESS] }
    val eBikeName: Flow<String> = context.dataStore.data.map { it[EBIKE_NAME] ?: "MyEBike" }
    val autoConnectBle: Flow<Boolean> = context.dataStore.data.map { it[AUTO_CONNECT_BLE] ?: true }
    val autoConnectMqtt: Flow<Boolean> = context.dataStore.data.map { it[AUTO_CONNECT_MQTT] ?: true }
    val autoLaunchFlow: Flow<Boolean> = context.dataStore.data.map { it[AUTO_LAUNCH_FLOW] ?: false }
    val backgroundStartup: Flow<Boolean> = context.dataStore.data.map { it[BACKGROUND_STARTUP] ?: false }
    val useDirectDetection: Flow<Boolean> = context.dataStore.data.map { it[USE_DIRECT_DETECTION] ?: true }
    val useHardwareConnectionTrigger: Flow<Boolean> = context.dataStore.data.map { it[USE_HARDWARE_CONNECTION_TRIGGER] ?: true }
    
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

    // Home Sync Window: 0 = disabled, 1-10 = minutes, default 2
    val homeSyncDurationMins: Flow<Int> = context.dataStore.data.map { it[HOME_SYNC_DURATION_MINS] ?: 2 }

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

    suspend fun saveBackgroundStartup(enabled: Boolean) {
        context.dataStore.edit { it[BACKGROUND_STARTUP] = enabled }
    }
    
    suspend fun saveUseDirectDetection(enabled: Boolean) {
        context.dataStore.edit { it[USE_DIRECT_DETECTION] = enabled }
    }

    suspend fun saveUseHardwareConnectionTrigger(enabled: Boolean) {
        context.dataStore.edit { it[USE_HARDWARE_CONNECTION_TRIGGER] = enabled }
    }

    suspend fun saveHomeSyncDuration(mins: Int) {
        context.dataStore.edit { it[HOME_SYNC_DURATION_MINS] = mins.coerceIn(0, 10) }
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
        val index = profiles.indexOfFirst { it.hardwareSerial == serial }
        if (index != -1) {
            profiles[index] = profiles[index].copy(
                lastDiscoveryVersion = version,
                modelName = model ?: profiles[index].modelName
            )
        } else {
            profiles.add(BatteryProfile(hardwareSerial = serial, modelName = model, lastDiscoveryVersion = version))
        }
        saveBatteryProfiles(profiles)
    }

    suspend fun updateBikeUsageHistory(mac: String, usage: List<com.example.ebikemonitor.data.model.UsageRecord?>, trip: List<Int>) {
        val profiles = bikeProfiles.first().toMutableList()
        val index = profiles.indexOfFirst { it.macAddress == mac }
        if (index != -1) {
            profiles[index] = profiles[index].copy(
                lastUsageRecords = usage,
                lastTripDistPerMode = trip
            )
            saveBikeProfiles(profiles)
        }
    }
}
