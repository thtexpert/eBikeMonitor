# MQTT Topic Reference

eBikeMonitor publishes data using the Home Assistant MQTT Discovery protocol. This document outlines the topic structure and the sensors available for both the eBike and the Battery (PowerTube).

## Device 1: eBike (Bosch Smart System)
**Topic Root**: `ebikemonitor/<device_id>/`  
*(Where `<device_id>` is the lower-case BLE MAC address without colons)*

| Topic | Sensor Name | HA Entity ID (Default) | Unit | Description |
|-------|-------------|-------------------------|------|-------------|
| `speed` | Speed | `sensor.<name>_speed` | km/h | Current travel speed. |
| `cadence` | Cadence | `sensor.<name>_cadence` | rpm | Pedaling cadence. |
| `power` | Human Power | `sensor.<name>_power` | W | Rider real-time human power input. |
| `motorpower` | Motor Power | `sensor.<name>_motor_power` | W | Instantaneous motor electrical power usage. |
| `assistmode` | Assist Mode | `sensor.<name>_assist_mode` | - | Current assist level name. |
| `totaldistance` | Total Distance | `sensor.<name>_total_distance` | km | Total odometer from the drive unit. (**Retained**) |
| `totalenergy` | Total Energy | `sensor.<name>_total_energy` | kWh | Cumulative motor energy usage. (**Retained**) |
| `totalbattery` | Total Battery | `sensor.<name>_total_battery` | kWh | Cumulative battery energy usage. (**Retained**) |
| `stateofcharge` | Battery Level | `sensor.<name>_battery_level` | % | Current UI battery percentage. |
| `status` | App Status | `sensor.<name>_app_status` | - | LWT status (`online`/`offline`). |
| `blestatus` | BLE Status | `sensor.<name>_ble_status` | - | BLE connection state. |
| `mqttconnecttimestamp`| MQTT Connect Time | `sensor.<name>_last_mqtt_connect_time` | ISO8601 | Last MQTT connection timestamp. (**Retained**) |
| `ebikeledsoftwareversion`| LED Version | `sensor.<name>_ebike_led_software_version` | - | Firmware version of the remote. |
| `batteryserialnumber` | Battery Serial | `sensor.<name>_battery_serial_number` | - | Serial of the current battery pack. |
| `totalhours` | Total Hours | `sensor.<name>_total_hours` | h | Lifetime drive unit operation hours. (**Retained**) |
| `pedal_energy_measured` | Pedal Energy (Measured) | `sensor.<name>_pedal_energy_measured` | kcal | Active-session cumulative human effort. (**Retained**) |
| `pedal_energy_estimated`| Pedal Energy (Estimated) | `sensor.<name>_pedal_energy_estimated` | kcal | Estimated human effort during offline segments. (**Retained**) |
| `pedal_energy_total` | Pedal Energy (Total) | `sensor.<name>_pedal_energy_total` | kcal | Total cumulative human effort (measured + estimated). (**Retained**) |
| `trip_pedal_energy` | Trip Pedal Energy | `sensor.<name>_trip_pedal_energy` | kcal | Rider effort for the current trip. |
| `trip_total_energy` | Trip Total Energy | `sensor.<name>_trip_total_energy` | Wh | Total mechanical energy (rider + motor) for the trip. |
| `trip_human_percentage`| Trip Human Percentage | `sensor.<name>_trip_human_percentage` | % | Rider contribution split percentage for the trip. |
| `trip_speed` | Trip Avg Speed | `sensor.<name>_trip_speed` | km/h | Average speed for the current trip. |
| `trip_max_speed` | Trip Max Speed | `sensor.<name>_trip_max_speed` | km/h | Maximum speed reached in the current trip. |
| `trip_cadence` | Trip Avg Cadence | `sensor.<name>_trip_cadence` | rpm | Average cadence for the current trip. |
| `trip_max_cadence` | Trip Max Cadence | `sensor.<name>_trip_max_cadence` | rpm | Maximum cadence reached in the current trip. |
| `trip_human_power` | Trip Avg Human Power | `sensor.<name>_trip_human_power` | W | Average human power for the current trip. |
| `trip_max_human_power`| Trip Max Human Power | `sensor.<name>_trip_max_human_power` | W | Maximum human power for the current trip. |

> [!NOTE]
> `<name>` is the **eBike Name** you configured in the app settings (e.g., `cubetoni`). Home Assistant automatically prefixes the sensor names with the device name.

### Per-Mode Statistics
- `sensor.<name>_<mode>_distance`: Distance in this mode (km) — **Retained**.
- `sensor.<name>_<mode>_energy`: Energy used in this mode (kWh) — **Retained**.
- `sensor.<name>_<mode>_range`: Dynamic remaining range in this mode (km), generated dynamically for all active support modes (derived from BLE topic `0x9857` range list index `r` mapped to Assist Mode index `r + 1` of decoded names).

> [!NOTE]
> Per-mode statistics, as well as **Total Distance**, **Total Energy**, **Total Battery**, and **MQTT Connect Time**, are published as **retained** MQTT messages. This ensures that their last known values are immediately available to Home Assistant even if it restarts.

---

## Device 2: Bosch PowerTube (Battery)
**Topic Root**: `powertube/<serial_number>/`  
*(Where `<serial_number>` is the unique battery serial)*

| Topic | Sensor Name | HA Entity ID (Default) | Unit | Description |
|-------|-------------|-------------------------|------|-------------|
| `stateofcharge` | Charge Level | `sensor.bosch_<model>_charge_level` | % | Current charge level of this pack. |
| `totalbattery` | Total Energy Used | `sensor.bosch_<model>_total_energy_used` | kWh | Lifetime total energy discharged (`0x809C`). (**Retained**) |
| `usable_energy_remaining` | Usable Energy Remaining | `sensor.bosch_<model>_usable_energy_remaining` | Wh | Remaining usable motor energy (`0x8091`). (**Retained**) |
| `nominal_capacity` | Nominal Design Capacity | `sensor.bosch_<model>_nominal_design_capacity` | Wh | Nominal factory design capacity (`0x80B4`). (**Retained**) |
| `usable_capacity` | Actual Usable Capacity | `sensor.bosch_<model>_actual_usable_capacity` | Wh | Actual maximum healthy usable capacity / SOH (`0x80D9`). (**Retained**) |
| `chargecycles` | Charge Cycles | `sensor.bosch_<model>_charge_cycles` | cycles | Cumulative full charge cycles (`0x8096`). (**Retained**) |
| `temperature` | Battery Temperature | `sensor.bosch_<model>_battery_temperature` | °C | Current battery cell temperature (`0x808B`). |
| `charging` | Charging State | `binary_sensor.bosch_<model>_charging` | - | Charging active binary state (state is `12` or charging active flag is `1`). |
| `serial` | Hardware Serial | `sensor.bosch_<model>_hardware_serial` | - | The unique hardware serial identifier (`0x0081`). (**Retained**) |

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

---

## Message Retention & Missing Data Strategy

To ensure data integrity and avoid corrupting graphs or alerts in Home Assistant, sensors follow specific retention and null-handling rules:

### 1. Persistent (Retained) vs. Transient (Non-Retained) Topics

* **Persistent (Retained)**: Messages are flagged with `retain=true`. The MQTT broker stores the last published value permanently. When Home Assistant restarts or a new dashboard is loaded, it immediately retrieves these values without waiting for the eBike to wake up and transmit.
  * *Odometer & Lifetime Totals*: `totaldistance`, `totalhours`, `totalenergy`, `totalbattery`, `pedal_energy_measured`, `pedal_energy_estimated`, `pedal_energy_total`.
  * *Configuration & Metadata*: `serial`, `batteryserialnumber`, `ebikeledsoftwareversion`.
  * *Degradation & Health Capacity*: `nominal_capacity`, `usable_capacity`, `chargecycles`, `usable_energy_remaining`.
  * *Per-Mode History*: `<mode>_distance`, `<mode>_energy`.
* **Transient (Non-Retained)**: Messages are flagged with `retain=false` (default). Values are dynamic and real-time. When the bike turns off, stale values are not held on the broker.
  * *Live Riding Dynamics*: `speed`, `cadence`, `power`, `motorpower`, `assistmode`.
  * *Battery Dynamic Metrics*: `stateofcharge`, `charging`, `temperature`.
  * *Trip & Session Statistics*: `trip_pedal_energy`, `trip_total_energy`, `trip_human_percentage`, and trip average/maximum metrics.
  * *Dynamic Ranges*: `<mode>_range`.

### 2. Missing Data Handling: Default to Zero vs. Fallback to Unknown

* **Default to 0**: Real-time active riding metrics default to `0` if no active telemetry is received from BLE while the bike is active (e.g. coasting, stopped at a light).
  * *Sensors*: `speed`, `cadence`, `power`, `motorpower`.
* **Fallback to Unknown (Null)**: Static metadata, state variables, and lifetime accumulators MUST NOT default to `0` when missing. Doing so would trigger false alerts (e.g. 0% SoC, 0°C temperature) or break database history statistics. They will report as `unknown` or remain unset in Home Assistant until valid data is decoded.
  * *Sensors*: `stateofcharge`, `temperature`, `charging`, `totaldistance`, `totalenergy`, `totalbattery`, `usable_capacity`, `nominal_capacity`, `usable_energy_remaining`, `serial`, `batteryserialnumber`, `ebikeledsoftwareversion`.
