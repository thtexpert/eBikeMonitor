package com.example.ebikemonitor.data.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.ebikemonitor.data.model.BikeStatus
import com.example.ebikemonitor.data.model.UsageRecord
import com.example.ebikemonitor.data.model.BikeProfile
import com.example.ebikemonitor.data.parser.BoschParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import com.example.ebikemonitor.FileLogger

@SuppressLint("MissingPermission") // Permissions are checked in UI/ViewModel
class BleManager(private val context: Context) {

    private val bluetoothAdapter: BluetoothAdapter? = 
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var bluetoothGatt: BluetoothGatt? = null
    
    private val BOSCH_SERVICE_UUID = UUID.fromString("00000010-eaa2-11e9-81b4-2a2ae2dbcce4")
    private val BOSCH_CHAR_UUID = UUID.fromString("00000011-eaa2-11e9-81b4-2a2ae2dbcce4")
    private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _scanResults = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scanResults = _scanResults.asStateFlow()

    private val _bikeStatus = MutableStateFlow(BikeStatus())
    val bikeStatus = _bikeStatus.asStateFlow()

    private val unsortedModeUsageList = mutableListOf<UsageRecord>()
    private val lastLoggedDiagnosticValues = mutableMapOf<Int, Int>()
    private var lastUsageRecordTimestamp: Long = 0L
    private val BURST_TIMEOUT_MS = 2000L
    private val DISCOVERY_INTERVAL_MS = 150L
    private var bleBuffer = ByteArray(0)

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val current = _scanResults.value
            if (!current.any { it.address == device.address }) {
                _scanResults.value = current + device
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                val description = when (status) {
                    19  -> "GATT_CONN_TERMINATE_PEER_USER — remote device closed connection"
                    22  -> "GATT_CONN_TERMINATE_LOCAL_HOST — local stack closed connection"
                    8   -> "GATT_CONN_TIMEOUT — connection supervision timeout"
                    133 -> "GATT_ERROR (133) — generic Android BLE stack error, often a stale handle"
                    147 -> "GATT_ERROR (147) — too many open BLE connections or Android internal error"
                    else -> "GATT status $status"
                }
                val errorMsg = "BleManager: GATT Error: $status ($description)"
                Log.e("BleManager", errorMsg)
                FileLogger.log(errorMsg)
                _isConnected.value = false
                gatt.close()
                bluetoothGatt = null
                lastLoggedDiagnosticValues.clear()
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                FileLogger.log("BleManager: Connected to GATT")
                _isConnected.value = true
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                FileLogger.log("BleManager: Disconnected from GATT")
                _isConnected.value = false
                gatt.close()
                bluetoothGatt = null
                lastLoggedDiagnosticValues.clear()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val service = gatt.getService(BOSCH_SERVICE_UUID)
                val characteristic = service?.getCharacteristic(BOSCH_CHAR_UUID)
                if (characteristic != null) {
                    gatt.setCharacteristicNotification(characteristic, true)
                    val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
                    descriptor?.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                Log.d("BleManager", "Notifications enabled. Ready for passive sync.")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == BOSCH_CHAR_UUID) {
                val newData = characteristic.value ?: return
                
                // 1. Add new fragments to the buffer
                bleBuffer = bleBuffer + newData
                
                // 2. Try to parse the combined data
                val (messages, consumed) = BoschParser.parse(bleBuffer)
                
                if (messages.isNotEmpty()) {
                    updateBikeStatus(messages)
                }
                
                // 3. Keep only the remaining bytes if the last message was parsed partially
                if (consumed > 0) {
                    bleBuffer = bleBuffer.drop(consumed).toByteArray()
                }

                if (bleBuffer.size > 2048) {
                    // Buffer safety valve
                    Log.w("BleManager", "Buffer overflow, clearing ${bleBuffer.size} bytes")
                    bleBuffer = ByteArray(0)
                }
            }
        }
    }

    private fun updateBikeStatus(messages: List<com.example.ebikemonitor.data.parser.BoschMessage>) {
        var currentStatus = _bikeStatus.value
        
        for (msg in messages) {
            if (msg.messageId in listOf(0x808A, 0x80C4, 0x808E, 0x808B, 0x8091, 0x8092, 0x809C, 0x80B0)) {
                val lastVal = lastLoggedDiagnosticValues[msg.messageId]
                if (lastVal != msg.value) {
                    lastLoggedDiagnosticValues[msg.messageId] = msg.value
                    val desc = when (msg.messageId) {
                        0x808A -> "Charging Active (0x808A)"
                        0x80C4 -> "Charger Connected (0x80C4)"
                        0x808E -> "Battery State (0x808E)"
                        0x808B -> "Battery Temperature (0x808B)"
                        0x8091 -> "Usable Motor Energy (0x8091)"
                        0x8092 -> "Total Battery Energy (0x8092)"
                        0x809C -> "Lifetime Discharged Energy (0x809C)"
                        0x80B0 -> "Total Electric Energy (0x80B0)"
                        else -> "Unknown"
                    }
                    Log.d("BleManager", "[DIAGNOSTIC] $desc = ${msg.value}")
                }
            }

            when (msg.messageId) {
                0x808E -> currentStatus = currentStatus.copy(isCharging = msg.value == 12)
                0x808A -> {
                    if (msg.value == 1) {
                        currentStatus = currentStatus.copy(isCharging = true)
                    }
                }
                0x80C4 -> {
                    if (msg.value == 1) {
                        currentStatus = currentStatus.copy(isCharging = true)
                    }
                }
                0x808B -> {
                    val rawTemp = msg.value
                    val correctedTemp = if (rawTemp > 32767) (rawTemp - 65536) else rawTemp
                    currentStatus = currentStatus.copy(batteryTemperature = correctedTemp / 20.0)
                }
                0x982D -> {
                    val speed = msg.value / 100.0
                    currentStatus = currentStatus.copy(speed = speed)
                    // Log.v("BleManager", "Speed update: $speed")
                }
                0x985A -> {
                    val cadence = msg.value / 2
                    currentStatus = currentStatus.copy(cadence = cadence)
                    Log.d("BleManager", "Cadence update: $cadence (raw: ${msg.value})")
                }
                0x985B -> {
                    val power = msg.value
                    currentStatus = currentStatus.copy(humanPower = power)
                    Log.d("BleManager", "Human power update: $power")
                }
                0x985D -> {
                    val motorPower = msg.value
                    currentStatus = currentStatus.copy(motorPower = motorPower)
                    Log.d("BleManager", "Motor power update: $motorPower")
                }
                0x80BC -> currentStatus = currentStatus.copy(batteryLevel = msg.value)
                0x9809 -> currentStatus = currentStatus.copy(assistMode = msg.value)
                0x9818 -> {
                    val odo = msg.value / 1000.0
                    currentStatus = currentStatus.copy(totalDistance = odo)
                    Log.d("BleManager", "Raw Odometer received: ${"%.3f".format(odo)} km (raw: ${msg.value})")
                }
                0x8096 -> currentStatus = currentStatus.copy(chargeCycles = msg.value / 10.0)
                0x8091 -> {
                    val usableEnergy = msg.value / 10.0
                    currentStatus = currentStatus.copy(usableEnergyRemaining = usableEnergy)
                    Log.d("BleManager", "Usable motor energy update: $usableEnergy Wh")
                }
                0x80B4 -> {
                    val nominalCap = msg.value / 10.0
                    currentStatus = currentStatus.copy(nominalCapacity = nominalCap)
                    Log.d("BleManager", "Nominal capacity update: $nominalCap Wh")
                }
                0x80D9 -> {
                    val usableCap = msg.value / 10.0
                    currentStatus = currentStatus.copy(usableCapacity = usableCap)
                    Log.d("BleManager", "Actual usable capacity update: $usableCap Wh")
                }
                0xA243 -> {
                    currentStatus = currentStatus.copy(
                        humanCalories = msg.value / 10.0,
                        tripTotalEnergy = msg.value * 0.05
                    )
                }
                0xA246 -> currentStatus = currentStatus.copy(tripSpeed = msg.value / 100.0)
                0xA247 -> currentStatus = currentStatus.copy(tripMaxSpeed = msg.value / 100.0)
                0xA248 -> currentStatus = currentStatus.copy(tripCadence = msg.value / 2)
                0xA249 -> currentStatus = currentStatus.copy(tripMaxCadence = msg.value / 2)
                0xA24A -> currentStatus = currentStatus.copy(tripHumanPower = msg.value)
                0xA24B -> currentStatus = currentStatus.copy(tripMaxHumanPower = msg.value)
                0xA251 -> {
                    currentStatus = currentStatus.copy(tripPedalEnergy = msg.value)
                    Log.d("BleManager", "Trip Pedal Energy updated: ${msg.value} kcal")
                }
                0xA254 -> {
                    currentStatus = currentStatus.copy(tripHumanPercentage = msg.value)
                    Log.d("BleManager", "Trip Human Percentage updated: ${msg.value}%")
                }
                0x9857 -> {
                    val ranges = msg.decodeRanges()
                    if (ranges != null) {
                        currentStatus = currentStatus.copy(remainingRanges = ranges)
                        Log.d("BleManager", "Remaining Ranges updated: $ranges")
                    }
                }
                0x9819 -> currentStatus = currentStatus.copy(driveUnitHours = msg.value)
                0x809C -> currentStatus = currentStatus.copy(totalBattery = msg.value / 1000.0)
                0x80B0 -> currentStatus = currentStatus.copy(totalElectricEnergy = msg.value * 0.1)
                0x206B -> {
                    val rawVersion = msg.decodeStringField()
                    val softwareVersion = com.example.ebikemonitor.data.parser.BoschParser.formatSoftwareVersion(rawVersion)
                    if (softwareVersion != null) {
                        currentStatus = currentStatus.copy(ledRemoteSoftwareVersion = softwareVersion)
                        Log.d("BleManager", "LED Remote Software Version decoded: $softwareVersion (raw: $rawVersion)")
                        com.example.ebikemonitor.FileLogger.log("BleManager: LED Remote Software Version decoded: $softwareVersion")
                    }
                }
                0x0086 -> {
                    val rawVersion = msg.decodeStringField()
                    val softwareVersion = com.example.ebikemonitor.data.parser.BoschParser.formatSoftwareVersion(rawVersion)
                    if (softwareVersion != null) {
                        currentStatus = currentStatus.copy(batterySoftwareVersion = softwareVersion)
                        Log.d("BleManager", "Battery Software Version decoded: $softwareVersion (raw: $rawVersion)")
                        com.example.ebikemonitor.FileLogger.log("BleManager: Battery Software Version decoded: $softwareVersion")
                    }
                }
                0x1806 -> {
                    val rawVersion = msg.decodeStringField()
                    val softwareVersion = com.example.ebikemonitor.data.parser.BoschParser.formatSoftwareVersion(rawVersion)
                    if (softwareVersion != null) {
                        currentStatus = currentStatus.copy(driveUnitSoftwareVersion = softwareVersion)
                        Log.d("BleManager", "Drive Unit Software Version decoded: $softwareVersion (raw: $rawVersion)")
                        com.example.ebikemonitor.FileLogger.log("BleManager: Drive Unit Software Version decoded: $softwareVersion")
                    }
                }
                0x0D06 -> {
                    val rawVersion = msg.decodeStringField()
                    val softwareVersion = com.example.ebikemonitor.data.parser.BoschParser.formatSoftwareVersion(rawVersion)
                    if (softwareVersion != null) {
                        currentStatus = currentStatus.copy(displaySoftwareVersion = softwareVersion)
                        Log.d("BleManager", "Display Software Version decoded: $softwareVersion (raw: $rawVersion)")
                        com.example.ebikemonitor.FileLogger.log("BleManager: Display Software Version decoded: $softwareVersion")
                    }
                }
                0x182E -> {
                    val model = msg.decodeStringField()
                    if (model != null) {
                        currentStatus = currentStatus.copy(driveUnitModel = model)
                        Log.d("BleManager", "Drive Unit Model decoded: $model")
                        com.example.ebikemonitor.FileLogger.log("BleManager: Drive Unit Model decoded: $model")
                    }
                }
                0x1803 -> {
                    val code = msg.decodeStringField()
                    if (code != null) {
                        currentStatus = currentStatus.copy(driveUnitProductCode = code)
                        Log.d("BleManager", "Drive Unit Product Code decoded: $code")
                        com.example.ebikemonitor.FileLogger.log("BleManager: Drive Unit Product Code decoded: $code")
                    }
                }
                0x0083 -> {
                    val partNum = msg.decodeStringField()
                    if (partNum != null) {
                        currentStatus = currentStatus.copy(batteryPartNumber = partNum)
                        Log.d("BleManager", "Battery Part Number decoded: $partNum")
                        com.example.ebikemonitor.FileLogger.log("BleManager: Battery Part Number decoded: $partNum")
                    }
                }
                0x0081 -> {
                    val batterySerial = msg.decodeStringField()
                    if (batterySerial != null) {
                        currentStatus = currentStatus.copy(batterySerialNumber = batterySerial)
                        Log.d("BleManager", "Battery Serial decoded: ${currentStatus.batterySerialNumber}")
                    }
                }
                0x009B -> {
                    val model = msg.decodeStringField()
                    if (model != null) {
                        currentStatus = currentStatus.copy(batteryModel = model)
                        Log.d("BleManager", "Battery Model decoded: $model")
                    }
                }
                0x180C -> {
                    val decodedModes = msg.decodeAssistModes()
                    if (decodedModes.isNotEmpty()) {
                        currentStatus = currentStatus.copy(assistModeNames = decodedModes)
                    }
                }
                0xA252 -> {
                    val tripDists = msg.decodeTripDistPerMode()
                    if (tripDists != null) {
                        currentStatus = currentStatus.copy(tripDistPerMode = tripDists)
                        Log.d("BleManager", "Trip distances updated: $tripDists")
                        
                        // Retry Startup Decoding if it was previously deferred
                        val expectedCount = currentStatus.assistModeNames.size
                        if (currentStatus.initialTripDistPerMode == null && 
                            currentStatus.persistentBaselines != null && 
                            unsortedModeUsageList.size == expectedCount && 
                            expectedCount > 0) {
                            
                            val result = BoschParser.findBestStartupMapping(
                                newBatch = unsortedModeUsageList,
                                currentTrip = tripDists,
                                storedBaselines = currentStatus.persistentBaselines!!
                            )
                            
                            currentStatus = currentStatus.copy(
                                startupDecodingStatus = result.status,
                                startupError = result.bestError,
                                startupSecondaryError = result.secondaryError
                            )

                            if (result.mapping != null) {
                                Log.d("BleManager", "Version B: Startup decoding successful (${result.status}) via late A252! Initializing baseline from storage. Error: ${result.bestError}")
                                com.example.ebikemonitor.FileLogger.log("BleManager: Startup decoding successful (${result.status}) via late A252! Error: ${result.bestError}")
                                
                                val newSortedRecords = ArrayList<UsageRecord?>(List(currentStatus.persistentBaselines!!.size) { null })
                                result.mapping.forEach { (modeIdx, batchIdx) ->
                                    newSortedRecords[modeIdx] = unsortedModeUsageList[batchIdx]
                                }

                                currentStatus = currentStatus.copy(
                                    initialTripDistPerMode = tripDists.toList(),
                                    initialUnsortedUsageRecords = unsortedModeUsageList.toList(),
                                    modeToInitialIndex = result.mapping,
                                    confirmedModeIndices = result.mapping.keys,
                                    sortedUsageRecordsB = newSortedRecords
                                )
                                
                                // Also trigger Version B update with discovery
                                val resultB = BoschParser.processVersionBWithDiscovery(unsortedModeUsageList, currentStatus)
                                if (resultB != null) {
                                    val (sortedB, confirmed, mapping) = resultB
                                    currentStatus = currentStatus.copy(
                                        sortedUsageRecordsB = sortedB,
                                        confirmedModeIndices = confirmed,
                                        modeToInitialIndex = mapping
                                    )
                                    Log.d("BleManager", "Version B Late Update: Confirmed = $confirmed, Mappings = $mapping")
                                }
                            } else {
                                Log.w("BleManager", "Version B: Startup decoding ritual did not succeed via late A252: ${result.status}")
                            }
                        }
                    }
                }
                0x108C -> {
                    val record = msg.decodeUsageRecord()
                        if (record != null) {
                            val now = System.currentTimeMillis()
                            if (now - lastUsageRecordTimestamp > BURST_TIMEOUT_MS && unsortedModeUsageList.isNotEmpty()) {
                                Log.d("BleManager", "Usage burst timeout (${now - lastUsageRecordTimestamp}ms). Clearing stale records: ${unsortedModeUsageList.size}")
                                unsortedModeUsageList.clear()
                            }
                            lastUsageRecordTimestamp = now

                            val expectedCount = if (currentStatus.assistModeNames.isNotEmpty()) {
                                currentStatus.assistModeNames.size
                            } else {
                                5
                            }

                            // Start a new batch if we've already reached the expected count
                            if (unsortedModeUsageList.size >= expectedCount) {
                                unsortedModeUsageList.clear()
                            }
                            unsortedModeUsageList.add(record)
                            
                            val sumKWh = if (unsortedModeUsageList.size == expectedCount) {
                                unsortedModeUsageList.sumOf { it.energy.toDouble() } / 1000.0
                            } else {
                                null
                            }

                            val batchValid = sumKWh != null && isValidUsageBatch(unsortedModeUsageList, currentStatus.totalDistance, expectedCount)

                            currentStatus = if (batchValid && sumKWh != null) {
                                currentStatus.copy(
                                    unsortedUsageRecords = unsortedModeUsageList.toList(),
                                    totalEnergyFromMotor = sumKWh
                                )
                            } else {
                                currentStatus.copy(
                                    unsortedUsageRecords = unsortedModeUsageList.toList()
                                )
                            }
                            
                            if (unsortedModeUsageList.size == expectedCount) {
                             Log.d("BleManager", "Batch of $expectedCount usage records complete: $unsortedModeUsageList")
                             val sumDist = unsortedModeUsageList.sumOf { it.distance.toDouble() } / 1000.0
                             val sumEnergy = sumKWh ?: 0.0
                             val modesDetail = unsortedModeUsageList.mapIndexed { idx, record ->
                                 "Mode$idx: ${"%.3f".format(record.distance / 1000.0)} km"
                             }.joinToString(", ")
                             com.example.ebikemonitor.FileLogger.log("BleManager: Raw Usage Batch complete: SumDist = ${"%.3f".format(sumDist)} km ($modesDetail), SumEnergy = ${"%.3f".format(sumEnergy)} kWh, Valid = $batchValid, BikeOdo = ${currentStatus.totalDistance} km")
                            
                            // 1. Startup Decoding Ritual (Persistent Storage)
                            if (currentStatus.initialTripDistPerMode == null && currentStatus.persistentBaselines != null) {
                                if (currentStatus.tripDistPerMode.isEmpty()) {
                                    Log.d("BleManager", "Version B: Deferring Startup Decoding until Trip Distances (A252) arrive")
                                    com.example.ebikemonitor.FileLogger.log("BleManager: Deferring Startup Decoding until Trip Distances (A252) arrive")
                                } else {
                                    val result = BoschParser.findBestStartupMapping(
                                        newBatch = unsortedModeUsageList,
                                        currentTrip = currentStatus.tripDistPerMode,
                                        storedBaselines = currentStatus.persistentBaselines!!
                                    )
                                    
                                    currentStatus = currentStatus.copy(
                                        startupDecodingStatus = result.status,
                                        startupError = result.bestError,
                                        startupSecondaryError = result.secondaryError
                                    )

                                    if (result.mapping != null) {
                                        Log.d("BleManager", "Version B: Startup decoding successful (${result.status})! Initializing baseline from storage. Error: ${result.bestError}")
                                        
                                        val newSortedRecords = ArrayList<UsageRecord?>(List(currentStatus.persistentBaselines!!.size) { null })
                                        result.mapping.forEach { (modeIdx, batchIdx) ->
                                            newSortedRecords[modeIdx] = unsortedModeUsageList[batchIdx]
                                        }

                                        currentStatus = currentStatus.copy(
                                            initialTripDistPerMode = currentStatus.tripDistPerMode.toList(),
                                            initialUnsortedUsageRecords = unsortedModeUsageList.toList(),
                                            modeToInitialIndex = result.mapping,
                                            confirmedModeIndices = result.mapping.keys,
                                            sortedUsageRecordsB = newSortedRecords
                                        )
                                    } else {
                                        Log.w("BleManager", "Version B: Startup decoding ritual did not succeed: ${result.status}")
                                    }
                                }
                            } else if (currentStatus.persistentBaselines == null) {
                                currentStatus = currentStatus.copy(startupDecodingStatus = "NO_STORED_BASELINES")
                            }

                            // 2. Normal Capture/Fallback: Session baseline if still not established
                            if (currentStatus.initialTripDistPerMode == null && currentStatus.tripDistPerMode.size == expectedCount) {
                                Log.d("BleManager", "Version B: Capturing initial trip baseline.")
                                currentStatus = currentStatus.copy(
                                    initialTripDistPerMode = currentStatus.tripDistPerMode.toList(),
                                    initialUnsortedUsageRecords = unsortedModeUsageList.toList()
                                )
                            }

                            // Version A: Consumption-based
                            val sortedA = BoschParser.processUsageRecords(unsortedModeUsageList)
                            if (sortedA != null) {
                                currentStatus = currentStatus.copy(sortedUsageRecordsA = sortedA)
                                Log.d("BleManager", "Version A successful: $sortedA")
                            }

                            // Version B: Cumulative Delta-based with Discovery Mapping
                            val resultB = BoschParser.processVersionBWithDiscovery(unsortedModeUsageList, currentStatus)
                            if (resultB != null) {
                                val (sortedB, confirmed, mapping) = resultB
                                currentStatus = currentStatus.copy(
                                    sortedUsageRecordsB = sortedB,
                                    confirmedModeIndices = confirmed,
                                    modeToInitialIndex = mapping
                                )
                                Log.d("BleManager", "Version B Update: Confirmed = $confirmed, Mappings = $mapping")
                            } else {
                                // Check for Trip Reset relative to the INITIAL baseline
                                val cur = currentStatus.tripDistPerMode
                                val initial = currentStatus.initialTripDistPerMode
                                if (initial != null && cur.size == initial.size && cur.indices.any { cur[it] < initial[it] }) {
                                    Log.d("BleManager", "Version B: Trip Reset detected relative to baseline. Clearing B history.")
                                    currentStatus = currentStatus.copy(
                                        initialTripDistPerMode = null,
                                        initialUnsortedUsageRecords = null,
                                        modeToInitialIndex = emptyMap(),
                                        sortedUsageRecordsB = emptyList(),
                                        confirmedModeIndices = emptySet(),
                                        prevTripDistPerMode = null,
                                        prevUnsortedUsageRecords = null
                                    )
                                }
                            }

                            // Update cycle history
                            currentStatus = currentStatus.copy(
                                prevTripDistPerMode = currentStatus.tripDistPerMode.toList(),
                                prevUnsortedUsageRecords = unsortedModeUsageList.toList()
                            )
                        }
                    }
                }
            }
        }
        _bikeStatus.value = currentStatus.copy(lastUpdateTimestamp = System.currentTimeMillis())
    }


    private fun isValidUsageBatch(records: List<UsageRecord>, totalDistance: Double?, expectedCount: Int): Boolean {
        if (records.size != expectedCount) return false
        
        // 1. Uniqueness check for non-zero distances
        val nonZeroDistances = records.map { it.distance }.filter { it > 0 }
        if (nonZeroDistances.distinct().size != nonZeroDistances.size) {
            Log.w("BleManager", "Usage batch rejected: Duplicate non-zero distances found: $nonZeroDistances")
            return false
        }

        // 2. Odometer cross-check
        if (totalDistance != null && totalDistance > 0) {
            val sumDistKm = records.sumOf { it.distance.toDouble() } / 1000.0
            // Allow 1.0 km tolerance for sync delays
            if (kotlin.math.abs(sumDistKm - totalDistance) > 1.0) {
                Log.w("BleManager", "Usage batch rejected: Odometer mismatch. Batch Sum: ${sumDistKm}km, Bike Odo: ${totalDistance}km")
                return false
            }
        }
        
        return true
    }

    fun startScan() {
        if (bluetoothAdapter?.isEnabled == true) {
            _scanResults.value = emptyList()
            bluetoothAdapter.bluetoothLeScanner?.startScan(scanCallback)
        }
    }

    fun stopScan() {
        if (bluetoothAdapter?.isEnabled == true) {
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }
    
    fun getBondedDevices(): List<BluetoothDevice> {
        return bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
    }

    val isConnectingOrConnected: Boolean
        get() = bluetoothGatt != null

    fun connect(macAddress: String) {
        if (bluetoothAdapter == null) return
        if (_isConnected.value || bluetoothGatt != null) {
            Log.d("BleManager", "Already connected or connecting. Ignoring request.")
            return
        }
        val device = bluetoothAdapter.getRemoteDevice(macAddress)
        bluetoothGatt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        _isConnected.value = false
        lastLoggedDiagnosticValues.clear()
    }

    fun clearTelemetryCache() {
        // Clear session tracking variables so the next connection starts fresh
        _bikeStatus.value = _bikeStatus.value.copy(
            initialTripDistPerMode = null,
            initialUnsortedUsageRecords = null,
            modeToInitialIndex = emptyMap(),
            confirmedModeIndices = emptySet(),
            startupDecodingStatus = null,
            startupError = null,
            startupSecondaryError = null,
            prevTripDistPerMode = null,
            prevUnsortedUsageRecords = null,
            lastUpdateTimestamp = 0L,
            // Clear telemetry cache to avoid corrupting next session startup decoding
            tripDistPerMode = emptyList(),
            sortedUsageRecordsB = emptyList(),
            sortedUsageRecordsA = emptyList(),
            unsortedUsageRecords = emptyList(),
            totalDistance = null,
            tripPedalEnergy = null,
            tripTotalEnergy = null,
            batteryLevel = null,
            totalBattery = null,
            remainingRanges = emptyList(),
            persistentBaselines = null
        )
    }

    fun isBluetoothEnabled(): Boolean {
        return bluetoothAdapter?.isEnabled == true
    }

    fun setAssistModeNames(names: List<String>) {
        if (_bikeStatus.value.assistModeNames.isEmpty() && names.isNotEmpty()) {
            _bikeStatus.value = _bikeStatus.value.copy(assistModeNames = names)
        }
    }

    fun setPersistentBaselines(baselines: List<Int>) {
        if (_bikeStatus.value.persistentBaselines == null) {
            _bikeStatus.value = _bikeStatus.value.copy(persistentBaselines = baselines)
            Log.d("BleManager", "Persistent baselines loaded: $baselines")
        }
    }

    fun prePopulateFromProfile(profile: BikeProfile) {
        val currentStatus = _bikeStatus.value
        val expectedCount = profile.lastUsageRecords.size
        
        // Calculate persistent baselines from usage history
        val baselines = if (profile.lastUsageRecords.isNotEmpty()) {
            profile.lastUsageRecords.mapIndexed { i, usage ->
                val trip = profile.lastTripDistPerMode.getOrNull(i) ?: 0
                (usage?.distance ?: 0) - trip
            }
        } else {
            null
        }

        val hasValidUsage = profile.lastUsageRecords.isNotEmpty() && profile.lastUsageRecords.all { it != null }
        
        _bikeStatus.value = currentStatus.copy(
            totalDistance = if (currentStatus.totalDistance == null || currentStatus.totalDistance == 0.0) profile.lastOdometer else currentStatus.totalDistance,
            tripPedalEnergy = if (currentStatus.tripPedalEnergy == null) profile.lastTripPedalEnergy else currentStatus.tripPedalEnergy,
            tripTotalEnergy = if (currentStatus.tripTotalEnergy == null) profile.lastTripRideEnergy else currentStatus.tripTotalEnergy,
            totalBattery = if (currentStatus.totalBattery == null) profile.lastTotalMotorEnergy else currentStatus.totalBattery,
            tripDistPerMode = if (currentStatus.tripDistPerMode.isEmpty()) profile.lastTripDistPerMode else currentStatus.tripDistPerMode,
            sortedUsageRecordsB = if (currentStatus.sortedUsageRecordsB.isEmpty() && hasValidUsage) profile.lastUsageRecords.toList() else currentStatus.sortedUsageRecordsB,
            persistentBaselines = baselines,
            // If we successfully pre-populated usage records, set confirmed indices and mapping
            confirmedModeIndices = if (currentStatus.confirmedModeIndices.isEmpty() && hasValidUsage && expectedCount > 0) {
                (0 until expectedCount).toSet()
            } else {
                currentStatus.confirmedModeIndices
            },
            modeToInitialIndex = if (currentStatus.modeToInitialIndex.isEmpty() && hasValidUsage && expectedCount > 0) {
                (0 until expectedCount).map { it to it }.toMap()
            } else {
                currentStatus.modeToInitialIndex
            }
        )
        Log.d("BleManager", "Pre-populated BLE status from profile: totalDistance=${_bikeStatus.value.totalDistance}, tripPedal=${_bikeStatus.value.tripPedalEnergy}, persistentBaselines=${_bikeStatus.value.persistentBaselines}")
    }

    fun forceStartupDecodingWithFallback(fallbackTripDists: List<Int>): Boolean {
        var statusCopy = _bikeStatus.value
        val expectedCount = statusCopy.assistModeNames.size
        if (expectedCount == 0 || statusCopy.unsortedUsageRecords.size != expectedCount || statusCopy.persistentBaselines == null) {
            return false
        }
        
        // Try Option 1: Assuming trip was NOT reset (use fallbackTripDists)
        var result = com.example.ebikemonitor.data.parser.BoschParser.findBestStartupMapping(
            newBatch = statusCopy.unsortedUsageRecords,
            currentTrip = fallbackTripDists,
            storedBaselines = statusCopy.persistentBaselines!!
        )
        var finalTripDists = fallbackTripDists
        
        // Try Option 2: Assuming trip WAS reset (use zeros)
        if (result.mapping == null) {
            result = com.example.ebikemonitor.data.parser.BoschParser.findBestStartupMapping(
                newBatch = statusCopy.unsortedUsageRecords,
                currentTrip = List(expectedCount) { 0 },
                storedBaselines = statusCopy.persistentBaselines!!
            )
            finalTripDists = List(expectedCount) { 0 }
        }
        
        val mapping = result.mapping
        if (mapping != null) {
            val newSortedRecords = ArrayList<com.example.ebikemonitor.data.model.UsageRecord?>(List(expectedCount) { null })
            mapping.forEach { (modeIdx, batchIdx) ->
                newSortedRecords[modeIdx] = statusCopy.unsortedUsageRecords[batchIdx]
            }
            
            _bikeStatus.value = statusCopy.copy(
                startupDecodingStatus = result.status + "_FALLBACK",
                startupError = result.bestError,
                startupSecondaryError = result.secondaryError,
                initialTripDistPerMode = finalTripDists.toList(),
                tripDistPerMode = finalTripDists.toList(),
                initialUnsortedUsageRecords = statusCopy.unsortedUsageRecords.toList(),
                modeToInitialIndex = mapping,
                confirmedModeIndices = mapping.keys,
                sortedUsageRecordsB = newSortedRecords
            )
            com.example.ebikemonitor.FileLogger.log("BleManager: Forced startup decoding successful with fallback! Status: ${result.status}_FALLBACK, Error: ${result.bestError}")
            return true
        } else {
            com.example.ebikemonitor.FileLogger.log("BleManager: Forced startup decoding failed to resolve a mapping with fallback.")
            return false
        }
    }

    fun handleMidRideReset(newTripDists: List<Int>, sortedUsageRecords: List<UsageRecord?>) {
        val expectedCount = _bikeStatus.value.assistModeNames.size
        if (sortedUsageRecords.size == expectedCount && sortedUsageRecords.all { it != null }) {
            val newBaselines = sortedUsageRecords.map { it!!.distance }
            _bikeStatus.value = _bikeStatus.value.copy(
                persistentBaselines = newBaselines,
                initialTripDistPerMode = newTripDists.toList(),
                initialUnsortedUsageRecords = _bikeStatus.value.unsortedUsageRecords.toList(),
                tripDistPerMode = newTripDists.toList()
            )
            Log.d("BleManager", "Version B: Mid-ride reset handled. New baselines: $newBaselines")
        }
    }

    fun updateEstimatedCalories(estKcal: Double?, errorPct: Double?) {
        _bikeStatus.value = _bikeStatus.value.copy(
            activeEstKcal = estKcal,
            activeEstError = errorPct
        )
    }
}
