# Battery Calibration Offsets

The eBikeMonitor app allows you to configure per-battery offsets for **Total Electric Energy (kWh)** and **Charge Cycles** to resolve discrepancies caused by historical BMS tracking limitations or firmware update boundaries.

> [!IMPORTANT]
> **When is calibration needed?**
> Calibration offsets are **only** required if the battery was run on a very old BMS firmware (which had cycle/energy tracking bugs) for a long period of time. For batteries that have always run modern firmware, the BMS registers these values accurately, and calibration is not needed.

<img src="images/Calibration.png" width="300" alt="Calibration Screen">

## The Paradox

A physically impossible mismatch can occur where the motor's native mechanical work counter (`0x809C`) exceeds the battery's reported electrical throughput (`0x80B0`):
*   Example: 46.50 kWh mechanical vs. 35.00 kWh electrical (apparent efficiency of >100%).
*   Example: 6,000 km odometer vs. 40.2 charge cycles (an impossible ~150 km per cycle average in Sport/Turbo support).

> [!NOTE]
> **Impact of Off-Bike (External) Charging on `0x80B0`**:
> Empirical testing shows that `0x80B0` only increments when charging via the eBike's frame charging socket. When a battery pack is removed from the bike and charged externally on a desktop charger, `0x8096` (charge cycles) increments normally, but `0x80B0` remains frozen (tracked in [#31](https://github.com/thtexpert/eBikeMonitor/issues/31)). Over time, regular off-bike charging causes `0x80B0` to severely fall behind true electrical throughput, which is a primary contributor to this paradox along with historical BMS tracking limitations on older firmware.

## How Calibration Offsets Work

Since the motor (Drive Unit) correctly tracks lifetime mechanical energy and odometer distance, we can use these values to calibrate the battery's electrical telemetry.

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
3.  The engine will read your active battery's serial number, usable capacity (e.g. 739 Wh), and the motor's cumulative mechanical energy.
4.  It estimates the expected electrical throughput and charge cycles based on a standard **78% electromechanical efficiency**.
5.  Tap **Apply** next to the suggested values to copy them, then tap **Save Offsets** to save them.

## Clearing Offsets

If you wish to remove the calibration offsets and view the raw telemetry reported directly by the BMS, tap **Clear Offsets** inside the calibration screen.
