package com.example.ebikemonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ebikemonitor.data.model.BikeStatus
import com.example.ebikemonitor.data.model.getAssistModeName
import com.example.ebikemonitor.viewmodel.MainViewModel

@Composable
fun DashboardScreen(
    viewModel: MainViewModel, 
    onNavigateToSettings: () -> Unit
) {
    val bikeStatus by viewModel.uiState.collectAsState()
    val isBleConnected by viewModel.isBleConnected.collectAsState()
    val isMqttConnected by viewModel.isMqttConnected.collectAsState()
    val eBikeName by viewModel.savedEBikeName.collectAsState()
    
    // Error Snackbar state
    val snackbarHostState = remember { SnackbarHostState() }
    
    // Collect errors
    LaunchedEffect(viewModel.mqttError) {
        viewModel.mqttError.collect { error ->
            snackbarHostState.showSnackbar(
                message = error,
                duration = SnackbarDuration.Long
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = eBikeName.ifEmpty { "eBike Monitor" }, 
                    style = MaterialTheme.typography.headlineMedium
                )
                IconButton(onClick = onNavigateToSettings) {
                    Text("⚙️") 
                }
            }
            
            // Action Buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // MQTT Button
                val mqttColor = if (isMqttConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
                Button(
                    onClick = { viewModel.toggleMqttConnection() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = mqttColor)
                ) {
                    Text("MQTT", fontWeight = FontWeight.Bold, color = Color.White)
                }

                // BLE Button
                val bleColor = if (isBleConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
                Button(
                    onClick = { viewModel.toggleBleConnection() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = bleColor)
                ) {
                    Text("BLE", fontWeight = FontWeight.Bold, color = Color.White)
                }

                // FLOW App Button
                val flowContext = androidx.compose.ui.platform.LocalContext.current
                val flowIntent = remember { flowContext.packageManager.getLaunchIntentForPackage("com.bosch.ebike.onebikeapp") }
                val isFlowInstalled = flowIntent != null
                
                Button(
                    onClick = { if (isFlowInstalled) viewModel.launchBoschApp() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isFlowInstalled) MaterialTheme.colorScheme.primary else Color.Gray
                    ),
                    enabled = isFlowInstalled
                ) {
                    Text("FLOW", fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
            
            // MQTT Error Text

            // Metrics List
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item { MetricListItem("Speed", bikeStatus.speed?.let { "%.1f".format(it) } ?: "--", "km/h", isMqttConnected && bikeStatus.speed != null) }
                item { MetricListItem("Assist Mode", getAssistModeName(bikeStatus.assistMode), "", isMqttConnected && bikeStatus.assistMode != null) }
                item { MetricListItem("Power Human", bikeStatus.humanPower?.toString() ?: "--", "W", isMqttConnected && bikeStatus.humanPower != null) }
                item { MetricListItem("SoC", bikeStatus.batteryLevel?.toString() ?: "--", "%", isMqttConnected && (bikeStatus.batteryLevel ?: 0) > 0) }
                item { MetricListItem("Total Distance", bikeStatus.totalDistance?.let { "%.1f".format(it) } ?: "--", "km", isMqttConnected && (bikeStatus.totalDistance ?: 0.0) > 0.0) }
                item { MetricListItem("Total Energy", bikeStatus.totalBattery?.let { "%.1f".format(it) } ?: "--", "kWh", isMqttConnected && bikeStatus.totalBattery != null) }
            }
        }
        
        // Version Info at Bottom
        Text(
            text = "v${com.example.ebikemonitor.BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        )
        
        // Snackbar Host
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp)
        )
    }
}

@Composable
fun MetricListItem(title: String, value: String, unit: String, transmitted: Boolean) {
    val valueColor = if (transmitted) MaterialTheme.colorScheme.onSurface else Color.Gray

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left Column: Name and Unit
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (unit.isNotEmpty()) {
                Text(text = "$unit", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
        }
        
        // Right Column: Value and Unit
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                modifier = Modifier.alignByBaseline()
            )
            // if (unit.isNotEmpty()) {
            //     Text(
            //         text = " $unit",
            //         style = MaterialTheme.typography.bodyLarge,
            //         color = valueColor,
            //         modifier = Modifier.alignByBaseline().padding(start = 2.dp)
            //     )
            // }
        }
    }
}


