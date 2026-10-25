# Battery Charge Cycles Calibration

The eBikeMonitor app allows you to configure per-battery offsets for **Charge Cycles** to resolve discrepancies caused by historical BMS tracking limitations on older firmware versions.

> [!IMPORTANT]
> **When is calibration needed?**
> Calibration offsets are **only** required if the battery was run on a very old BMS firmware (which had charge cycle tracking bugs) for a long period of time. For batteries that have always run modern firmware, the BMS registers charge cycles accurately, and calibration is not needed.

<img src="images/Calibration.png" width="300" alt="Calibration Screen">

## The Discrepancy

When an eBike battery was operated on older BMS firmware for an extended period, it may have failed to record all charge cycles (`0x8096`). Over time, this leads to an unrealistic discrepancy where high odometer mileage or cumulative motor mechanical work (`0x809C`) exists alongside an impossibly low charge cycle count:
*   Example: 6,000 km odometer vs. 40.2 charge cycles (an impossible ~150 km per cycle average in Sport/Turbo support).

> [!NOTE]
> **Clarification on `0x80B0`**:
> Earlier versions attempted to calibrate an electrical energy offset using `0x80B0`. Empirical investigation revealed that `0x80B0` is **not** electrical energy, but specifically **On-Bike Charge Cycles** (frame socket charging), which stays frozen during off-bike external charging (tracked in [#31](https://github.com/thtexpert/eBikeMonitor/issues/31)). Because `0x80B0` does not track external charging, it has been discontinued in the app and does not require calibration. Calibration applies strictly to **Charge Cycles (`0x8096`)**.

## How Calibration Offsets Work

Since the motor (Drive Unit) correctly tracks lifetime mechanical energy and odometer distance, we can use these reliable values to estimate true cumulative charge cycles.

Offsets are stored on a **per-battery** basis, identified by the battery's unique hardware serial number (`0x0081`). When the app connects to the eBike, the corresponding offsets are loaded and applied automatically:
1.  They are displayed in the App UI.
2.  They are applied before publishing metrics to Home Assistant via MQTT.
3.  This keeps the dashboard and smart home integration physically consistent.

### Battery Swapping & Multi-Device Setup
*   **Automatic Profile Switching**: When swapping battery packs (e.g., between primary and secondary packs), the app detects the new serial number and immediately applies that battery pack's specific offsets without manual re-entry.
*   **Multi-Phone Isolation**: Battery profiles are saved locally in each phone's secure app storage (`DataStore`). If a battery is shared across multiple family bikes and smartphones, configure the desired calibration offsets once on each device.
*   **Unregistered Batteries**: If a newly inserted battery has no profile saved yet, eBikeMonitor defaults offsets to `0.0` and processes incoming BMS metrics immediately with complete data isolation, preventing stale metric carry-over from the previous battery.

## Using the Suggestion Engine

To make calibration simple, the Calibration screen includes a suggestion engine:

1.  Connect your smartphone to the eBike.
2.  Navigate to **Settings** -> **Calibration Offsets**.
3.  The engine will read your active battery's serial number, usable capacity (e.g. 739 Wh), and the motor's cumulative mechanical energy (`0x809C`).
4.  It estimates the expected charge cycles using the motor mechanical work and a standard 78% electromechanical drivetrain efficiency:
    $$\text{Expected Cycles} = \frac{E_{\text{motor, mech}} (\text{kWh}) / 0.78}{\text{Usable Capacity} (\text{kWh})}$$
5.  Tap **Apply** next to the suggested charge cycles to copy it, then tap **Save Offsets** to save.

## Clearing Offsets

If you wish to remove the calibration offsets and view the raw telemetry reported directly by the BMS, tap **Clear Offsets** inside the calibration screen.
