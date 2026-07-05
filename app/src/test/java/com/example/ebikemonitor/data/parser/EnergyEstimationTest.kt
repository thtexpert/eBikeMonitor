package com.example.ebikemonitor.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.ebikemonitor.data.model.UsageRecord

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
}
