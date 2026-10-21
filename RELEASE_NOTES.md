# 🚀 eBikeMonitor v1.15.3: Battery Swap Telemetry Integrity & Offset Management

We are pleased to announce **v1.15.3**, resolving a critical telemetry synchronization bug when swapping eBike batteries and ensuring complete data isolation between battery packs.

---

## 🌟 What's New & Fixed in v1.15.3

### 🔋 1. Battery Swap Telemetry Isolation (Issue #30)
*   **Stale Metric Invalidation**: Completely clears in-memory BMS caches (`totalElectricEnergy` and `chargeCycles`) upon BLE disconnection or whenever a new battery serial number is detected.
*   **Prevented Metric Glitches in Home Assistant**: Eliminates the transient spike where Home Assistant sensors with `state_class: total_increasing` recorded the previous battery pack's values under a newly mounted battery.
*   **Zero-Offset Fallback for Unregistered Batteries**: Fixed an early-return bug in offset calculation that previously froze incoming telemetry when an unregistered battery was inserted; newly detected batteries now immediately update with a default 0.0 offset.
*   **MQTT Freshness Gating**: Added synchronization gates in `EBikeBackgroundService` to ensure battery electric throughput and cycle counts are only published once fresh BMS packets matching the active battery serial have arrived.

### 🧪 2. Comprehensive Battery Lifecycle Unit Tests
*   Added `BatterySwapTest` to the unit test suite, validating:
    *   Disconnect telemetry clearing and freshness flag resets.
    *   Unregistered battery swaps without stale metric carry-over.
    *   Multi-battery offset isolation (preventing cross-battery offset contamination).
    *   Mid-connection battery serial change handling.

---

### 📦 Installation
Load the compiled debug APK below directly onto your Android device:

*   **Download**: [eBikeMonitor-1.15.3.apk](file:///d:/Antigravity/eBikeMonitor/app/build/outputs/apk/debug/eBikeMonitor-1.15.3.apk)
