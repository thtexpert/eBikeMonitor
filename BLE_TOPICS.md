# BLE Topics Summary

This document summarizes the Bluetooth Low Energy (BLE) message IDs (topics) used by the eBikeMonitor app to communicate with Bosch eBike systems.

## Global Message Structure
All messages received from the Bosch BLE characteristic (`00000011-eaa2-11e9-81b4-2a2ae2dbcce4`) follow a common chunking pattern:
- `Byte 0`: Start Byte (`0x30`)
- `Byte 1`: Message Length (excluding header)
- `Byte 2-3`: Message ID (Big Endian)
- `Byte 4-N`: Payload (encoded as Varints, Strings, or Protobuf-like structures)

## BLE Topics (Message IDs)

| Key (Hex) | Sensor Name | Data Type / Decoding | Comment |
| :--- | :--- | :--- | :--- |
| `0x0081` | Battery Serial Number | String (`decodeStringField`) | Decoded from UTF-8 bytes. |
| `0x009B` | Battery Model | String (`decodeStringField`) | Decoded from UTF-8 bytes. |
| `0x108C` | Usage Record | Protobuf-like | Contains Distance (Tag 1, Varint) and Energy (Tag 2, Varint). Used for per-mode consumption tracking. |
| `0x180C` | Assist Mode Names | List of Strings (`decodeAssistModes`) | Length-delimited UTF-8 strings. Typically starts with 'Off', followed by active support modes from lowest to highest support (e.g. Eco, Tour, Sport, Turbo). |
| `0x206B` | LED Software Version | String (`decodeStringField`) | Software version of the Control Unit / LED Remote. |
| `0x8088` | BMS Battery Level | `Int` (%) | Raw Battery State of Charge (SOC) reported directly by the BMS. Typically maintains a +1% to +2% offset above the UI Battery Level (0x80BC). |
| `0x808A` | Charging Active | `Int` (Boolean) | 1 = Charging active/power drawing, 0/Absent = inactive. |
| `0x808B` | Battery Temperature | `Int` / 20.0 (°C) | Current battery cell temperature. |
| `0x808E` | Battery State | `Int` (Enum) | Current battery operating mode. 2 = Discharging/Ride, 12 = Charging. |
| `0x8091` | Usable Motor Energy | `Int` / 10.0 (Wh) | Remaining usable motor energy. Correlates linearly with SOC. |
| `0x8092` | Total Battery Energy | `Int` / 10.0 (Wh) | Total remaining battery energy. Exactly 30.0 Wh higher than 0x8091 (reserved lights buffer). |
| `0x8096` | Charge Cycles | `Int` / 10.0 | Cumulative number of full charge cycles. |
| `0x809C` | Lifetime Total Energy | `Int` (Wh) | Cumulative total energy discharged from the battery over its lifetime. |
| `0x80B4` | Nominal Factory Capacity| `Int` / 10.0 (Wh) | Nominal design capacity of the battery at the factory (e.g. 743.0 Wh for PowerTube 750). |
| `0x80BC` | Battery Level | `Int` (%) | Current UI state of charge in percent. |
| `0x80C4` | Charger Connected | `Int` (Boolean) | 1 = Charger connected, 0/Absent = disconnected. |
| `0x80D9` | Actual Usable Capacity | `Int` / 10.0 (Wh) | Current maximum healthy usable capacity (SOH capacity) of the battery cells (e.g. 713.0 Wh). |
| `0x9809` | Assist Mode Index | `Int` | Current assist level index (0 = Off, etc). |
| `0x9818` | Total Distance | `Int` / 1000.0 (km) | System odometer. |
| `0x9819` | Drive Unit Hours | `Int` | Total operating hours of the motor unit. |
| `0x982D` | Speed | `Int` / 100.0 (km/h) | Real-time speed. |
| `0x9857` | Remaining Ranges | List of Varints (`decodeRanges`) | List of estimated remaining ranges in km corresponding to the active assist modes (elements 1..N of the Assist Mode Names list). |
| `0x985A` | Cadence | `Int` / 2 (rpm) | Pedal rotations per minute. |
| `0x985B` | Human Power | `Int` (W) | Rider's power input. |
| `0x985D` | Motor Power | `Int` (W) | Electrical power output from the motor. |
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
| `0x10` (Tag 2) | Energy | Varint (Wh) |

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
Motor electrical consumption from the battery ($E_{\text{motor, elec}}$ in `Wh`) is converted to mechanical assist force on the drivetrain using an average brushless DC motor efficiency of **79%**:
$$E_{\text{motor, mech}} (\text{Wh}) = E_{\text{motor, elec}} (\text{Wh}) \times 0.79$$

### 3. Trip Total Energy (Mechanical Wh)
Corresponds to BLE topic `0xA243`. It is the sum of mechanical work done by both the motor and the rider:
$$E_{\text{trip\_total, mech}} (\text{Wh}) = E_{\text{motor, mech}} (\text{Wh}) + E_{\text{rider, mech}} (\text{Wh})$$
$$E_{\text{trip\_total, mech}} (\text{Wh}) = \left( E_{\text{motor, elec}} (\text{Wh}) \times 0.79 \right) + \left( E_{\text{rider, metabolic}} (\text{kcal}) \times 0.29075 \right)$$

### 4. Trip Human Percentage (%)
Corresponds to BLE topic `0xA254`. It represents the proportion of total mechanical energy contributed by the rider:
$$\text{Trip Human Percentage} (\%) = \frac{E_{\text{rider, mech}} (\text{Wh})}{E_{\text{trip\_total, mech}} (\text{Wh})} \times 100$$



