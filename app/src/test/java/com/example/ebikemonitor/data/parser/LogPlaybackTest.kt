package com.example.ebikemonitor.data.parser

import com.example.ebikemonitor.data.model.BikeStatus
import com.example.ebikemonitor.data.model.UsageRecord
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LogPlaybackTest {

    @Test
    fun testFullLogFilePlayback() {
        val userDir = System.getProperty("user.dir")
        println("INFO: Starting search for log file from: ${userDir}")

        var currentSearchDir: File? = File(userDir)
        var logFile: File? = null
        
        repeat(5) {
            if (logFile == null && currentSearchDir != null) {
                val candidate = File(currentSearchDir, "Wireshark/bosch_log.txt")
                if (candidate.exists()) {
                    logFile = candidate
                } else {
                    currentSearchDir = currentSearchDir?.parentFile
                }
            }
        }
        
        if (logFile == null || !logFile!!.exists()) {
            println("ERROR: Could not find 'Wireshark/bosch_log.txt' in any parent of ${userDir}")
            return
        } else {
            println("SUCCESS: Found log file at: ${logFile!!.absolutePath}")
            runPlayback(logFile!!)
        }
    }

    private fun runPlayback(file: File) {
        var status = BikeStatus()
        var boschPacketCount = 0
        val packetBuffer = mutableListOf<Byte>()
        val allRawRecords = mutableListOf<UsageRecord>()

        file.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("0000")) {
                if (packetBuffer.isNotEmpty()) {
                    val messages = parseBuffer(packetBuffer)
                    for (msg in messages) {
                        boschPacketCount++
                        if (msg.messageId == 0x108C) {
                            msg.decodeUsageRecord()?.let { allRawRecords.add(it) }
                        }
                        status = applyMessageToStatus(msg, status)
                    }
                    packetBuffer.clear()
                }
            }
            packetBuffer.addAll(sanitizeLine(line).toList())
        }
        
        if (packetBuffer.isNotEmpty()) {
            val messages = parseBuffer(packetBuffer)
            for (msg in messages) {
                boschPacketCount++
                if (msg.messageId == 0x108C) {
                    msg.decodeUsageRecord()?.let { allRawRecords.add(it) }
                }
                status = applyMessageToStatus(msg, status)
            }
        }

        println("\n--- RAW USAGE RECORD SCAN ---")
        val uniqueRecords = allRawRecords.distinctBy { it.energy }.sortedByDescending { it.energy }
        uniqueRecords.forEachIndexed { i, r ->
            println("Record #$i: ${r.distance}m | ${r.energy}Wh")
        }
        
        val totalSum = uniqueRecords.sumOf { it.energy.toDouble() } / 1000.0
        println("TOTAL CALCULATED SUM: $totalSum kWh")

        println("\n===============================================")
        println("   WIRESHARK LOG PLAYBACK SUMMARY")
        println("===============================================")
        println("Parsed Packets: $boschPacketCount")
        println("-----------------------------------------------")
        println("Odometer:      ${status.totalDistance ?: "N/A"} km")
        println("Assist Mode:   ${status.assistMode ?: "N/A"}")
        println("Mode Names:    ${status.assistModeNames.joinToString(", ")}")
        println("Bat Level:     ${status.batteryLevel ?: "N/A"} %")
        println("Bat Energy:    ${status.totalBattery ?: "N/A"} kWh")
        println("Charge Cycles: ${status.chargeCycles ?: "N/A"}")
        println("Software Ver:  ${status.ebikeLedSoftwareVersion ?: "N/A"}")
        println("Bat Serial:    ${status.batterySerialNumber ?: "N/A"}")
        println("Usage Records: ${uniqueRecords.size} unique found")
        println("===============================================\n")

        assertEquals(14939.116, status.totalDistance ?: 0.0, 0.001)
    }

    private fun parseBuffer(buffer: List<Byte>): List<BoschMessage> {
        val rawBytes = buffer.toByteArray()
        if (rawBytes.size > 12 && rawBytes[0] == 0x02.toByte()) {
            val boschBytes = rawBytes.sliceArray(12 until rawBytes.size)
            return BoschParser.parse(boschBytes).first
        }
        return emptyList()
    }

    private fun applyMessageToStatus(msg: BoschMessage, current: BikeStatus): BikeStatus {
        return when (msg.messageId) {
            0x9818 -> current.copy(totalDistance = msg.value / 1000.0)
            0x9819 -> current.copy(driveUnitHours = msg.value)
            0x809C -> current.copy(totalBattery = msg.value / 1000.0)
            0x8096 -> current.copy(chargeCycles = msg.value / 10.0)
            0x80BC -> current.copy(batteryLevel = msg.value)
            0x9809 -> current.copy(assistMode = msg.value)
            0x206B -> msg.decodeStringField()?.let { current.copy(ebikeLedSoftwareVersion = it) } ?: current
            0x0081 -> msg.decodeStringField()?.let { current.copy(batterySerialNumber = it) } ?: current
            0x009B -> msg.decodeStringField()?.let { current.copy(batteryModel = it) } ?: current
            0x108C -> msg.decodeUsageRecord()?.let { current.copy(unsortedUsageRecords = current.unsortedUsageRecords + it) } ?: current
            0x180C, 0x180D -> {
                val modes = msg.decodeAssistModes()
                if (modes.isNotEmpty()) current.copy(assistModeNames = modes) else current
            }
            else -> current
        }
    }

    private fun sanitizeLine(line: String): ByteArray {
        val parts = line.trim().split(Regex("\\s{2,}"))
        val hexPart = if (parts.size >= 2) parts[1] else return f(line)
        val hexChars = hexPart.split(" ").filter { it.length == 2 && it.all { c -> c.isDigit() || (c.lowercaseChar() >= 'a' && c.lowercaseChar() <= 'f') } }
        return hexChars.map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun f(line: String): ByteArray {
        val hexChars = line.trim().split(" ").filter { it.length == 2 && it.all { c -> c.isDigit() || (c.lowercaseChar() >= 'a' && c.lowercaseChar() <= 'f') } }
        return hexChars.map { it.toInt(16).toByte() }.toByteArray()
    }
}
