package com.example.ebikemonitor.service

import android.companion.CompanionDeviceService
import android.content.Intent
import com.example.ebikemonitor.BikePresenceManager
import com.example.ebikemonitor.EBikeBackgroundService
import com.example.ebikemonitor.FileLogger

class EBikeCompanionService : CompanionDeviceService() {

    override fun onDeviceAppeared(address: String) {
        FileLogger.log("EBikeCompanionService: Device appeared: $address")
        BikePresenceManager.updatePresence(true, "CompanionService")

        // Start the background service (exempt from background start limits because of CDM)
        val serviceIntent = Intent(this, EBikeBackgroundService::class.java)
        try {
            startForegroundService(serviceIntent)
            FileLogger.log("EBikeCompanionService: Background service started via Foreground")
        } catch (e: Exception) {
            FileLogger.log("EBikeCompanionService: Failed to start service: ${e.message}")
        }
    }

    override fun onDeviceDisappeared(address: String) {
        FileLogger.log("EBikeCompanionService: Device disappeared: $address")
        BikePresenceManager.updatePresence(false, "CompanionService")
    }
}
