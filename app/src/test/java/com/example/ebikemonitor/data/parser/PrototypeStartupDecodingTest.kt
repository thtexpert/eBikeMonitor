package com.example.ebikemonitor.data.parser
import com.example.ebikemonitor.data.model.UsageRecord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import kotlin.math.abs

class PrototypeStartupDecodingTest {

    @Test
    fun testStartupDecodingWithUserExample() {
        // User's "Last stored sorted usage"
        val storedUsage = listOf(
            UsageRecord(59747, 0),    // OFF
            UsageRecord(943602, 1453),  // ECO
            UsageRecord(3212074, 13406), // TOUR+
            UsageRecord(5073079, 33981), // SPORT
            UsageRecord(3986705, 29511)  // TURBO
        )
        // Assume trip was 0 when stored or we store the baseline (Usage - Trip)
        val storedTrip = listOf(0, 0, 0, 0, 0)
        val storedBaselines = storedUsage.zip(storedTrip).map { (u, t) -> u.distance - t }

        // User's "Actual input from BLE (unsorted)"
        val newBatch = listOf(
            UsageRecord(3986705, 29511), // U1
            UsageRecord(3214116, 13416), // U2
            UsageRecord(60818, 0),       // U3
            UsageRecord(5073344, 33983), // U4
            UsageRecord(946063, 1461)    // U5
        )

        // New Trip data (as received by BLE at startup, sorted by mode)
        // Derived from user's "should be decoded into" example
        val newTrip = listOf(1071, 2461, 2042, 265, 0)

        val result = findBestPermutation(newBatch, newTrip, storedBaselines)
        
        assertNotNull("Should find a valid permutation", result)
        val decoded = result!!
        
        // Expected order matches user's "should be decoded into"
        assertEquals("OFF", 60818, decoded[0].distance)
        assertEquals("ECO", 946063, decoded[1].distance)
        assertEquals("TOUR+", 3214116, decoded[2].distance)
        assertEquals("SPORT", 5073344, decoded[3].distance)
        assertEquals("TURBO", 3986705, decoded[4].distance)
        
        println("Decoded successfully into:")
        val names = listOf("OFF", "ECO", "TOUR+", "SPORT", "TURBO")
        decoded.forEachIndexed { i, record ->
            println("${names[i]}: ${record.distance} m ${record.energy} Wh")
        }
    }

    @Test
    fun testDeferredStartupDecodingWhenTripDistancesArriveLate() {
        val storedUsage = listOf(
            UsageRecord(59747, 0),    // OFF
            UsageRecord(943602, 1453),  // ECO
            UsageRecord(3212074, 13406), // TOUR+
            UsageRecord(5073079, 33981), // SPORT
            UsageRecord(3986705, 29511)  // TURBO
        )
        val storedTrip = listOf(0, 0, 0, 0, 0)
        val storedBaselines = storedUsage.zip(storedTrip).map { (u, t) -> u.distance - t }

        val newBatch = listOf(
            UsageRecord(3986705, 29511), // TURBO
            UsageRecord(3214116, 13416), // TOUR+
            UsageRecord(60818, 0),       // OFF
            UsageRecord(5073344, 33983), // SPORT
            UsageRecord(946063, 1461)    // ECO
        )

        // Scenario 1: Trip distances are initially empty (mimicking A252 deferred / not yet arrived)
        val emptyTrip = emptyList<Int>()
        val resultDeferred = findBestPermutation(newBatch, emptyTrip, storedBaselines)
        
        // Decoding should be deferred (returns null)
        org.junit.Assert.assertNull("Startup decoding should be deferred when trip distances are empty", resultDeferred)

        // Scenario 2: Trip distances arrive (A252)
        val newTrip = listOf(1071, 2461, 2042, 265, 0) // OFF, ECO, TOUR+, SPORT, TURBO
        val resultResolved = findBestPermutation(newBatch, newTrip, storedBaselines)
        
        // Decoding should now succeed
        assertNotNull("Startup decoding should succeed once trip distances arrive", resultResolved)
        val decoded = resultResolved!!
        
        assertEquals("OFF", 60818, decoded[0].distance)
        assertEquals("ECO", 946063, decoded[1].distance)
        assertEquals("TOUR+", 3214116, decoded[2].distance)
        assertEquals("SPORT", 5073344, decoded[3].distance)
        assertEquals("TURBO", 3986705, decoded[4].distance)
    }

    @Test
    fun testForcedFallbackDecodingWhenStationary() {
        val storedUsage = listOf(
            UsageRecord(60818, 0),       // OFF
            UsageRecord(946063, 1461),    // ECO
            UsageRecord(3214116, 13416), // TOUR+
            UsageRecord(5073344, 33983), // SPORT
            UsageRecord(3986705, 29511)  // TURBO
        )
        // Last active trip values at disconnect
        val lastTripDists = listOf(1071, 2461, 2042, 265, 0)
        val storedBaselines = storedUsage.zip(lastTripDists).map { (u, t) -> u.distance - t }

        // Scenario A: Power cycle WITHOUT trip reset
        // New batch matches old batch because bike is stationary
        val newBatchNoReset = listOf(
            UsageRecord(3986705, 29511), // TURBO
            UsageRecord(3214116, 13416), // TOUR+
            UsageRecord(60818, 0),       // OFF
            UsageRecord(5073344, 33983), // SPORT
            UsageRecord(946063, 1461)    // ECO
        )
        
        // Option 1: Try decoding assuming no reset (uses fallback lastTripDists)
        val resultNoReset = findBestPermutation(newBatchNoReset, lastTripDists, storedBaselines)
        assertNotNull("Should map successfully using fallback trip distances when no reset occurred", resultNoReset)
        assertEquals("ECO", 946063, resultNoReset!![1].distance)

        // Scenario B: Power cycle WITH trip reset (trip reset occurred offline or on startup)
        // Since trip reset, the raw odometer records on the bike drop back to baseline values
        val newBatchWithReset = listOf(
            UsageRecord(storedBaselines[4], 29511), // TURBO baseline
            UsageRecord(storedBaselines[2], 13416), // TOUR+ baseline
            UsageRecord(storedBaselines[0], 0),     // OFF baseline
            UsageRecord(storedBaselines[3], 33983), // SPORT baseline
            UsageRecord(storedBaselines[1], 1461)    // ECO baseline
        )
        
        // Option 1 fails because baseline (record - lastTripDists) is less than stored baseline
        val resultWithResetFailed = findBestPermutation(newBatchWithReset, lastTripDists, storedBaselines)
        org.junit.Assert.assertNull("Decoding should fail when trying to apply non-zero fallback to a reset trip", resultWithResetFailed)

        // Option 2: Retry assuming trip WAS reset (uses zeros)
        val zeros = List(lastTripDists.size) { 0 }
        val resultWithResetSucceeded = findBestPermutation(newBatchWithReset, zeros, storedBaselines)
        assertNotNull("Decoding should succeed when using zeros after a trip reset", resultWithResetSucceeded)
        assertEquals("ECO", storedBaselines[1], resultWithResetSucceeded!![1].distance)
    }

    private fun findBestPermutation(
        newBatch: List<UsageRecord>,
        newTrip: List<Int>,
        storedBaselines: List<Int>
    ): List<UsageRecord>? {
        val numModes = newTrip.size
        if (newBatch.size != numModes || storedBaselines.size != numModes) return null

        val indices = (0 until numModes).toList()
        val allPermutations = mutableListOf<List<Int>>()
        generatePermutations(indices, 0, allPermutations)

        var bestP: List<Int>? = null
        var minTotalError = Long.MAX_VALUE
        var secondaryMinError = Long.MAX_VALUE
        val tolerance = 100 // 100 meters drift tolerance

        for (p in allPermutations) {
            var totalError = 0L
            var possible = true
            
            for (i in 0 until numModes) {
                val record = newBatch[p[i]]
                val currentBaseline = record.distance - newTrip[i]
                val storedBaseline = storedBaselines[i]
                
                // Constraint: Odometer only increases. 
                // Baseline (Value at last trip reset) must be >= previous baseline.
                if (currentBaseline < storedBaseline - tolerance) {
                    possible = false
                    break
                }
                
                totalError += abs(currentBaseline - storedBaseline)
            }
            
            if (possible) {
                if (totalError < minTotalError) {
                    secondaryMinError = minTotalError
                    minTotalError = totalError
                    bestP = p
                } else if (totalError < secondaryMinError) {
                    secondaryMinError = totalError
                }
            }
        }

        // Stability check: if multiple permutations are very close, it's ambiguous
        if (bestP != null && secondaryMinError != Long.MAX_VALUE) {
            if (secondaryMinError - minTotalError < 20) { // If second best is within 20m of best
                println("Ambiguity detected! Best error: $minTotalError, Second best: $secondaryMinError")
                // return null // Optimization: for now let's see what happens
            }
        }

        return bestP?.map { newBatch[it] }
    }

    private fun generatePermutations(list: List<Int>, start: Int, result: MutableList<List<Int>>) {
        if (start == list.size) {
            result.add(list.toList())
            return
        }
        val mutableList = list.toMutableList()
        for (i in start until list.size) {
            swap(mutableList, start, i)
            generatePermutations(mutableList, start + 1, result)
            swap(mutableList, start, i)
        }
    }

    private fun swap(list: MutableList<Int>, i: Int, j: Int) {
        val temp = list[i]
        list[i] = list[j]
        list[j] = temp
    }

    @Test
    fun testReconstructModeMappingOnMidRideReset() {
        val sortedRecords = listOf(
            UsageRecord(1697241, 50),  // OFF
            UsageRecord(332110, 10),   // ECO
            UsageRecord(4014065, 120), // TOUR
            UsageRecord(4728828, 180), // SPORT
            UsageRecord(5623501, 250)  // TURBO
        )
        // Shuffled raw records
        val unsortedRecords = listOf(
            UsageRecord(332110, 10),   // ECO (index 0)
            UsageRecord(4014065, 120), // TOUR (index 1)
            UsageRecord(1697241, 50),  // OFF (index 2)
            UsageRecord(4728828, 180), // SPORT (index 3)
            UsageRecord(5623501, 250)  // TURBO (index 4)
        )

        val mapping = BoschParser.reconstructModeMapping(sortedRecords, unsortedRecords)

        assertEquals(2, mapping[0])
        assertEquals(0, mapping[1])
        assertEquals(1, mapping[2])
        assertEquals(3, mapping[3])
        assertEquals(4, mapping[4])
    }

    @Test
    fun testStartupDecodingWithLateA252TripResetRace() {
        // 1. Establish the profile stored state from a previous ride
        // 2. Afternoon ride starts.
        // On connection, the app pre-populates tripDistPerMode with last active trip values from the morning ride (stale database values).
        // Since A252 has not arrived yet, the app captures the pre-populated values as initial trip distances.
        
        // Unsorted usage records at the start of afternoon ride (which are close to stored values, but bike has power cycled)
        val initialBatch = listOf(
            UsageRecord(4015653, 1000), // TURBO (index 0)
            UsageRecord(1700196, 500),  // ECO (index 1)
            UsageRecord(5672126, 4000), // SPORT (index 2)
            UsageRecord(4745811, 2000), // TOUR (index 3)
            UsageRecord(335317, 0)      // OFF (index 4)
        )

        // Initial mapping captured from the pre-populated state (assuming 0->0, 1->1, 2->2, 3->3, 4->4 dummy/stale mapping)
        // This maps TOUR to index 3 (initial distance 4745811).
        
        // As the user rides, actual trip distances on the bike reset to 0 and start counting up from 0.
        // But A252 hasn't arrived yet, so the app still uses prepopulatedTripDists as initial baseline.
        // User rides in TOUR (actual TOUR distance goes from 0 to 500m).
        // Actual Tour record in unsorted batch goes from 4745811 to 4746311.
        val unsortedBatchMidRide = listOf(
            UsageRecord(4015653, 1000), // TURBO (0m delta)
            UsageRecord(1700196, 500),  // ECO (0m delta)
            UsageRecord(5672126, 4000), // SPORT (0m delta)
            UsageRecord(4746311, 2010), // TOUR (+500m delta)
            UsageRecord(335317, 0)      // OFF (0m delta)
        )

        // Try mapping with stale baseline (no A252 yet, so totalTripDeltas = [0,0,0,0,0])
        val totalTripDeltasStale = List(5) { 0 }
        
        // For TOUR (mapped to initial index 3), the app searches for a record in the batch close to 4745811.
        // The actual TOUR record has increased to 4746311 (500m difference, > 30m tolerance).
        // So the mapping fails for TOUR (returns null for TOUR).
        val matchedTour = unsortedBatchMidRide.find { r ->
            abs((r.distance - initialBatch[3].distance) - totalTripDeltasStale[2]) <= 30
        }
        org.junit.Assert.assertNull("Should fail to map TOUR when baseline is stale and TOUR has moved", matchedTour)

        // 3. The real A252 (tripDistPerMode) finally arrives mid-ride!
        // Actual trip distances from the bike: OFF=0, ECO=0, TOUR=500, SPORT=0, TURBO=0
        val realTripDists = listOf(0, 0, 500, 0, 0)

        // The app detects a trip reset because realTripDists < prepopulatedTripDists for almost all modes.
        // It clears B history and establishes a new baseline from the current live state:
        val correctedInitialTripDists = realTripDists.toList()
        val correctedInitialUnsortedUsageRecords = unsortedBatchMidRide.toList()

        // 4. Ride continues. TOUR mode increases by another 400m (total 900m trip distance).
        // Tour record becomes 4746711.
        val unsortedBatchLater = listOf(
            UsageRecord(4015653, 1000), // TURBO
            UsageRecord(1700196, 500),  // ECO
            UsageRecord(5672126, 4000), // SPORT
            UsageRecord(4746711, 2020), // TOUR (+900m)
            UsageRecord(335317, 0)      // OFF
        )
        val currentRealTripDists = listOf(0, 0, 900, 0, 0)

        // Calculate deltas relative to corrected baseline:
        // totalDeltaTrip for TOUR is 900 - 500 = 400m
        val totalDeltaTripTour = currentRealTripDists[2] - correctedInitialTripDists[2] // 400m
        
        // Find TOUR match in the new batch relative to corrected initial TOUR record (index 3 in correctedInitialUnsortedUsageRecords, which has distance 4746311)
        val matchedTourCorrect = unsortedBatchLater.find { r ->
            abs((r.distance - correctedInitialUnsortedUsageRecords[3].distance) - totalDeltaTripTour) <= 30
        }

        assertNotNull("Should successfully map TOUR using corrected baseline once trip reset is detected", matchedTourCorrect)
        assertEquals(4746711, matchedTourCorrect!!.distance)
    }

    @Test
    fun testStartupDecodingWithTodaysRealRideData() {
        // 1. Stored database state at the end of Session 2
        val storedUsage = listOf(
            UsageRecord(336386, 0),     // OFF
            UsageRecord(1701641, 0),    // ECO
            UsageRecord(5686513, 0),    // TOUR
            UsageRecord(4751513, 0),    // SPORT
            UsageRecord(4016105, 0)     // TURBO
        )
        val lastTripDists = listOf(46, 188, 688, 0, 0)
        val storedBaselines = storedUsage.zip(lastTripDists).map { (u, t) -> u.distance - t }

        // 2. First batch at startup of Session 3
        val batch1 = listOf(
            UsageRecord(1701702, 0), // ECO
            UsageRecord(5686513, 0), // TOUR
            UsageRecord(336483, 0),  // OFF
            UsageRecord(4752082, 0), // SPORT
            UsageRecord(4016105, 0)  // TURBO
        )

        // --- Scenario A: Startup sync with high error is rejected by 100m sanity limit ---
        val staleTripDists = lastTripDists
        val resultStale = BoschParser.findBestStartupMapping(batch1, staleTripDists, storedBaselines)
        assertEquals("HIGH_ERROR_REJECTED", resultStale.status)
        org.junit.Assert.assertNull("High-error startup decoding mapping must be rejected", resultStale.mapping)

        // --- Scenario B: Startup sync waits for real BLE trip distances (The Fix) ---
        val realTripDists = listOf(143, 249, 688, 569, 0)
        val realTripDists2 = listOf(143, 249, 688, 605, 0) // Sport increases by 36m (569 + 36 = 605)
        val batch2 = listOf(
            UsageRecord(1701702, 0), // ECO (0m change)
            UsageRecord(5686513, 0), // TOUR (0m change)
            UsageRecord(336483, 0),  // OFF (0m change)
            UsageRecord(4752118, 0), // SPORT (+36m change)
            UsageRecord(4016105, 0)  // TURBO (0m change)
        )

        val resultReal = BoschParser.findBestStartupMapping(batch1, realTripDists, storedBaselines)
        assertNotNull(resultReal.mapping)
        
        val mappingReal = resultReal.mapping!!
        val sortedB1Real = ArrayList<UsageRecord?>(List(5) { null })
        mappingReal.forEach { (modeIdx, batchIdx) ->
            sortedB1Real[modeIdx] = batch1[batchIdx]
        }

        var statusReal = com.example.ebikemonitor.data.model.BikeStatus(
            assistModeNames = listOf("OFF", "ECO", "TOUR", "SPORT", "TURBO"),
            initialTripDistPerMode = realTripDists.toList(),
            initialUnsortedUsageRecords = batch1.toList(),
            tripDistPerMode = realTripDists.toList(),
            modeToInitialIndex = mappingReal,
            confirmedModeIndices = mappingReal.keys,
            sortedUsageRecordsB = sortedB1Real
        )

        // Second batch arrives
        statusReal = statusReal.copy(tripDistPerMode = realTripDists2)
        val resultB2Real = BoschParser.processVersionBWithDiscovery(batch2, statusReal)
        assertNotNull(resultB2Real)
        
        val sortedReal = resultB2Real!!.first
        assertEquals(
            "Sport usage record distance should successfully update to the new batch 2 value",
            batch2[3].distance,
            sortedReal[3]!!.distance
        )
    }

    @Test
    fun testSyncTimeoutBehaviorWithMissingUsageRecords() {
        val storedUsage = listOf(
            UsageRecord(336386, 0),     // OFF
            UsageRecord(1701641, 0),    // ECO
            UsageRecord(5686513, 0),    // TOUR
            UsageRecord(4751513, 0),    // SPORT
            UsageRecord(4016105, 0)     // TURBO
        )
        val lastTripDists = listOf(46, 188, 688, 0, 0)
        val storedBaselines = storedUsage.zip(lastTripDists).map { (u, t) -> u.distance - t }

        // Simulating sync timeout: usage records did not arrive on time, but fallback is triggered
        // If fallback triggers, forceStartupDecodingWithFallback uses option 1 (no reset - lastTripDists) or option 2 (reset - zeros)
        // Let's verify option 1 with lastTripDists (assuming stationary):
        val stationaryBatch = listOf(
            UsageRecord(4016105, 0), // TURBO
            UsageRecord(5686513, 0), // TOUR
            UsageRecord(1701641, 0), // ECO
            UsageRecord(336386, 0),  // OFF
            UsageRecord(4751513, 0)  // SPORT
        )
        val resultNoReset = BoschParser.findBestStartupMapping(stationaryBatch, lastTripDists, storedBaselines)
        assertNotNull("Sync timeout fallback should succeed using last trip distances if stationary", resultNoReset.mapping)
        val mapping = resultNoReset.mapping!!
        assertEquals(3, mapping[0]) // OFF (336386) is at batch index 3
        assertEquals(2, mapping[1]) // ECO (1701641) is at batch index 2
    }

    @Test
    fun testSyncSucceedsBeforeTimeoutUnderNormalConditions() {
        val storedUsage = listOf(
            UsageRecord(336386, 0),     // OFF
            UsageRecord(1701641, 0),    // ECO
            UsageRecord(5686513, 0),    // TOUR
            UsageRecord(4751513, 0),    // SPORT
            UsageRecord(4016105, 0)     // TURBO
        )
        val lastTripDists = listOf(46, 188, 688, 0, 0)
        val storedBaselines = storedUsage.zip(lastTripDists).map { (u, t) -> u.distance - t }

        // Normal condition: all data arrives on time within 10-15 seconds.
        val normalBatch = listOf(
            UsageRecord(336386, 0),  // OFF
            UsageRecord(1701641, 0), // ECO
            UsageRecord(5686513, 0), // TOUR
            UsageRecord(4751513, 0), // SPORT
            UsageRecord(4016105, 0)  // TURBO
        )
        val normalTripDists = listOf(46, 188, 688, 0, 0)
        
        val resultNormal = BoschParser.findBestStartupMapping(normalBatch, normalTripDists, storedBaselines)
        assertNotNull("Normal sync should succeed before timeout", resultNormal.mapping)
        val mapping = resultNormal.mapping!!
        assertEquals(0, mapping[0])
        assertEquals(1, mapping[1])
        assertEquals(2, mapping[2])
        assertEquals(3, mapping[3])
        assertEquals(4, mapping[4])
    }

    @Test
    fun testStartupDecodingRejectsHighErrorCandidate() {
        val storedBaselines = listOf(336340, 1701453, 5685825, 4751513, 4016105)
        // High error batch (e.g. 24.4 km drift)
        val highErrorBatch = listOf(
            UsageRecord(360386, 0),     // OFF + 24km drift
            UsageRecord(1725641, 0),    // ECO + 24km drift
            UsageRecord(5710513, 0),    // TOUR + 24km drift
            UsageRecord(4775513, 0),    // SPORT + 24km drift
            UsageRecord(4040105, 0)     // TURBO + 24km drift
        )
        val currentTrip = listOf(0, 0, 0, 0, 0)
        
        val result = BoschParser.findBestStartupMapping(highErrorBatch, currentTrip, storedBaselines)
        assertEquals("HIGH_ERROR_REJECTED", result.status)
        org.junit.Assert.assertNull("Mapping should be null when candidate is rejected due to high error", result.mapping)
    }
}


