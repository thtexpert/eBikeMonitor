# Changelog

All notable changes to the **eBikeMonitor** project will be documented in this file.

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
