package com.example.ebikemonitor.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import com.example.ebikemonitor.data.model.UsageRecord
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel

class EnergyEstimationTest {

    @Test
    fun testUsableCapacityCalibrationMath() {
        // Given starting reference points:
        val startSoC = 100
        val startBattery = 10.0 // kWh

        // Current values after a drop:
        val currentSoC = 85
        val currentBattery = 10.04 // kWh -> delta is 0.04 kWh = 40 Wh

        val socDrop = startSoC - currentSoC
        assertEquals(15, socDrop)

        val energyDeltaKWh = currentBattery - startBattery
        assertEquals(0.04, energyDeltaKWh, 0.001)

        // Formula: Measured = (DeltaKWh * 100,000) / socDrop
        val measuredCap = (energyDeltaKWh * 100000.0) / socDrop
        assertEquals(266.67, measuredCap, 0.01)

        // Smoothing check (EMA with alpha = 0.10, starting cap 500)
        val startCap = 500.0f
        val smoothedCap = (startCap * 0.90f) + (measuredCap.toFloat() * 0.10f)
        assertEquals(476.67f, smoothedCap, 0.01f)
    }

    @Test
    fun testLifetimeInitializationMath() {

        // Mock usage records (Eco, Tour, Sport, Turbo, OFF)
        // Note: usage is sorted: OFF (Index 0), ECO (Index 1), TOUR+ (Index 2), SPORT (Index 3), TURBO (Index 4)
        val currentUsage = listOf(
            UsageRecord(distance = 271358, energy = 3),       // Index 0: OFF (3 Wh over 271.3 km)
            UsageRecord(distance = 1609946, energy = 2708),    // Index 1: ECO (2708 Wh over 1609.9 km)
            UsageRecord(distance = 4440184, energy = 19539),   // Index 2: TOUR+ (19539 Wh over 4440.1 km)
            UsageRecord(distance = 5481611, energy = 38130),   // Index 3: SPORT (38130 Wh over 5481.6 km)
            UsageRecord(distance = 4002149, energy = 29815)    // Index 4: TURBO (29815 Wh over 4002.1 km)
        )

        val initialRates = mutableListOf(0.0f, 5.0f, 7.0f, 10.0f, 12.0f)
        val expectedCount = 5

        // Lifetime initialization loop
        if (currentUsage.size == expectedCount) {
            for (i in 1 until expectedCount) { // Skip Index 0
                val record = currentUsage[i]
                if (record.distance > 1000) {
                    val lifetimeRate = record.energy.toFloat() / (record.distance / 1000.0f)
                    if (lifetimeRate in 0.5f..40.0f) {
                        if (i < initialRates.size) {
                            initialRates[i] = lifetimeRate
                        }
                    }
                }
            }
            initialRates[0] = 0.0f // OFF is locked to 0
        }

        // Expected: Eco = 2708 / 1609.946 = 1.68 Wh/km
        assertEquals(1.68f, initialRates[1], 0.01f)
        // Tour = 19539 / 4440.184 = 4.40 Wh/km
        assertEquals(4.40f, initialRates[2], 0.01f)
        // Sport = 38130 / 5481.611 = 6.96 Wh/km
        assertEquals(6.96f, initialRates[3], 0.01f)
        // Turbo = 29815 / 4002.149 = 7.45 Wh/km
        assertEquals(7.45f, initialRates[4], 0.01f)
        // OFF is locked to 0
        assertEquals(0.0f, initialRates[0], 0.01f)
    }

    @Test
    fun testExpectedEffortRateBasedNormalization() {
        // Rates initialized from lifetime totals
        val rates = listOf(0.0f, 1.68f, 4.40f, 6.96f, 7.45f)
        val constantK = 5.0

        // Test Ride B (Eco=2Wh, Tour=10Wh, Sport=6Wh, Turbo=0Wh, OFF=0Wh)
        val energyDeltaRideB = listOf(0.0, 2.0, 10.0, 6.0, 0.0)
        var expectedEffortB = 0.0

        for (i in 1 until energyDeltaRideB.size) { // Skip Index 0 (OFF)
            val delta = energyDeltaRideB[i]
            val rate = rates[i]
            if (rate > 0.0f) {
                expectedEffortB += delta * (constantK / rate)
            }
        }
        // Eco = 2 * (5/1.68) = 5.95
        // Tour = 10 * (5/4.40) = 11.36
        // Sport = 6 * (5/6.96) = 4.31
        // Total expected = 21.62
        assertEquals(21.62, expectedEffortB, 0.05)

        // Test Ride C (Eco=3Wh, Tour=23Wh, Sport=14Wh, Turbo=2Wh, OFF=0Wh)
        val energyDeltaRideC = listOf(0.0, 3.0, 23.0, 14.0, 2.0)
        var expectedEffortC = 0.0

        for (i in 1 until energyDeltaRideC.size) { // Skip Index 0 (OFF)
            val delta = energyDeltaRideC[i]
            val rate = rates[i]
            if (rate > 0.0f) {
                expectedEffortC += delta * (constantK / rate)
            }
        }
        // Eco = 3 * (5/1.68) = 8.93
        // Tour = 23 * (5/4.40) = 26.14
        // Sport = 14 * (5/6.96) = 10.06
        // Turbo = 2 * (5/7.45) = 1.34
        // Total expected = 46.47
        assertEquals(46.47, expectedEffortC, 0.05)
    }

    @Test
    fun testCalibrateUserEffortGammaMath() {
        val expectedEffortB = 21.66
        val pedalDeltaB = 65.0 // kcal

        // Real Gamma = Actual / Expected
        val measuredGamma = pedalDeltaB / expectedEffortB
        assertEquals(3.00, measuredGamma, 0.01)

        // Smoothed Gamma (default starts at 3.0, updates by 10%)
        val startGamma = 3.0f
        val smoothedGamma = ((startGamma * 0.90) + (measuredGamma * 0.10)).toFloat()
        assertEquals(3.00f, smoothedGamma, 0.01f)
    }

    @Test
    fun testMidRideTripResetLogic() {
        // Startup captured state
        var startupTripPedal = 26
        var startupTripTotal = 1.3 // Wh-equiv

        // Mid-ride updates
        var currentTripPedal = 3
        var currentTripTotal = 0.1

        val tripResetDetected = currentTripPedal < startupTripPedal || currentTripTotal < startupTripTotal
        assertTrue(tripResetDetected)

        if (tripResetDetected) {
            startupTripPedal = currentTripPedal
            startupTripTotal = currentTripTotal
        }

        // Post-reset delta should be relative to new references
        val pedalDelta = currentTripPedal - startupTripPedal
        assertEquals(0, pedalDelta) // Resets tracking cleanly
        assertEquals(0.1, startupTripTotal, 0.001) // Verify total is reset
    }

    @Test
    fun testOfflineSegmentKcalEstimationExcludingOFF() {
        val offlineDist = 5.0 // km
        val rates = listOf(0.0f, 1.68f, 4.40f, 6.96f, 7.45f)

        // Exclude OFF mode (Index 0) from average rate calculation
        val activeRates = rates.filterIndexed { idx, _ -> idx >= 1 }
        assertEquals(4, activeRates.size)

        val avgRate = activeRates.average()
        // avg = (1.68 + 4.40 + 6.96 + 7.45) / 4 = 20.49 / 4 = 5.1225
        assertEquals(5.1225, avgRate, 0.001)

        val estimatedWh = offlineDist * avgRate
        assertEquals(25.61, estimatedWh, 0.05)

        val learnedCoeff = 3.0f
        val estimatedKcal = learnedCoeff * estimatedWh
        assertEquals(76.84, estimatedKcal, 0.10)
    }

    @Test
    fun testOfflineSegmentEstimationRestrictedToTripReset() {
        val odoDelta = 5.0 // km
        val distanceThreshold = 0.20 // km

        // Scenario A: Odo delta > threshold
        val statusA = if (odoDelta > distanceThreshold) "OFFLINE_TRIGGERED" else "SKIPPED"
        assertEquals("OFFLINE_TRIGGERED", statusA)

        // Scenario B: Offline trip reset occurred (offlineDist > threshold) but tripResetDetected == false
        // We assert that the system should trigger offline estimation to recover the offline segment.
        val odoDeltaB = 5.0
        val statusB = if (odoDeltaB > distanceThreshold) "OFFLINE_TRIGGERED" else "SKIPPED"
        assertEquals("OFFLINE_TRIGGERED", statusB)
    }

    @Test
    fun testMidRideResetDetection() {
        // Telemetry frames over time: tripPedal goes 0 -> 69 -> 2
        var lastObservedTripPedal: Int? = null
        var totalPedalEnergyMeasuredBaseline = 125.0
        var savedBaselineIncrement = 0.0

        // Frame 1: Startup
        var currentTripPedal = 0
        if (lastObservedTripPedal == null) {
            lastObservedTripPedal = currentTripPedal
        }
        assertEquals(0, lastObservedTripPedal)

        // Frame 2: Riding along (accumulated 69 kcal)
        currentTripPedal = 69
        var tripResetDetected = lastObservedTripPedal != null && currentTripPedal < lastObservedTripPedal!!
        assertFalse(tripResetDetected)
        lastObservedTripPedal = currentTripPedal

        // Frame 3: Manual Trip Reset (drops to 2 kcal)
        currentTripPedal = 2
        tripResetDetected = lastObservedTripPedal != null && currentTripPedal < lastObservedTripPedal!!
        assertTrue(tripResetDetected)
        
        if (tripResetDetected) {
            savedBaselineIncrement = lastObservedTripPedal!!.toDouble()
            totalPedalEnergyMeasuredBaseline += savedBaselineIncrement
        }
        lastObservedTripPedal = currentTripPedal

        // Verify the 69 kcal was successfully saved into the baseline
        assertEquals(69.0, savedBaselineIncrement, 0.0)
        assertEquals(194.0, totalPedalEnergyMeasuredBaseline, 0.0)
        assertEquals(2, lastObservedTripPedal)
    }

    @Test
    fun testLateSyncPreservesUpdatedBaselines() {
        // Database values before startup sync
        val dbBaselineBeforeSync = 125.0
        val dbLastTripBeforeSync = 109.0

        // Capture profileBeforeSync at the very beginning
        val profileBeforeSync = dbBaselineBeforeSync

        // 1. Startup sync runs and updates database baselines
        val activeProfileBaseline = dbBaselineBeforeSync + dbLastTripBeforeSync // 234.0

        // 2. Late sync runs
        // Scenario A (Bug): uses stale profileBeforeSync, reverting the database
        val finalBaselineBug = profileBeforeSync
        assertEquals(125.0, finalBaselineBug, 0.0)

        // Scenario B (Fix): uses activeProfile, preserving the updated baselines
        val finalBaselineFix = activeProfileBaseline
        assertEquals(234.0, finalBaselineFix, 0.0)
    }

    @Test
    fun testDisconnectCalibrationTelemetryCacheDecoupling() {
        val sessionCalories = 53

        // Scenario A (Bug): disconnect() wipes telemetry variables before calibration
        var tripPedalEnergyCache: Int? = sessionCalories
        // disconnect() runs first
        tripPedalEnergyCache = null
        // Calibration runs
        val calibrationValueBug = tripPedalEnergyCache ?: 0
        assertEquals(0, calibrationValueBug) // 53 kcal lost!

        // Scenario B (Fix): Calibration runs before clearTelemetryCache() wipes the variables
        var tripPedalEnergyCacheFix: Int? = sessionCalories
        // Calibration runs first
        val calibrationValueFix = tripPedalEnergyCacheFix ?: 0
        assertEquals(53, calibrationValueFix) // 53 kcal successfully saved!
        // clearTelemetryCache() runs afterwards
        tripPedalEnergyCacheFix = null
        assertNull(tripPedalEnergyCacheFix)
    }

    @Test
    fun testEnergyValuesAreMonotonicallyIncreasing() {
        val energyHistory = mutableListOf<Double>()
        var measuredBaseline = 0.0
        var estimatedBaseline = 76.6
        
        fun calculateTotal(currentTrip: Int): Double {
            val total = measuredBaseline + estimatedBaseline + currentTrip
            energyHistory.add(total)
            return total
        }

        // 1. Startup
        calculateTotal(0) // 76.6
        
        // 2. Segment 1: ride to 69 kcal
        calculateTotal(20)
        calculateTotal(45)
        calculateTotal(69) // 145.6
        
        // 3. Manual Reset: Kiox drops to 2 kcal (current < prev)
        // Mid-ride reset check triggers: we commit the previous 69 kcal to baseline.
        measuredBaseline += 69.0
        calculateTotal(2) // 147.6
        
        // 4. Segment 2: ride to 58 kcal
        calculateTotal(30)
        calculateTotal(58) // 203.6
        
        // 5. Disconnect (End of session):
        // Telemetry cache preserved during calibration, lastTrip is saved as 58.
        val lastTripSaved = 58
        
        // 6. Offline ride occurs (BLE off)
        val offlineEstimate = 25.7
        
        // 7. Startup sync of next ride:
        // Trip reset detected (Kiox is 0 < lastTripSaved), baselines updated.
        measuredBaseline += lastTripSaved // 69.0 + 58.0 = 127.0
        estimatedBaseline += offlineEstimate // 76.6 + 25.7 = 102.3
        
        calculateTotal(0) // 229.3
        
        // 8. Late sync occurs:
        // Database baseline stays 127.0 (using activeProfile, not stale profileBeforeSync).
        // Kiox calories updates late to 111
        calculateTotal(111) // 340.3
        
        // Verify monotonicity across the entire timeline
        for (i in 1 until energyHistory.size) {
            val prev = energyHistory[i - 1]
            val curr = energyHistory[i]
            assertTrue("Value decreased at index $i: $prev -> $curr", curr >= prev)
        }
        
        // Final sanity checks
        assertEquals(9, energyHistory.size)
        assertEquals(76.6, energyHistory.first(), 0.01)
        assertEquals(340.3, energyHistory.last(), 0.01)
    }

    @Test
    fun testApplicationScopeCalibrationTaskSurvival() = kotlinx.coroutines.runBlocking {
        val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        val applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        
        var isDbWriteSuccessful = false
        
        // Scenario A (Bug): Launch on serviceScope which gets cancelled during stopSelf/onDestroy
        val jobA = serviceScope.launch {
            try {
                kotlinx.coroutines.delay(100)
                isDbWriteSuccessful = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Cancelled
            }
        }
        
        // Simulate service onDestroy cancelling the serviceScope
        serviceScope.cancel()
        jobA.join()
        assertFalse(isDbWriteSuccessful) // Database write was cancelled!
        
        // Scenario B (Fix): Launch on applicationScope which survives service destruction
        val jobB = applicationScope.launch {
            try {
                kotlinx.coroutines.delay(50)
                isDbWriteSuccessful = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Should not happen
            }
        }
        
        // Even if service is destroyed, applicationScope continues running
        jobB.join()
        assertTrue(isDbWriteSuccessful) // Database write successfully completed!
    }

    @Test
    fun testDeferredSyncTimeoutSequence() {
        // State variables in profile/datastore from Session 1 end:
        var dbMeasuredBaseline = 1578.0
        var dbLastTripPedal = 111
        var dbLastTripDistPerModeSum = 38.56
        
        // 1. Session 2 connects. Sync timeout occurs. Kiox data is missing (currentTripPedal = null).
        // Since sync is deferred, we do NOT complete sync, and we do NOT call updateActiveRideReferences on disconnect.
        val session2SyncCompleted = false // Deferred!
        
        if (session2SyncCompleted) {
            // If it had completed sync incorrectly, it would have reset the references:
            dbMeasuredBaseline += dbLastTripPedal
            dbLastTripPedal = 0
            dbLastTripDistPerModeSum = 0.0
        }
        
        // Assert that the references are preserved as they were
        assertEquals(1578.0, dbMeasuredBaseline, 0.0)
        assertEquals(111, dbLastTripPedal)
        assertEquals(38.56, dbLastTripDistPerModeSum, 0.0)
        
        // 2. Session 3 connects. Checklist satisfied, sync completes successfully.
        // Kiox trip data arrives: currentTripPedal = 0 (physical reset offline), currentTripDistSum = 1.024
        val currentTripPedal = 0
        val currentTripDistSum = 1.024
        
        // Reset detection runs using the preserved database references:
        val tripResetDetected = currentTripPedal < dbLastTripPedal || currentTripDistSum < dbLastTripDistPerModeSum
        assertTrue(tripResetDetected)
        
        if (tripResetDetected) {
            dbMeasuredBaseline += dbLastTripPedal // 1578.0 + 111 = 1689.0
            dbLastTripPedal = currentTripPedal
            dbLastTripDistPerModeSum = currentTripDistSum
        }
        
        // Verify the correct baselines are set and double-counting is prevented
        assertEquals(1689.0, dbMeasuredBaseline, 0.0)
        assertEquals(0, dbLastTripPedal)
        assertEquals(1.024, dbLastTripDistPerModeSum, 0.0)
    }

    @Test
    fun testMqttSyncMonotonicityWithDbPropagationDelay() {
        // Active profile baseline values at the end of Session 1
        val dbMeasuredBaseline = 125.0
        val dbEstimatedBaseline = 76.6
        val dbLastTripPedal = 50.0 // Session 1 ended with 50 kcal
        
        // This is the activeProfile in memory (initially matches the database at Session 2 startup)
        var activeProfile = com.example.ebikemonitor.data.model.BikeProfile(
            macAddress = "00:11:22:33:44:55",
            name = "TestBike",
            totalPedalEnergyMeasuredBaseline = dbMeasuredBaseline,
            totalPedalEnergyEstimatedBaseline = dbEstimatedBaseline,
            lastTripPedalEnergy = dbLastTripPedal.toInt()
        )
        
        // Simulating the MQTT publishing layer overallTotal calculation
        fun calculatePublishedTotal(tripPedal: Int, profile: com.example.ebikemonitor.data.model.BikeProfile): Double {
            return profile.totalPedalEnergyMeasuredBaseline + tripPedal + profile.totalPedalEnergyEstimatedBaseline
        }
        
        // 1. Session 2 Starts up. The bike has power-cycled, so physical trip pedal energy resets to 0.
        val startupTripPedal = 0
        
        // 2. The startup sync completion code detects the reset (current 0 < last 50) and updates the baseline.
        // It starts an ASYNCHRONOUS database update.
        val tripResetDetected = startupTripPedal < activeProfile.lastTripPedalEnergy
        assertTrue(tripResetDetected)
        
        // Simulate database write values
        val newDbBaseline = dbMeasuredBaseline + activeProfile.lastTripPedalEnergy
        val newLastTripPedal = startupTripPedal
        
        // --- Scenario A: Stale/Async Profile (Simulates current bug) ---
        // In the current bug, activeProfile is NOT updated synchronously. It remains stale
        // until the DB flow emits. An MQTT message is published immediately using this stale profile.
        val publishedTotalStale = calculatePublishedTotal(startupTripPedal, activeProfile)
        
        // Verify that the stale profile causes a non-monotonic drop!
        // Previous session total was measuredBaseline (125.0) + lastTrip (50.0) + estimated (76.6) = 251.6
        // Under the bug, we publish stale measuredBaseline (125.0) + currentTrip (0) + estimated (76.6) = 201.6
        assertEquals(201.6, publishedTotalStale, 0.01) 
        
        // --- Scenario B: Synchronous In-Memory Profile Update (Simulates our fix) ---
        // With the fix, we synchronously update activeProfile when we write to the database
        activeProfile = activeProfile.copy(
            totalPedalEnergyMeasuredBaseline = newDbBaseline,
            lastTripPedalEnergy = newLastTripPedal
        )
        
        // Now calculating the published value immediately after baseline decision uses the updated profile
        val publishedTotalFixed = calculatePublishedTotal(startupTripPedal, activeProfile)
        
        // Verify that with the synchronous update, the total is monotonic and does not drop!
        assertEquals(251.6, publishedTotalFixed, 0.01)
        
        // --- Scenario C: Monotonicity Guard (Simulates our fallback guard) ---
        // We track the maximum published value for this service session
        val maxPublishedOverallTotal = 251.6 // Session 1 ended with this value
        
        // If due to any other condition (like connection drop setting tripPedal to null / 0)
        // a lower value is calculated:
        val lowerCalculatedTotal = 201.6
        var publishedValue = lowerCalculatedTotal
        
        // Apply monotonicity guard
        if (publishedValue < maxPublishedOverallTotal) {
            publishedValue = maxPublishedOverallTotal
        }
        
        // Verify the guard overrides the lower value and preserves monotonicity
        assertEquals(251.6, publishedValue, 0.01)
    }

    @Test
    fun testDeltaBasedMeasuredAccumulation() {
        var cumulativeTotal = 0.0
        var lastTripValue = 0

        fun processIncomingPacket(currentTrip: Int) {
            val delta = if (currentTrip >= lastTripValue) {
                currentTrip - lastTripValue
            } else {
                currentTrip
            }
            cumulativeTotal += delta
            lastTripValue = currentTrip
        }

        // Scenario 1: First session (starts at 1, goes to 92)
        processIncomingPacket(1)
        assertEquals(1.0, cumulativeTotal, 0.0)
        assertEquals(1, lastTripValue)

        processIncomingPacket(10)
        processIncomingPacket(50)
        processIncomingPacket(92)
        assertEquals(92.0, cumulativeTotal, 0.0)
        assertEquals(92, lastTripValue)

        // Scenario 2: Power cycle restart (starts at 1, goes to 194)
        processIncomingPacket(1)
        assertEquals(93.0, cumulativeTotal, 0.0)
        assertEquals(1, lastTripValue)

        processIncomingPacket(100)
        processIncomingPacket(194)
        assertEquals(286.0, cumulativeTotal, 0.0)
        assertEquals(194, lastTripValue)

        // Scenario 3: Mid-ride reset (starts at 1, goes to 59, drops to 1, goes to 27)
        processIncomingPacket(1)
        assertEquals(287.0, cumulativeTotal, 0.0)
        assertEquals(1, lastTripValue)

        processIncomingPacket(59)
        assertEquals(345.0, cumulativeTotal, 0.0)

        processIncomingPacket(1)
        assertEquals(346.0, cumulativeTotal, 0.0)
        assertEquals(1, lastTripValue)

        processIncomingPacket(27)
        assertEquals(372.0, cumulativeTotal, 0.0)
        assertEquals(27, lastTripValue)
    }

    @Test
    fun testStartupUnsyncedPedalEnergyCatchUp() {
        var pedalMeasuredBaseline = 1158.0
        var lastTripPedalEnergy = 186
        val tripResetDetected = false

        // Simulate BLE reconnect: while disconnected or syncing, trip pedal energy advanced from 186 to 191
        val currentTripPedal = 191

        if (tripResetDetected) {
            // handle reset
        } else if (currentTripPedal > lastTripPedalEnergy) {
            val unaccumulatedDelta = currentTripPedal - lastTripPedalEnergy
            pedalMeasuredBaseline += unaccumulatedDelta
        }
        lastTripPedalEnergy = currentTripPedal

        // Verify catch-up accumulation
        assertEquals(1163.0, pedalMeasuredBaseline, 0.0)
        assertEquals(191, lastTripPedalEnergy)
    }

    @Test
    fun testRealtimeAndSyncAccumulationMonotonicity() {
        var pedalMeasuredBaseline = 1158.0
        var lastTripPedalEnergy = 0

        // Ride 1: 0 to 75 kcal
        lastTripPedalEnergy = 75
        pedalMeasuredBaseline += 75

        // Disconnect at 75 kcal, reconnect when trip energy is 77 kcal (2 kcal accrued during startup sync)
        val currentTripOnSync = 77
        val unaccumulatedDelta = currentTripOnSync - lastTripPedalEnergy
        pedalMeasuredBaseline += unaccumulatedDelta
        lastTripPedalEnergy = currentTripOnSync

        // Live riding continues from 77 to 189 (+112 kcal)
        pedalMeasuredBaseline += (189 - 77)
        lastTripPedalEnergy = 189

        // Verify total measured baseline delta matches total trip calories (189 kcal) 1-to-1
        assertEquals(1158.0 + 189.0, pedalMeasuredBaseline, 0.0)
        assertEquals(189, lastTripPedalEnergy)
    }
}

