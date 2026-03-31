package com.example.ebikemonitor.data.parser

import android.util.Log

data class UsageRecord(
    val distance: Int,
    val energy: Int
)

data class BoschMessage(
    val messageId: Int,
    val messageType: Int,
    val value: Int,
    val rawBytes: List<Byte>
) {
    fun decodeUsageRecord(): UsageRecord? {
        if (messageId != 0x108C) return null
        
        // 30-10-10-8C-C0-80-55-0A-09-08-9A-D4-B5-02-10-C1-89-02
        // rawBytes[0]=30, [1]=10, [2]=10, [3]=8C, [4]=C0, [5]=80, [6]=55, [7]=0A, [8]=09, [9]=08
        
        var distance = 0
        var energy = 0
        
        var idx = 7 // Start after the 3-byte separator (C0 80 5?)
        if (idx >= rawBytes.size) return null
        
        // Expecting 0x0A (Tag 1, WireType 2 - length delimited)
        if (rawBytes[idx] == 0x0A.toByte()) {
            val totalLen = rawBytes[idx + 1].toInt() and 0xFF
            idx += 2
            val endIdx = idx + totalLen
            
            while (idx < endIdx && idx < rawBytes.size) {
                val tag = rawBytes[idx].toInt() and 0xFF
                idx++
                when (tag) {
                    0x08 -> { // Field 1: Varint distance
                        val (valDist, consumed) = BoschParser.decodeVarint(rawBytes.subList(idx, rawBytes.size))
                        distance = valDist
                        idx += consumed
                    }
                    0x10 -> { // Field 2: Varint energy
                        val (valEnergy, consumed) = BoschParser.decodeVarint(rawBytes.subList(idx, rawBytes.size))
                        energy = valEnergy
                        idx += consumed
                    }
                    else -> {
                        // Unknown tag, skip (assuming varint for now as fallback if unknown)
                        val (_, consumed) = BoschParser.decodeVarint(rawBytes.subList(idx, rawBytes.size))
                        idx += consumed
                    }
                }
            }
        }
        
        return UsageRecord(distance, energy)
    }

    fun decodeTripDistPerMode(): List<Int>? {
        if (messageId != 0xA252) return null
        
        // 30-0D-A2-52-0A-09-B2-08-A0-13-F5-10-E4-02-00
        // rawBytes[0]=30, [1]=0D, [2]=A2, [3]=52, [4]=0A, [5]=09, [6]=B2, [7]=08 ...
        
        val distances = mutableListOf<Int>()
        var idx = 4 // Tag 1 (0x0A)
        if (idx >= rawBytes.size) return null
        
        if (rawBytes[idx] == 0x0A.toByte()) {
            val totalLen = rawBytes[idx + 1].toInt() and 0xFF
            idx += 2
            val endIdx = idx + totalLen
            
            while (idx < endIdx && idx < rawBytes.size) {
                val (value, consumed) = BoschParser.decodeVarint(rawBytes.subList(idx, rawBytes.size))
                distances.add(value)
                idx += consumed
            }
        }
        
        return distances
    }

    fun decodeAssistModes(): List<String> {
        val modes = mutableListOf<String>()
        if (messageId != 0x180C) return modes

        // We know rawBytes contains the full message starting from msgId chunk payload
        // Pattern: [0x0A (10), length, ascii bytes...]
        var idx = 0
        while (idx < rawBytes.size - 1) {
            if (rawBytes[idx] == 0x0A.toByte()) {
                val len = rawBytes[idx + 1].toInt() and 0xFF
                if (idx + 2 + len <= rawBytes.size) {
                    val strBytes = rawBytes.subList(idx + 2, idx + 2 + len).toByteArray()
                    modes.add(String(strBytes, Charsets.UTF_8))
                    idx += 2 + len
                    continue
                }
            }
            idx++
        }
        return modes
    }
}

object BoschParser {
    private const val TAG = "BoschParser"

    fun parse(bytes: ByteArray): List<BoschMessage> {
        val messages = mutableListOf<BoschMessage>()
        var index = 0

        while (index < bytes.size) {
            // Basic validation for header
            if (index + 2 >= bytes.size) break

            val startByte = bytes[index]
            val messageLength = bytes[index + 1].toInt() and 0xFF
            val totalMessageSize = messageLength + 2

            if (messageLength < 2 || messageLength > 50) {
                // Invalid or padding, skip byte
                index++
                continue
            }

            if (index + totalMessageSize > bytes.size) {
                // Incomplete message
                break
            }

            if (index + 4 >= bytes.size) break

            // Message ID is 2 bytes (Big Endian based on reference logic shift)
            // Reference: (bytes[index + 2] shl 8) or bytes[index + 3]
            // Note: Kotlin bytes are signed, need to mask
            val messageId = ((bytes[index + 2].toInt() and 0xFF) shl 8) or (bytes[index + 3].toInt() and 0xFF)
            
            // Extract body
            val messageBytes = bytes.slice(index until (index + totalMessageSize))
            
            var dataValue = 0
            var dataType = 0

            if (messageLength > 2) {
                val dataTypeIndex = 4 // Relative to chunk start
                if (dataTypeIndex < messageBytes.size) {
                    dataType = messageBytes[dataTypeIndex].toInt() and 0xFF
                    
                    val dataStartIndex = 5
                    if (dataStartIndex < messageBytes.size) {
                         // Parse based on type
                         when (dataType) {
                             0x08 -> {
                                 // Varint
                                 val dataChunk = messageBytes.drop(dataStartIndex)
                                 val (value, _) = decodeVarint(dataChunk)
                                 dataValue = value
                             }
                             0x0A -> {
                                 // Byte value
                                 dataValue = messageBytes[dataStartIndex].toInt() and 0xFF
                             }
                             else -> {
                                 // Default fallback
                                 dataValue = 0
                             }
                         }
                    }
                }
            }

            messages.add(BoschMessage(messageId, dataType, dataValue, messageBytes))
            index += totalMessageSize
        }

        return messages
    }

    fun decodeVarint(bytes: List<Byte>): Pair<Int, Int> {
        var value = 0
        var shift = 0
        var bytesConsumed = 0
        
        for (byte in bytes) {
            val b = byte.toInt()
            value = value or ((b and 0x7F) shl shift)
            shift += 7
            bytesConsumed++
            if ((b and 0x80) == 0) {
                break
            }
        }
        return Pair(value, bytesConsumed)
    }

    fun processUsageRecords(records: List<UsageRecord>): List<UsageRecord>? {
        if (records.isEmpty()) return null
        
        // 1. Abort if any distance is 0
        if (records.any { it.distance == 0 }) return null
        
        // 2. Check Uniqueness
        if (records.distinctBy { Pair(it.distance, it.energy) }.size != records.size) return null
        
        // 3. Sort by consumption (Wh/m)
        val sortedList = records.sortedBy { it.energy.toDouble() / it.distance }
        
        // 4. Validate Minimum Consumption Difference (Threshold: 5 Wh / 100km = 0.00005 Wh/m)
        val thresholdWhM = 0.00005 
        for (i in 0 until sortedList.size - 1) {
            val c1 = sortedList[i].energy.toDouble() / sortedList[i].distance
            val c2 = sortedList[i + 1].energy.toDouble() / sortedList[i + 1].distance
            if ((c2 - c1) < thresholdWhM) {
                 return null
            }
        }
        
        return sortedList
    }

    private const val MODE_MAPPING_TOLERANCE_METERS = 20

    /**
     * Version B: Delta-based Matching with Discovery Status.
     * Uses cumulative trip deltas from the baseline (start of session) and persistent
     * index mapping to remain resilient to broadcast reordering.
     * Returns a Triple of (Updated Sorted List, Updated Confirmed Indices, Updated Mode-to-Initial-Index Map).
     */
    fun processVersionBWithDiscovery(
        newBatch: List<UsageRecord>,
        status: com.example.ebikemonitor.data.model.BikeStatus
    ): Triple<List<UsageRecord?>, Set<Int>, Map<Int, Int>>? {
        val initialDist = status.initialTripDistPerMode ?: return null
        val curDist = status.tripDistPerMode
        val initialBatch = status.initialUnsortedUsageRecords ?: return null
        val numModes = status.assistModeNames.size
        
        if (numModes == 0) return null
        if (curDist.size != numModes || initialDist.size != numModes || newBatch.size != numModes || initialBatch.size != numModes) {
            return null
        }

        // 1. Check for Trip Reset relative to baseline
        for (i in curDist.indices) {
            if (curDist[i] < initialDist[i]) return null 
        }

        val totalTripDeltas = curDist.mapIndexed { i, cur -> cur - initialDist[i] }
        val newSortedList = status.sortedUsageRecordsB.toMutableList()
        if (newSortedList.size != numModes) {
            newSortedList.clear()
            repeat(numModes) { newSortedList.add(null) }
        }
        val newConfirmedIndices = status.confirmedModeIndices.toMutableSet()
        val newModeToInitialIndex = status.modeToInitialIndex.toMutableMap()

        // 2. Discover/Update Modes by matching against the initial baseline and tracked indices
        for (modeIdx in 0 until numModes) {
            val totalDeltaTrip = totalTripDeltas[modeIdx]
            
            if (newModeToInitialIndex.containsKey(modeIdx)) {
                // CASE: ALREADY MAPPED
                // We know which initial record (by index) belongs to this mode.
                val initialIdx = newModeToInitialIndex[modeIdx]!!
                val rInitial = initialBatch[initialIdx]
                
                // Find which record in the SHUFFLED new batch matches the delta from that initial record.
                val match = newBatch.find { rNew ->
                    kotlin.math.abs((rNew.distance - rInitial.distance) - totalDeltaTrip) <= MODE_MAPPING_TOLERANCE_METERS
                }
                
                if (match != null) {
                    newSortedList[modeIdx] = match
                    newConfirmedIndices.add(modeIdx)
                }
            } else if (totalDeltaTrip > 0) {
                // CASE: LATE DISCOVERY
                // This mode moved for the first time. Try to map it to an UNUSED initial slot.
                val usedInitialIndices = newModeToInitialIndex.values.toSet()
                val potentialMappings = mutableListOf<Pair<Int, Int>>() // (InitialIdx, NewRecordIdx)
                
                for (j in 0 until numModes) {
                    if (usedInitialIndices.contains(j)) continue // Slot already mapped to another mode
                    
                    val rInitial = initialBatch[j]
                    for (i in 0 until numModes) {
                        val rNew = newBatch[i]
                        val usageDelta = rNew.distance - rInitial.distance
                        if (kotlin.math.abs(usageDelta - totalDeltaTrip) <= MODE_MAPPING_TOLERANCE_METERS) {
                            potentialMappings.add(j to i)
                        }
                    }
                }
                
                // If only one unique (InitialSlot, NewRecord) pair matches this mode's delta, discover it!
                if (potentialMappings.size == 1) {
                    val (initialIdx, newIdx) = potentialMappings[0]
                    newModeToInitialIndex[modeIdx] = initialIdx
                    newSortedList[modeIdx] = newBatch[newIdx]
                    newConfirmedIndices.add(modeIdx)
                }
            }
        }
        
        return Triple(newSortedList, newConfirmedIndices, newModeToInitialIndex)
    }
}
