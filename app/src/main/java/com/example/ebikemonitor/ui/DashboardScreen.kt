package com.example.ebikemonitor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.*
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
import com.example.ebikemonitor.data.model.UsageRecord
import com.example.ebikemonitor.viewmodel.MainViewModel
import android.content.res.Configuration
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts

private const val SHOW_DEBUG_UI = true

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
    
    val bluetoothEnableLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.toggleBleConnection()
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
                        CompactActionButtons(viewModel, isMqttConnected, isBleConnected, bluetoothEnableLauncher)
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
                PortraitLayout(viewModel, bikeStatus, isMqttConnected, isBleConnected, bluetoothEnableLauncher)
            }

            // Version Info at Bottom
            val versionText = buildString {
                append("V${com.example.ebikemonitor.BuildConfig.VERSION_NAME}")
                bikeStatus.ebikeLedSoftwareVersion?.let { append(" | LED: $it") }
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
fun AllSensorsDebugCard(
    bikeStatus: BikeStatus,
    isMqttConnected: Boolean,
    isBleConnected: Boolean,
    isFlowRunning: Boolean,
    mqttConnectTime: String?
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "All Sensors (Discovery Order / Alphabetical)",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Define sensor entries
            val sensors = mutableListOf<Pair<String, String>>()
            
            // Standard Included
            sensors.add("App Status" to if (isFlowRunning) "running" else "stopped")
            sensors.add("Battery Serial Number" to (bikeStatus.batterySerialNumber ?: "--"))
            sensors.add("BLE Status" to if (isBleConnected) "connected" else "disconnected")
            sensors.add("Cadence" to (bikeStatus.cadence?.toString() ?: "--") + " rpm")
            sensors.add("Charge Cycles" to (bikeStatus.chargeCycles?.toString() ?: "--"))
            sensors.add("eBike LED Software Version" to (bikeStatus.ebikeLedSoftwareVersion ?: "--"))
            sensors.add("last MQTT Connect Time" to (mqttConnectTime ?: "--"))
            sensors.add("Total Hours" to (bikeStatus.driveUnitHours?.toString() ?: "--") + " h")

            // Per-Mode
            val modeNames = bikeStatus.assistModeNames
            bikeStatus.sortedUsageRecordsB.forEachIndexed { index, record ->
                val name = modeNames.getOrNull(index) ?: "Mode$index"
                val dist = record?.let { "%.3f km".format(it.distance / 1000.0) } ?: "--"
                val energy = record?.let { "%.3f kWh".format(it.energy / 1000.0) } ?: "--"
                sensors.add("$name Distance" to dist)
                sensors.add("$name Energy" to energy)
            }

            // Sort Alphabetically by Name
            val sortedSensors = sensors.sortedBy { it.first }

            // Display
            sortedSensors.forEach { (name, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
fun PortraitLayout(
    viewModel: MainViewModel,
    bikeStatus: BikeStatus,
    isMqttConnected: Boolean,
    isBleConnected: Boolean,
    bluetoothEnableLauncher: androidx.activity.compose.ManagedActivityResultLauncher<Intent, androidx.activity.result.ActivityResult>
) {
    val isFlowRunning by viewModel.isFlowRunning.collectAsState()
    val mqttConnectTime by viewModel.mqttSessionConnectTime.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        ActionButtonsRow(viewModel, isMqttConnected, isBleConnected, bluetoothEnableLauncher)
        
        UsageAccessWarning(viewModel)
        
        DiscoveryUpdateNudges(viewModel)

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            item {
                HomeAssistantCard(title = "Ride", icon = Icons.Default.DirectionsBike) {
                    SensorRow("Speed", bikeStatus.speed?.let { "%.1f".format(it) } ?: "--", "km/h", Icons.Default.Speed, isMqttConnected && bikeStatus.speed != null)
                    SensorRow("Assist Mode", getAssistModeName(bikeStatus.assistMode, bikeStatus.assistModeNames), "", Icons.Default.SettingsAccessibility, isMqttConnected && bikeStatus.assistMode != null)
                    SensorRow("Human Power", bikeStatus.humanPower?.toString() ?: "--", "W", Icons.Default.Bolt, isMqttConnected && bikeStatus.humanPower != null)
                    SensorRow("Motor Power", bikeStatus.motorPower?.toString() ?: "--", "W", Icons.Default.ElectricBolt, isMqttConnected && bikeStatus.motorPower != null)
                }
            }

            item {
                HomeAssistantCard(title = "Totals", icon = Icons.Default.Analytics) {
                    SensorRow("SoC", bikeStatus.batteryLevel?.toString() ?: "--", "%", getBatteryIcon(bikeStatus.batteryLevel ?: 0), isMqttConnected && (bikeStatus.batteryLevel ?: 0) > 0)
                    SensorRow("Total Distance", bikeStatus.totalDistance?.let { "%.1f".format(it) } ?: "--", "km", Icons.Default.Route, isMqttConnected && (bikeStatus.totalDistance ?: 0.0) > 0.0)
                    SensorRow("Total Energy", bikeStatus.totalEnergyFromMotor?.let { "%.3f".format(it) } ?: "--", "kWh", Icons.Default.Bolt, isMqttConnected && bikeStatus.totalEnergyFromMotor != null)
                    SensorRow("Total Battery", bikeStatus.totalBattery?.let { "%.3f".format(it) } ?: "--", "kWh", Icons.Default.ElectricBike, isMqttConnected && bikeStatus.totalBattery != null)
                }
            }

            if (SHOW_DEBUG_UI) {
                item {
                    val expectedCount = if (bikeStatus.assistModeNames.isNotEmpty()) bikeStatus.assistModeNames.size else 5
                    DebugUsageRecords(
                        bikeStatus = bikeStatus,
                        records = bikeStatus.unsortedUsageRecords,
                        sortedRecordsA = bikeStatus.sortedUsageRecordsA,
                        sortedRecordsB = bikeStatus.sortedUsageRecordsB,
                        confirmedIndices = bikeStatus.confirmedModeIndices,
                        persistentBaselines = bikeStatus.persistentBaselines,
                        modeNames = bikeStatus.assistModeNames,
                        expectedCount = expectedCount
                    )
                }
                item {
                    AllSensorsDebugCard(
                        bikeStatus = bikeStatus,
                        isMqttConnected = isMqttConnected,
                        isBleConnected = isBleConnected,
                        isFlowRunning = isFlowRunning,
                        mqttConnectTime = mqttConnectTime
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LandscapeLayout(
    viewModel: MainViewModel,
    bikeStatus: BikeStatus,
    isMqttConnected: Boolean,
    isBleConnected: Boolean
) {
    val isFlowRunning by viewModel.isFlowRunning.collectAsState()
    val mqttConnectTime by viewModel.mqttSessionConnectTime.collectAsState()

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
    ) {
        item(span = { GridItemSpan(2) }) {
            UsageAccessWarning(viewModel)
        }
        item(span = { GridItemSpan(2) }) {
            DiscoveryUpdateNudges(viewModel)
        }
        item {
            HomeAssistantCard(title = "Totals", icon = Icons.Default.Analytics) {
                SensorRow("SoC", bikeStatus.batteryLevel?.toString() ?: "--", "%", getBatteryIcon(bikeStatus.batteryLevel ?: 0), isMqttConnected && (bikeStatus.batteryLevel ?: 0) > 0)
                SensorRow("Total Distance", bikeStatus.totalDistance?.let { "%.1f".format(it) } ?: "--", "km", Icons.Default.Route, isMqttConnected && (bikeStatus.totalDistance ?: 0.0) > 0.0)
                SensorRow("Total Energy", bikeStatus.totalEnergyFromMotor?.let { "%.3f".format(it) } ?: "--", "kWh", Icons.Default.Bolt, isMqttConnected && bikeStatus.totalEnergyFromMotor != null)
                SensorRow("Total Battery", bikeStatus.totalBattery?.let { "%.3f".format(it) } ?: "--", "kWh", Icons.Default.ElectricBike, isMqttConnected && bikeStatus.totalBattery != null)
            }
        }
        item {
            HomeAssistantCard(title = "Ride", icon = Icons.Default.DirectionsBike) {
                SensorRow("Speed", bikeStatus.speed?.let { "%.1f".format(it) } ?: "--", "km/h", Icons.Default.Speed, isMqttConnected && bikeStatus.speed != null)
                SensorRow("Assist Mode", getAssistModeName(bikeStatus.assistMode, bikeStatus.assistModeNames), "", Icons.Default.SettingsAccessibility, isMqttConnected && bikeStatus.assistMode != null)
                SensorRow("Human Power", bikeStatus.humanPower?.toString() ?: "--", "W", Icons.Default.Bolt, isMqttConnected && bikeStatus.humanPower != null)
                SensorRow("Motor Power", bikeStatus.motorPower?.toString() ?: "--", "W", Icons.Default.ElectricBolt, isMqttConnected && bikeStatus.motorPower != null)
            }
        }
        
        if (SHOW_DEBUG_UI) {
            item {
                val expectedCount = if (bikeStatus.assistModeNames.isNotEmpty()) bikeStatus.assistModeNames.size else 5
                DebugUsageRecords(
                    bikeStatus = bikeStatus,
                    records = bikeStatus.unsortedUsageRecords,
                    sortedRecordsA = bikeStatus.sortedUsageRecordsA,
                    sortedRecordsB = bikeStatus.sortedUsageRecordsB,
                    confirmedIndices = bikeStatus.confirmedModeIndices,
                    persistentBaselines = bikeStatus.persistentBaselines,
                    modeNames = bikeStatus.assistModeNames,
                    expectedCount = expectedCount
                )
            }
            item {
                AllSensorsDebugCard(
                    bikeStatus = bikeStatus,
                    isMqttConnected = isMqttConnected,
                    isBleConnected = isBleConnected,
                    isFlowRunning = isFlowRunning,
                    mqttConnectTime = mqttConnectTime
                )
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
    isBleConnected: Boolean,
    bluetoothEnableLauncher: androidx.activity.compose.ManagedActivityResultLauncher<Intent, androidx.activity.result.ActivityResult>
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
            onClick = { 
                if (!isBleConnected && !viewModel.isBluetoothEnabled()) {
                    bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } else {
                    viewModel.toggleBleConnection()
                }
            },
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
    isBleConnected: Boolean,
    bluetoothEnableLauncher: androidx.activity.compose.ManagedActivityResultLauncher<Intent, androidx.activity.result.ActivityResult>
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val mqttColor = if (isMqttConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
        val bleColor = if (isBleConnected) Color(0xFF4CAF50) else Color(0xFFF44336)
        
        StatusCapsule("MQTT", mqttColor) { viewModel.toggleMqttConnection() }
        StatusCapsule("BLE", bleColor) { 
            if (!isBleConnected && !viewModel.isBluetoothEnabled()) {
                bluetoothEnableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } else {
                viewModel.toggleBleConnection() 
            }
        }
        
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
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Usage Access required", 
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    TextButton(
                        onClick = { viewModel.openUsageAccessSettings() },
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text("GRANT", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Text(
                    "Restricted? / Eingeschränkt? -> Try GRANT once, then: App Info > 3 dots > Allow restricted settings / Eingeschränkte Einstellungen zulassen",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    fontSize = 10.sp,
                    lineHeight = 12.sp
                )
            }
        }
    }
}

@Composable
fun DiscoveryUpdateNudges(viewModel: MainViewModel) {
    val isBikeOutdated by viewModel.isBikeDiscoveryOutdated.collectAsState()
    val isBatteryOutdated by viewModel.isBatteryDiscoveryOutdated.collectAsState()
    
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isBikeOutdated) {
            DiscoveryNudge(
                message = "New eBike MQTT topics available",
                buttonText = "UPDATE BIKE",
                onUpdate = { viewModel.updateBikeDiscovery() }
            )
        }
        
        if (isBatteryOutdated) {
            DiscoveryNudge(
                message = "Register this PowerTube in HA",
                buttonText = "REGISTER BATTERY",
                onUpdate = { viewModel.updateBatteryDiscovery() }
            )
        }
    }
}

@Composable
fun DiscoveryNudge(
    message: String,
    buttonText: String,
    onUpdate: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(
                onClick = onUpdate,
                contentPadding = PaddingValues(horizontal = 8.dp)
            ) {
                Text(buttonText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun DebugUsageRecords(
    bikeStatus: BikeStatus,
    records: List<UsageRecord>,
    sortedRecordsA: List<UsageRecord>,
    sortedRecordsB: List<UsageRecord?>,
    confirmedIndices: Set<Int>,
    persistentBaselines: List<Int>?,
    modeNames: List<String>,
    expectedCount: Int
) {
    if (records.isNotEmpty() || persistentBaselines != null) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            shape = RoundedCornerShape(8.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {

                // Version B
                if (modeNames.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "V-B: Delta Discovery", 
                            style = MaterialTheme.typography.labelMedium, 
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2196F3)
                        )
                        
                        // Startup Diagnostics
                        val status = bikeStatus.startupDecodingStatus ?: "PENDING"
                        val statusColor = when (status) {
                            "SUCCESS", "SUCCESS_ZERO_TRIP" -> Color(0xFF4CAF50)
                            "AMBIGUOUS", "WAITING_FOR_TRIP_DATA" -> Color(0xFFFF9800)
                            else -> Color(0xFFF44336)
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = statusColor.copy(alpha = 0.1f)
                        ) {
                            Text(
                                text = status,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = statusColor,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                    
                    if (bikeStatus.startupError != null) {
                        val errorText = buildString {
                            append("Error: ${bikeStatus.startupError}m")
                            bikeStatus.startupSecondaryError?.let { append(" (Sec: ${it}m)") }
                        }
                        Text(
                            text = errorText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    modeNames.forEachIndexed { index, modeName ->
                        val isConfirmed = confirmedIndices.contains(index)
                        val record = if (index < sortedRecordsB.size) sortedRecordsB[index] else null
                        
                        val textColor = if (isConfirmed) Color(0xFF4CAF50) else Color.Gray
                        val label = if (isConfirmed) "[OK]" else "[Pending]"
                        
                        Text(
                            text = if (record != null) {
                                "$modeName $label: ${record.distance}m, ${record.energy}Wh"
                            } else {
                                "$modeName $label: ---"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor
                        )
                    }
                    Divider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }

                // Persistent Baselines (Stored)
                if (persistentBaselines != null) {
                    val hasTrip = bikeStatus.tripDistPerMode.isNotEmpty()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Stored Baselines (from Phone)", 
                            style = MaterialTheme.typography.labelMedium, 
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF9800)
                        )
                        Text(
                            text = if (hasTrip) "TRIP OK" else "TRIP MISSING",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (hasTrip) Color(0xFF4CAF50) else Color.Gray
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    val displayCount = if (modeNames.isNotEmpty()) modeNames.size else persistentBaselines.size
                    repeat(displayCount) { index ->
                        val name = modeNames.getOrElse(index) { "Mode $index" }
                        val baseline = persistentBaselines.getOrNull(index)
                        Text(
                            text = "$name: ${baseline ?: "---"} m",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (records.isNotEmpty()) {
                        Divider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }

                Text(
                    text = "Debug: Unsorted Incoming (${records.size}/$expectedCount)", 
                    style = MaterialTheme.typography.labelMedium, 
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                records.forEachIndexed { index, record ->
                    Text(
                        text = "${index + 1}: ${record.distance}m, ${record.energy}Wh", 
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
