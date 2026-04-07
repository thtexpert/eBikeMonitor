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
    val unsortedUsageRecords: List<UsageRecord> = emptyList(),
    val sortedUsageRecordsA: List<UsageRecord> = emptyList(),
    val sortedUsageRecordsB: List<UsageRecord?> = emptyList(),
    val confirmedModeIndices: Set<Int> = emptySet(),
    val tripDistPerMode: List<Int> = emptyList(),
    val prevTripDistPerMode: List<Int>? = null,
    val prevUnsortedUsageRecords: List<UsageRecord>? = null,
    val initialTripDistPerMode: List<Int>? = null,
    val initialUnsortedUsageRecords: List<UsageRecord>? = null,
    val modeToInitialIndex: Map<Int, Int> = emptyMap(), // ModeIndex -> InitialRecordIndex
    val totalEnergyFromMotor: Double? = null,
    val batterySerialNumber: String? = null,
    val batteryModel: String? = null,
    val chargeCycles: Double? = null,
    val driveUnitHours: Int? = null,
    val persistentBaselines: List<Int>? = null,
    val lastUpdateTimestamp: Long = 0L
)

fun getAssistModeName(mode: Int?, modeNames: List<String> = emptyList()): String {
    if (mode == null) return "--"
    if (mode >= 0 && mode < modeNames.size) {
        return modeNames[mode]
    }
    return mode.toString()
}
