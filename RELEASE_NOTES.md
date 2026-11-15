# 🛠️ eBikeMonitor v1.15.3: Bugfix & Stability Release

We are pleased to announce **v1.15.3**, a focused maintenance and bugfix release resolving critical battery telemetry synchronization bugs, fixing negative temperature decoding during sub-zero operations, and enhancing diagnostic observability.

---

## 🌟 What's Fixed & Improved in v1.15.3

### ❄️ 1. Negative Battery Temperature Decoding (Issue #32)
*   **Protobuf ZigZag Decoding**: Fixed battery cell temperature decoding for BLE topic `0x808B` by implementing Google Protocol Buffers ZigZag `sint32` decoding in 0.1°C units (`decodeZigZag32(raw) / 10.0`).
*   **Resolved Sub-Zero Anomaly**: Eliminated the physical anomaly where charging or operating at temperatures below 0°C erroneously displayed inverted positive temperatures that counted down toward zero.
*   **Zero & Sub-Zero Unit Test Coverage**: Added comprehensive test cases covering negative values (e.g., `-1.3°C`), empty zero-temperature frames (`0.0°C`), positive temperatures, and ZigZag bitwise edge cases.

### 🔋 2. Battery Swap Telemetry Isolation & Glitch Prevention (Issue #30)
*   **Stale Metric Invalidation**: Completely clears in-memory BMS caches (raw charge cycles `0x8096` and electric throughput `0x80B0`) upon BLE disconnection or whenever a new battery serial number is detected.
*   **Home Assistant Sensor Protection**: Prevents transient spikes where Home Assistant sensors with `state_class: total_increasing` recorded the previous battery pack's values under a newly mounted battery.
*   **Zero-Offset Fallback for Unregistered Batteries**: Fixed an early-return bug in offset calculation that previously froze incoming telemetry when an unregistered battery was inserted; newly detected batteries now immediately update with a default `0.0` offset.
*   **MQTT Freshness Gating**: Added synchronization gates in `EBikeBackgroundService` to ensure battery electric metrics are only published once fresh BMS packets matching the active battery serial have arrived.

### 🔌 3. On-Bike Charge Cycles Classification (Issue #31)
*   **Dual Counter Clarity**: Reclassified topic `0x80B0` as **On-Bike Charge Cycles** (cumulative full charge cycles conducted strictly via the eBike frame charging socket) to distinguish from `0x8096` (lifetime total charge cycles across all charging methods).
*   **Off-Bike Desktop Charging Calculation**: Documented that the difference (`0x8096 - 0x80B0`) accurately reflects external off-bike desktop charging sessions.

### 📶 4. BLE Signal Strength Monitoring & Home Assistant Diagnostic Category
*   **Live BLE RSSI (`blerssi`)**: Real-time Bluetooth signal strength polling (every 5 seconds while connected), displayed in Dashboard UI and published to `ebikemonitor/<device_id>/blerssi`.
*   **Home Assistant Diagnostic Entity Category**: Assigned `"entity_category": "diagnostic"` to `BLE Signal Strength`, `BLE Status`, `App Status`, and `last MQTT Connect Time` (`CURRENT_BIKE_DISCOVERY_VERSION = 7`) so they appear under the **Diagnostic** section on the Home Assistant device page without cluttering primary dashboard entities or long-term statistics.
*   **Flow Notification Fallback Trigger**: When `Hardware Connection Trigger` is enabled, `EBikeNotificationListener` acts as an automatic fallback if `BikePresenceManager.isBikePresent` is `false` (e.g. after a missed ACL event during weak signal or OS process restart).
*   **GATT Status 147 Clarification**: Updated error logging to explicitly identify GATT status 147 as `GATT_CONNECTION_TIMEOUT (147) — BLE link supervision timeout (weak/lost signal)`.

### 🧪 5. Expanded Unit Test Suite
*   Added `BatterySwapTest` validating disconnect telemetry clearing, unregistered battery swaps without stale carry-over, multi-battery offset isolation, and mid-connection serial changes.
*   Added unit tests in `BoschParserTest` for ZigZag decoding and sub-zero battery temperature frames.

---

## 📦 Installation
Install the compiled debug APK directly on your Android device:

*   **Download APK**: [eBikeMonitor-1.15.3.apk](file:///d:/Antigravity/eBikeMonitor/app/build/outputs/apk/debug/eBikeMonitor-1.15.3.apk)
