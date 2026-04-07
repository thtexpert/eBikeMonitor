# MQTT Topic Reference

eBikeMonitor publishes data using the Home Assistant MQTT Discovery protocol. This document outlines the topic structure and the sensors available for both the eBike and the Battery (PowerTube).

## Device 1: eBike (Bosch Smart System)
**Topic Root**: `ebikemonitor/<device_id>/`  
*(Where `<device_id>` is the lower-case BLE MAC address without colons)*

| Topic | Sensor Name | HA Entity ID (Default) | Unit | Description |
|-------|-------------|-------------------------|------|-------------|
| `speed` | Speed | `sensor.<name>_speed` | km/h | Current travel speed. |
| `cadence` | Cadence | `sensor.<name>_cadence` | rpm | Pedaling cadence. |
| `power` | Human Power | `sensor.<name>_power` | W | Calculated human power input. |
| `motorpower` | Motor Power | `sensor.<name>_motor_power` | W | Instantaneous motor power usage. |
| `assistmode` | Assist Mode | `sensor.<name>_assist_mode` | - | Current mode (e.g., ECO, TOUR+, TURBO). |
| `totaldistance` | Total Distance | `sensor.<name>_total_distance` | km | Total odometer from the drive unit. (**Retained**) |
| `totalenergy` | Total Energy | `sensor.<name>_total_energy` | kWh | **Primary** motor energy usage. (**Retained**) |
| `totalbattery` | Total Battery | `sensor.<name>_total_battery` | kWh | Total energy drained from the pack. (**Retained**) |
| `stateofcharge` | Battery Level | `sensor.<name>_battery_level` | % | Current battery percentage. |
| `status` | App Status | `sensor.<name>_app_status` | - | LWT status (`online`/`offline`). |
| `blestatus` | BLE Status | `sensor.<name>_ble_status` | - | App <-> eBike connection state. |
| `mqttconnecttimestamp`| MQTT Connect Time | `sensor.<name>_last_mqtt_connect_time` | ISO8601 | Last MQTT connection timestamp. (**Retained**) |
| `ebikeledsoftwareversion`| LED Version | `sensor.<name>_ebike_led_software_version` | - | Firmware version of the remote. |
| `batteryserialnumber` | Battery Serial | `sensor.<name>_battery_serial_number` | - | Serial of the current pack. |
| `totalhours` | Total Hours | `sensor.<name>_total_hours` | h | Lifetime drive unit operation hours. (**Retained**) |

> [!NOTE]
> `<name>` is the **eBike Name** you configured in the app settings (e.g., `cubetoni`). Home Assistant automatically prefixes the sensor names with the device name.

### Per-Mode Statistics
- `sensor.<name>_<mode>_distance`: Distance in this mode (km) — **Retained**.
- `sensor.<name>_<mode>_energy`: Energy used in this mode (kWh) — **Retained**.

> [!NOTE]
> Per-mode statistics, as well as **Total Distance**, **Total Energy**, **Total Battery**, and **MQTT Connect Time**, are published as **retained** MQTT messages. This ensures that their last known values are immediately available to Home Assistant even if it restarts.

---

## Device 2: Bosch PowerTube (Battery)
**Topic Root**: `powertube/<serial_number>/`  
*(Where `<serial_number>` is the unique battery serial)*

| Topic | Sensor Name | HA Entity ID (Default) | Unit | Description |
|-------|-------------|-------------------------|------|-------------|
| `stateofcharge` | Charge Level | `sensor.bosch_<model>_charge_level` | % | Current charge level of this pack. |
| `totalbattery` | Total Energy Used | `sensor.bosch_<model>_total_energy_used` | kWh | Lifetime total energy discharged. |
| `chargecycles` | Charge Cycles | `sensor.bosch_<model>_charge_cycles` | cycles | Estimated aggregate charge cycles. |
| `serial` | Hardware Serial | `sensor.bosch_<model>_hardware_serial` | - | The unique hardware identifier. |

> [!NOTE]
> `<model>` is the decoded battery model (e.g., `powertube_750`). Home Assistant uses the device name (e.g., `Bosch PowerTube 750`) to create these entity IDs.

---

## Managing Devices in Home Assistant

Home Assistant allows you to personalize how these devices and sensors appear in your dashboard:

### Renaming Devices
You can rename the device (e.g., from `Bosch PowerTube 750` to `Main Battery`) directly in the Home Assistant **Settings > Devices & Services > MQTT** page.
- When you rename a device, Home Assistant will ask if you want to rename all of its entities as well. 
- **Recommendation**: If you rename the device, allow Home Assistant to rename the entities to keep them consistent (e.g., `sensor.main_battery_charge_level`).

### Manual Entity ID Updates
If you prefer specific naming (e.g., `sensor.ebike_odometer` instead of `sensor.cubetoni_total_distance`), you can edit the **Entity ID** of any individual sensor in the Home Assistant UI:
1. Click on the sensor in HA.
2. Click the **Settings (cog)** icon.
3. Update the **Entity ID** field.
4. **Caution**: If you change an Entity ID, you must update any existing dashboards or automations that use the old ID.

### Handling Hardware Swaps
Because this app uses unique hardware identifiers (MAC for bikes, Serials for batteries), swapping a battery between two bikes will **correctly** keep the battery's energy statistics tied to the battery device, even if the eBike device name changes.
