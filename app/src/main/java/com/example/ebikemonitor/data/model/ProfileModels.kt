package com.example.ebikemonitor.data.model
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@Serializable
data class UsageRecord(
    val distance: Int,
    val energy: Int
)

@Serializable
data class BikeProfile(
    val macAddress: String,
    val name: String,
    val lastDiscoveryVersion: Int = 0,
    val lastUsageRecords: List<UsageRecord?> = emptyList(),
    val lastTripDistPerMode: List<Int> = emptyList()
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class BatteryProfile(
    val hardwareSerial: String,
    val modelName: String? = null,
    val lastKnownChargeCycles: Double? = null,
    val lastDiscoveryVersion: Int = 0
)
