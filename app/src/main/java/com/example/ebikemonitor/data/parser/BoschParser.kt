package com.example.ebikemonitor.data.parser

import android.util.Log

data class BoschMessage(
    val messageId: Int,
    val messageType: Int,
    val value: Int,
    val rawBytes: List<Byte>
) {
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

    private fun decodeVarint(bytes: List<Byte>): Pair<Int, Int> {
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
}
