package com.example.ebikemonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ebikemonitor.data.model.BikeStatus
import com.example.ebikemonitor.data.model.getAssistModeName
import com.example.ebikemonitor.viewmodel.MainViewModel
import android.content.res.Configuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MainViewModel, 
    onNavigateToSettings: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = eBikeName.ifEmpty { "eBike Monitor" },
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    ) 
                },
                actions = {
                    if (isLandscape) {
                        CompactActionButtons(viewModel, isMqttConnected, isBleConnected)
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (isLandscape) {
                LandscapeLayout(viewModel, bikeStatus, isMqttConnected, isBleConnected)
            } else {
                PortraitLayout(viewModel, bikeStatus, isMqttConnected, isBleConnected)
            }

            // Version Info at Bottom
            val versionText = buildString {
                append("v${com.example.ebikemonitor.BuildConfig.VERSION_NAME}")
                bikeStatus.ebikeLedSoftwareVersion?.let {
                    append(" | LED: $it")
                }
            }
            Text(
                text = versionText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
            )
        }
    }
}

@Composable
fun PortraitLayout(
    viewModel: MainViewModel,
    bikeStatus: BikeStatus,
    isMqttConnected: Boolean,
    isBleConnected: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ActionButtonsRow(viewModel, isMqttConnected, isBleConnected)
        
        UsageAccessWarning(viewModel)

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            item {
                HomeAssistantCard(title = "Ride", icon = Icons.Default.DirectionsBike) {
                    SensorRow("Speed", bikeStatus.speed?.let { "%.1f".format(it) } ?: "--", "km/h", Icons.Default.Speed, isMqttConnected && bikeStatus.speed != null)
                    SensorRow("Assist Mode", getAssistModeName(bikeStatus.assistMode), "", Icons.Default.SettingsAccessibility, isMqttConnected && bikeStatus.assistMode != null)
                    SensorRow("Human Power", bikeStatus.humanPower?.toString() ?: "--", "W", Icons.Default.Bolt, isMqttConnected && bikeStatus.humanPower != null)
                    SensorRow("Motor Power", bikeStatus.motorPower?.toString() ?: "--", "W", Icons.Default.ElectricBolt, isMqttConnected && bikeStatus.motorPower != null)
                }
            }
            item {
                HomeAssistantCard(title = "Totals", icon = Icons.Default.Analytics) {
                    SensorRow("SoC", bikeStatus.batteryLevel?.toString() ?: "--", "%", getBatteryIcon(bikeStatus.batteryLevel ?: 0), isMqttConnected && (bikeStatus.batteryLevel ?: 0) > 0)
                    SensorRow("Total Distance", bikeStatus.totalDistance?.let { "%.1f".format(it) } ?: "--", "km", Icons.Default.Route, isMqttConnected && (bikeStatus.totalDistance ?: 0.0) > 0.0)
                    SensorRow("Total Energy", bikeStatus.totalBattery?.let { "%.3f".format(it) } ?: "--", "kWh", Icons.Default.ElectricBike, isMqttConnected && bikeStatus.totalBattery != null)
                }
            }
        }
    }
}

@Composable
fun LandscapeLayout(
    viewModel: MainViewModel,
    bikeStatus: BikeStatus,
    isMqttConnected: Boolean,
    isBleConnected: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            UsageAccessWarning(viewModel)
            HomeAssistantCard(title = "Totals", icon = Icons.Default.Analytics) {
                SensorRow("SoC", bikeStatus.batteryLevel?.toString() ?: "--", "%", getBatteryIcon(bikeStatus.batteryLevel ?: 0), isMqttConnected && (bikeStatus.batteryLevel ?: 0) > 0)
                SensorRow("Total Distance", bikeStatus.totalDistance?.let { "%.1f".format(it) } ?: "--", "km", Icons.Default.Route, isMqttConnected && (bikeStatus.totalDistance ?: 0.0) > 0.0)
                SensorRow("Total Energy", bikeStatus.totalBattery?.let { "%.3f".format(it) } ?: "--", "kWh", Icons.Default.ElectricBike, isMqttConnected && bikeStatus.totalBattery != null)
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                HomeAssistantCard(title = "Ride", icon = Icons.Default.DirectionsBike) {
                    SensorRow("Speed", bikeStatus.speed?.let { "%.1f".format(it) } ?: "--", "km/h", Icons.Default.Speed, isMqttConnected && bikeStatus.speed != null)
                    SensorRow("Assist Mode", getAssistModeName(bikeStatus.assistMode), "", Icons.Default.SettingsAccessibility, isMqttConnected && bikeStatus.assistMode != null)
                    SensorRow("Human Power", bikeStatus.humanPower?.toString() ?: "--", "W", Icons.Default.Bolt, isMqttConnected && bikeStatus.humanPower != null)
                    SensorRow("Motor Power", bikeStatus.motorPower?.toString() ?: "--", "W", Icons.Default.ElectricBolt, isMqttConnected && bikeStatus.motorPower != null)
                }
            }
        }
    }
}

@Composable
fun HomeAssistantCard(
    title: String,
    icon: ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            content()
        }
    }
}

@Composable
fun SensorRow(
    label: String,
    value: String,
    unit: String,
    icon: ImageVector,
    transmitted: Boolean
) {
    val valueColor = if (transmitted) MaterialTheme.colorScheme.onSurface else Color.Gray
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(24.dp))
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.wrapContentWidth()
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                lineHeight = 32.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.End
            )
            Text(
                text = if (unit.isNotEmpty()) " $unit" else "",
                style = MaterialTheme.typography.labelSmall,
                color = Color.Gray,
                modifier = Modifier
                    .width(44.dp)
                    .padding(start = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
                maxLines = 1
            )
        }
    }
}

@Composable
fun ActionButtonsRow(
    viewModel: MainViewModel,
    isMqttConnected: Boolean,
    isBleConnected: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // MQTT Button
        val mqttColor = if (isMqttConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
        Button(
            onClick = { viewModel.toggleMqttConnection() },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = mqttColor),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            Text("MQTT", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
        }

        // BLE Button
        val bleColor = if (isBleConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
        Button(
            onClick = { viewModel.toggleBleConnection() },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = bleColor),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            Text("BLE", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
        }

        // FLOW App Button
        val flowContext = androidx.compose.ui.platform.LocalContext.current
        val isFlowRunning by viewModel.isFlowRunning.collectAsState()
        val flowIntent = remember { flowContext.packageManager.getLaunchIntentForPackage("com.bosch.ebike.onebikeapp") }
        val isFlowInstalled = flowIntent != null
        
        Button(
            onClick = { 
                if (isFlowInstalled) {
                    if (isFlowRunning) viewModel.stopBoschApp() else viewModel.launchBoschApp()
                }
            },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = when {
                    !isFlowInstalled -> Color.Gray
                    isFlowRunning -> Color(0xFF4CAF50) 
                    else -> MaterialTheme.colorScheme.primary
                }
            ),
            enabled = isFlowInstalled,
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 4.dp)
        ) {
            Text("FLOW", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
fun CompactActionButtons(
    viewModel: MainViewModel,
    isMqttConnected: Boolean,
    isBleConnected: Boolean
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val mqttColor = if (isMqttConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
        val bleColor = if (isBleConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
        
        StatusCapsule("MQTT", mqttColor) { viewModel.toggleMqttConnection() }
        StatusCapsule("BLE", bleColor) { viewModel.toggleBleConnection() }
        
        val isFlowRunning by viewModel.isFlowRunning.collectAsState()
        val flowContext = androidx.compose.ui.platform.LocalContext.current
        val flowIntent = remember { flowContext.packageManager.getLaunchIntentForPackage("com.bosch.ebike.onebikeapp") }
        val isFlowInstalled = flowIntent != null
        
        val flowColor = when {
            !isFlowInstalled -> Color.Gray
            isFlowRunning -> Color(0xFF4CAF50)
            else -> MaterialTheme.colorScheme.primary
        }
        
        StatusCapsule("FLOW", flowColor, enabled = isFlowInstalled) {
            if (isFlowInstalled) {
                if (isFlowRunning) viewModel.stopBoschApp() else viewModel.launchBoschApp()
            }
        }
    }
}

@Composable
fun StatusCapsule(
    label: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        color = color,
        modifier = Modifier.height(28.dp)
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
fun UsageAccessWarning(viewModel: MainViewModel) {
    val isUsageAccessGranted by viewModel.isUsageAccessGranted.collectAsState()
    if (!isUsageAccessGranted) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            shape = RoundedCornerShape(8.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Usage Access required ", 
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                TextButton(
                    onClick = { viewModel.openUsageAccessSettings() },
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text("GRANT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

fun getBatteryIcon(level: Int): ImageVector {
    return when {
        level >= 90 -> Icons.Default.BatteryFull
        level >= 70 -> Icons.Default.Battery5Bar
        level >= 50 -> Icons.Default.Battery4Bar
        level >= 30 -> Icons.Default.Battery3Bar
        level >= 10 -> Icons.Default.Battery2Bar
        else -> Icons.Default.BatteryAlert
    }
}
