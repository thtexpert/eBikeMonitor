# BLE Topics Summary

This document summarizes the Bluetooth Low Energy (BLE) message IDs (topics) used by the eBikeMonitor app to communicate with Bosch eBike systems.

## Global Message Structure
All messages received from the Bosch BLE characteristic (`00000011-eaa2-11e9-81b4-2a2ae2dbcce4`) follow a common chunking pattern:
- `Byte 0`: Start Byte (`0x30`)
- `Byte 1`: Message Length (excluding header)
- `Byte 2-3`: Message ID (Big Endian)
- `Byte 4-N`: Payload (encoded as Varints, Strings, or Protobuf-like structures)

## Electrical vs. Mechanical Energy

To correctly interpret telemetry, it is vital to distinguish between two distinct energy realms in the reported data:

1. **Electrical Energy (BMS-based)**
   - Tracks chemical/electrical capacity and energy stored inside or discharged directly from the battery cells.
   - **Key Topics**:
     - `0x80B0` (Lifetime Total Energy Throughput): Cumulative electrical energy throughput (in 0.1 kWh / 100 Wh units).
     - `0x8092` / `0x80C5` / `0x8091` (Remaining Energy): Remaining electrical energy in the pack (in 0.1 Wh units).
     - `0x80E2` / `0x80B4` / `0x80D9` (SOH Capacities): Usable/nominal electrical energy capacity in Wh/Ah.
     - `0x8096` (Charge Cycles): Cumulative number of full charge cycles (increments only during charging, representing one complete charge of the battery pack's usable capacity).

2. **Mechanical Energy (Motor-based)**
   - Tracks actual mechanical work delivered at the spindle/crankshaft.
   - **Key Topics**:
     - `0x108C` (Usage Record Energy): Cumulative mechanical energy delivered by the motor per assist mode (in Wh).
     - `0x809C` (Lifetime Total Mechanical Energy): Cumulative motor mechanical work over the lifespan of the battery pack (matches the sum of all `0x108C` mode energies).
     - `0xA243` (Trip Total Energy): Total mechanical work done by both the motor and the rider during the current trip (in 0.05 Wh units).

3. **Connecting Efficiency**
   The two realms are related directly by the electromechanical efficiency of the motor/drivetrain ($\approx 78\%$):
   $$\text{Mechanical Energy} = \text{Electrical Energy} \times 0.78$$
   For example, if your trip reports consuming **100 Wh (mechanical)** of motor work, the battery cell pack will have discharged **~133 Wh (electrical)**, which corresponds to a **~19% SOC drop** on a 702 Wh usable battery pack.

## BLE Topics (Message IDs)

The following tables group the BLE topics (Message IDs) by hardware unit and functional category:

### Battery Management System (BMS)

| Key (Hex) | Sensor Name | Data Type / Decoding | Comment |
| :--- | :--- | :--- | :--- |
| `0x0081` | Battery Serial Number | String (`decodeStringField`) | Decoded from UTF-8 bytes. |
| `0x0083` | Battery Part Number | String (`decodeStringField`) | Part/model number of the battery pack (e.g. `BBP3770`). |
| `0x0086` | Battery Software Version | String (`decodeStringField`) | Firmware version of the battery BMS (e.g. `19.17.1`). |
| `0x009B` | Battery Model | String (`decodeStringField`) | Decoded from UTF-8 bytes. |
| `0x8088` | BMS Battery Level | `Int` (%) | Raw Battery State of Charge (SOC) reported directly by the BMS. Typically maintains a +1% to +2% offset above the UI Battery Level (0x80BC). (from BMS) |
| `0x808A` | Charging Active | `Int` (Boolean) | 1 = Charging active/power drawing, 0/Absent = inactive. (from BMS) |
| `0x808B` | Battery Temperature | `Int` / 20.0 (°C) | Current battery cell temperature. (from BMS) |
| `0x808E` | Battery State | `Int` (Enum) | Current battery operating mode. 2 = Discharging/Ride, 12 = Charging. (from BMS) |
| `0x8092` | Total Battery Energy | `Int` / 10.0 (Wh) | Total remaining battery energy. Exactly 30.0 Wh higher than 0x8091 (reserved lights buffer). (from BMS) |
| `0x8096` | Charge Cycles | `Int` / 10.0 | Cumulative number of full charge cycles (increments only during charging). (from BMS) |
| `0x8097` | BMS Chip Serial | Protobuf submessage | Diagnostic battery serial / hardware ID (nested structure `1a0a08...`). |
| `0x809C` | Lifetime Total Mechanical Energy | `Int` (Wh) | Cumulative total mechanical energy delivered by the motor over the lifetime of this battery pack (from BMS, matches motor mechanical work). |
| `0x80B0` | Lifetime Total Energy Throughput | `Int` / 10.0 (kWh) | Cumulative electrical energy throughput over the battery pack's lifetime. 1 raw unit = 0.1 kWh. **(Not implemented in app)** |
| `0x80B1` | SOH Parameter | Semi-static | BMS capacity calibration index (updates step-wise as SOH degrades). |
| `0x80B4` | Actual Capacity (Wh) | `Int` / 10.0 (Wh) | Actual maximum healthy usable capacity of the battery cell pack in Wh (e.g. 743.0 Wh). Calculated internally by the BMS as Ah (0x80E2) * 36.0V nominal. |
| `0x80C5` | Usable Motor Energy (BMS)| `Int` / 10.0 (Wh) | Alternate/redundant usable motor energy. (from BMS) |
| `0x80CA` | BMS Battery Level | `Int` (%) | Raw Battery State of Charge (SOC) reported by the BMS. **(Not implemented in app)** |
| `0x80D9` | Usable Motor Capacity | `Int` / 10.0 (Wh) | Maximum healthy capacity usable by the motor drive unit in Wh (e.g. 713.0 Wh). Exactly 30.0 Wh lower than Actual Capacity (0x80B4), representing the safety reserve for lights/system. |
| `0x80E2` | Actual Usable Capacity (Ah) | `Int` / 100.0 (Ah) | Actual maximum healthy usable capacity of the battery cells in Ampere-hours (e.g. 20.60 Ah). |

### Drive Unit (DU) / Motor

| Key (Hex) | Sensor Name | Data Type / Decoding | Comment |
| :--- | :--- | :--- | :--- |
| `0x1801` | Drive Unit Serial Number | String | Serial number of the motor unit (e.g. `12345-6789-01-A01-00-0000`). |
| `0x1802` | Drive Unit Part Number | String | Part number of the motor unit (e.g. `EB11 100 001`). |
| `0x1803` | Drive Unit Product Code | String (`decodeStringField`) | Product code of the motor unit (e.g. `BDU3741`). |
| `0x1806` | Drive Unit Software Version | String (`decodeStringField`) | Firmware version of the motor drive unit (e.g. `19.54.0`). |
| `0x182E` | Drive Unit Model | String (`decodeStringField`) | Model name of the motor unit (e.g. `Drive Unit Performance Line CX`). |
| `0x180C` | Assist Mode Names | List of Strings (`decodeAssistModes`) | Length-delimited UTF-8 strings. Typically starts with 'Off', followed by active support modes from lowest to highest support (e.g. Eco, Tour, Sport, Turbo). |
| `0x8091` | Usable Motor Energy (DU)| `Int` / 10.0 (Wh) | Remaining usable motor energy. Correlates linearly with SOC. (from Drive Unit) |
| `0x9818` | Total Distance | `Int` / 1000.0 (km) | System odometer. |
| `0x9819` | Drive Unit Hours | `Int` | Total operating hours of the motor unit. |
| `0x982D` | Speed | `Int` / 100.0 (km/h) | Real-time speed. |
| `0x9857` | Remaining Ranges | List of Varints (`decodeRanges`) | List of estimated remaining ranges in km corresponding to the active assist modes (elements 1..N of the Assist Mode Names list). |
| `0x985A` | Cadence | `Int` / 2 (rpm) | Pedal rotations per minute. |
| `0x985B` | Human Power | `Int` (W) | Rider's power input. |
| `0x985D` | Motor Power | `Int` (W) | Electrical power output from the motor. |

### LED Remote

| Key (Hex) | Sensor Name | Data Type / Decoding | Comment |
| :--- | :--- | :--- | :--- |
| `0x2061` | LED Remote Serial Number | String | Serial number of the control unit (e.g. `12345-6789-01-A01-00-0000`). |
| `0x2062` | LED Remote Part Number | String | Part number of the control unit (e.g. `EB13 100 00E`). |
| `0x2065` | LED Remote Product Code | String | Product code of the control unit (e.g. `BRC3600`). |
| `0x206B` | LED Remote Software Version | String (`decodeStringField`) | Software version of the Control Unit / LED Remote (e.g. `19.22.0`). |

### Display / System Controller

| Key (Hex) | Sensor Name | Data Type / Decoding | Comment |
| :--- | :--- | :--- | :--- |
| `0x0D01` | Display Serial Number | String | Serial number of the display (e.g. `12345-6789-01-A01-00-0000`). |
| `0x0D02` | Display Part Number | String | Part number of the display (e.g. `EB13 100 003`). |
| `0x0D03` | Display Product Code | String | Product code of the display mount / system controller mount (e.g. `BHU3600`). |
| `0x0D06` | Display Software Version | String (`decodeStringField`) | Firmware version of the display (e.g. `19.11.0`). |
| `0x0D09` | Display Model Name | String | Model name of the display unit (e.g. `Kiox 300`). |
| `0x80BC` | Battery Level | `Int` (%) | Current UI state of charge in percent. (from Display / System Controller) |
| `0x80C4` | Charger Connected | `Int` (Boolean) | 1 = Charger connected, 0/Absent = disconnected. (from BMS/System Controller) |

### Ride & Trip Statistics

| Key (Hex) | Sensor Name | Data Type / Decoding | Comment |
| :--- | :--- | :--- | :--- |
| `0x108C` | Usage Record | Protobuf-like | Contains Distance (Tag 1, Varint) and Energy (Tag 2, Varint). Used for per-mode consumption tracking. |
| `0x9809` | Assist Mode Index | `Int` | Current assist level index (0 = Off, etc). |
| `0xA243` | Trip Total Energy | `Int` / 20.0 (Wh) | Total mechanical energy (rider + motor) for the trip. 1 raw unit = 0.05 Wh (20 units/Wh). Used by Kiox to compute support percentage split with 25% human efficiency. |
| `0xA246` | Trip Speed | `Int` / 100.0 (km/h) | Average speed for the current trip. |
| `0xA247` | Trip Max Speed | `Int` / 100.0 (km/h) | Maximum speed reached in the current trip. |
| `0xA248` | Trip Cadence | `Int` / 2 (rpm) | Average cadence for the current trip. |
| `0xA249` | Trip Max Cadence | `Int` / 2 (rpm) | Maximum cadence reached in the current trip. |
| `0xA24A` | Trip Human Power | `Int` (W) | Average human power for the current trip. |
| `0xA24B` | Trip Max Human Power | `Int` (W) | Maximum human power reached in the current trip. |
| `0xA251` | Trip Pedal Energy | `Int` (kcal) | Pedal energy in kcal, matches FLOW and KIOX display. |
| `0xA252` | Trip Dist per Mode | List of Varints (`decodeTripDistPerMode`) | List of distances traveled in each assist mode for the current trip. |
| `0xA254` | Trip Human Percentage | `Int` (%) | Rider contribution percentage for the trip. Starts at 100.0% if motor support is OFF, and decreases as motor support contributes. |


## Specialized Decoding Details

### Varint Decoding
Used for most numeric fields and protobuf tags. It uses a standard LEB128 encoding (7-bit payloads with the 8th bit as a continuation flag).

### Usage Record (0x108C) Internal Tags
| Tag (Hex) | Field | Decoding |
| :--- | :--- | :--- |
| `0x08` (Tag 1) | Distance | Varint (meters) |
| `0x10` (Tag 2) | Energy | Varint (mechanical Wh) |

### Assist Modes (0x180C)
The payload contains multiple consecutive string blocks. Each block starts with `0x0A` (Tag 1, Wire Type 2), followed by a length byte and the UTF-8 string data.

### Remaining Ranges (0x9857)
The payload contains a list of Varints under tag `0x0A` (Field 1, Wire Type 2). It contains the remaining battery range in km corresponding to the active assist modes in order of increasing support (mapping to index `r + 1` of the Assist Mode Names list, where `r` is the 0-based range index).

## Calculated & Derived Trip Metrics

The Bosch system calculates total mechanical effort and the ratio of human-to-motor assistance using the following conversions:

### 1. Rider Mechanical Work
Rider metabolic input (in `kcal` from `0xA251`) is converted to mechanical pedal output (in `Wh`) using a standard human metabolic efficiency of **25%** and the thermal conversion factor $1 \text{ kcal} \approx 1.163 \text{ Wh}$:
$$E_{\text{rider, mech}} (\text{Wh}) = E_{\text{rider, metabolic}} (\text{kcal}) \times 1.163 \times 0.25 \approx E_{\text{rider, metabolic}} (\text{kcal}) \times 0.29075$$

To convert in reverse (from mechanical Wh on pedals to calories burned):
$$\text{Pedal Energy} (\text{kcal}) = E_{\text{rider, mech}} (\text{Wh}) \times 3.44 \quad \left(\text{since } \frac{1}{0.29075} \approx 3.44\right)$$

### 2. Motor Mechanical Work
Motor mechanical work ($E_{\text{motor, mech}}$ in `Wh`) is reported directly by the Drive Unit via `0x108C` (Usage Records) and cumulative lifetime `0x809C`. It is related to the electrical energy throughput of the battery cells ($E_{\text{battery, elec\_throughput}}$ from `0x80B0` in Wh) using an average electromechanical drivetrain efficiency of **~78%**:
$$E_{\text{motor, mech}} (\text{Wh}) = E_{\text{battery, elec\_throughput}} (\text{Wh}) \times 0.78$$

### 3. Trip Total Energy (Mechanical Wh)
Corresponds to BLE topic `0xA243`. It is the sum of mechanical work done by both the motor and the rider:
$$E_{\text{trip\_total, mech}} (\text{Wh}) = E_{\text{motor, mech}} (\text{Wh}) + E_{\text{rider, mech}} (\text{Wh})$$
$$E_{\text{trip\_total, mech}} (\text{Wh}) = E_{\text{motor, mech}} (\text{Wh}) + \left( E_{\text{rider, metabolic}} (\text{kcal}) \times 0.29075 \right)$$

### 4. Trip Human Percentage (%)
Corresponds to BLE topic `0xA254`. It represents the proportion of total mechanical energy contributed by the rider:
$$\text{Trip Human Percentage} (\%) = \frac{E_{\text{rider, mech}} (\text{Wh})}{E_{\text{trip\_total, mech}} (\text{Wh})} \times 100$$



