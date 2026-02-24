package com.example.ebikemonitor.data.model

data class BikeStatus(
    val speed: Double? = null,
    val cadence: Int? = null,
    val humanPower: Int? = null,
    val motorPower: Int? = null,
    val batteryLevel: Int? = null,
    val assistMode: Int? = null,
    val totalDistance: Double? = null,
    val totalBattery: Double? = null,
    val lastUpdateTimestamp: Long = 0L
)

fun getAssistModeName(mode: Int?): String {
    if (mode == null) return "--"
    return when (mode) {
        0 -> "OFF"
        1 -> "ECO"
        2 -> "TOUR+"
        3 -> "SPORT" // or eMTB
        4 -> "TURBO"
        // Add other modes as discovered
        else -> "Mode $mode"
    }
}
