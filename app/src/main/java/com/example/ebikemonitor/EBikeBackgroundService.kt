package com.example.ebikemonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import com.example.ebikemonitor.service.EBikeNotificationListener

class EBikeBackgroundService : Service() {

    // Cache for delta-publishing
    private var lastPublishedStatus: com.example.ebikemonitor.data.model.BikeStatus? = null
    private var profileBeforeSync: com.example.ebikemonitor.data.model.BikeProfile? = null
    private var syncCompletionTime: Long = 0L
    private var lastTelemetryLogTime: Long = 0L
    private var lastObservedTripPedal: Int? = null
    private var lastObservedTripTotal: Double? = null

    // Home Sync Window — keeps MQTT alive after bike-off to push end-of-ride data
    private var homeSyncJob: kotlinx.coroutines.Job? = null
    private var endOfRideSnapshot: com.example.ebikemonitor.data.model.BikeStatus? = null

    // Phase 1B: State tracking for event-driven synchronization
    private var isSyncing = false
    private var isSyncComplete = false
    private var syncTimeoutJob: kotlinx.coroutines.Job? = null
    
    // Starting reference points captured during sync completion
    private var startupOdometer: Double? = null
    private var startupTotalBattery: Double? = null
    private var startupTripPedal: Int? = null
    private var startupTripTotal: Double? = null
    private var startupTripDists: List<Int>? = null
    private var startupSoC: Int? = null
    private var startupUsageRecords: List<com.example.ebikemonitor.data.model.UsageRecord?> = emptyList()
    
    // Usable capacity session tracking
    private var activeSessionStartBattery: Double? = null
    private var lastReferenceWriteTime = 0L
    private var activeProfile: com.example.ebikemonitor.data.model.BikeProfile? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val CHANNEL_ID = "EBikeMonitorChannel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        updateNotification("Waiting for Bosch Flow connection...")
        FileLogger.log("EBikeBackgroundService onCreate")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
        
        // 1. Initial Setup
        val app = application as EBikeApplication
        val bleManager = app.bleManager
        val mqttManager = app.mqttManager
        val settings = app.settingsRepository

        // Start dynamic profile tracking
        serviceScope.launch {
            kotlinx.coroutines.flow.combine(
                settings.bikeProfiles,
                settings.activeBikeMac,
                settings.bleMacAddress
            ) { profiles, activeMac, bleMac ->
                val mac = activeMac ?: bleMac
                profiles.find { it.macAddress == mac }
            }.collect { profile ->
                activeProfile = profile
            }
        }
        
        // 2. Pre-load Baselines so we are ready the moment we connect
        kotlinx.coroutines.runBlocking {
            try {
                // Use activeBikeMac if available, otherwise fallback to legacy bleMacAddress
                val mac = settings.activeBikeMac.first() ?: settings.bleMacAddress.first()
                if (!mac.isNullOrEmpty()) {
                    val profiles = settings.bikeProfiles.first()
                    val profile = profiles.find { it.macAddress == mac }
                    
                    if (profile != null) {
                        activeProfile = profile
                        if (profile.lastUsageRecords.isNotEmpty()) {
                            val baselines = profile.lastUsageRecords.mapIndexed { i, usage ->
                                val trip = profile.lastTripDistPerMode.getOrNull(i) ?: 0
                                (usage?.distance ?: 0) - trip
                            }
                            if (baselines.size == profile.lastUsageRecords.size) {
                                FileLogger.log("EBikeBackgroundService: Pre-loading ${baselines.size} persistent baselines for $mac")
                                bleManager.setPersistentBaselines(baselines)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                FileLogger.log("EBikeBackgroundService: Baseline pre-load error: ${e.message}")
            }
        }

        // 3. Observe all key states → emit a single [STATUS] summary line on any change
        serviceScope.launch {
            combine(
                bleManager.isConnected,
                mqttManager.isConnected,
                mqttManager.isConnecting,
                settings.autoConnectMqtt,
                BikePresenceManager.isBikePresent
            ) { arr ->
                // arr: [bleConn, mqttConn, mqttConnecting, autoMqtt, bikePresent]
                arr.toList()
            }.combine(app.isUiActive) { stateArr, uiActive ->
                stateArr + uiActive
            }.distinctUntilChanged()
             .collect { states ->
                val bleConnected     = states[0] as Boolean
                val mqttConnected    = states[1] as Boolean
                val mqttConnecting   = states[2] as Boolean
                val autoMqtt         = states[3] as Boolean
                val bikePresent      = states[4] as Boolean
                val uiActive         = states[5] as Boolean

                val bleStr  = if (bleConnected) "Connected"
                              else if (bleManager.isConnectingOrConnected) "Connecting"
                              else "Disconnected"
                val mqttStr = if (mqttConnected) "Connected"
                              else if (mqttConnecting) "Connecting"
                              else if (autoMqtt) "Disconnected(auto)"
                              else "Off"
                val appStr  = if (uiActive) "Foreground" else "Background"
                val flowStr = if (bikePresent) "Present" else "Absent"
                val monStr  = if (reconnectionJob?.isActive == true) "ON" else "OFF"

                FileLogger.log("[STATUS] APP:$appStr | Flow:$flowStr | BLE:$bleStr | MQTT:$mqttStr | Monitor:$monStr")
                // Update the persistent notification with the core connection states
                if (!bikePresent && homeSyncJob?.isActive != true) {
                    updateNotification("Session Ended")
                } else {
                    val notifBle  = if (bleConnected) "Connected" else if (bikePresent && autoMqtt) "Reconnecting..." else "Disconnected"
                    val notifMqtt = if (mqttConnected) "Connected" else if ((bikePresent || homeSyncJob?.isActive == true) && autoMqtt) "Reconnecting..." else "Disconnected"
                    updateNotification("BLE: $notifBle | MQTT: $notifMqtt")
                }
             }
        }

        // 4. Monitoring and Publishing Logic
        serviceScope.launch {
            bleManager.bikeStatus
                .sample(1000)
                .collect { status ->
                    if (mqttManager.isConnected.value && status.lastUpdateTimestamp > 0 && isSyncComplete) {
                        syncToMqtt(status)
                    }
                    
                    val odoVal = status.totalDistance
                    val usageCount = status.sortedUsageRecordsB.size
                    val usageSum = if (status.sortedUsageRecordsB.isNotEmpty()) {
                        status.sortedUsageRecordsB.sumOf { it?.distance?.toDouble() ?: 0.0 } / 1000.0
                    } else {
                        0.0
                    }
                    val nowVal = System.currentTimeMillis()
                    if (nowVal - lastTelemetryLogTime >= 30000) {
                        lastTelemetryLogTime = nowVal
                        FileLogger.log("EBikeBackgroundService: Live Status Check: Odo: $odoVal km, UsageSum: ${"%.3f".format(usageSum)} km, RecordsCount: $usageCount, Speed: ${status.speed} m/s")
                        val profile = activeProfile
                        if (isSyncComplete && profile != null) {
                            val currentTripPedal = status.tripPedalEnergy ?: 0
                            val measuredTotal = profile.totalPedalEnergyMeasuredBaseline + currentTripPedal
                            val estimatedTotal = profile.totalPedalEnergyEstimatedBaseline
                            val overallTotal = measuredTotal + estimatedTotal
                            val formattedOdo = if (odoVal != null) "%.3f".format(odoVal) else "null"
                            FileLogger.log("[MQTT_DRYRUN] Trigger: Active Riding | Odo: $formattedOdo km | Topics: pedal_energy_measured = ${"%.1f".format(measuredTotal)} kcal | pedal_energy_estimated = ${"%.1f".format(estimatedTotal)} kcal | pedal_energy_total = ${"%.1f".format(overallTotal)} kcal")
                        }
                    }
                    
                    val mac = settings.activeBikeMac.first() ?: settings.bleMacAddress.first() ?: return@collect
                    
                    // Sync Gating Checklist
                    if (bleManager.isConnected.value && isSyncing && !isSyncComplete) {
                        val expectedCount = status.assistModeNames.size
                        val checklistOk = expectedCount > 0 &&
                                         status.totalDistance != null &&
                                         status.sortedUsageRecordsB.size == expectedCount &&
                                         status.sortedUsageRecordsB.all { it != null } &&
                                         status.totalBattery != null &&
                                         status.tripDistPerMode.size == expectedCount &&
                                         status.remainingRanges.isNotEmpty() &&
                                         status.tripPedalEnergy != null &&
                                         status.tripTotalEnergy != null &&
                                         status.batteryLevel != null
                                         
                        if (checklistOk) {
                            FileLogger.log("EBikeBackgroundService: BLE Sync checklist fully satisfied. Completing sync.")
                            completeSync(force = false)
                        }
                    }

                    // Late Sync Reference Adjustment
                    if (isSyncComplete && bleManager.isConnected.value && System.currentTimeMillis() - syncCompletionTime <= 30000) {
                        val currentOdo = status.totalDistance
                        val currentUsage = status.sortedUsageRecordsB
                        val expectedCount = status.assistModeNames.size
                        
                        val currentUsageSum = if (currentUsage.size == expectedCount) currentUsage.sumOf { it?.distance?.toDouble() ?: 0.0 } / 1000.0 else 0.0
                        val startupUsageSum = if (startupUsageRecords.size == expectedCount) startupUsageRecords.sumOf { it?.distance?.toDouble() ?: 0.0 } / 1000.0 else 0.0
                        
                        val speed = status.speed ?: 0.0
                        val elapsedSeconds = (System.currentTimeMillis() - syncCompletionTime) / 1000.0
                        val maxPossibleDistKm = ((speed / 3600.0) * elapsedSeconds) + 0.05 // Min 50m threshold to prevent tiny drift triggers
                        
                        val odoJump = if (currentOdo != null && startupOdometer != null) currentOdo - startupOdometer!! else 0.0
                        val usageJump = currentUsageSum - startupUsageSum
                        
                        val odoTrigger = odoJump > maxPossibleDistKm
                        val usageTrigger = usageJump > maxPossibleDistKm
                        
                        if (odoTrigger || usageTrigger) {
                            val oldStartupOdo = startupOdometer
                            val triggerReason = if (odoTrigger && usageTrigger) "Odo & Usage jump" else if (odoTrigger) "Odo jump" else "Usage jump"
                            val resolvedOdo = currentOdo ?: startupOdometer ?: 0.0
                            
                            FileLogger.log("EBikeBackgroundService: Late sync detected! Trigger: $triggerReason. Odo: $oldStartupOdo -> $resolvedOdo, UsageSum: $startupUsageSum -> $currentUsageSum (jump: ${"%.3f".format(odoJump)}/${"%.3f".format(usageJump)} km vs max expected ${"%.3f".format(maxPossibleDistKm)} km). Re-running sync with updated references.")
                            
                            // 1. Update starting reference points
                            startupOdometer = resolvedOdo
                            startupTotalBattery = status.totalBattery ?: startupTotalBattery
                            startupTripPedal = status.tripPedalEnergy ?: startupTripPedal
                            startupTripTotal = status.tripTotalEnergy ?: startupTripTotal
                            startupTripDists = if (status.tripDistPerMode.isNotEmpty()) status.tripDistPerMode else startupTripDists
                            startupSoC = status.batteryLevel ?: startupSoC
                            startupUsageRecords = currentUsage
                            
                            // 2. Re-run offline segment detection using activeProfile
                            app.applicationScope.launch {
                                val originalProfile = activeProfile
                                if (originalProfile != null) {
                                    val odoDelta = resolvedOdo - originalProfile.lastOdometer
                                    val tripResetDetected = (status.tripPedalEnergy ?: 0) < originalProfile.lastTripPedalEnergy ||
                                                       (status.tripTotalEnergy ?: 0.0) < originalProfile.lastTripRideEnergy
                                                       
                                    var pedalBaseline = originalProfile.totalPedalEnergyBaseline
                                    var rideBaseline = originalProfile.totalRideEnergyBaseline
                                    var pedalMeasuredBaseline = originalProfile.totalPedalEnergyMeasuredBaseline
                                    var pedalEstimatedBaseline = originalProfile.totalPedalEnergyEstimatedBaseline
                                
                                if (tripResetDetected) {
                                    FileLogger.log("EBikeBackgroundService: Trip reset detected on late sync. Updating baselines.")
                                    pedalBaseline += originalProfile.lastTripPedalEnergy
                                    pedalMeasuredBaseline += originalProfile.lastTripPedalEnergy
                                    rideBaseline += originalProfile.lastTripRideEnergy
                                }
                                
                                if (odoDelta > 0.20) {
                                    val prevTripDistSum = if (tripResetDetected) 0.0 else originalProfile.lastTripDistPerMode.sum() / 1000.0
                                    val currentTripDistSum = (status.tripDistPerMode.sum()) / 1000.0
                                    val tripDelta = currentTripDistSum - prevTripDistSum
                                    val offlineDist = odoDelta - tripDelta
                                    
                                    val formattedLastOdo = "%.3f".format(originalProfile.lastOdometer)
                                    val formattedCurrentOdo = "%.3f".format(resolvedOdo)
                                    val formattedOdoDelta = "%.3f".format(odoDelta)
                                    val formattedLastTrip = "%.3f".format(prevTripDistSum)
                                    val formattedCurrentTrip = "%.3f".format(currentTripDistSum)
                                    val formattedTripDelta = "%.3f".format(tripDelta)
                                    val formattedOfflineDist = "%.3f".format(offlineDist)
                                    val offlineStatus = if (offlineDist > 0.20) "OFFLINE_TRIGGERED" else "SKIPPED"
                                    
                                    FileLogger.log("[OFFLINE_CHECK_LATE] lastOdo: $formattedLastOdo | currentOdo: $formattedCurrentOdo | odoDelta: $formattedOdoDelta | lastTripSum: $formattedLastTrip | currentTripSum: $formattedCurrentTrip | tripDelta: $formattedTripDelta | offlineDist: $formattedOfflineDist | status: $offlineStatus")
                                    
                                    if (offlineDist > 0.20) {
                                        var capacity = originalProfile.learnedUsableMotorCapacity
                                        if (capacity == 500.0f && !status.batteryModel.isNullOrEmpty()) {
                                            if (status.batteryModel.contains("750")) capacity = 750f * 0.70f
                                            else if (status.batteryModel.contains("625")) capacity = 625f * 0.70f
                                            else if (status.batteryModel.contains("500")) capacity = 500f * 0.70f
                                            else if (status.batteryModel.contains("400")) capacity = 400f * 0.70f
                                        }
                                        val rangeArray = status.remainingRanges
                                        val modesCount = status.assistModeNames.size
                                        val rates = originalProfile.learnedMotorConsumptionRates.toMutableList()
                                        while (rates.size < modesCount) rates.add(8.0f)
                                        val learnedCoeff = originalProfile.learnedUserEffortCoefficient
                                        
                                        if (rangeArray.isNotEmpty() && (status.batteryLevel ?: 0) >= 10) {
                                            for (r in 0 until rangeArray.size) {
                                                val range = rangeArray[r]
                                                if (range > 0) {
                                                    val modeIndex = r + 1
                                                    if (modeIndex < rates.size) {
                                                        rates[modeIndex] = (capacity * ((status.batteryLevel ?: 0) / 100.0f)) / range
                                                    }
                                                }
                                            }
                                            if (rates.isNotEmpty()) rates[0] = 0.0f
                                            settings.updateBikeLearningParams(mac, learnedCoeff, rates)
                                        }
                                        
                                        val energyDeltaKWh = (status.totalBattery ?: originalProfile.lastTotalMotorEnergy) - originalProfile.lastTotalMotorEnergy
                                        val isOffRide = energyDeltaKWh <= 0.001
                                        val estimatedWh: Double
                                        val estimatedKcal: Double
                                        
                                        if (isOffRide) {
                                            estimatedWh = 0.0
                                            estimatedKcal = offlineDist * 22.0
                                            FileLogger.log("EBikeBackgroundService: Offline segment detected as unassisted (no SoC drop). Using distance-based estimate.")
                                        } else {
                                            val activeRates = rates.filterIndexed { idx, _ -> idx >= 1 }
                                            val avgRate = if (activeRates.isNotEmpty()) activeRates.average() else 8.0
                                            estimatedWh = offlineDist * avgRate
                                            estimatedKcal = learnedCoeff * estimatedWh
                                        }
                                        
                                        FileLogger.log("EBikeBackgroundService: Late Offline Est - Wh: ${"%.2f".format(estimatedWh)}, kcal: ${"%.1f".format(estimatedKcal)}")
                                        
                                        pedalBaseline += estimatedKcal
                                        pedalEstimatedBaseline += estimatedKcal
                                        rideBaseline += estimatedWh
                                    }
                                }
                                
                                settings.updateBikeBaselines(
                                    mac = mac,
                                    pedalBaseline = pedalBaseline,
                                    rideBaseline = rideBaseline,
                                    pedalMeasuredBaseline = pedalMeasuredBaseline,
                                    pedalEstimatedBaseline = pedalEstimatedBaseline,
                                    lastTripPedal = status.tripPedalEnergy ?: 0,
                                    lastTripRide = status.tripTotalEnergy ?: 0.0,
                                    lastOdo = resolvedOdo,
                                    lastTotalBattery = status.totalBattery ?: originalProfile.lastTotalMotorEnergy
                                )
                            }
                        }
                    }
                    }

                    // Active mid-ride trip reset check
                    if (isSyncComplete && bleManager.isConnected.value) {
                        val currentTripPedal = status.tripPedalEnergy ?: 0
                        val currentTripTotal = status.tripTotalEnergy ?: 0.0
                        val currentTripDists = if (status.tripDistPerMode.isNotEmpty()) status.tripDistPerMode else List(status.assistModeNames.size) { 0 }
                        
                        val prevPedal = lastObservedTripPedal
                        val prevTotal = lastObservedTripTotal
                        
                        if (prevPedal == null || prevTotal == null) {
                            lastObservedTripPedal = currentTripPedal
                            lastObservedTripTotal = currentTripTotal
                        } else {
                            val tripResetDetected = currentTripPedal < prevPedal || currentTripTotal < prevTotal
                            
                            if (tripResetDetected) {
                                FileLogger.log("EBikeBackgroundService: Mid-ride trip reset detected (Pedal: $prevPedal -> $currentTripPedal, Total: $prevTotal -> $currentTripTotal). Saving accumulated session calories ($prevPedal kcal) to database baseline.")
                                val profile = activeProfile
                                if (profile != null) {
                                    val newPedalMeasuredBaseline = profile.totalPedalEnergyMeasuredBaseline + prevPedal
                                    val newPedalBaseline = profile.totalPedalEnergyBaseline + prevPedal
                                    val newRideBaseline = profile.totalRideEnergyBaseline + prevTotal
                                    
                                    app.applicationScope.launch {
                                        settings.updateBikeBaselines(
                                            mac = mac,
                                            pedalBaseline = newPedalBaseline,
                                            rideBaseline = newRideBaseline,
                                            pedalMeasuredBaseline = newPedalMeasuredBaseline,
                                            pedalEstimatedBaseline = profile.totalPedalEnergyEstimatedBaseline,
                                            lastTripPedal = currentTripPedal,
                                            lastTripRide = currentTripTotal,
                                            lastOdo = status.totalDistance ?: profile.lastOdometer,
                                            lastTotalBattery = status.totalBattery ?: profile.lastTotalMotorEnergy
                                        )
                                    }
                                }
                                startupTripPedal = currentTripPedal
                                startupTripTotal = currentTripTotal
                                startupTripDists = currentTripDists
                                startupUsageRecords = status.sortedUsageRecordsB
                            }
                            lastObservedTripPedal = currentTripPedal
                            lastObservedTripTotal = currentTripTotal
                        }
                    }

                    // Periodic active reference syncing (every 10 seconds)
                    if (isSyncComplete && bleManager.isConnected.value) {
                        val now = System.currentTimeMillis()
                        if (now - lastReferenceWriteTime >= 10000) {
                            lastReferenceWriteTime = now
                            val currentOdo = status.totalDistance
                            val currentBattery = status.totalBattery
                            val currentTripPedal = status.tripPedalEnergy ?: 0
                            val currentTripTotal = status.tripTotalEnergy ?: 0.0
                            if (currentOdo != null && currentBattery != null) {
                                settings.updateActiveRideReferences(
                                     mac = mac,
                                     currentOdo = currentOdo,
                                     currentTotalBattery = currentBattery,
                                     currentTripPedal = currentTripPedal,
                                     currentTripRide = currentTripTotal,
                                     tripDistPerMode = status.tripDistPerMode,
                                     usageRecords = status.sortedUsageRecordsB
                                 )
                            }
                        }
                    }

                    // Real-time calorie estimation for UI debug status
                    if (isSyncComplete && bleManager.isConnected.value) {
                        val currentUsage = status.sortedUsageRecordsB
                        val expectedCountForSim = status.assistModeNames.size
                        
                        var simulatedKcalSum = 0.0
                        var totalExpectedEffort = 0.0
                        
                        val profile = activeProfile
                        if (profile != null && currentUsage.size == expectedCountForSim && startupUsageRecords.size == expectedCountForSim) {
                            val effortCoeff = profile.learnedUserEffortCoefficient
                            val rates = profile.learnedMotorConsumptionRates.toMutableList()
                            while (rates.size < expectedCountForSim) rates.add(8.0f)
                            
                            for (i in 1 until expectedCountForSim) { // Skip Index 0 (OFF)
                                val cur = currentUsage[i]
                                val start = startupUsageRecords.getOrNull(i)
                                if (cur != null && start != null) {
                                    val energyDeltaWh = cur.energy - start.energy
                                    val rate = rates.getOrNull(i) ?: 8.0f
                                    if (rate > 0.0f && energyDeltaWh > 0) {
                                        totalExpectedEffort += energyDeltaWh * (5.0 / rate)
                                    }
                                }
                            }
                            simulatedKcalSum = effortCoeff * totalExpectedEffort
                        }
                        
                        val currentTripPedal = status.tripPedalEnergy ?: 0
                        val pedalDelta = currentTripPedal - (startupTripPedal ?: 0)
                        
                        val errorPct = if (pedalDelta > 0) {
                            ((simulatedKcalSum - pedalDelta) / pedalDelta) * 100.0
                        } else {
                            0.0
                        }
                        
                        bleManager.updateEstimatedCalories(simulatedKcalSum, errorPct)
                    }
                    
                    // Periodic history saving (Crucial for next start!)
                    val expectedCount = status.assistModeNames.size
                    if (expectedCount > 0 && 
                        status.confirmedModeIndices.size == expectedCount && 
                        status.sortedUsageRecordsB.size == expectedCount) {
                        
                        settings.updateBikeUsageHistory(mac, status.sortedUsageRecordsB, status.tripDistPerMode)
                    }
                }
        }

        // 5. Connection Gate Logic
        var lastBikePresent: Boolean? = null
        serviceScope.launch {
            combine(
                BikePresenceManager.isBikePresent,
                settings.backgroundStartup,
                settings.useDirectDetection,
                settings.useHardwareConnectionTrigger,
                app.isUiActive
            ) { bikePresent, bgStartup, directDetection, hwTrigger, uiActive ->
                listOf(bikePresent, bgStartup, directDetection, hwTrigger, uiActive)
            }.distinctUntilChanged()
             .collect { states ->
                val bikePresent = states[0] as Boolean
                val bgStartup = states[1] as Boolean
                val directDetection = states[2] as Boolean
                val hwTrigger = states[3] as Boolean
                val uiActive = states[4] as Boolean

                val shouldMonitor = bikePresent && (bgStartup || directDetection || hwTrigger)
                val shouldServiceRun = uiActive || shouldMonitor
                
                FileLogger.log("EBikeBackgroundService: State updated: bikePresent=$bikePresent, bgStartup=$bgStartup, directDetection=$directDetection, hwTrigger=$hwTrigger, uiActive=$uiActive -> shouldMonitor=$shouldMonitor, shouldServiceRun=$shouldServiceRun")

                // ── Bike-off transition: handled BEFORE shouldServiceRun check ──────────
                // This must fire regardless of uiActive so the Home Sync Window starts
                // even when shouldServiceRun=false (UI inactive + bike off simultaneously).
                if (lastBikePresent == true && !bikePresent) {
                    val snapshot = app.bleManager.bikeStatus.value
                    FileLogger.log("EBikeBackgroundService: Bike disconnected. Disconnecting BLE.")
                    bleManager.disconnect()
                    val homeSyncMins = settings.homeSyncDurationMins.first()
                    if (homeSyncMins > 0 && snapshot.lastUpdateTimestamp > 0 && !mqttManager.isConnected.value) {
                        // MQTT not connected — Home Sync Window will push the snapshot when WiFi arrives
                        startHomeSyncWindow(snapshot, homeSyncMins * 60_000L)
                    } else {
                        if (mqttManager.isConnected.value) {
                            FileLogger.log("EBikeBackgroundService: MQTT already connected at bike-off — data synced during ride. Disconnecting MQTT.")
                        } else {
                            FileLogger.log("EBikeBackgroundService: Home Sync Window disabled or no BLE data. Disconnecting MQTT.")
                        }
                        mqttManager.disconnect()
                    }
                }

                // ── Service lifecycle gate ────────────────────────────────────────────
                if (shouldServiceRun) {
                    if (shouldMonitor) {
                        cancelHomeSyncWindow()  // bike is back on — main loop takes over
                        startReconnectionLoop()
                    } else {
                        stopReconnectionLoop()
                        // Bike-off disconnect already handled above
                    }
                } else {
                    if (homeSyncJob?.isActive == true) {
                        // Home Sync Window keeps the service alive — let it finish
                        FileLogger.log("EBikeBackgroundService: [HOME SYNC] shouldServiceRun=false but home sync active — keeping service alive")
                    } else {
                        stopReconnectionLoop()
                        FileLogger.log("EBikeBackgroundService: Stopping service reactively (UI inactive & Bike disconnected).")
                        stopSelf()
                    }
                }
                
                lastBikePresent = bikePresent
            }
        }

        // MQTT Re-sync logic: Trigger full sync or Home Sync Window snapshot push when connected
        serviceScope.launch {
            app.mqttManager.isConnected.collect { connected ->
                if (connected) {
                    val snapshot = endOfRideSnapshot
                    if (snapshot != null) {
                        // Home Sync Window path: force full sync of end-of-ride snapshot
                        FileLogger.log("EBikeBackgroundService: [HOME SYNC] MQTT connected — pushing end-of-ride snapshot (full sync)")
                        lastPublishedStatus = null  // ensure all values are published, not just diffs
                        syncToMqtt(snapshot)
                        cancelHomeSyncWindow()
                        stopSelf()
                    } else {
                        // Normal path: full sync of current BLE data — only if we have live data
                        if (app.bleManager.bikeStatus.value.lastUpdateTimestamp > 0) {
                            FileLogger.log("EBikeBackgroundService: MQTT Connected. Flagging for full sync.")
                            lastPublishedStatus = null
                            syncToMqtt(app.bleManager.bikeStatus.value)
                        } else {
                            FileLogger.log("EBikeBackgroundService: MQTT Connected but no live BLE data yet — deferring sync.")
                        }
                    }
                }
            }
        }
        
        // Heartbeat for resource monitoring — includes all key states
        serviceScope.launch {
            while (true) {
                val runtime = Runtime.getRuntime()
                val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
                val totalMem = runtime.totalMemory() / 1024 / 1024

                val bleStr  = if (app.bleManager.isConnected.value) "Connected"
                              else if (app.bleManager.isConnectingOrConnected) "Connecting"
                              else "Disconnected"
                val mqttStr = if (app.mqttManager.isConnected.value) "Connected"
                              else if (app.mqttManager.isConnecting.value) "Connecting"
                              else "Disconnected"
                val appStr  = if (app.isUiActive.value) "Foreground" else "Background"
                val flowStr = if (BikePresenceManager.isBikePresent.value) "Present" else "Absent"
                val monStr  = if (reconnectionJob?.isActive == true) "ON" else "OFF"

                FileLogger.log("[HEARTBEAT] Mem:${usedMem}MB/${totalMem}MB | APP:$appStr | Flow:$flowStr | BLE:$bleStr | MQTT:$mqttStr | Monitor:$monStr")
                kotlinx.coroutines.delay(60000) // 1 minute
            }
        }

        // Observe BLE connection status for synchronization window
        serviceScope.launch {
            bleManager.isConnected.collect { connected ->
                if (connected) {
                    onBleConnected()
                } else {
                    onBleDisconnected()
                }
            }
        }
    }

    private suspend fun syncToMqtt(status: com.example.ebikemonitor.data.model.BikeStatus) {
        val app = application as EBikeApplication
        val mac = app.settingsRepository.activeBikeMac.first() ?: app.settingsRepository.bleMacAddress.first()
        val deviceId = mac?.lowercase()?.replace(":", "") ?: "unknown"
        val bikeTopic = "ebikemonitor/$deviceId"
        val last = lastPublishedStatus

        fun pubIfDiff(topicPath: String, current: Any?, lastVal: Any?, retained: Boolean = false) {
            if (current != null && current != lastVal) {
                app.mqttManager.publish(topicPath, current.toString(), retained)
            }
        }

        // Log the sync with correct prefix
        if (last == null) {
            FileLogger.log("EBikeBackgroundService: Performing full sync for $deviceId")
        }

        // Essential live metrics
        pubIfDiff("$bikeTopic/speed", status.speed, last?.speed)
        pubIfDiff("$bikeTopic/cadence", status.cadence, last?.cadence)
        pubIfDiff("$bikeTopic/power", status.humanPower, last?.humanPower)
        pubIfDiff("$bikeTopic/motorpower", status.motorPower, last?.motorPower)
        
        if (status.assistMode != last?.assistMode) {
             app.mqttManager.publish("$bikeTopic/assistmode", com.example.ebikemonitor.data.model.getAssistModeName(status.assistMode, status.assistModeNames))
        }

        // Totals & Per-mode (Retained)
        pubIfDiff("$bikeTopic/stateofcharge", status.batteryLevel, last?.batteryLevel, true)
        pubIfDiff("$bikeTopic/totaldistance", status.totalDistance, last?.totalDistance, true)
        pubIfDiff("$bikeTopic/totalenergy", status.totalEnergyFromMotor, last?.totalEnergyFromMotor, true)
        pubIfDiff("$bikeTopic/totalbattery", status.totalBattery, last?.totalBattery, true)

        // Metadata & Versions (Retained)
        pubIfDiff("$bikeTopic/ebikeledsoftwareversion", status.ebikeLedSoftwareVersion, last?.ebikeLedSoftwareVersion, true)
        pubIfDiff("$bikeTopic/batteryserialnumber", status.batterySerialNumber, last?.batterySerialNumber, true)
        pubIfDiff("$bikeTopic/totalhours", status.driveUnitHours, last?.driveUnitHours, true)

        // Per-Mode Metrics
        status.sortedUsageRecordsB.forEachIndexed { index, record ->
            if (record != null) {
                val modeName = status.assistModeNames.getOrNull(index) ?: return@forEachIndexed
                val safeMode = sanitizeForMqtt(modeName)
                val lastRecord = last?.sortedUsageRecordsB?.getOrNull(index)
                
                if (record.distance != lastRecord?.distance && record.distance > 0) {
                    app.mqttManager.publish("$bikeTopic/${safeMode}distance", (record.distance / 1000.0).toString(), true)
                }
                if (record.energy != lastRecord?.energy && record.energy > 0) {
                    app.mqttManager.publish("$bikeTopic/${safeMode}energy", (record.energy / 1000.0).toString(), true)
                }
            }
        }

        // PowerTube Specific Topics
        val batterySerial = status.batterySerialNumber
        if (!batterySerial.isNullOrEmpty()) {
            val battTopic = "powertube/$batterySerial"
            pubIfDiff("$battTopic/stateofcharge", status.batteryLevel, last?.batteryLevel, true)
            pubIfDiff("$battTopic/totalbattery", status.totalBattery, last?.totalBattery, true)
            pubIfDiff("$battTopic/chargecycles", status.chargeCycles, last?.chargeCycles, true)
            pubIfDiff("$battTopic/serial", batterySerial, last?.batterySerialNumber, true)
        }

        lastPublishedStatus = status
    }

    private fun sanitizeForMqtt(input: String): String {
        return input.lowercase().replace(Regex("[^a-z0-9]"), "_")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Keeps the service running, but do not restart automatically if killed
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "eBike Monitor Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // Only stop if we shouldn't be monitoring in the background
        serviceScope.launch {
            val app = application as EBikeApplication
            val bikePresent = BikePresenceManager.isBikePresent.value
            val bgStartup = app.settingsRepository.backgroundStartup.first()
            val directDetection = app.settingsRepository.useDirectDetection.first()
            val hwTrigger = app.settingsRepository.useHardwareConnectionTrigger.first()
            
            if (!(bikePresent && (bgStartup || directDetection || hwTrigger))) {
                FileLogger.log("EBikeBackgroundService: onTaskRemoved: No active background monitoring. Stopping service.")
                stopSelf()
            } else {
                FileLogger.log("EBikeBackgroundService: onTaskRemoved: Background monitoring active. Keeping service running.")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        FileLogger.log("EBikeBackgroundService: onDestroy")
        val app = application as EBikeApplication
        
        cancelHomeSyncWindow()
        
        // Stop foreground and cancel notifications
        stopForeground(true)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(NOTIFICATION_ID)
        
        // Disconnect BLE and MQTT
        app.bleManager.disconnect()
        app.bleManager.clearTelemetryCache()
        app.mqttManager.disconnect()
        serviceScope.cancel() 
        
        // WE REMOVED THE AGGRESSIVE KILLPROCESS LOGIC HERE
        // This allows the app to stay in memory normally.
    }

    private fun createNotification(text: String): Notification {
        // PendingIntent to open the app on click
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this, 0, intent, 
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("eBike Background Monitoring")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher) // Use app icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setContentIntent(pendingIntent) // CLICK TO OPEN APP
            .build()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private var reconnectionJob: kotlinx.coroutines.Job? = null

    private fun startReconnectionLoop() {
        if (reconnectionJob != null && reconnectionJob!!.isActive) return
        
        FileLogger.log("EBikeBackgroundService: Starting reconnection loop")
        reconnectionJob = serviceScope.launch {
            val app = application as EBikeApplication
            val settings = app.settingsRepository
            val bleManager = app.bleManager
            val mqttManager = app.mqttManager
            
            while (isActive) {
                // Guard: exit immediately if bike is no longer present (handles stale iterations
                // that slip through after cancel() due to coroutine scheduling timing)
                if (!BikePresenceManager.isBikePresent.value) {
                    FileLogger.log("EBikeBackgroundService: Reconnection Loop: Bike absent — exiting stale iteration")
                    break
                }

                // 1. Maintain BLE Connection
                if (!bleManager.isConnected.value && !bleManager.isConnectingOrConnected) {
                    val savedMac = settings.activeBikeMac.first() ?: settings.bleMacAddress.first()
                    if (!savedMac.isNullOrEmpty()) {
                        FileLogger.log("EBikeBackgroundService: Reconnection Loop: Connecting BLE to $savedMac")
                        bleManager.connect(savedMac)
                    }
                }
                
                // 2. Maintain MQTT Connection — only when BLE is confirmed connected
                // This prevents publishing stale data if Flow fires a false-positive
                // pocket-mode notification (e.g. Flow starting without eBike turned on).
                val autoMqtt = settings.autoConnectMqtt.first()
                if (bleManager.isConnected.value && autoMqtt && !mqttManager.isConnected.value && !mqttManager.isConnecting.value) {
                    val uri = settings.mqttBrokerUri.first()
                    val user = settings.mqttUser.first()
                    val pass = settings.mqttPassword.first()
                    val eBikeName = settings.eBikeName.first()
                    val mac = settings.bleMacAddress.first()
                    
                    if (uri.isNotEmpty()) {
                        val clientId = if (eBikeName.isNotEmpty()) eBikeName else "MyEbike"
                        val deviceId = mac?.lowercase()?.replace(":", "") ?: "unknown"
                        val topic = "ebikemonitor/$deviceId"
                        
                        FileLogger.log("EBikeBackgroundService: Reconnection Loop: Connecting MQTT to $uri")
                        mqttManager.connect(uri, clientId, user, pass, topic)
                    }
                }
                
                kotlinx.coroutines.delay(10000)
            }
        }
    }

    private fun stopReconnectionLoop() {
        if (reconnectionJob != null) {
            FileLogger.log("EBikeBackgroundService: Stopping reconnection loop")
            reconnectionJob?.cancel()
            reconnectionJob = null
        }
    }

    // ── Home Sync Window ─────────────────────────────────────────────────────────
    // Keeps an MQTT-only reconnect loop alive after bike-off so end-of-ride data
    // is pushed to the broker when the phone reaches home WiFi.

    private fun startHomeSyncWindow(snapshot: com.example.ebikemonitor.data.model.BikeStatus, durationMs: Long) {
        cancelHomeSyncWindow()
        endOfRideSnapshot = snapshot
        FileLogger.log("EBikeBackgroundService: [HOME SYNC] Window started (${durationMs / 1000}s) — MQTT-only reconnect for post-ride sync")

        homeSyncJob = serviceScope.launch {
            val app = application as EBikeApplication
            val settings = app.settingsRepository
            val mqttManager = app.mqttManager
            val deadline = System.currentTimeMillis() + durationMs

            while (isActive && System.currentTimeMillis() < deadline) {
                val autoMqtt = settings.autoConnectMqtt.first()
                if (autoMqtt && !mqttManager.isConnected.value && !mqttManager.isConnecting.value) {
                    val uri = settings.mqttBrokerUri.first()
                    val user = settings.mqttUser.first()
                    val pass = settings.mqttPassword.first()
                    val eBikeName = settings.eBikeName.first()
                    val mac = settings.activeBikeMac.first() ?: settings.bleMacAddress.first()
                    if (uri.isNotEmpty()) {
                        val clientId = if (eBikeName.isNotEmpty()) eBikeName else "MyEbike"
                        val deviceId = mac?.lowercase()?.replace(":", "") ?: "unknown"
                        val topic = "ebikemonitor/$deviceId"
                        FileLogger.log("EBikeBackgroundService: [HOME SYNC] Connecting MQTT to $uri")
                        mqttManager.connect(uri, clientId, user, pass, topic)
                    }
                }
                kotlinx.coroutines.delay(10000)
            }

            // Window expired without a successful sync
            FileLogger.log("EBikeBackgroundService: [HOME SYNC] Window expired without MQTT sync. Stopping service.")
            endOfRideSnapshot = null
            stopSelf()
        }
    }

    private fun cancelHomeSyncWindow() {
        if (homeSyncJob?.isActive == true) {
            FileLogger.log("EBikeBackgroundService: [HOME SYNC] Window cancelled")
            homeSyncJob?.cancel()
        }
        homeSyncJob = null
        endOfRideSnapshot = null
    }

    private fun onBleConnected() {
        if (isSyncing || isSyncComplete) return
        FileLogger.log("EBikeBackgroundService: BLE connected. Starting synchronization window.")
        isSyncing = true
        isSyncComplete = false
        
        // Start 10-second safety timeout
        syncTimeoutJob?.cancel()
        syncTimeoutJob = serviceScope.launch {
            kotlinx.coroutines.delay(10000)
            val app = application as EBikeApplication
            val currentStatus = app.bleManager.bikeStatus.value
            val activeMac = app.settingsRepository.activeBikeMac.first() ?: app.settingsRepository.bleMacAddress.first()
            if (activeMac != null) {
                val profiles = app.settingsRepository.bikeProfiles.first()
                val profile = profiles.find { it.macAddress == activeMac }
                val odoDelta = if (profile != null && currentStatus.totalDistance != null) {
                    currentStatus.totalDistance - profile.lastOdometer
                } else {
                    0.0
                }
                
                // If odometer changed, do NOT force completion, wait for trip data.
                // Otherwise, force completion now.
                if (odoDelta <= 0.01) {
                    FileLogger.log("EBikeBackgroundService: BLE Sync timeout. Odometer unchanged (delta: $odoDelta km). Forcing SYNC_COMPLETE.")
                    completeSync(force = true)
                } else {
                    FileLogger.log("EBikeBackgroundService: BLE Sync timeout. Odometer changed (delta: $odoDelta km) but trip data missing. Deferring sync completion until movement.")
                }
            } else {
                FileLogger.log("EBikeBackgroundService: BLE Sync timeout. No active bike MAC. Forcing SYNC_COMPLETE.")
                completeSync(force = true)
            }
        }
    }

    private fun onBleDisconnected() {
        FileLogger.log("EBikeBackgroundService: BLE disconnected. Resetting sync status.")
        syncTimeoutJob?.cancel()
        
        // Run end-of-session calibration BEFORE clearing starting reference points
        runEndOfSessionCalibration()
        
        isSyncing = false
        isSyncComplete = false
        
        startupOdometer = null
        startupTotalBattery = null
        startupTripPedal = null
        startupTripTotal = null
        startupTripDists = null
        startupSoC = null
        startupUsageRecords = emptyList()
        profileBeforeSync = null
        syncCompletionTime = 0L
        
        lastObservedTripPedal = null
        lastObservedTripTotal = null
        
        // Clear telemetry cache AFTER calibration has completed
        val app = application as EBikeApplication
        app.bleManager.clearTelemetryCache()
    }

    private fun completeSync(force: Boolean) {
        if (isSyncComplete) return
        isSyncComplete = true
        syncTimeoutJob?.cancel()
        
        val app = application as EBikeApplication
        val bleManager = app.bleManager
        val settings = app.settingsRepository
        val status = bleManager.bikeStatus.value
        
        FileLogger.log("EBikeBackgroundService: SYNC_COMPLETE (forced=$force)")
        
        app.applicationScope.launch {
            try {
                val mac = settings.activeBikeMac.first() ?: settings.bleMacAddress.first() ?: return@launch
                var profile = activeProfile ?: com.example.ebikemonitor.data.model.BikeProfile(macAddress = mac, name = "MyBike")
                
                // Re-initialize/Repair database if references are empty (self-healing)
                val currentUnsorted = status.unsortedUsageRecords
                if (profile.lastUsageRecords.isEmpty() && currentUnsorted.size == status.assistModeNames.size) {
                    val sortedList = com.example.ebikemonitor.data.parser.BoschParser.processUsageRecords(currentUnsorted)
                    if (sortedList != null && sortedList.size == status.assistModeNames.size) {
                        FileLogger.log("EBikeBackgroundService: [SELF_HEALING] Restoring empty database usage records using consumption rate sorting.")
                        val tripDists = if (status.tripDistPerMode.isNotEmpty()) status.tripDistPerMode else List(status.assistModeNames.size) { 0 }
                        settings.updateBikeUsageHistory(mac, sortedList, tripDists)
                        
                        profile = profile.copy(
                            lastUsageRecords = sortedList,
                            lastTripDistPerMode = tripDists
                        )
                        activeProfile = profile
                    }
                }
                
                profileBeforeSync = profile
                syncCompletionTime = System.currentTimeMillis()
                
                // Capture starting values
                val currentOdo = status.totalDistance ?: profile.lastOdometer
                val currentTotalBattery = status.totalBattery ?: profile.lastTotalMotorEnergy
                val currentTripPedal = status.tripPedalEnergy ?: profile.lastTripPedalEnergy
                val currentTripTotal = status.tripTotalEnergy ?: profile.lastTripRideEnergy
                val currentTripDists = if (status.tripDistPerMode.isNotEmpty()) status.tripDistPerMode else if (profile.lastTripDistPerMode.isNotEmpty()) profile.lastTripDistPerMode else List(status.assistModeNames.size) { 0 }
                val currentSoC = status.batteryLevel ?: 0
                
                startupOdometer = currentOdo
                startupTotalBattery = currentTotalBattery
                startupTripPedal = currentTripPedal
                startupTripTotal = currentTripTotal
                startupTripDists = currentTripDists
                startupSoC = currentSoC
                startupUsageRecords = status.sortedUsageRecordsB
                
                // 1. Detect Trip Reset
                val tripResetDetected = currentTripPedal < profile.lastTripPedalEnergy || 
                                       currentTripTotal < profile.lastTripRideEnergy ||
                                       (currentTripDists.isNotEmpty() && profile.lastTripDistPerMode.isNotEmpty() && 
                                        currentTripDists.sum() < profile.lastTripDistPerMode.sum())
                                        
                var pedalBaseline = profile.totalPedalEnergyBaseline
                var rideBaseline = profile.totalRideEnergyBaseline
                var pedalMeasuredBaseline = profile.totalPedalEnergyMeasuredBaseline
                var pedalEstimatedBaseline = profile.totalPedalEnergyEstimatedBaseline
                
                if (tripResetDetected) {
                    FileLogger.log("EBikeBackgroundService: Trip reset detected on startup. Updating baselines.")
                    pedalBaseline += profile.lastTripPedalEnergy
                    pedalMeasuredBaseline += profile.lastTripPedalEnergy
                    rideBaseline += profile.lastTripRideEnergy
                }
                
                // 2. Odometer Deduplication & Offline Recovery / Lifetime Initialization
                val odoDelta = currentOdo - profile.lastOdometer
                
                if (profile.lastOdometer == 0.0) {
                    FileLogger.log("EBikeBackgroundService: First sync detected. Running Lifetime Initialization from usage records.")
                    val initialRates = profile.learnedMotorConsumptionRates.toMutableList()
                    val currentUsage = status.sortedUsageRecordsB
                    val expectedCount = status.assistModeNames.size
                    
                    if (currentUsage.size == expectedCount) {
                        for (i in 1 until expectedCount) { // Skip Index 0 (OFF)
                            val record = currentUsage[i]
                            if (record != null && record.distance > 1000) {
                                val lifetimeRate = record.energy.toFloat() / (record.distance / 1000.0f)
                                if (lifetimeRate in 0.5f..40.0f) {
                                    if (i < initialRates.size) {
                                        initialRates[i] = lifetimeRate
                                    } else {
                                        initialRates.add(lifetimeRate)
                                    }
                                }
                            }
                        }
                        if (initialRates.isNotEmpty()) {
                            initialRates[0] = 0.0f
                        }
                        settings.updateBikeLearningParams(mac, profile.learnedUserEffortCoefficient, initialRates)
                    }
                } else if (odoDelta > 0.01) {
                    val prevTripDistSum = if (tripResetDetected) 0.0 else profile.lastTripDistPerMode.sum() / 1000.0
                    val currentTripDistSum = currentTripDists.sum() / 1000.0
                    val tripDelta = currentTripDistSum - prevTripDistSum
                    
                    val offlineDist = odoDelta - tripDelta
                    
                    val formattedLastOdo = "%.3f".format(profile.lastOdometer)
                    val formattedCurrentOdo = "%.3f".format(currentOdo)
                    val formattedOdoDelta = "%.3f".format(odoDelta)
                    val formattedLastTrip = "%.3f".format(prevTripDistSum)
                    val formattedCurrentTrip = "%.3f".format(currentTripDistSum)
                    val formattedTripDelta = "%.3f".format(tripDelta)
                    val formattedOfflineDist = "%.3f".format(offlineDist)
                    val offlineStatus = if (offlineDist > 0.20) "OFFLINE_TRIGGERED" else "SKIPPED"
                    
                    FileLogger.log("[OFFLINE_CHECK] lastOdo: $formattedLastOdo | currentOdo: $formattedCurrentOdo | odoDelta: $formattedOdoDelta | lastTripSum: $formattedLastTrip | currentTripSum: $formattedCurrentTrip | tripDelta: $formattedTripDelta | offlineDist: $formattedOfflineDist | status: $offlineStatus")
                    
                    if (offlineDist > 0.20) { // Threshold: 200 meters (0.20 km)
                        FileLogger.log("EBikeBackgroundService: Offline segment of ${"%.3f".format(offlineDist)} km detected.")
                        
                        var capacity = profile.learnedUsableMotorCapacity
                        if (capacity == 500.0f && !status.batteryModel.isNullOrEmpty()) {
                            if (status.batteryModel.contains("750")) capacity = 750f * 0.70f
                            else if (status.batteryModel.contains("625")) capacity = 625f * 0.70f
                            else if (status.batteryModel.contains("500")) capacity = 500f * 0.70f
                            else if (status.batteryModel.contains("400")) capacity = 400f * 0.70f
                        }
                        
                        val rangeArray = status.remainingRanges
                        val modesCount = status.assistModeNames.size
                        
                        val rates = profile.learnedMotorConsumptionRates.toMutableList()
                        while (rates.size < modesCount) rates.add(8.0f)
                        
                        val learnedCoeff = profile.learnedUserEffortCoefficient
                        
                        if (rangeArray.isNotEmpty() && currentSoC >= 10) {
                            for (r in 0 until rangeArray.size) {
                                val range = rangeArray[r]
                                if (range > 0) {
                                    val modeIndex = r + 1
                                    if (modeIndex < rates.size) {
                                        rates[modeIndex] = (capacity * (currentSoC / 100.0f)) / range
                                    }
                                }
                            }
                            if (rates.isNotEmpty()) rates[0] = 0.0f
                            settings.updateBikeLearningParams(mac, learnedCoeff, rates)
                        }
                        
                        val energyDeltaKWh = currentTotalBattery - profile.lastTotalMotorEnergy
                        val isOffRide = energyDeltaKWh <= 0.001
                        
                        val estimatedWh: Double
                        val estimatedKcal: Double
                        
                        if (isOffRide) {
                            estimatedWh = 0.0
                            estimatedKcal = offlineDist * 22.0
                            FileLogger.log("EBikeBackgroundService: Offline segment detected as unassisted (no SoC drop). Using distance-based estimate.")
                        } else {
                            val activeRates = rates.filterIndexed { idx, _ -> idx >= 1 }
                            val avgRate = if (activeRates.isNotEmpty()) activeRates.average() else 8.0
                            estimatedWh = offlineDist * avgRate
                            estimatedKcal = learnedCoeff * estimatedWh
                        }
                        
                        FileLogger.log("EBikeBackgroundService: Offline Est - Wh: ${"%.2f".format(estimatedWh)}, kcal: ${"%.1f".format(estimatedKcal)}")
                        
                        pedalBaseline += estimatedKcal
                        pedalEstimatedBaseline += estimatedKcal
                        rideBaseline += estimatedWh
                    }
                }
                
                settings.updateBikeBaselines(
                    mac = mac,
                    pedalBaseline = pedalBaseline,
                    rideBaseline = rideBaseline,
                    pedalMeasuredBaseline = pedalMeasuredBaseline,
                    pedalEstimatedBaseline = pedalEstimatedBaseline,
                    lastTripPedal = currentTripPedal,
                    lastTripRide = currentTripTotal,
                    lastOdo = currentOdo,
                    lastTotalBattery = currentTotalBattery
                )
                val activeEstKcal = pedalMeasuredBaseline + currentTripPedal
                val activeEstEstimated = pedalEstimatedBaseline
                val activeEstTotal = activeEstKcal + activeEstEstimated
                val formattedOdo = if (currentOdo != null) "%.3f".format(currentOdo) else "null"
                FileLogger.log("[MQTT_DRYRUN] Trigger: Startup Sync Completion | Odo: $formattedOdo km | Topics: pedal_energy_measured = ${"%.1f".format(activeEstKcal)} kcal | pedal_energy_estimated = ${"%.1f".format(activeEstEstimated)} kcal | pedal_energy_total = ${"%.1f".format(activeEstTotal)} kcal")
            } catch (e: Exception) {
                FileLogger.log("EBikeBackgroundService: sync completion error: ${e.message}")
            }
        }
    }

    private fun runEndOfSessionCalibration() {
        val app = application as EBikeApplication
        val bleManager = app.bleManager
        val settings = app.settingsRepository
        val status = bleManager.bikeStatus.value
        
        val localStartupOdometer = startupOdometer ?: return
        val localStartupUsageRecords = startupUsageRecords.toList()
        val localStartupTripPedal = startupTripPedal
        
        if (!isSyncComplete) return
        
        app.applicationScope.launch {
            try {
                val mac = settings.activeBikeMac.first() ?: settings.bleMacAddress.first() ?: return@launch
                val profile = activeProfile ?: return@launch
                
                val currentOdo = status.totalDistance ?: profile.lastOdometer
                val odoDelta = currentOdo - localStartupOdometer
                
                val currentUsage = status.sortedUsageRecordsB
                val expectedCount = status.assistModeNames.size
                
                val rates = profile.learnedMotorConsumptionRates.toMutableList()
                while (rates.size < expectedCount) rates.add(8.0f)
                
                val usageSum = if (currentUsage.size == expectedCount && localStartupUsageRecords.size == expectedCount) {
                    currentUsage.mapIndexed { i, cur -> 
                        val start = localStartupUsageRecords.getOrNull(i)
                        if (cur != null && start != null) (cur.distance - start.distance) / 1000.0 else 0.0
                    }.sum()
                } else {
                    0.0
                }
                val isDataConsistent = odoDelta <= 0.20 || kotlin.math.abs(odoDelta - usageSum) <= 0.20
                
                if (!isDataConsistent) {
                    FileLogger.log("EBikeBackgroundService: End-of-session calibration skipped due to Odo/Usage mismatch (OdoDelta: ${"%.3f".format(odoDelta)} km, UsageSum: ${"%.3f".format(usageSum)} km). Startup sync will reconcile.")
                }
                
                var totalMotorEnergyDelta = 0.0
                var totalExpectedEffort = 0.0
                
                // 1. Usable Capacity Calibration
                val endSoC = status.batteryLevel
                val endBattery = status.totalBattery
                val startSoC = startupSoC
                val startBattery = startupTotalBattery
                var finalUsableCap = profile.learnedUsableMotorCapacity
                
                if (isDataConsistent && endSoC != null && endBattery != null && startSoC != null && startBattery != null) {
                    val socDrop = startSoC - endSoC
                    if (socDrop >= 3) {
                        val energyDeltaKWh = endBattery - startBattery
                        if (energyDeltaKWh > 0.0) {
                            val measuredCap = (energyDeltaKWh * 100000.0) / socDrop
                            val smoothedCap = (profile.learnedUsableMotorCapacity * 0.90f) + (measuredCap.toFloat() * 0.10f)
                            finalUsableCap = smoothedCap
                            settings.updateBikeUsableCapacity(mac, smoothedCap)
                            FileLogger.log("EBikeBackgroundService: Calibrated Usable Capacity at session end: Wh delta: ${"%.2f".format(energyDeltaKWh * 1000.0)}, SoC drop: $socDrop% -> Measured Usable Capacity: ${"%.1f".format(measuredCap)} Wh (smoothed: ${"%.1f".format(smoothedCap)} Wh)")
                        }
                    }
                }

                if (isDataConsistent && currentUsage.size == expectedCount && localStartupUsageRecords.size == expectedCount) {
                    for (i in 1 until expectedCount) { // Skip Index 0 (OFF)
                        val cur = currentUsage[i]
                        val start = localStartupUsageRecords.getOrNull(i)
                        if (cur != null && start != null) {
                            val distDeltaMeters = cur.distance - start.distance
                            val energyDeltaWh = cur.energy - start.energy
                            
                            if (distDeltaMeters >= 500) {
                                val distDeltaKm = distDeltaMeters / 1000.0
                                val measuredRate = energyDeltaWh / distDeltaKm
                                rates[i] = (rates[i] * 0.90f) + (measuredRate.toFloat() * 0.10f)
                                FileLogger.log("EBikeBackgroundService: Calibrated Consumption Rate for Mode $i: ${"%.2f".format(measuredRate)} Wh/km")
                            }
                            FileLogger.log("EBikeBackgroundService: Mode $i session delta: dist=${distDeltaMeters}m, energy=${energyDeltaWh}Wh (start=${start.energy}Wh, cur=${cur.energy}Wh)")
                            
                            totalMotorEnergyDelta += energyDeltaWh
                            
                            val rate = rates.getOrNull(i) ?: 8.0f
                            if (rate > 0.0f) {
                                totalExpectedEffort += energyDeltaWh * (5.0 / rate)
                            }
                        }
                    }
                }
                if (rates.isNotEmpty()) rates[0] = 0.0f
                
                var currentGamma = profile.learnedUserEffortCoefficient
                val currentTripPedal = status.tripPedalEnergy ?: localStartupTripPedal ?: profile.lastTripPedalEnergy
                val currentTripTotal = status.tripTotalEnergy ?: startupTripTotal ?: profile.lastTripRideEnergy
                val pedalDelta = currentTripPedal - (localStartupTripPedal ?: 0)
                
                if (isDataConsistent && totalMotorEnergyDelta >= 10.0 && pedalDelta > 0 && totalExpectedEffort > 0.0) {
                    val measuredGamma = pedalDelta / totalExpectedEffort
                    val boundedGamma = measuredGamma.coerceIn(0.2, 5.0)
                    currentGamma = ((currentGamma * 0.90) + (boundedGamma * 0.10)).toFloat()
                    FileLogger.log("EBikeBackgroundService: Calibrated Effort Gamma: Actual: $pedalDelta kcal, Expected: ${"%.2f".format(totalExpectedEffort)} Wh-equivalent -> Gamma: ${"%.3f".format(boundedGamma)} (smoothed: ${"%.3f".format(currentGamma)})")
                }
                
                if (isDataConsistent) {
                    settings.updateBikeLearningParams(mac, currentGamma, rates)
                }
                
                var simulatedKcalSum = 0.0
                if (currentUsage.size == expectedCount && localStartupUsageRecords.size == expectedCount) {
                    for (i in 1 until expectedCount) { // Skip Index 0 (OFF)
                        val cur = currentUsage[i]
                        val start = localStartupUsageRecords.getOrNull(i)
                        if (cur != null && start != null) {
                            val energyDeltaWh = cur.energy - start.energy
                            val rate = rates.getOrNull(i) ?: 8.0f
                            if (rate > 0.0f) {
                                simulatedKcalSum += currentGamma * energyDeltaWh * (5.0 / rate)
                            }
                        }
                    }
                }
                
                // Write final references on disconnect to prevent offline double-counting
                settings.updateActiveRideReferences(
                    mac = mac,
                    currentOdo = currentOdo,
                    currentTotalBattery = endBattery ?: profile.lastTotalMotorEnergy,
                    currentTripPedal = currentTripPedal,
                    currentTripRide = currentTripTotal,
                    tripDistPerMode = status.tripDistPerMode,
                    usageRecords = status.sortedUsageRecordsB
                )
                
                val errorPct = if (pedalDelta > 0) {
                    ((simulatedKcalSum - pedalDelta) / pedalDelta) * 100.0
                } else {
                    0.0
                }
                
                val modesDistsStr = if (currentUsage.size == expectedCount && localStartupUsageRecords.size == expectedCount) {
                    currentUsage.mapIndexed { i, cur -> 
                        val start = localStartupUsageRecords.getOrNull(i)
                        val dist = if (cur != null && start != null) (cur.distance - start.distance) / 1000.0 else 0.0
                        "%.3f".format(dist)
                    }.joinToString("/")
                } else {
                    ""
                }
                
                val rangesStr = status.remainingRanges.joinToString("/")
                
                FileLogger.log("[ESTIMATOR_DIAGNOSTIC] SessionEnded | OdoDelta: ${"%.3f".format(odoDelta)} | ModeDists: [$modesDistsStr] | RemainingRanges: [$rangesStr] | SoC: ${status.batteryLevel}% | UsableCap: ${"%.1f".format(finalUsableCap)} | LearnedGamma: ${"%.3f".format(currentGamma)} | ActualKcal: $pedalDelta | EstKcal: ${"%.1f".format(simulatedKcalSum)} | Error: ${"%.1f".format(errorPct)}%")
                FileLogger.log("EBikeBackgroundService: End-of-Session Baselines - Measured: ${"%.1f".format(profile.totalPedalEnergyMeasuredBaseline)} kcal, Estimated: ${"%.1f".format(profile.totalPedalEnergyEstimatedBaseline)} kcal (Live Session Trip: $currentTripPedal kcal)")
                val measuredTotal = profile.totalPedalEnergyMeasuredBaseline + currentTripPedal
                val estimatedTotal = profile.totalPedalEnergyEstimatedBaseline
                val overallTotal = measuredTotal + estimatedTotal
                val formattedOdo = if (currentOdo != null) "%.3f".format(currentOdo) else "null"
                FileLogger.log("[MQTT_DRYRUN] Trigger: BLE Disconnect | Odo: $formattedOdo km | Topics: pedal_energy_measured = ${"%.1f".format(measuredTotal)} kcal | pedal_energy_estimated = ${"%.1f".format(estimatedTotal)} kcal | pedal_energy_total = ${"%.1f".format(overallTotal)} kcal")
                
            } catch (e: Exception) {
                FileLogger.log("EBikeBackgroundService: End-of-session calibration error: ${e.message}")
            }
        }
    }

    private suspend fun getLearnedUsableCapacity(mac: String): Float {
        val profiles = (application as EBikeApplication).settingsRepository.bikeProfiles.first()
        val profile = profiles.find { it.macAddress == mac }
        return profile?.learnedUsableMotorCapacity ?: 500.0f
    }
}
