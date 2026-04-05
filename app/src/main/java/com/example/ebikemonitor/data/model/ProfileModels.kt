package com.example.ebikemonitor.data.model

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(InternalSerializationApi::class)
@Serializable
data class BikeProfile(
    val macAddress: String,
    val name: String,
    val lastDiscoveryVersion: Int = 0
)

@OptIn(InternalSerializationApi::class)
@Serializable
data class BatteryProfile(
    val serialNumber: String,
    val model: String? = null,
    val lastDiscoveryVersion: Int = 0
)
