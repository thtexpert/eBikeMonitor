package com.example.ebikemonitor.ui

import android.annotation.SuppressLint
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import android.widget.Toast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.ebikemonitor.viewmodel.MainViewModel
import androidx.compose.ui.res.stringResource
import com.example.ebikemonitor.R

@OptIn(ExperimentalMaterial3Api::class)
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

    val context = LocalContext.current
    val mqttError by viewModel.mqttErrorText.collectAsState()
    
    LaunchedEffect(mqttError) {
        if (mqttError.isNotEmpty()) {
            Toast.makeText(context, mqttError, Toast.LENGTH_LONG).show()
        }
    }

    CompositionLocalProvider(
        LocalMinimumInteractiveComponentEnforcement provides false
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
        // Header
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onNavigateBack,
                modifier = Modifier.height(38.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
            ) { Text(stringResource(R.string.btn_back)) }
            Spacer(modifier = Modifier.width(16.dp))
            Text(stringResource(R.string.title_settings), style = MaterialTheme.typography.titleMedium)
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
             // General
             item {
                 Text(stringResource(R.string.header_general), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                 
                 var localName by remember { mutableStateOf(eBikeName) }
                 LaunchedEffect(eBikeName) { localName = eBikeName }
                 
                 OutlinedTextField(
                    value = localName,
                    onValueChange = { 
                        localName = it
                        viewModel.updateEBikeName(it) 
                    },
                    label = { Text(stringResource(R.string.label_ebike_name), style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.fillMaxWidth().height(62.dp),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
             }
             // Auto launch
             item {
                 Text(stringResource(R.string.header_launch), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                 Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Switch(checked = autoMqtt, onCheckedChange = { viewModel.updateAutoConnectMqtt(it) })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.switch_auto_connect_mqtt), style = MaterialTheme.typography.bodyMedium)
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Switch(checked = autoBle, onCheckedChange = { viewModel.updateAutoConnectBle(it) })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.switch_auto_connect_ble), style = MaterialTheme.typography.bodyMedium)
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                    Switch(checked = autoLaunch, onCheckedChange = { viewModel.updateAutoLaunchFlow(it) })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.switch_auto_launch_flow), style = MaterialTheme.typography.bodyMedium)
                }
             }

             // Bluetooth
             item {
                 Text(stringResource(R.string.header_bluetooth), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                 
                 Text(text = stringResource(R.string.text_target_mac, bleMac ?: stringResource(R.string.text_not_set)), style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { 
                        viewModel.bleManager.startScan()
                        showDeviceDialog = true 
                    },
                    modifier = Modifier.height(38.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                ) {
                    Text(stringResource(R.string.btn_select_bonded_device))
                }
             }
             
             // MQTT
             item {
                 Text(stringResource(R.string.header_mqtt), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                 
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
                    label = { Text(stringResource(R.string.label_broker_uri), style = MaterialTheme.typography.labelSmall) }, 
                    modifier = Modifier.fillMaxWidth().height(62.dp),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                OutlinedTextField(
                    value = user, 
                    onValueChange = { user = it }, 
                    label = { Text(stringResource(R.string.label_mqtt_user), style = MaterialTheme.typography.labelSmall) }, 
                    modifier = Modifier.fillMaxWidth().height(62.dp),
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                OutlinedTextField(
                    value = pass, 
                    onValueChange = { pass = it }, 
                    label = { Text(stringResource(R.string.label_mqtt_password), style = MaterialTheme.typography.labelSmall) }, 
                    modifier = Modifier.fillMaxWidth().height(62.dp),
                    visualTransformation = if (passVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        val image = if (passVisible) "👁️" else "🔒" 
                        IconButton(onClick = { passVisible = !passVisible }) {
                            Text(image)
                        }
                    },
                    textStyle = MaterialTheme.typography.bodyMedium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                 Button(
                     onClick = { viewModel.updateMqttConfig(uri, user, pass) },
                     modifier = Modifier.height(38.dp),
                     contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                 ) {
                     Text(stringResource(R.string.btn_save_mqtt_config))
                 }

                 Spacer(modifier = Modifier.height(4.dp))

                 Row(
                     verticalAlignment = Alignment.CenterVertically,
                     modifier = Modifier.fillMaxWidth()
                 ) {
                     Text(stringResource(R.string.text_discovery_prefix), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                     OutlinedButton(
                         onClick = { viewModel.sendHomeAssistantDiscovery() },
                         modifier = Modifier.height(38.dp),
                         contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                     ) {
                         Text(stringResource(R.string.btn_send_discovery))
                     }
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
        title = { Text(stringResource(R.string.dialog_title_select_ebike)) },
        text = {
            LazyColumn {
                item { Text(stringResource(R.string.dialog_label_bonded_devices), style = MaterialTheme.typography.titleSmall) }
                items(bondedDevices) { device ->
                    DeviceItem(device, onDeviceSelected)
                }
                
                item { Spacer(modifier = Modifier.height(8.dp)) }
                
                item { Text(stringResource(R.string.dialog_label_scanned_devices), style = MaterialTheme.typography.titleSmall) }
                if (scannedDevices.isEmpty()) {
                    item { Text(stringResource(R.string.dialog_text_scanning), style = MaterialTheme.typography.bodySmall) }
                }
                items(scannedDevices) { device ->
                    DeviceItem(device, onDeviceSelected)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                modifier = Modifier.height(38.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
            ) { Text(stringResource(R.string.btn_cancel)) }
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
            Text(text = device.name ?: stringResource(R.string.text_unknown_device), style = MaterialTheme.typography.bodyMedium)
            Text(text = device.address, style = MaterialTheme.typography.bodySmall)
        }
    }
}
