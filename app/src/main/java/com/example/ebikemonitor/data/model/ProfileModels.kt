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
    val lastTripDistPerMode: List<Int> = emptyList(),
    val totalPedalEnergyBaseline: Double = 0.0,
    val totalRideEnergyBaseline: Double = 0.0,
    val totalPedalEnergyMeasuredBaseline: Double = 0.0,
    val totalPedalEnergyEstimatedBaseline: Double = 0.0,
    val lastTripPedalEnergy: Int = 0,
    val lastTripRideEnergy: Double = 0.0,
    val learnedUsableMotorCapacity: Float = 500.0f,
    val learnedUserEffortCoefficient: Float = 3.0f,
    val learnedMotorConsumptionRates: List<Float> = listOf(0.0f, 5.0f, 7.0f, 10.0f, 12.0f),
    val lastOdometer: Double = 0.0,
    val lastTotalMotorEnergy: Double = 0.0
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class BatteryProfile(
    val hardwareSerial: String,
    val modelName: String? = null,
    val lastKnownChargeCycles: Double? = null,
    val lastDiscoveryVersion: Int = 0,
    val totalElectricEnergyOffset: Double = 0.0,
    val chargeCyclesOffset: Double = 0.0
)
