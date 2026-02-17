package com.example.ebikemonitor.data.mqtt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*

class MqttManager(private val context: Context) {
    
    private var client: MqttAndroidClient? = null
    private val TAG = "MqttManager"

    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _connectionError = MutableSharedFlow<String>()
    val connectionError = _connectionError.asSharedFlow()

    suspend fun emitError(message: String) {
        _connectionError.emit(message)
    }

    private var keepAliveJob: kotlinx.coroutines.Job? = null
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO + kotlinx.coroutines.SupervisorJob())

    fun connect(brokerUrl: String, clientId: String, user: String, pass: String, topic: String) {
        if (client != null && client!!.isConnected) {
            return
        }

        val serverUri = "$brokerUrl"
        client = MqttAndroidClient(context, serverUri, clientId)
        
        val options = MqttConnectOptions().apply {
            userName = user
            password = pass.toCharArray()
            isAutomaticReconnect = true
            isCleanSession = false
        }
        options.setKeepAliveInterval(60);
        // --- LWT CONFIGURATION ---
        options.setWill("$topic/status", "offline".toByteArray(), 0, true);

        try {
            client?.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "Connected to MQTT")
                    _isConnected.value = true
                    startKeepAlive(topic)
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "Failed to connect: ${exception?.message}")
                    _isConnected.value = false
                     try {
                         _connectionError.tryEmit("MQTT Connection Failed: ${exception?.message}")
                     } catch (e: Exception) {}
                }
            })
            
            client?.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                     Log.d(TAG, "Connection Complete. Reconnect=$reconnect")
                     _isConnected.value = true
                     startKeepAlive(topic)
                }
                override fun connectionLost(cause: Throwable?) {
                     Log.d(TAG, "Connection Lost")
                     _isConnected.value = false
                     // stopKeepAlive()
                     _connectionError.tryEmit("MQTT Connection Lost: ${cause?.message}")
                }
                override fun messageArrived(topic: String?, message: MqttMessage?) {}
                override fun deliveryComplete(token: IMqttDeliveryToken?) {}
            })

        } catch (e: Exception) {
            Log.e(TAG, "Error connecting: ${e.message}")
        }
    }

    private fun startKeepAlive(topic: String) {
        stopKeepAlive()
        keepAliveJob = scope.launch {
            while (isActive) {
                if (isConnected.value) {
                    publish("$topic/status", "online", retained = false)
                }
                delay(15000) // 15 seconds
            }
        }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
    }

    fun publish(topic: String, message: String, retained: Boolean = false) {
        if (client == null || !client!!.isConnected) return
        
        try {
            val mqttMessage = MqttMessage().apply {
                payload = message.toByteArray()
                isRetained = retained
                qos = 0
            }
            client?.publish(topic, mqttMessage)
            Log.v(TAG, "Published $message to $topic")
        } catch (e: Exception) {
            Log.e(TAG, "Error publishing: ${e.message}")
        }
    }

    fun disconnect(topic: String) {
        try {
            if (isConnected.value) {
                publish("$topic/status", "offline", retained = false)
            }
            stopKeepAlive()
            client?.disconnect()
            client = null
            _isConnected.value = false
        } catch (e: Exception) {
             Log.e(TAG, "Error disconnecting: ${e.message}")
        }
    }
}
