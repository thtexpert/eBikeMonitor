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
    val ebikeLedSoftwareVersion: String? = null,
    val assistModeNames: List<String> = emptyList(),
    val lastUpdateTimestamp: Long = 0L
)

fun getAssistModeName(mode: Int?, modeNames: List<String> = emptyList()): String {
    if (mode == null) return "--"
    if (mode >= 0 && mode < modeNames.size) {
        return modeNames[mode]
    }
    return mode.toString()
}
