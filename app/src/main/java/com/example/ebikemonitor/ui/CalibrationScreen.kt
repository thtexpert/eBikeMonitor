package com.example.ebikemonitor.ui

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.ebikemonitor.R
import com.example.ebikemonitor.viewmodel.MainViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    viewModel: MainViewModel,
    onNavigateBack: () -> Unit
) {
    val status by viewModel.uiState.collectAsState()
    val serial = status.batterySerialNumber

    val batteryProfiles by viewModel.batteryProfiles.collectAsState()
    val activeProfile = batteryProfiles.find { it.hardwareSerial == serial }

    var energyOffsetStr by remember { mutableStateOf("") }
    var cyclesOffsetStr by remember { mutableStateOf("") }

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Initialize text fields when profile loads
    LaunchedEffect(activeProfile) {
        if (activeProfile != null) {
            energyOffsetStr = String.format(Locale.US, "%.2f", activeProfile.totalElectricEnergyOffset)
            cyclesOffsetStr = String.format(Locale.US, "%.1f", activeProfile.chargeCyclesOffset)
        }
    }

    // Common variables for suggestions
    val usableCapacity = status.usableCapacity ?: 739.0
    val rawEnergy = status.rawTotalElectricEnergy ?: 0.0
    val rawCycles = status.rawChargeCycles ?: 0.0
    val mechEnergy = status.totalBattery ?: 0.0

    val expectedElectricEnergy = if (mechEnergy > 0) mechEnergy / 0.78 else 0.0
    val suggestedEnergyOffset = if (expectedElectricEnergy > 0) expectedElectricEnergy - rawEnergy else 0.0
    
    val expectedCycles = if (expectedElectricEnergy > 0 && usableCapacity > 0) expectedElectricEnergy / (usableCapacity / 1000.0) else 0.0
    val suggestedCyclesOffset = if (expectedCycles > 0) expectedCycles - rawCycles else 0.0

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
                Text("Calibration Offsets", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (serial.isNullOrEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Please connect your eBike to calibrate the active battery.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                if (isLandscape) {
                    // Landscape layout: Split Screen
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Left Column: Battery Info, Inputs & Action buttons
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Active Battery Header
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Active Battery Info", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Model: ${status.batteryModel ?: "PowerTube"}", style = MaterialTheme.typography.bodyMedium)
                                    Text("Serial: $serial", style = MaterialTheme.typography.bodyMedium)
                                    Text("SOH Capacity: ${usableCapacity.toInt()} Wh", style = MaterialTheme.typography.bodyMedium)
                                }
                            }

                            // Configuration fields
                            OutlinedTextField(
                                value = energyOffsetStr,
                                onValueChange = { energyOffsetStr = it },
                                label = { Text("Total Electric Energy Offset (kWh)", style = MaterialTheme.typography.labelSmall) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth().height(62.dp),
                                textStyle = MaterialTheme.typography.bodyMedium
                            )

                            OutlinedTextField(
                                value = cyclesOffsetStr,
                                onValueChange = { cyclesOffsetStr = it },
                                label = { Text("Charge Cycles Offset (cycles)", style = MaterialTheme.typography.labelSmall) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth().height(62.dp),
                                textStyle = MaterialTheme.typography.bodyMedium
                            )

                            // Action buttons
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = {
                                        val energyOffset = energyOffsetStr.toDoubleOrNull()
                                        val cyclesOffset = cyclesOffsetStr.toDoubleOrNull()
                                        if (energyOffset != null && cyclesOffset != null) {
                                            viewModel.updateBatteryOffsets(serial, energyOffset, cyclesOffset)
                                            Toast.makeText(context, "Battery offsets saved!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Invalid numeric values!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                                ) {
                                    Text("Save Offsets")
                                }

                                OutlinedButton(
                                    onClick = {
                                        energyOffsetStr = "0.0"
                                        cyclesOffsetStr = "0.0"
                                        viewModel.updateBatteryOffsets(serial, 0.0, 0.0)
                                        Toast.makeText(context, "Battery offsets cleared!", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                                ) {
                                    Text("Clear")
                                }
                            }
                        }

                        // Right Column: Calibration Suggestions Card
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Calibration Suggestion", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "Based on 78% electromechanical efficiency, motor mechanical energy (${String.format(Locale.US, "%.2f", mechEnergy)} kWh) and raw battery stats:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.height(12.dp))
                                    
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Electric Energy Offset:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                            Text(
                                                "Suggested: +${String.format(Locale.US, "%.2f", suggestedEnergyOffset)} kWh\n(Expected: ${String.format(Locale.US, "%.2f", expectedElectricEnergy)} kWh)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                        Button(
                                            onClick = { energyOffsetStr = String.format(Locale.US, "%.2f", suggestedEnergyOffset) },
                                            enabled = suggestedEnergyOffset > 0,
                                            modifier = Modifier.height(30.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text("Apply", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Charge Cycles Offset:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                            Text(
                                                "Suggested: +${String.format(Locale.US, "%.1f", suggestedCyclesOffset)} cycles\n(Expected: ${String.format(Locale.US, "%.1f", expectedCycles)} cycles)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                        Button(
                                            onClick = { cyclesOffsetStr = String.format(Locale.US, "%.1f", suggestedCyclesOffset) },
                                            enabled = suggestedCyclesOffset > 0,
                                            modifier = Modifier.height(30.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text("Apply", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Portrait layout: Stacked
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Active Battery Header
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Active Battery Info", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text("Model: ${status.batteryModel ?: "PowerTube"}", style = MaterialTheme.typography.bodyMedium)
                                    Text("Serial: $serial", style = MaterialTheme.typography.bodyMedium)
                                    Text("SOH Capacity: ${usableCapacity.toInt()} Wh", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }

                        // Calibration Suggestions
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Calibration Suggestion", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        "Based on 78% electromechanical efficiency, motor mechanical energy (${String.format(Locale.US, "%.2f", mechEnergy)} kWh) and raw battery stats:",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Electric Energy Offset:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                            Text(
                                                "Suggested: +${String.format(Locale.US, "%.2f", suggestedEnergyOffset)} kWh (Expected: ${String.format(Locale.US, "%.2f", expectedElectricEnergy)} kWh)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                        Button(
                                            onClick = { energyOffsetStr = String.format(Locale.US, "%.2f", suggestedEnergyOffset) },
                                            enabled = suggestedEnergyOffset > 0,
                                            modifier = Modifier.height(30.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text("Apply", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Charge Cycles Offset:", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                            Text(
                                                "Suggested: +${String.format(Locale.US, "%.1f", suggestedCyclesOffset)} cycles (Expected: ${String.format(Locale.US, "%.1f", expectedCycles)} cycles)",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                        Button(
                                            onClick = { cyclesOffsetStr = String.format(Locale.US, "%.1f", suggestedCyclesOffset) },
                                            enabled = suggestedCyclesOffset > 0,
                                            modifier = Modifier.height(30.dp),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                        ) {
                                            Text("Apply", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }

                        // Configuration fields
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = energyOffsetStr,
                                    onValueChange = { energyOffsetStr = it },
                                    label = { Text("Total Electric Energy Offset (kWh)", style = MaterialTheme.typography.labelSmall) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth().height(62.dp),
                                    textStyle = MaterialTheme.typography.bodyMedium
                                )

                                OutlinedTextField(
                                    value = cyclesOffsetStr,
                                    onValueChange = { cyclesOffsetStr = it },
                                    label = { Text("Charge Cycles Offset (cycles)", style = MaterialTheme.typography.labelSmall) },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth().height(62.dp),
                                    textStyle = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }

                        // Action buttons
                        item {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Button(
                                    onClick = {
                                        val energyOffset = energyOffsetStr.toDoubleOrNull()
                                        val cyclesOffset = cyclesOffsetStr.toDoubleOrNull()
                                        if (energyOffset != null && cyclesOffset != null) {
                                            viewModel.updateBatteryOffsets(serial, energyOffset, cyclesOffset)
                                            Toast.makeText(context, "Battery offsets saved!", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "Invalid numeric values!", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                                ) {
                                    Text("Save Offsets")
                                }

                                OutlinedButton(
                                    onClick = {
                                        energyOffsetStr = "0.0"
                                        cyclesOffsetStr = "0.0"
                                        viewModel.updateBatteryOffsets(serial, 0.0, 0.0)
                                        Toast.makeText(context, "Battery offsets cleared!", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp)
                                ) {
                                    Text("Clear Offsets")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
