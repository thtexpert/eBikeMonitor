package com.example.ebikemonitor.data.parser

import org.junit.Assert.assertEquals
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
}
