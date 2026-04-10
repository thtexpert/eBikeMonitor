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
| `0x180C` | Assist Mode Names | List of Strings (`decodeAssistModes`) | Length-delimited UTF-8 strings. Typically: Off, Eco, Tour, Sport, Turbo. |
| `0x206B` | LED Software Version | String (`decodeStringField`) | Software version of the Control Unit / LED Remote. |
| `0x8096` | Charge Cycles | `Int` / 10.0 | Cumulative number of full charge cycles. |
| `0x809C` | Total Battery Energy | `Int` / 1000.0 (kWh) | This sensor likely reports total battery capacity or cumulative energy. |
| `0x80BC` | Battery Level | `Int` (%) | Current state of charge in percent. |
| `0x9809` | Assist Mode Index | `Int` | Current assist level index (0 = Off, etc). |
| `0x9818` | Total Distance | `Int` / 1000.0 (km) | System odometer. |
| `0x9819` | Drive Unit Hours | `Int` | Total operating hours of the motor unit. |
| `0x982D` | Speed | `Int` / 100.0 (km/h) | Real-time speed. |
| `0x985A` | Cadence | `Int` / 2 (rpm) | Pedal rotations per minute. |
| `0x985B` | Human Power | `Int` (W) | Rider's power input. |
| `0x985D` | Motor Power | `Int` (W) | Electrical power output from the motor. |
| `0xA252` | Trip Dist per Mode | List of Varints (`decodeTripDistPerMode`) | List of distances traveled in each assist mode for the current trip. |

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
