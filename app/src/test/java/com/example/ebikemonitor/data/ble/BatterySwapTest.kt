package com.example.ebikemonitor.data.ble

import com.example.ebikemonitor.data.model.BatteryProfile
import com.example.ebikemonitor.data.model.BikeStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BatterySwapTest {

    private lateinit var batteryStateManager: BatteryStateManager

    @Before
    fun setUp() {
        batteryStateManager = BatteryStateManager()
    }

    @Test
    fun testDisconnectResetsBatteryTelemetryAndFlags() {
        val initialStatus = BikeStatus()
        val profiles = listOf(
            BatteryProfile(hardwareSerial = "BAT_A", totalElectricEnergyOffset = 0.0, chargeCyclesOffset = 0.0)
        )

        // 1. Connect Battery A and receive serial + telemetry
        val (status1, _) = batteryStateManager.onBatterySerialDecoded("BAT_A", initialStatus, profiles)
        val status2 = batteryStateManager.onTotalElectricEnergyReceived(106.9, status1, profiles)
        val status3 = batteryStateManager.onChargeCyclesReceived(155.0, status2, profiles)

        assertEquals("BAT_A", status3.batterySerialNumber)
        assertEquals(106.9, status3.totalElectricEnergy ?: 0.0, 0.001)
        assertEquals(155.0, status3.chargeCycles ?: 0.0, 0.001)
        assertTrue(batteryStateManager.hasReceivedRealBatteryEnergy)
        assertTrue(batteryStateManager.hasReceivedRealChargeCycles)

        // 2. Disconnect BLE
        val disconnectedStatus = batteryStateManager.resetOnDisconnect(status3)

        // 3. Verify telemetry is cleared and flags are reset
        assertNull(disconnectedStatus.rawTotalElectricEnergy)
        assertNull(disconnectedStatus.totalElectricEnergy)
        assertNull(disconnectedStatus.rawChargeCycles)
        assertNull(disconnectedStatus.chargeCycles)
        assertNull(batteryStateManager.rawTotalElectricEnergy)
        assertNull(batteryStateManager.rawChargeCycles)
        assertFalse(batteryStateManager.hasReceivedRealBatteryEnergy)
        assertFalse(batteryStateManager.hasReceivedRealChargeCycles)
    }

    @Test
    fun testBatterySwapToUnregisteredBatteryDoesNotLeakStaleValues() {
        val initialStatus = BikeStatus()
        val profiles = listOf(
            BatteryProfile(hardwareSerial = "BAT_GREY", totalElectricEnergyOffset = 0.0, chargeCyclesOffset = 0.0)
        )

        // 1. Ride with Battery Grey
        val (s1, _) = batteryStateManager.onBatterySerialDecoded("BAT_GREY", initialStatus, profiles)
        val s2 = batteryStateManager.onTotalElectricEnergyReceived(106.9, s1, profiles)
        val s3 = batteryStateManager.onChargeCyclesReceived(155.0, s2, profiles)
        assertEquals(106.9, s3.totalElectricEnergy ?: 0.0, 0.001)

        // 2. Bike powers off
        val disconnectedStatus = batteryStateManager.resetOnDisconnect(s3)

        // 3. Swap in Battery Red (not in profiles list)
        val (sRedInit, serialChanged) = batteryStateManager.onBatterySerialDecoded("BAT_RED", disconnectedStatus, profiles)
        assertTrue(serialChanged)
        assertEquals("BAT_RED", sRedInit.batterySerialNumber)

        // Critical: Before new BMS packets arrive, energy & cycles must be null (no leakage of 106.9 or 155.0!)
        assertNull("Stale electric energy leaked to new battery!", sRedInit.totalElectricEnergy)
        assertNull("Stale charge cycles leaked to new battery!", sRedInit.chargeCycles)
        assertFalse(batteryStateManager.hasReceivedRealBatteryEnergy)
        assertFalse(batteryStateManager.hasReceivedRealChargeCycles)

        // 4. Low-frequency BMS packets for Red finally arrive
        val sRedWithEnergy = batteryStateManager.onTotalElectricEnergyReceived(35.7, sRedInit, profiles)
        val sRedComplete = batteryStateManager.onChargeCyclesReceived(40.9, sRedWithEnergy, profiles)

        // Verify unregistered battery immediately adopts raw values with 0.0 offset (not ignored or frozen)
        assertEquals(35.7, sRedComplete.totalElectricEnergy ?: 0.0, 0.001)
        assertEquals(40.9, sRedComplete.chargeCycles ?: 0.0, 0.001)
        assertTrue(batteryStateManager.hasReceivedRealBatteryEnergy)
        assertTrue(batteryStateManager.hasReceivedRealChargeCycles)
    }

    @Test
    fun testBatterySwapWithOffsetsDoesNotCrossContaminate() {
        val initialStatus = BikeStatus()
        val profiles = listOf(
            BatteryProfile(hardwareSerial = "BAT_A", totalElectricEnergyOffset = 0.0, chargeCyclesOffset = 0.0),
            BatteryProfile(hardwareSerial = "BAT_B", totalElectricEnergyOffset = 24.32, chargeCyclesOffset = 40.2)
        )

        // 1. Battery A was active with raw 106.9 kWh and 155.0 cycles
        val (sA1, _) = batteryStateManager.onBatterySerialDecoded("BAT_A", initialStatus, profiles)
        val sA2 = batteryStateManager.onTotalElectricEnergyReceived(106.9, sA1, profiles)
        val sA3 = batteryStateManager.onChargeCyclesReceived(155.0, sA2, profiles)
        assertEquals(106.9, sA3.totalElectricEnergy ?: 0.0, 0.001)
        assertEquals(155.0, sA3.chargeCycles ?: 0.0, 0.001)

        // 2. Disconnect and swap to Battery B (which has +24.32 kWh offset)
        val disconnected = batteryStateManager.resetOnDisconnect(sA3)
        val (sBInit, serialChanged) = batteryStateManager.onBatterySerialDecoded("BAT_B", disconnected, profiles)
        assertTrue(serialChanged)

        // Critical: Battery B's offset must NOT be added to Battery A's raw values (106.9 + 24.32 = 131.22 must NOT occur!)
        assertNull("Premature offset addition on stale raw value!", sBInit.totalElectricEnergy)
        assertNull("Premature offset addition on stale raw cycles!", sBInit.chargeCycles)

        // 3. Battery B's raw values arrive from BMS: 35.7 kWh and 40.9 cycles
        val sBEnergy = batteryStateManager.onTotalElectricEnergyReceived(35.7, sBInit, profiles)
        val sBComplete = batteryStateManager.onChargeCyclesReceived(40.9, sBEnergy, profiles)

        // Verify Battery B's offsets are applied to Battery B's own raw values:
        // 35.7 + 24.32 = 60.02 kWh
        // 40.9 + 40.2 = 81.1 cycles
        assertEquals(60.02, sBComplete.totalElectricEnergy ?: 0.0, 0.001)
        assertEquals(81.1, sBComplete.chargeCycles ?: 0.0, 0.001)
    }

    @Test
    fun testSerialChangeMidConnectionInvalidatesStaleTelemetry() {
        val initialStatus = BikeStatus()
        val profiles = listOf(
            BatteryProfile(hardwareSerial = "BAT_1", totalElectricEnergyOffset = 0.0, chargeCyclesOffset = 0.0),
            BatteryProfile(hardwareSerial = "BAT_2", totalElectricEnergyOffset = 10.0, chargeCyclesOffset = 5.0)
        )

        // Battery 1 has active telemetry
        val (s1, _) = batteryStateManager.onBatterySerialDecoded("BAT_1", initialStatus, profiles)
        val s2 = batteryStateManager.onTotalElectricEnergyReceived(50.0, s1, profiles)
        val s3 = batteryStateManager.onChargeCyclesReceived(20.0, s2, profiles)
        assertEquals(50.0, s3.totalElectricEnergy ?: 0.0, 0.001)

        // Serial unexpectedly changes mid-connection to BAT_2 without disconnect
        val (sChanged, changed) = batteryStateManager.onBatterySerialDecoded("BAT_2", s3, profiles)
        assertTrue(changed)
        assertEquals("BAT_2", sChanged.batterySerialNumber)
        assertNull(sChanged.totalElectricEnergy)
        assertNull(sChanged.chargeCycles)
        assertFalse(batteryStateManager.hasReceivedRealBatteryEnergy)
        assertFalse(batteryStateManager.hasReceivedRealChargeCycles)
    }
}
