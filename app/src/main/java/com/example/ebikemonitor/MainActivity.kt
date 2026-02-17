package com.example.ebikemonitor

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ebikemonitor.data.ble.BleManager
import com.example.ebikemonitor.data.datasource.SettingsRepository
import com.example.ebikemonitor.data.mqtt.MqttManager
import com.example.ebikemonitor.ui.DashboardScreen
import com.example.ebikemonitor.ui.SettingsScreen
import com.example.ebikemonitor.ui.theme.EBikeMonitorTheme
import com.example.ebikemonitor.viewmodel.MainViewModel
import com.example.ebikemonitor.viewmodel.MainViewModelFactory

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // Handle permissions granted/denied
        // For now, we assume if they are granted, things work.
        // If critical are missing, we might show a dialog.
    }

    override fun onDestroy() {
        super.onDestroy()
        val serviceIntent = android.content.Intent(this, EBikeBackgroundService::class.java)
        stopService(serviceIntent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Start Background Service
        val serviceIntent = android.content.Intent(this, EBikeBackgroundService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        // Dependencies via Application
        val app = application as EBikeApplication
        val settingsRepository = app.settingsRepository
        val bleManager = app.bleManager
        val mqttManager = app.mqttManager
        
        val factory = MainViewModelFactory(application, settingsRepository, bleManager, mqttManager)
        viewModel = ViewModelProvider(this, factory)[MainViewModel::class.java]

        requestPermissions()

        setContent {
            EBikeMonitorTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppNavigation(viewModel)
                }
            }
        }
    }
    
    private fun requestPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        
        requestPermissionLauncher.launch(permissions.toTypedArray())
    }
}

@Composable
fun AppNavigation(viewModel: MainViewModel) {
    val navController = rememberNavController()
    
    NavHost(navController = navController, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen(
                viewModel = viewModel,
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }
        composable("settings") {
            SettingsScreen(
                viewModel = viewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
