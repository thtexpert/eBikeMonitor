package com.example.ebikemonitor

import com.example.ebikemonitor.data.parser.BoschParser
import org.junit.Assert.assertEquals
import org.junit.Test

class BoschParserTest {

    @Test
    fun testParseSpeed() {
        // Speed 0x982D, value 2500 (25.0 km/h) context: byte array construction
        // Structure: [Start(1)][Length(1)][ID_Hi][ID_Lo][Type][Data...]
        // Example: 0x01, 0x06, 0x98, 0x2D, 0x0A, 0x19 ... (fake example)
        
        // Let's create a synthetic packet based on reference:
        // Message ID: 0x982D (Speed)
        // Value: 2500 -> 25.00 km/h
        // If Type is 0x0A (byte) it only holds 1 byte.
        // If Type is 0x08 (varint), it can hold more.
        
        // Constructing a varint for 2500:
        // 2500 = 100111000100 -> 
        // Group 7 bits: 
        // Lower: 1000100 (0x44) -> with MSB 1 -> 0xC4
        // Upper: 0010011 (0x13) -> with MSB 0 -> 0x13
        // Varint: C4 13
        
        val payload = byteArrayOf(
            0x01, // Start
            0x06, // Length (2 for ID + 1 for Type + 2 for Varint = 5? Wait. Length is payload size?)
                  // Ref says: totalMessageSize = messageLength + 2.
                  // ID is at index+2.
                  // dataType at index+4.
                  
            // Let's reverse engineer the parser:
            // index=0
            // messageLength = bytes[1]
            // totalMessageSize = messageLength + 2
            // messageId = bytes[2] << 8 | bytes[3]
            // dataType = bytes[4]
            // dataStart = 5
            
            // So for ID 0x982D:
            0x98.toByte(), 0x2D.toByte(),
            
            // Type 0x08 (Varint)
            0x08.toByte(),
            
            // Value 2500 (0xC4, 0x13)
            0xC4.toByte(), 0x13.toByte()
        )
        // Length check: ID(2) + Type(1) + Data(2) = 5.
        // So byte[1] should be 5.
        payload[1] = 0x05.toByte()
        
        val messages = BoschParser.parse(payload)
        assertEquals(1, messages.size)
        assertEquals(0x982D, messages[0].messageId)
        assertEquals(2500, messages[0].value)
    }

    @Test
    fun testParseBattery() {
        // Battery 0x80BC, Value 85%
        // Type 0x0A (Byte)? Reference says "Different encoding type - try as raw bytes" for 0x0A
        // Let's assume it fits in a byte. 85 = 0x55
        
        val payload = byteArrayOf(
            0x01,
            0x04, // Length: ID(2) + Type(1) + Data(1) = 4
            0x80.toByte(), 0xBC.toByte(),
            0x0A.toByte(),
            0x55.toByte()
        )
        
        val messages = BoschParser.parse(payload)
        assertEquals(1, messages.size)
        assertEquals(0x80BC, messages[0].messageId)
        assertEquals(85, messages[0].value)
    }
}
