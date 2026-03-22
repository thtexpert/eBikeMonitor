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
import com.example.ebikemonitor.data.parser.BoschParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

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
                Log.e("BleManager", "GATT Error: $status")
                _isConnected.value = false
                gatt.close()
                bluetoothGatt = null
                return
            }

            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d("BleManager", "Connected to GATT")
                _isConnected.value = true
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.d("BleManager", "Disconnected from GATT")
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
                0x809C -> currentStatus = currentStatus.copy(totalBattery = msg.value / 1000.0)
                0x206B -> {
                    val raw = msg.rawBytes
                    if (raw.size >= 9 && raw[7] == 0x0A.toByte()) {
                        val strLen = raw[8].toInt() and 0xFF
                        if (raw.size >= 9 + strLen) {
                            val strBytes = raw.subList(9, 9 + strLen).toByteArray()
                            currentStatus = currentStatus.copy(ebikeLedSoftwareVersion = String(strBytes, Charsets.UTF_8))
                        }
                    }
                }
                0x180C -> {
                    val decodedModes = msg.decodeAssistModes()
                    if (decodedModes.isNotEmpty()) {
                        currentStatus = currentStatus.copy(assistModeNames = decodedModes)
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
}
