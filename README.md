# Bosch smart system ebike (BES3) live data monitoring to MQTT

## eBikeMonitor

 <img src="images/IconVersion2.png" width="120">

## Description
This Android app collects data from your Bosch eBike by listening to the Bluetooth traffic between the LED remote and the Flow app, then pushes the values to an MQTT broker. It acts as a bridge between your Bosch Gen4 Smart System eBike and your smart home system (e.g., Home Assistant).

<img src="images/HA_eBikeDashboard.png" width="400">

The app separates data for different bikes and batteries. If you use multiple eBikes or battery packs, the app will automatically detect and display the data for the currently connected hardware.

<img src="images/HA_BatteriesDashboard.png" width="400">

The app features a simple dashboard to display current sensor values.

<img src="images/DashboardScreen.jpg" width="400">

---

## ⚡ Key Highlights (v1.15.2)

*   **Triple Background Connection Triggers**: Low-latency startup triggers run in parallel to wake the app automatically (~250ms faster) using low-level Bluetooth hardware events (`ACTION_ACL_CONNECTED`), Android Companion Device Manager presence detection, and Notification Listener notifications.
*   **Offline Segment Recovery**: Automatical detection of offline segments. If you ride without your phone, the app compares odometer growth on reconnection, estimates the unmonitored distance, and seamlessly injects the estimated calorie metrics.
*   **Constant Voltage (CV) Top-Off Tracking**: Battery diagnostics monitor the late-stage CV top-off charging phase (accumulating Wh usable energy even after the UI displays 100% SoC) and support 2-hour sleep wakeup cycles.
*   **Home Sync Window (Offline Resilience)**: Keeps the MQTT connection loop alive for a configurable window (e.g., 2 minutes) when the bike turns off. If you reach home WiFi within this window, all ride summary metrics are automatically pushed.
*   **Bijective Assist Mode Mapping**: Dynamic Discovery tracks separate distances and mechanical energies for each active assist mode (Eco, Tour, Turbo, etc.), with automatic self-healing fallback and mid-ride Kiox reset tracking.

---

## 🏗️ System Architecture & Data Flow

The diagram below illustrates how eBikeMonitor acts as an invisible background observer, sniffing local Bluetooth packets, performing startup sync, and integrating with Home Assistant:

```mermaid
graph TD
    %% Node Styling
    classDef device fill:#2f343f,stroke:#666,stroke-width:1px,color:#fff;
    classDef app fill:#1e3a8a,stroke:#3b82f6,stroke-width:2px,color:#fff;
    classDef server fill:#0f172a,stroke:#475569,stroke-width:1px,color:#d3d7cf;
    classDef ha fill:#14532d,stroke:#22c55e,stroke-width:2px,color:#fff;

    Bike["Bosch Smart System eBike"]:::device
    Remote["LED Remote / Kiox"]:::device
    FlowApp["Bosch Flow App"]:::device
    
    subgraph "Android Device (sniffing pipeline)"
        Trigger["Connection Triggers<br>ACL Bluetooth / Companion Device / Notification Listener"]:::app
        Monitor["eBikeMonitor Service<br>Background Data Collector"]:::app
        DB[("Persistent SQLite<br>Baselines & Calibration")]:::app
    end
    
    subgraph "Smart Home"
        Broker["MQTT Broker"]:::server
        HA["Home Assistant"]:::ha
    end

    %% Connections
    Remote ---|Bonded BLE Connection| FlowApp
    FlowApp ---|GATT Telemetry| Bike
    Trigger -->|Wake up Service| Monitor
    Bike -.->|Sniffed BLE Characteristic notifications| Monitor
    Monitor ---|Read/Write Baselines| DB
    Monitor -->|MQTT Discovery & Live Metrics| Broker
    Broker -->|Home Assistant Auto-Discovery| HA
```

---

## 🚴 Pedal Energy Tracking (Metabolic Calories)

eBikeMonitor calculates the rider's metabolic calorie burn in real-time and estimates unmonitored human effort when riding disconnected (offline). The calculated metrics are pushed to Home Assistant as retained cumulative accumulators to prevent statistical data resets:

### 1. Real-Time Accumulation (Online)
When the phone is connected, metabolic energy is accumulated directly from the bike's high-precision crankshaft torque sensor (`0xA251` pedal energy) by tracking positive changes ($\Delta \text{PedalEnergy}$).
- **Startup Sync Catch-up**: To ensure no calories are lost during the 10–30s Bluetooth discovery handshake, any delta pedaled during connection setup is calculated and added to the baseline.

### 2. Offline Segment Estimation
When the app reconnects and detects an odometer gap ($\Delta \text{Odo} > 0.20 \text{ km}$), it determines the offline distance ($d\_{\text{offline}} = \Delta \text{Odo} - \Delta \text{TripDistSum}$). If the battery State of Charge or motor energy delta indicates active assist, the app estimates the offline calorie burn using:

*   **Path A: Assisted Riding (Motor ON)**:
    $\text{AvgRate}\_{\text{active}} \text{ (Wh/km)} = \frac{\sum\_{i=1}^{M} \text{LearnedRate}\_i}{M} \quad \text{(excluding OFF mode)}$
    $E\_{\text{est, Wh}} = d\_{\text{offline}} \times \text{AvgRate}\_{\text{active}}$
    $E\_{\text{est, kcal}} = \gamma \times E\_{\text{est, Wh}}$
    *Where $\gamma$ is the `learnedUserEffortCoefficient` (representing the user's metabolic contribution relative to motor electrical input).*
*   **Path B: Unassisted Riding (Motor OFF)**:
    $E\_{\text{est, kcal}} = d\_{\text{offline}} \times 22.0 \text{ kcal/km} \quad \text{(standard unassisted cycling rate)}$

---

## 🔋 Battery Diagnostics & Advanced Charging Curves

The app integrates deep Battery Management System (BMS) metrics, capturing cell performance details that are normally hidden from the user.

### Constant Voltage (CV) Late-Stage Top-Off
When a lithium-ion battery reaches **100% State of Charge (SoC)**, the charge cycle is not finished. The charger enters the **Constant Voltage (CV) phase**, where current decreases slowly while energy continues to pack into the cells.
- **Top-Off Wh Capture**: eBikeMonitor continues logging usable energy during the top-off phase. Real-world testing (shown below) demonstrates that a **PowerTube 750** stores an additional **+34.6 Wh (+4.9% capacity)** and continues drawing power for **36.5 minutes** after the UI displays 100% SoC.
- **2-Hour Sleep Wakeup Cycles**: When charging is complete but the charger remains connected, the BMS briefly wakes up **every 2 hours** to broadcast temperature, SoC, and remaining capacity. eBikeMonitor captures these transient wakeups to update your smart home, without triggering false ride-start sequences or resetting baselines.

<img src="images/HA_Battery_Charge.png" width="600">

---

## How this works
The eBikeMonitor listens to the BLE traffic between the eBike and the official Bosch Flow app. Since this requires a bonded BLE connection, eBikeMonitor must be installed on the same Android device as the Bosch Flow app.

For detailed packet decoding specifications, see [BLE TOPICS](BLE_TOPICS.md) and [USAGE DECODING](USAGE_DECODING.md).

## Getting Started / How to Use

### 1. Initial Setup

<img src="images/SettingsScreen.jpg" width="200">

1. **Pairing**: Ensure your eBike is paired (bonded) with your Android device via the system Bluetooth settings.
2. **Permissions**: The app requires **Notification Access** to automatically detect when the Bosch Flow app connects in the background. If this is missing, the dashboard will display a warning banner allowing you to grant it directly. Also, ensure Bluetooth is enabled.
3. **Settings Configuration**:
   - Open the **Settings** screen (gear icon).
   - **eBike Name**: Enter a name for your eBike. This name will be used for the MQTT device in Home Assistant.
   - **Select eBike**: Choose your paired eBike from the Bluetooth devices list so the app knows which hardware to track.
   - **MQTT Setup**: Enter your MQTT broker URI (e.g., `tcp://192.168.1.10:1883`), username, and password.
   - **Automation**: Configure "Auto-Connect MQTT", "Background Startup", "Direct eBike Detection (Fallback)", "Hardware Connection Trigger (Fast Startup)", and **"Home Sync Window"** to manage how the app runs automatically in the background.

### Privacy & Permissions Note
For background automation, the app requires **Notification Access** to read when the Flow app connects to your eBike. If this is missing, a prominent warning banner is displayed on the dashboard to prompt you to grant it, as it is critical for auto-startup. The app also optionally utilizes **Usage Access** (`PACKAGE_USAGE_STATS`) and **Query All Packages** (`QUERY_ALL_PACKAGES`) to check if the Flow app is currently active, and utilizes the Android **Companion Device Manager** to directly detect your eBike's Bluetooth hardware presence for fast direct startup triggers. These permissions are used exclusively for local state detection and automation (auto-launching/stopping). No personal browsing data or package lists are transmitted or collected.

### 2. First Ride

As soon as BLE connects to the eBike, the latest active data is updated on the screen.
Assist mode-specific distance and energy data require internal decoding. You may need to ride for a short distance in each mode before valid data can be transmitted via MQTT.

### 3. Daily Usage Sequence
With background automation enabled, the sequence is completely hands-off:

1. **Turn on your eBike.**
2. That's it! 
   - If **Hardware Connection Trigger** is enabled, eBikeMonitor will wake up instantly by listening to your phone's low-level Bluetooth connection events, starting even faster than the Flow app notification.
   - If **Background Startup** is enabled, eBikeMonitor will wake up automatically as soon as the Bosch Flow app connects. *(**CRITICAL:** You must enable "Ride Screen / Pocket Mode" notifications inside the official Bosch Flow app settings for this to work!)*
   - If **Direct eBike Detection** is enabled, the Android system will wake eBikeMonitor directly when it senses the eBike's Bluetooth hardware.
3. The app will silently connect to BLE and MQTT in the background to sync your ride data. You will see a small, persistent notification indicating that background monitoring is active.

**Manual Mode:**
If you prefer not to use background automation:
1. Turn on your eBike.
2. Launch the eBikeMonitor app. It will automatically connect (if auto-connect is enabled) and pull in data.
**Automatic MQTT Reconnection & Home Sync Window**: 
You can start your ride even if you are away from home. Once your eBike turns off, the app continues trying to reach the MQTT broker for a configurable **Home Sync Window** (default: 2 minutes). When your phone reconnects to your home network, the app automatically transmits your latest ride data — so nothing is ever lost, even if you rode away from home WiFi.

### 4. Monitoring UI & Features
- **Status Dashboard**: The top row displays an active status indicator showing whether the background monitoring is actively connected to your eBike or waiting for a connection. It also includes an **MQTT** diagnostic button (Green = Connected, Red = Disconnected) that you can tap to manually restart the MQTT connection if needed.
- **Charging**: To monitor charging, follow the startup sequence above, then connect the charger to the bike. The battery must be charged while mounted in the eBike, and the smartphone must maintain a BLE connection to the eBike throughout the process.
- **Debug Info**: Swiping up (scrolling down) on the main dashboard reveals detailed diagnostic and debugging information, including raw sensor payloads, BLE characteristics, and assist mode discovery metrics. A persistent debug log file is written to the device storage — see [LOG REFERENCE](LOG_REFERENCE.md) for a full description of all log entries and states.
- **Versions**: The lower-left corner of the UI displays the eBikeMonitor version and the LED remote software version. See [CHANGELOG](CHANGELOG.md) for the full release history.

### 5. Troubleshooting & Profile Reset
- **Reset Profiles**: If your per-mode telemetry (distance or energy) gets scrambled (e.g. Eco showing up as Sport due to an invalid calibration after a trip reset), you can reset the profiles selectively:
  1. Go to the **Settings** screen.
  2. Scroll down to the **Bluetooth** section.
  3. Tap the red **"Reset Profiles"** button. This will clear the scrambled mode-specific data and baselines in the database, but **keep your MQTT credentials, selected BLE MAC address, and learned battery capacity/user effort calibration intact**.
  4. On the next connection, the app will automatically perform a fresh startup decoding and re-learn the correct mode mapping. **Note**: Re-learning requires you to ride in each assist mode for at least a few hundred meters so that the discovery algorithm can gather enough distance delta to distinguish them.
- **Battery Calibration**: If your battery's charge cycle and electrical energy counters have fallen out of sync with the motor's mechanical odometer work (only needed if the battery was run on very old BMS firmware for a long period of time), you can apply offsets to calibrate them. See [CALIBRATION](CALIBRATION.md) for full instructions.

### 6. Synchronization Alerts & Error Notifications
To ensure you are immediately informed of any synchronization issues when coming home, the app creates a dedicated Android notification channel named **eBike Sync Errors**.

This channel is used to post two types of alerts:
- **MQTT Sync Unsuccessful**: Triggered if the post-ride **Home Sync Window** expires without the app successfully connecting to your MQTT broker (e.g., if your phone did not reconnect to home WiFi in time or the MQTT broker was unreachable).
- **MQTT Sync Data Warning**: Triggered at the end of a ride if a data mismatch is detected between the total odometer distance change and the sum of assist mode usage distances (usually caused by BLE connection dropouts during the ride). This alerts you that the data synced to MQTT may be incomplete or stale.

**Bypassing Silent Mode / Do Not Disturb:**
By default, the `eBike Sync Errors` channel is configured with **High Importance** to display heads-up alerts. If you want these critical warnings to vibrate/ring even when your phone is in silent mode or Do Not Disturb:
1. Open your Android **Settings** -> **Apps** -> **eBikeMonitor** -> **Notifications** (or **Notification Categories**).
2. Tap on the **eBike Sync Errors** category.
3. Enable **Bypass Do Not Disturb** (or **Override Do Not Disturb**).

## Home Assistant Integration

The eBikeMonitor app supports **MQTT Discovery** for Home Assistant, meaning you do not need to manually configure sensors.

1. Ensure the MQTT connection is active (the MQTT button on the dashboard is green).
2. Go to the **Settings** screen.
3. Tap the **"Send Discovery to Home Assistant"** button.
4. Your eBike will automatically appear as a new Device in Home Assistant, with all its sensors (Speed, Battery, Assist Mode, Power, etc.) ready to use.

<img src="images/HomeassistantDeviceInfo.png" width="500">

5. The mounted battery will be automatically discovered and shown as a new device in Home Assistant. You can rename these devices or update their Entity IDs to match your preferred naming convention.

<img src="images/HA_MQTT_devices.png" width="500">

For detailed MQTT topics see [MQTT TOPICS](MQTT_TOPICS.md).

## Initial BLE Decoding Information
BLE decoding info is based on: https://github.com/RobbyPee/Bosch-Smart-System-Ebike-Garmin-Android

## License
This project is licensed under the GNU GPL v3.0 - see the [LICENSE] file for details.
