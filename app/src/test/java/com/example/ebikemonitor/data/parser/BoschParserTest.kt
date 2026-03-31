package com.example.ebikemonitor.data.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoschParserTest {
    @Test
    fun testParse180cAssistModes() {
        val hexString = "3024180CC080110A034F46460A0345434F0A05544F55522B0A0553504F52540A05545552424F"
        val bytes = hexString.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val messages = BoschParser.parse(bytes)
        
        val msg180c = messages.find { it.messageId == 0x180C }
        assert(msg180c != null) { "Message 0x180C not found" }
        
        val modes = msg180c!!.decodeAssistModes()
        assertEquals(listOf("OFF", "ECO", "TOUR+", "SPORT", "TURBO"), modes)
    }

    @Test
    fun testParse108cUsageRecords() {
        val testData = listOf(
            "3010108CC080550A09089AD4B50210C18902" to UsageRecord(5073434, 33985),
            "300E108CC080580A07088FDF3910B50B" to UsageRecord(946063, 1461),
            "300F108CC080540A08088D97C40110E968" to UsageRecord(3214221, 13417),
            "300B108CC0805A0A040892DB03" to UsageRecord(60818, 0),
            "3010108CC0805B0A090891AAF30110C7E601" to UsageRecord(3986705, 29511)
        )

        for ((hex, expected) in testData) {
            val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val messages = BoschParser.parse(bytes)
            val msg = messages.find { it.messageId == 0x108C }
            assert(msg != null) { "Message 0x108C not found for $hex" }
            val record = msg!!.decodeUsageRecord()
            assertEquals("Distance mismatch for $hex", expected.distance, record?.distance)
            assertEquals("Energy mismatch for $hex", expected.energy, record?.energy)
        }
    }

    @Test
    fun testProcessUsageRecordsSorting() {
        val records = listOf(
            UsageRecord(3986705, 29511),  // TURBO (~0.0074 Wh/m)
            UsageRecord(3214221, 13417),  // TOUR+ (~0.0041 Wh/m)
            UsageRecord(60818, 0),        // OFF (0.0 Wh/m)
            UsageRecord(5073434, 33985),  // SPORT (~0.0066 Wh/m)
            UsageRecord(946063, 1461)     // ECO (~0.0015 Wh/m)
        ).shuffled() // Ensure it sorts correctly regardless of input order

        val sorted = BoschParser.processUsageRecords(records)
        assert(sorted != null) { "Post-processing failed" }
        assertEquals(5, sorted!!.size)
        
        // Expected order: lowest consumption to highest
        assertEquals(0, sorted[0].energy) // OFF
        assertEquals(1461, sorted[1].energy) // ECO
        assertEquals(13417, sorted[2].energy) // TOUR+
        assertEquals(33985, sorted[3].energy) // SPORT
        assertEquals(29511, sorted[4].energy) // TURBO
    }

    @Test
    fun testParseA252TripDistPerMode() {
        // 30-0D-A2-52-0A-09-B2-08-A0-13-F5-10-E4-02-00
        val hex = "300DA2520A09B208A013F510E40200"
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val messages = BoschParser.parse(bytes)
        val msg = messages.find { it.messageId == 0xA252 }
        
        assert(msg != null) { "Message 0xA252 not found" }
        val distances = msg!!.decodeTripDistPerMode()
        assertEquals(listOf(1074, 2464, 2165, 356, 0), distances)
    }

    @Test
    fun testProcessVersionBDiscovery() {
        // Initial state: Baseline established at start
        val modeNames = listOf("OFF", "ECO", "TOUR", "SPORT", "TURBO")
        val initialTrip = listOf(100, 200, 300, 400, 500)
        val initialBatch = listOf(
            UsageRecord(10100, 100), // OFF
            UsageRecord(20200, 200), // ECO
            UsageRecord(30300, 300), // TOUR
            UsageRecord(40400, 400), // SPORT
            UsageRecord(50500, 500)  // TURBO
        )
        
        var status = com.example.ebikemonitor.data.model.BikeStatus(
            assistModeNames = modeNames,
            tripDistPerMode = initialTrip,
            initialTripDistPerMode = initialTrip,
            initialUnsortedUsageRecords = initialBatch,
            sortedUsageRecordsB = ArrayList<UsageRecord?>(List(5) { null }),
            confirmedModeIndices = emptySet()
        )

        // First Cycle: ONLY ECO moves by 50m relative to baseline
        val tripDists1 = listOf(100, 250, 300, 400, 500) // ECO delta = 50
        val batch1 = listOf(
            UsageRecord(10100, 100), // OFF (0)
            UsageRecord(20250, 210), // ECO (+50)
            UsageRecord(30300, 300), // TOUR (0)
            UsageRecord(40400, 400), // SPORT (0)
            UsageRecord(50500, 500)  // TURBO (0)
        )
        status = status.copy(tripDistPerMode = tripDists1)

        val result1 = BoschParser.processVersionBWithDiscovery(batch1, status)
        assertNotNull(result1)
        val (sorted1, confirmed1) = result1!!

        // ECO (index 1) should be confirmed
        assertTrue("ECO should be confirmed", confirmed1.contains(1))
        assertEquals(20250, sorted1[1]?.distance)
        
        // Second Cycle: ECO moves another 50m (Total Delta 100 relative to baseline)
        val tripDists2 = listOf(100, 300, 300, 400, 500)
        val batch2 = listOf(
            UsageRecord(10100, 100), // OFF (0)
            UsageRecord(20300, 220), // ECO (+100)
            UsageRecord(30300, 300), // TOUR (0)
            UsageRecord(40400, 400), // SPORT (0)
            UsageRecord(50500, 500)  // TURBO (0)
        )
        // Update status with confirmed from cycle 1
        status = status.copy(
            tripDistPerMode = tripDists2,
            sortedUsageRecordsB = sorted1,
            confirmedModeIndices = confirmed1
        )

        val result2 = BoschParser.processVersionBWithDiscovery(batch2, status)
        assertNotNull(result2)
        assertTrue("ECO should remain confirmed", result2!!.second.contains(1))
        assertEquals("ECO distance should be updated", 20300, result2.first[1]?.distance)
    }

    @Test
    fun testProcessVersionBLateDiscoveryWithShuffling() {
        // 1. BASELINE: Establish trip start
        val modeNames = listOf("OFF", "ECO", "TOUR", "SPORT", "TURBO")
        val initialTrip = listOf(100, 200, 300, 400, 500)
        val initialBatch = listOf(
            UsageRecord(10100, 100), // Index 0: OFF
            UsageRecord(20200, 200), // Index 1: ECO
            UsageRecord(30300, 300), // Index 2: TOUR
            UsageRecord(40400, 400), // Index 3: SPORT
            UsageRecord(50500, 500)  // Index 4: TURBO
        )
        
        var status = com.example.ebikemonitor.data.model.BikeStatus(
            assistModeNames = modeNames,
            tripDistPerMode = initialTrip,
            initialTripDistPerMode = initialTrip,
            initialUnsortedUsageRecords = initialBatch,
            sortedUsageRecordsB = ArrayList<UsageRecord?>(List(5) { null }),
            confirmedModeIndices = emptySet(),
            modeToInitialIndex = emptyMap()
        )

        // 2. PHASE 1: After long ECO ride (10km). Records are SHUFFLED by the bike.
        val trip1 = listOf(100, 10200, 300, 400, 500) // ECO +10000
        val batch1 = listOf(
            UsageRecord(30300, 300), // TOUR (Index 2)
            UsageRecord(30200, 210), // ECO (was 20200, now 30200)
            UsageRecord(10100, 100), // OFF (Index 0)
            UsageRecord(50500, 500), // TURBO (Index 4)
            UsageRecord(40400, 400)  // SPORT (Index 3)
        ).shuffled()
        
        status = status.copy(tripDistPerMode = trip1)
        val res1 = BoschParser.processVersionBWithDiscovery(batch1, status)
        assertNotNull("Res1 should not be null", res1)
        assertTrue("ECO (index 1) should be discovered", res1!!.second.contains(1))
        assertEquals("ECO should map to initial slot 1", 1, res1.third[1])
        
        status = status.copy(
            sortedUsageRecordsB = res1.first,
            confirmedModeIndices = res1.second,
            modeToInitialIndex = res1.third
        )

        // 3. PHASE 2: Late Discovery of TURBO (moves 100m). Shuffled again.
        val trip2 = listOf(100, 10200, 300, 400, 600) // TURBO (mode index 4) +100
        val batch2 = listOf(
            UsageRecord(30300, 300), // TOUR
            UsageRecord(30200, 210), // ECO
            UsageRecord(10100, 100), // OFF
            UsageRecord(50600, 510), // TURBO (was 50500, now 50600)
            UsageRecord(40400, 400)  // SPORT
        ).shuffled()
        
        status = status.copy(tripDistPerMode = trip2)
        val res2 = BoschParser.processVersionBWithDiscovery(batch2, status)
        assertNotNull("Res2 should not be null", res2)
        
        assertTrue("ECO should remain confirmed", res2!!.second.contains(1))
        assertTrue("TURBO should be Late Discovered", res2.second.contains(4))
        assertEquals("TURBO should map to initial slot 4", 4, res2.third[4])
        assertEquals("TURBO distance should match the record in the shuffled batch", 50600, res2.first[4]?.distance)
    }

    @Test
    fun testProcessVersionBMixedIncrease() {
        // 1. BASELINE: Start of trip
        val modeNames = listOf("OFF", "ECO", "TOUR", "SPORT", "TURBO")
        val initialTrip = listOf(100, 200, 300, 400, 500)
        val initialBatch = listOf(
            UsageRecord(10000, 100), // Index 0: OFF
            UsageRecord(20000, 200), // Index 1: ECO
            UsageRecord(30000, 300), // Index 2: TOUR
            UsageRecord(40000, 400), // Index 3: SPORT
            UsageRecord(50000, 500)  // Index 4: TURBO
        )
        
        var status = com.example.ebikemonitor.data.model.BikeStatus(
            assistModeNames = modeNames,
            tripDistPerMode = initialTrip,
            initialTripDistPerMode = initialTrip,
            initialUnsortedUsageRecords = initialBatch,
            sortedUsageRecordsB = ArrayList<UsageRecord?>(List(5) { null }),
            confirmedModeIndices = emptySet(),
            modeToInitialIndex = emptyMap()
        )

        // 2. PHASE 1: Established TOUR ride (2km)
        val trip1 = listOf(100, 200, 2300, 400, 500) // TOUR (index 2) +2000m
        val batch1 = listOf(
            UsageRecord(10000, 100),
            UsageRecord(20000, 200),
            UsageRecord(32000, 310), // TOUR (+2000)
            UsageRecord(40000, 400),
            UsageRecord(50000, 500)
        ).shuffled()
        
        status = status.copy(tripDistPerMode = trip1)
        val res1 = BoschParser.processVersionBWithDiscovery(batch1, status)
        assertNotNull(res1)
        assertTrue("TOUR should be discovered", res1!!.second.contains(2))
        
        status = status.copy(
            sortedUsageRecordsB = res1.first,
            confirmedModeIndices = res1.second,
            modeToInitialIndex = res1.third
        )

        // 3. PHASE 2: MIXED INCREASE
        // TOUR+ adds 50m (Total 2050)
        // ECO starts with 80m (Total 80)
        // OFF gains 120m (Total 120)
        val trip2 = listOf(100 + 120, 200 + 80, 2300 + 50, 400, 500)
        val batch2 = listOf(
            UsageRecord(10120, 105), // OFF (+120)
            UsageRecord(20080, 205), // ECO (+80)
            UsageRecord(32050, 315), // TOUR (+2050 relative to baseline)
            UsageRecord(40000, 400),
            UsageRecord(50000, 500)
        ).shuffled()
        
        status = status.copy(tripDistPerMode = trip2)
        val res2 = BoschParser.processVersionBWithDiscovery(batch2, status)
        assertNotNull(res2)
        
        val confirmed = res2!!.second
        assertTrue("OFF (index 0) should be late discovered", confirmed.contains(0))
        assertTrue("ECO (index 1) should be late discovered", confirmed.contains(1))
        assertTrue("TOUR (index 2) should still be confirmed", confirmed.contains(2))
        
        assertEquals("OFF record distance", 10120, res2.first[0]?.distance)
        assertEquals("ECO record distance", 20080, res2.first[1]?.distance)
        assertEquals("TOUR record distance", 32050, res2.first[2]?.distance)
    }
}
