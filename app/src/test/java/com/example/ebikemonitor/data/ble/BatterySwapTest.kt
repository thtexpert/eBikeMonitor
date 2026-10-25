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
            BatteryProfile(hardwareSerial = "BAT_A", chargeCyclesOffset = 0.0)
        )

        // 1. Connect Battery A and receive serial + telemetry
        val (status1, _) = batteryStateManager.onBatterySerialDecoded("BAT_A", initialStatus, profiles)
        val status2 = batteryStateManager.onChargeCyclesReceived(155.0, status1, profiles)

        assertEquals("BAT_A", status2.batterySerialNumber)
        assertEquals(155.0, status2.chargeCycles ?: 0.0, 0.001)
        assertTrue(batteryStateManager.hasReceivedRealChargeCycles)

        // 2. Disconnect BLE
        val disconnectedStatus = batteryStateManager.resetOnDisconnect(status2)

        // 3. Verify telemetry is cleared and flags are reset
        assertNull(disconnectedStatus.rawChargeCycles)
        assertNull(disconnectedStatus.chargeCycles)
        assertNull(batteryStateManager.rawChargeCycles)
        assertFalse(batteryStateManager.hasReceivedRealChargeCycles)
    }

    @Test
    fun testBatterySwapToUnregisteredBatteryDoesNotLeakStaleValues() {
        val initialStatus = BikeStatus()
        val profiles = listOf(
            BatteryProfile(hardwareSerial = "BAT_GREY", chargeCyclesOffset = 0.0)
        )

        // 1. Ride with Battery Grey
        val (s1, _) = batteryStateManager.onBatterySerialDecoded("BAT_GREY", initialStatus, profiles)
        val s2 = batteryStateManager.onChargeCyclesReceived(155.0, s1, profiles)
        assertEquals(155.0, s2.chargeCycles ?: 0.0, 0.001)

        // 2. Bike powers off
        val disconnectedStatus = batteryStateManager.resetOnDisconnect(s2)

        // 3. Swap in Battery Red (not in profiles list)
        val (sRedInit, serialChanged) = batteryStateManager.onBatterySerialDecoded("BAT_RED", disconnectedStatus, profiles)
        assertTrue(serialChanged)
        assertEquals("BAT_RED", sRedInit.batterySerialNumber)

        // Critical: Before new BMS packets arrive, cycles must be null (no leakage of 155.0!)
        assertNull("Stale charge cycles leaked to new battery!", sRedInit.chargeCycles)
        assertFalse(batteryStateManager.hasReceivedRealChargeCycles)

        // 4. Low-frequency BMS packets for Red finally arrive
        val sRedComplete = batteryStateManager.onChargeCyclesReceived(40.9, sRedInit, profiles)

        // Verify unregistered battery immediately adopts raw values with 0.0 offset (not ignored or frozen)
        assertEquals(40.9, sRedComplete.chargeCycles ?: 0.0, 0.001)
        assertTrue(batteryStateManager.hasReceivedRealChargeCycles)
    }

    @Test
    fun testBatterySwapWithOffsetsDoesNotCrossContaminate() {
        val initialStatus = BikeStatus()
        val profiles = listOf(
            BatteryProfile(hardwareSerial = "BAT_A", chargeCyclesOffset = 0.0),
            BatteryProfile(hardwareSerial = "BAT_B", chargeCyclesOffset = 40.2)
        )

        // 1. Battery A was active with raw 155.0 cycles
        val (sA1, _) = batteryStateManager.onBatterySerialDecoded("BAT_A", initialStatus, profiles)
        val sA2 = batteryStateManager.onChargeCyclesReceived(155.0, sA1, profiles)
        assertEquals(155.0, sA2.chargeCycles ?: 0.0, 0.001)

        // 2. Disconnect and swap to Battery B (which has +40.2 cycles offset)
        val disconnected = batteryStateManager.resetOnDisconnect(sA2)
        val (sBInit, serialChanged) = batteryStateManager.onBatterySerialDecoded("BAT_B", disconnected, profiles)
        assertTrue(serialChanged)

        // Critical: Battery B's offset must NOT be added to Battery A's raw values (155.0 + 40.2 = 195.2 must NOT occur!)
        assertNull("Premature offset addition on stale raw cycles!", sBInit.chargeCycles)

        // 3. Battery B's raw values arrive from BMS: 40.9 cycles
        val sBComplete = batteryStateManager.onChargeCyclesReceived(40.9, sBInit, profiles)

        // Verify Battery B's offsets are applied to Battery B's own raw values:
        // 40.9 + 40.2 = 81.1 cycles
        assertEquals(81.1, sBComplete.chargeCycles ?: 0.0, 0.001)
    }

    @Test
    fun testSerialChangeMidConnectionInvalidatesStaleTelemetry() {
        val initialStatus = BikeStatus()
        val profiles = listOf(
            BatteryProfile(hardwareSerial = "BAT_1", chargeCyclesOffset = 0.0),
            BatteryProfile(hardwareSerial = "BAT_2", chargeCyclesOffset = 5.0)
        )

        // Battery 1 has active telemetry
        val (s1, _) = batteryStateManager.onBatterySerialDecoded("BAT_1", initialStatus, profiles)
        val s2 = batteryStateManager.onChargeCyclesReceived(20.0, s1, profiles)
        assertEquals(20.0, s2.chargeCycles ?: 0.0, 0.001)

        // Serial unexpectedly changes mid-connection to BAT_2 without disconnect
        val (sChanged, changed) = batteryStateManager.onBatterySerialDecoded("BAT_2", s2, profiles)
        assertTrue(changed)
        assertEquals("BAT_2", sChanged.batterySerialNumber)
        assertNull(sChanged.chargeCycles)
        assertFalse(batteryStateManager.hasReceivedRealChargeCycles)
    }
}

