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
}
