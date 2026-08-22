# Changelog

All notable changes to the **eBikeMonitor** project will be documented in this file.

## [1.15.2] - 2026-08-22
### Added
- **BLE Status Topic (`blestatus`)**: Published BLE connection status (`connected`/`disconnected`) to the `ebikemonitor/<device_id>/blestatus` topic in real-time.
- **Dynamic Calibration Loop (Self-Learning)**: Automatically refines battery Usable Capacity (Wh) during SoC drops, calculates average Wh/km consumption rates per assist mode, and calibrates the effort coefficient (Gamma) based on physical work.
- **PowerTube Battery Diagnostics**: Integrated detailed battery health telemetry including Actual Usable Capacity (SOH), Nominal Capacity, Usable Energy Remaining, Temperature, and a binary Charging State sensor.
- **Remaining Mode Ranges**: Publishes dynamic remaining battery range in km (`0x9857`) for each active assist mode to MQTT.
- **Trip Performance Dynamics**: Added trip diagnostics to MQTT including average/maximum speeds, cadences, human power outputs, trip total mechanical energy, and human contribution split percentage.
- **Monotonicity Guard**: Prevents cumulative energy and calorie sensors from experiencing downward steps in Home Assistant due to reconnection delays or database baseline latency.
- **Human Energy Estimator**: Real-time integration of rider human pedal energy (measured via BLE when online, and automatically estimated based on Usable Motor Capacity, assist mode ranges, and effort coefficient when offline).
- **Offline Ride Recovery**: Automatically detects offline segments by comparing odometer increments when reconnecting, calculates the unmonitored distance, and injects the estimated calorie metrics seamlessly into Home Assistant.
- **Self-Healing Database Repair**: If database profile records ever get corrupted or wiped, the app automatically repairs them from the first complete incoming BLE batch using consumption-rate sorting.
- **Mid-Ride Reset Support**: Automatically detects Kiox trip resets mid-ride, propagates the reset to realign BLE manager session baselines, and prevents per-mode telemetry updates from freezing.
- **Total Electric Energy (`0x80B0`)**: Decoded and integrated lifetime electrical energy throughput (in kWh) from the battery BMS.
- **Total Mechanical Energy (`0x809C`)**: Renamed the friendly sensor names in Home Assistant from `"Total Battery"` / `"Total Energy Used"` to `"Total Mechanical Energy"` to clearly distinguish mechanical crankshaft work from electrical cell throughput.
- **Home Assistant Discovery Version Bump**: Incremented both bike and battery discovery versions (to 6 and 5 respectively) to trigger automatic entities update on dashboard reload.

### Fixed
- **Database Baseline Corruption**: Implemented repository guards to prevent transient connections (stationary power cycles with missing Kiox characteristics) from overwriting valid database references with empty lists.
- **Calibration Safe Fallbacks**: Uses database-stored active ride metrics instead of defaulting to `0` during short sessions, preventing negative calorie outputs on disconnect.

### Rider Guide: Resetting Kiox Trip Data
To maintain clean baseline synchronization with Home Assistant:
*   **Stationary Resets (Best Practice)**: Reset your trip distance **directly on the Kiox unit** while the bike is turned on.
*   **Post-Offline Rides**: After riding offline (without the phone connected), the Kiox unit will keep its trip data hidden until it registers movement. **Roll/move the bike slightly at startup** to wake up the Kiox BLE broadcast; the app will immediately complete its sync and reconcile the offline distance.
*   **Avoid Resetting via App**: Resetting the trip via the Bosch Flow App while the eBikeMonitor service is connected can cause brief data inconsistencies due to app notification conflicts. Direct Kiox reset is always preferred.

## [1.15.1] - 2026-06-17
### Added
- **Hardware Connection Trigger**: Fast startup feature that directly listens to Android's low-level Bluetooth events (`ACTION_ACL_CONNECTED`), instantly starting the app ~250ms *before* the Bosch Flow app even posts a notification. Configurable in Settings → Automation.
- **Hardware Disconnect Trigger**: Reliably shuts down the background service the exact millisecond the eBike turns off via `ACTION_ACL_DISCONNECTED`, bypassing flawed notification removal logic.
- **Latency Tracking**: Automatically tracks and logs the exact millisecond delay between the hardware connection and the Bosch notification appearing in `ebike_monitor_debug.txt`.
- **Home Sync Window**: After eBike turns off, keeps MQTT-only reconnect loop alive for a configurable duration (0–10 min, default 2 min) to push end-of-ride data when the phone reaches home WiFi.
- Enhanced logging: `[STATUS]` combined status line on every state change, enriched `[HEARTBEAT]`, full `[APP STATE]` lifecycle entries, and `[USER ACTION]` tags for UI-initiated connections.
- GATT error code human-readable descriptions in BLE log entries.

### Fixed
- **Zero-Trip Fallback Race Condition**: Fixed a bug where `108C` packets arriving before `A252` packets would incorrectly zero out trip distances. Decoding is now safely deferred until `A252` arrives.
- **Zombie Reconnecting Bug**: Completely eliminated the infinite reconnection loop caused by crashed Bosch apps leaving stuck "Pocket Mode" notifications on the screen.
- **MQTT reconnect storm** (#28): `isAutomaticReconnect=false` and manual reconnect loop is now the sole authority. Eliminates burst reconnections on connection loss.
- Duplicate Bosch Flow pocket-mode notifications no longer trigger redundant background service starts.
- MQTT `connectionTimeout` reduced to 10 s for faster retry cycles (~20 s instead of ~40 s).

### Changed
- Removed 30 s MQTT timestamp suppression guard — every connection is now timestamped immediately.
- `[HOME SYNC]` log prefix replaces previous internal "Grace Window" terminology.

## [1.15.0] - 2026-06-06
### Added
- Hardware-level Direct eBike Detection using Android `CompanionDeviceManager` as a fallback.
- Background Startup mechanism using Notification Listener to detect when Bosch Flow connects.

### Fixed
- Persistent session tracking bug across power cycles (multi-ride corruption).
- Reduced MQTT broker spam by adding a 30-second connection debounce.
- Cleaned up MQTT Reconnection loop to prevent connection collisions.

### Changed
- Reverted mutual exclusivity of detection settings, allowing both Notification and Direct Detection to run in tandem.

## [1.14.0] - 2026-04-26
### Added
- Comprehensive Bosch BLE decoding for `0x108C` (Usage Records) and `0xA252` (Trip Distance).
- Robust mode mapping system (Version B: Delta Discovery) with persistent slot mapping.
- Live dashboard UI with real-time sensor updates for Speed, Power, Cadence, and Energy.
- Home Assistant MQTT Discovery integration for eBikes and PowerTube batteries.
- Background status detection for the Bosch Flow app.
- Auto-Connect and Auto-Launch features.
- "Stop Flow" functionality to manage the Bosch app lifecycle.

### Changed
- Improved MQTT publishing rules to reduce redundant traffic.
- Enhanced app icon with adaptive layers and custom background coloring.
- Refactored project structure for better maintenance.
- Localized UI strings to resource files (Standardization).

### Fixed
- Resolved "white boundary" icon issue on modern Android versions.
- Fixed energy telemetry glitches by implementing burst validation rules.
- Corrected MQTT discovery for multiple battery devices.

## [1.0.0] - 2026-03-15
### Added
- Initial release with basic BLE connectivity.
- Basic MQTT publishing for speed and battery.
- Settings screen for MQTT configuration.

---
*Generated based on implementation history.*
