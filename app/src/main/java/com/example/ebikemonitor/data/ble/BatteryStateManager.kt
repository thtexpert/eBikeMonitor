package com.example.ebikemonitor.data.ble

import com.example.ebikemonitor.data.model.BikeStatus
import com.example.ebikemonitor.data.model.BatteryProfile

/**
 * Manages the state, telemetry caching, and offset calculations for eBike batteries.
 * Encapsulates battery lifecycle transitions (connection, disconnect, battery swap)
 * to prevent stale telemetry leakage across different battery packs (Issue #30).
 */
class BatteryStateManager {

    var rawTotalElectricEnergy: Double? = null
        private set

    var rawChargeCycles: Double? = null
        private set

    var hasReceivedRealBatteryEnergy: Boolean = false
        private set

    var hasReceivedRealChargeCycles: Boolean = false
        private set

    /**
     * Resets transient BLE arrival flags on connection state change.
     */
    fun resetFlags() {
        hasReceivedRealBatteryEnergy = false
        hasReceivedRealChargeCycles = false
    }

    /**
     * Fully resets battery telemetry and raw caches upon BLE disconnection
     * to prevent leaking metrics to subsequent connections or different batteries.
     */
    fun resetOnDisconnect(status: BikeStatus): BikeStatus {
        rawTotalElectricEnergy = null
        rawChargeCycles = null
        resetFlags()
        return status.copy(
            rawTotalElectricEnergy = null,
            totalElectricEnergy = null,
            rawChargeCycles = null,
            chargeCycles = null
        )
    }

    /**
     * Handles decoding of the battery serial number (BLE topic 0x0081).
     * If the serial differs from the previously connected battery, immediately invalidates
     * the cached raw values to prevent cross-battery telemetry contamination.
     *
     * @return Pair of updated BikeStatus and a Boolean indicating if a serial change occurred.
     */
    fun onBatterySerialDecoded(
        serial: String,
        currentStatus: BikeStatus,
        batteryProfiles: List<BatteryProfile>
    ): Pair<BikeStatus, Boolean> {
        val previousSerial = currentStatus.batterySerialNumber
        val serialChanged = previousSerial != null && previousSerial != serial

        var status = currentStatus
        if (serialChanged) {
            rawTotalElectricEnergy = null
            rawChargeCycles = null
            resetFlags()
            status = status.copy(
                rawTotalElectricEnergy = null,
                totalElectricEnergy = null,
                rawChargeCycles = null,
                chargeCycles = null
            )
        }

        status = status.copy(batterySerialNumber = serial)
        val updated = applyBatteryOffsets(status, batteryProfiles)
        return Pair(updated, serialChanged)
    }

    /**
     * Handles incoming Total Electric Energy (BLE topic 0x80B0).
     */
    fun onTotalElectricEnergyReceived(
        rawKWh: Double,
        currentStatus: BikeStatus,
        batteryProfiles: List<BatteryProfile>
    ): BikeStatus {
        rawTotalElectricEnergy = rawKWh
        hasReceivedRealBatteryEnergy = true
        return applyBatteryOffsets(currentStatus, batteryProfiles)
    }

    /**
     * Handles incoming Cumulative Charge Cycles (BLE topic 0x8096).
     */
    fun onChargeCyclesReceived(
        rawCycles: Double,
        currentStatus: BikeStatus,
        batteryProfiles: List<BatteryProfile>
    ): BikeStatus {
        rawChargeCycles = rawCycles
        hasReceivedRealChargeCycles = true
        return applyBatteryOffsets(currentStatus, batteryProfiles)
    }

    /**
     * Applies configured offsets from the matching BatteryProfile to the raw BMS metrics.
     * If no profile is registered for the serial, defaults offsets to 0.0 so raw metrics
     * are immediately reflected in BikeStatus without locking or aborting.
     */
    fun applyBatteryOffsets(
        status: BikeStatus,
        batteryProfiles: List<BatteryProfile>
    ): BikeStatus {
        val serial = status.batterySerialNumber
        val profile = if (serial != null) batteryProfiles.find { it.hardwareSerial == serial } else null
        val energyOffset = profile?.totalElectricEnergyOffset ?: 0.0
        val cyclesOffset = profile?.chargeCyclesOffset ?: 0.0

        val computedEnergy = rawTotalElectricEnergy?.let { it + energyOffset }
        val computedCycles = rawChargeCycles?.let { it + cyclesOffset }

        return status.copy(
            rawTotalElectricEnergy = rawTotalElectricEnergy,
            totalElectricEnergy = computedEnergy,
            rawChargeCycles = rawChargeCycles,
            chargeCycles = computedCycles
        )
    }
}
