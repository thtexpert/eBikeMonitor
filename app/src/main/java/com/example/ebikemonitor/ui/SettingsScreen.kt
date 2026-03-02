package com.example.ebikemonitor.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ebikemonitor.viewmodel.MainViewModel

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val bleMac by viewModel.savedBleMac.collectAsState()
    val eBikeName by viewModel.savedEBikeName.collectAsState()
    
    val mqttUri by viewModel.settingsRepository.mqttBrokerUri.collectAsState("")
    val mqttUser by viewModel.settingsRepository.mqttUser.collectAsState("")
    val mqttPass by viewModel.settingsRepository.mqttPassword.collectAsState("")
    
    val autoBle by viewModel.autoConnectBle.collectAsState()
    val autoMqtt by viewModel.settingsRepository.autoConnectMqtt.collectAsState(true)
    val autoLaunch by viewModel.settingsRepository.autoLaunchFlow.collectAsState(false)

    var showDeviceDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onNavigateBack) { Text("Back") }
            Spacer(modifier = Modifier.width(16.dp))
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
             // General
             item {
                 Text("General", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                 
                 var localName by remember { mutableStateOf(eBikeName) }
                 LaunchedEffect(eBikeName) { localName = eBikeName }
                 
                 OutlinedTextField(
                     value = localName,
                     onValueChange = { 
                         localName = it
                         viewModel.updateEBikeName(it) 
                     },
                     label = { Text("eBike Name") },
                     modifier = Modifier.fillMaxWidth()
                 )
             }
             // Auto launch
             item {
                 Text("Launch", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                 Row(verticalAlignment = Alignment.CenterVertically) {
                     Switch(checked = autoMqtt, onCheckedChange = { viewModel.updateAutoConnectMqtt(it) })
                     Spacer(modifier = Modifier.width(8.dp))
                     Text("Auto-Connect MQTT")
                 }

                 Row(verticalAlignment = Alignment.CenterVertically) {
                     Switch(checked = autoBle, onCheckedChange = { viewModel.updateAutoConnectBle(it) })
                     Spacer(modifier = Modifier.width(8.dp))
                     Text("Auto-Connect BLE")
                 }

                 Row(verticalAlignment = Alignment.CenterVertically) {
                     Switch(checked = autoLaunch, onCheckedChange = { viewModel.updateAutoLaunchFlow(it) })
                     Spacer(modifier = Modifier.width(8.dp))
                     Text("Auto-Launch Flow App")
                 }
             }

             // Bluetooth
             item {
                 Text("Bluetooth", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                 
                 Text(text = "Target MAC: ${bleMac ?: "Not Set"}")
                 Button(
                     onClick = { 
                         viewModel.bleManager.startScan()
                         showDeviceDialog = true 
                     }
                 ) {
                     Text("Select Bonded Device")
                 }
             }
             
             // MQTT
             item {
                 Text("MQTT", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                 
                 var uri by remember { mutableStateOf(mqttUri) }
                 var user by remember { mutableStateOf(mqttUser) }
                 var pass by remember { mutableStateOf(mqttPass) }
                 var passVisible by remember { mutableStateOf(false) }
                 
                 // Update local state when flow updates
                 LaunchedEffect(mqttUri) { uri = mqttUri }
                 LaunchedEffect(mqttUser) { user = mqttUser }
                 LaunchedEffect(mqttPass) { pass = mqttPass }
                 
                 OutlinedTextField(
                     value = uri, 
                     onValueChange = { uri = it }, 
                     label = { Text("Broker URI (e.g. tcp://192.168.1.1:1883)") }, 
                     modifier = Modifier.fillMaxWidth()
                 )
                 
                 OutlinedTextField(
                     value = user, 
                     onValueChange = { user = it }, 
                     label = { Text("User (Optional)") }, 
                     modifier = Modifier.fillMaxWidth()
                 )
                 
                 OutlinedTextField(
                     value = pass, 
                     onValueChange = { pass = it }, 
                     label = { Text("Password (Optional)") }, 
                     modifier = Modifier.fillMaxWidth(),
                     visualTransformation = if (passVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                     trailingIcon = {
                         val image = if (passVisible) "👁️" else "🔒" // Using text as icons for simplicity
                         IconButton(onClick = { passVisible = !passVisible }) {
                             Text(image)
                         }
                     }
                 )
                 
                  Button(onClick = { viewModel.updateMqttConfig(uri, user, pass) }) {
                      Text("Save MQTT Config")
                  }

                  Spacer(modifier = Modifier.height(8.dp))

                  Row(
                      verticalAlignment = Alignment.CenterVertically,
                      modifier = Modifier.fillMaxWidth()
                  ) {
                      Text("Discovery Config (homeassistant)", modifier = Modifier.weight(1f))
                      OutlinedButton(onClick = { viewModel.sendHomeAssistantDiscovery() }) {
                          Text("Send")
                      }
                  }
             }
        }
    }
    
    if (showDeviceDialog) {
        DeviceSelectionDialog(
           viewModel = viewModel,
           onDismiss = { 
               viewModel.bleManager.stopScan()
               showDeviceDialog = false 
           },
           onDeviceSelected = { device ->
               viewModel.connectToDevice(device.address)
               // Name update if needed?
               viewModel.bleManager.stopScan()
               showDeviceDialog = false
           }
        )
    }
}

@Composable
fun DeviceSelectionDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
    onDeviceSelected: (android.bluetooth.BluetoothDevice) -> Unit
) {
    val bondedDevices = remember { viewModel.bleManager.getBondedDevices() }
    val scannedDevices by viewModel.bleManager.scanResults.collectAsState()
    
    // Merge or show tabs? For now just show bonded as priority
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select eBike") },
        text = {
            LazyColumn {
                item { Text("Bonded Devices", style = MaterialTheme.typography.titleSmall) }
                items(bondedDevices) { device ->
                    DeviceItem(device, onDeviceSelected)
                }
                
                item { Spacer(modifier = Modifier.height(16.dp)) }
                
                item { Text("Scanned Devices", style = MaterialTheme.typography.titleSmall) }
                if (scannedDevices.isEmpty()) {
                    item { Text("Scanning...", style = MaterialTheme.typography.bodySmall) }
                }
                items(scannedDevices) { device ->
                    DeviceItem(device, onDeviceSelected)
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@SuppressLint("MissingPermission")
@Composable
fun DeviceItem(device: android.bluetooth.BluetoothDevice, onClick: (android.bluetooth.BluetoothDevice) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(device) }
            .padding(8.dp)
    ) {
        Column {
            Text(text = device.name ?: "Unknown", style = MaterialTheme.typography.bodyMedium)
            Text(text = device.address, style = MaterialTheme.typography.bodySmall)
        }
    }
}
