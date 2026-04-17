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
    
    // UUIDs
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
                val errorMsg = "BleManager: GATT Error: $status"
                Log.e("BleManager", errorMsg)
                FileLogger.log(errorMsg)
                _isConnected.value = false
                gatt.close()
                bluetoothGatt = null
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

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == BOSCH_CHAR_UUID) {
                val messages = BoschParser.parse(characteristic.value)
                updateBikeStatus(messages)
            }
        }
    }

    private fun updateBikeStatus(messages: List<com.example.ebikemonitor.data.parser.BoschMessage>) {
        var currentStatus = _bikeStatus.value
        
        for (msg in messages) {
            when (msg.messageId) {
                0x982D -> currentStatus = currentStatus.copy(speed = msg.value / 100.0)
                0x985A -> currentStatus = currentStatus.copy(cadence = msg.value / 2)
                0x985B -> currentStatus = currentStatus.copy(humanPower = msg.value)
                0x985D -> currentStatus = currentStatus.copy(motorPower = msg.value)
                0x80BC -> currentStatus = currentStatus.copy(batteryLevel = msg.value)
                0x9809 -> currentStatus = currentStatus.copy(assistMode = msg.value)
                0x9818 -> currentStatus = currentStatus.copy(totalDistance = msg.value / 1000.0)
                0x9819 -> currentStatus = currentStatus.copy(driveUnitHours = msg.value)
                0x809C -> currentStatus = currentStatus.copy(totalBattery = msg.value / 1000.0)
                0x8096 -> currentStatus = currentStatus.copy(chargeCycles = msg.value / 10.0)
                0x206B -> {
                    val softwareVersion = msg.decodeStringField()
                    if (softwareVersion != null) {
                        currentStatus = currentStatus.copy(ebikeLedSoftwareVersion = softwareVersion)
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
                    }
                }
                0x108C -> {
                    val record = msg.decodeUsageRecord()
                    if (record != null) {
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

                        currentStatus = currentStatus.copy(
                            unsortedUsageRecords = unsortedModeUsageList.toList(),
                            totalEnergyFromMotor = sumKWh
                        )
                        
                        if (unsortedModeUsageList.size == expectedCount) {
                            Log.d("BleManager", "Batch of $expectedCount usage records complete: $unsortedModeUsageList")
                            
                            // 1. Startup Decoding Ritual (Persistent Storage)
                            if (currentStatus.initialTripDistPerMode == null && currentStatus.persistentBaselines != null) {
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
                                        initialTripDistPerMode = if (currentStatus.tripDistPerMode.isEmpty()) {
                                            List(currentStatus.persistentBaselines!!.size) { 0 }
                                        } else {
                                            currentStatus.tripDistPerMode.toList()
                                        },
                                        initialUnsortedUsageRecords = unsortedModeUsageList.toList(),
                                        modeToInitialIndex = result.mapping,
                                        confirmedModeIndices = result.mapping.keys,
                                        sortedUsageRecordsB = newSortedRecords
                                    )
                                } else {
                                    Log.w("BleManager", "Version B: Startup decoding ritual did not succeed: ${result.status}")
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

    fun connect(macAddress: String) {
        if (bluetoothAdapter == null) return
        val device = bluetoothAdapter.getRemoteDevice(macAddress)
        bluetoothGatt = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        bluetoothGatt?.disconnect()
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
}
