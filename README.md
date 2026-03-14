# Bosch smart system ebike (BES3) data monitoring to MQTT

## eBikeMonitor <img src="images/IconVersion2.png" width="100">

## Description
This Android app gets data from your Bosch eBike and pushes values via MQTT to a broker. It acts as a bridge between your Bosch Gen4 Smart System Ebike and your smart home system (e.g., Home Assistant).

<img src="images/DashboardScreen.jpg" width="400">


## How this works
The eBikeMonitor listens to the BLE traffic between the eBike and the official Bosch Flow app. 
To do this successfully, **the eBikeMonitor app must be started and connected to your bonded smart eBike before the Flow app is started.**
Running eBikeMonitor on a different Android device than the one with the Bosch Flow app will not work!

## Getting Started / How to Use

### 1. Initial Setup

<img src="images/SettingsScreen.jpg" width="200">

1. **Pairing**: Ensure your eBike is paired (bonded) with your Android device via system Bluetooth settings.
2. **Permissions**: The app requires **Usage Access** permission to detect if the Bosch Flow app is running. The dashboard will warn you and prompt you to grant this if it is missing. Also, ensure Bluetooth is powered on.
3. **Settings Configuration**:
   - Open the **Settings** screen (gear icon).
   - **Select eBike**: Choose your paired eBike from the Bluetooth devices list.
   - **MQTT Setup**: Enter your MQTT broker URI (e.g., `tcp://192.168.1.10:1883`), username, and password.
   - **Preferences**: Toggle auto-connect for BLE and MQTT, and auto-launch for the Flow app as desired.

### 2. Daily Usage Sequence
To ensure reliable data collection, follow this precise sequence:
1. **Ensure the Bosch Flow app is NOT running** in the background (force stop it if necessary).
2. **Turn on your eBike.**
3. **Launch the eBikeMonitor app.**
4. If auto-connect is enabled in settings, eBikeMonitor will automatically connect to the eBike (BLE) and start the MQTT connection. The corresponding action buttons will turn green.
5. If auto-launch is enabled, eBikeMonitor will automatically start the Bosch Flow app once the BLE connection is established. Otherwise, tap the **FLOW** action button to start it.
6. **Keep the Flow app open** until real-time data appears on the eBikeMonitor dashboard.
7. **Automatic MQTT Reconnection**: You can launch the app and start your ride even if you are away from home. If auto-connect is enabled, the app will automatically establish the MQTT connection as soon as your phone reaches your home network (or wherever your broker is located) and will immediately send the latest received sensor data.

### 3. Monitoring UI & Features
- **Action Buttons**: The top row shows the status of MQTT, BLE, and the FLOW app. Green means connected/running, red/gray means disconnected/stopped. You can tap these to manually toggle connections or launch/stop the Flow app.
- **Sensor Data Transfer Verification**: Values (Speed, Assist Mode, Power, etc.) are displayed on the dashboard. If the value text is standard color, the data has been successfully sent to your MQTT broker. If it is grayed out, it means the MQTT transfer is pending or unavailable.
- **Charging**: To monitor the charging process, run the startup sequence above, and afterwards connect the charger to the bike. The battery must be charged while mounted in the eBike. The smartphone must maintain a BLE connection to the eBike during the entire charging process.
Charging curve example:
<img src="images/ChargeCurve.png" width="300">

## Home Assistant Integration

The eBikeMonitor app supports **MQTT Discovery** for Home Assistant, meaning you do not need to manually configure sensors.

1. Ensure the MQTT connection is active (the MQTT button on the dashboard is green).
2. Go to the **Settings** screen.
3. Tap the **"Send Discovery to Home Assistant"** button.
4. Your eBike will automatically appear as a new Device in Home Assistant, with all its sensors (Speed, Battery, Assist Mode, Power, etc.) ready to use.

<img src="images/HomeassistantDeviceInfo.png" width="500">

## initial BLE decoding info
BLE decoding info is based on: https://github.com/RobbyPee/Bosch-Smart-System-Ebike-Garmin-Android

## License
This project is licensed under the GNU GPL v3.0 - see the [LICENSE] file for details.
