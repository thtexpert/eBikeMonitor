package com.example.ebikemonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.BorderStroke
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
            
            // Status Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatusChip(label = "BLE", isConnected = isBleConnected)
                StatusChip(label = "MQTT", isConnected = isMqttConnected)
            }
            
            // MQTT Error Text
            val mqttErrorText by viewModel.mqttErrorText.collectAsState()
            if (!isMqttConnected && mqttErrorText.isNotEmpty()) {
                Text(
                    text = "MQTT Error: $mqttErrorText",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            
            // Launch App Button
            if (isBleConnected) {
                Button(
                    onClick = { viewModel.launchBoschApp() },
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Text("Open Bosch Flow App")
                }
            }

            // Metrics Grid
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                item { MetricCard("Speed", bikeStatus.speed?.let { "%.1f".format(it) } ?: "--", "km/h") }
                item { MetricCard("Battery", bikeStatus.batteryLevel?.toString() ?: "--", "%") }
                item { MetricCard("Assist", getAssistModeName(bikeStatus.assistMode), "") }
                item { MetricCard("Range", "N/A", "km") } 
                item { MetricCard("Power (User)", bikeStatus.humanPower?.toString() ?: "--", "W") }
                item { MetricCard("Power (Motor)", bikeStatus.motorPower?.toString() ?: "--", "W") }
                item { MetricCard("Cadence", bikeStatus.cadence?.toString() ?: "--", "rpm") }
                item { MetricCard("Distance", bikeStatus.totalDistance?.let { "%.1f".format(it) } ?: "--", "km") }
            }
        }
        
        // Snackbar Host
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun StatusChip(label: String, isConnected: Boolean) {
    val color = if (isConnected) Color.Green else Color.Red
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, color)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = if(isConnected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
fun MetricCard(title: String, value: String, unit: String) {
    Card(
        modifier = Modifier
            .aspectRatio(1.2f)
            .fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, style = MaterialTheme.typography.labelMedium)
            Text(
                text = value, 
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold
            )
            if (unit.isNotEmpty()) {
                Text(text = unit, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
