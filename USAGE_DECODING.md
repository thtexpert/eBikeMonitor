# Bosch Usage Data & Energy Decoding Specifications

This document outlines the logic and strategies used by eBikeMonitor to decode per-mode distance, energy consumption, and total motor energy from Bosch Performance Line (Smart System) BLE data.

## 1. Raw Data Acquisition

### The 0x108C Message
Usage records are transmitted via the `0x108C` characteristic. Each message corresponds to a single assist mode and contains:
- **Distance**: Cumulative odometer for the mode (in meters).
- **Energy**: Cumulative energy consumed in the mode (in Watt-hours).

### Burst Management (The "1-Second Rule")
Bosch eBikes transmit usage records in a dense burst (typically 5 records within < 1 second). These bursts are separated by larger gaps (5-10 seconds).
- **Timeout Logic**: A **2.0s timeout** is implemented. If a record arrives after a >2s gap, any partial/stale data from a previous interrupted burst is discarded.
- **Selective Updating**: The system status is only updated with a new `totalEnergyFromMotor` value once a **complete** batch (defined by the number of assist modes) is successfully collected.

## 2. Batch Validation & Accuracy

Before any data is used for telemetry or per-mode mapping, the entire burst must pass two rigorous validation checks:

1.  **Uniqueness Verification**: The system checks for duplicate distances in the batch. If non-zero distances are duplicated, the batch is rejected as corrupted (likely due to repeated BLE notifications).
2.  **Odometer Cross-Check**: The sum of distances from all 5 usage records is compared against the bike's master odometer (`totalDistance` / `0x9818`). A tolerance of **1.0 km** is allowed for synchronization delays. If the deviation is larger, the batch is discarded.

## 3. Total Energy Creation

The `totalEnergyFromMotor` sensor in Home Assistant is derived from the sum of energy values in a **validated** batch.
- **Stability**: During the <1s collection window, the application retains the **last known valid total**. This prevents the sensor from "glitching" to 0 or partial values in HA.
- **Precision**: Values are converted from Wh to kWh for standardized reporting.

## 4. Per-Mode Decoding Strategies

Since the `0x108C` message does not contain a mode index, eBikeMonitor employs two parallel strategies to map records to their respective modes (Eco, Tour, Turbo, etc.):

### Strategy A: Consumption-Based (Fallback)
Records are sorted by their **instantaneous consumption** ($\text{Wh} / \text{meter}$). 
- **Assumption**: Higher assist modes (Turbo) always have higher Wh/km than lower modes (Eco).
- **Usage**: Used as a fallback when high-precision trip data is unavailable.

### Strategy B: Delta-Based Discovery (High Precision)
This strategy uses the Trip Distance per Mode (`0xA252`) as a reference.
- **Logic**: For each mode $i$, we know the Trip Distance $\Delta T_i$. We find the usage record $R_j$ whose distance delta relative to a stored baseline matches $\Delta T_i$ within a 30-meter tolerance.
- **Benefit**: Immune to consumption fluctuations and allows for perfect mode identification even if consumption is similar across modes.
- **Note on Discovery**: For a complete mapping, the bike must be ridden a short distance (approx. 50-100 meters) in **each assist mode**. Until a mode has moved, the system cannot mathematically "confirm" its identity through delta-matching.

---

## 5. New Installation & First-Run Flow

When eBikeMonitor is installed for the first time, it undergoes a "Discovery Ritual" to establish baselines:

1.  **Initial Connection**: The app connects and waits for the first valid burst of 5 usage records and the `0x180C` (Mode Names) notification.
2.  **Baseline Capture**:
    *   The app captures the current usage records ($U_{initial}$) and the current trip distances from the Flow app ($T_{initial}$).
    *   It calculates a **Persistent Baseline** for each mode: $B_i = U_{initial,i} - T_{initial,i}$.
3.  **Persistent Storage**: These baselines are saved in the app's encrypted internal storage.
4.  **Session Recovery**: On all subsequent app restarts, the saved baselines are loaded. This allows the app to immediately identify modes and calculate "Current Ride" metrics even if the app was closed during the ride.

---
*Document Version: 1.1 - April 2026*
