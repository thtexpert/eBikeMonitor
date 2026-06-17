package com.example.ebikemonitor.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.ebikemonitor.BikePresenceManager
import com.example.ebikemonitor.EBikeApplication
import com.example.ebikemonitor.EBikeBackgroundService
import com.example.ebikemonitor.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BluetoothConnectionReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val macAddress = device?.address ?: return

        scope.launch {
            val app = context.applicationContext as EBikeApplication
            val settings = app.settingsRepository
            val targetMac = settings.bleMacAddress.first()
            val useHardwareTrigger = settings.useHardwareConnectionTrigger.first()

            if (macAddress == targetMac) {
                if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                    if (useHardwareTrigger) {
                        FileLogger.log("BluetoothConnectionReceiver: Hardware connection detected for eBike ($macAddress). Starting service...")
                        BikePresenceManager.lastHardwareConnectTime = System.currentTimeMillis()
                        BikePresenceManager.updatePresence(true, "HardwareReceiver")
                        
                        // Start the background service safely
                        val serviceIntent = Intent(context, EBikeBackgroundService::class.java)
                        try {
                            context.startForegroundService(serviceIntent)
                        } catch (e: Exception) {
                            FileLogger.log("BluetoothConnectionReceiver: Failed to start service: ${e.message}")
                        }
                    } else {
                        // Even if not using as trigger, log it for latency tracking
                        BikePresenceManager.lastHardwareConnectTime = System.currentTimeMillis()
                        FileLogger.log("BluetoothConnectionReceiver: Hardware connection detected, but trigger disabled. Logging timestamp for latency.")
                    }
                } else if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                    if (useHardwareTrigger) {
                        FileLogger.log("BluetoothConnectionReceiver: Hardware disconnection detected for eBike ($macAddress).")
                        BikePresenceManager.updatePresence(false, "HardwareReceiver")
                    } else {
                        FileLogger.log("BluetoothConnectionReceiver: Hardware disconnection detected, but trigger disabled.")
                    }
                }
            }
        }
    }
}
