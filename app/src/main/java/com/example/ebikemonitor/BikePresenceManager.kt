package com.example.ebikemonitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.example.ebikemonitor.FileLogger

object BikePresenceManager {
    private val _isBikePresent = MutableStateFlow(false)
    val isBikePresent = _isBikePresent.asStateFlow()
    
    var lastHardwareConnectTime: Long = 0L

    fun updatePresence(present: Boolean, source: String) {
        if (_isBikePresent.value != present) {
            _isBikePresent.value = present
            FileLogger.log("BikePresenceManager: Bike presence updated to $present by $source")
        }
    }
}
