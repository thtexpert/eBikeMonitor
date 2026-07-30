#!/usr/bin/env python3
"""
Refined Bosch BLE Hex Analyser
Based on BoschParser logic from eBikeMonitor Android App.
"""

import os
import re
import sys
import json
import csv
from collections import defaultdict, Counter
from datetime import datetime

# Optional: matplotlib for plotting
try:
    import matplotlib
    matplotlib.use('Agg') # Non-interactive backend
    import matplotlib.pyplot as plt
    HAS_MATPLOTLIB = True
except ImportError:
    HAS_MATPLOTLIB = False

class BoschHexAnalyser:
    # Known Message IDs and their human-readable names/scaling factors
    KNOWN_TOPICS = {
        0x0081: {"name": "BatterySerial", "type": "string"},
        0x0083: {"name": "BatteryPartNumber", "type": "string"},
        0x0086: {"name": "BatterySoftwareVersion", "type": "string"},
        0x009B: {"name": "BatteryModel", "type": "string"},
        0x0D01: {"name": "DisplaySerial", "type": "string"},
        0x0D02: {"name": "DisplayPartNumber", "type": "string"},
        0x0D03: {"name": "DisplayProductCode", "type": "string"},
        0x0D06: {"name": "DisplaySoftwareVersion", "type": "string"},
        0x0D09: {"name": "DisplayModelName", "type": "string"},
        0x108C: {"name": "UsageRecord", "type": "usage_record"},
        0x1801: {"name": "DriveUnitSerial", "type": "string"},
        0x1802: {"name": "DriveUnitPartNumber", "type": "string"},
        0x1803: {"name": "DriveUnitProductCode", "type": "string"},
        0x1806: {"name": "DriveUnitSoftwareVersion", "type": "string"},
        0x180C: {"name": "AssistModeNames", "type": "string_list"},
        0x2061: {"name": "LEDRemoteSerial", "type": "string"},
        0x2062: {"name": "LEDRemotePartNumber", "type": "string"},
        0x2065: {"name": "LEDRemoteProductCode", "type": "string"},
        0x206B: {"name": "LEDRemoteSoftwareVersion", "type": "string"},
        0x8088: {"name": "BMSBatteryLevel", "unit": "%", "scale": 1.0},
        0x808A: {"name": "ChargingActive", "unit": "Boolean", "scale": 1.0},
        0x808B: {"name": "BatteryTemp", "unit": "°C", "scale": 0.05},
        0x808E: {"name": "BatteryState", "unit": "Enum", "scale": 1.0},
        0x8091: {"name": "UsableMotorEnergy", "unit": "Wh", "scale": 0.1},
        0x8092: {"name": "TotalBatteryEnergy", "unit": "Wh", "scale": 0.1},
        0x8096: {"name": "ChargeCycles", "unit": "cycles", "scale": 0.1},
        0x809C: {"name": "LifetimeTotalEnergy", "unit": "Wh", "scale": 1.0},
        0x80B0: {"name": "LifetimeTotalDischarged", "unit": "Ah", "scale": 0.5},
        0x80B4: {"name": "NominalFactoryCapacity", "unit": "Wh", "scale": 0.1},
        0x80BC: {"name": "BatteryLevel", "unit": "%", "scale": 1.0},
        0x80C4: {"name": "ChargerConnected", "unit": "Boolean", "scale": 1.0},
        0x80C5: {"name": "UsableMotorEnergyBMS", "unit": "Wh", "scale": 0.1},
        0x80CA: {"name": "BMSBatteryLevelAlt", "unit": "%", "scale": 1.0},
        0x80D9: {"name": "ActualUsableCapacity", "unit": "Wh", "scale": 0.1},
        0x80E2: {"name": "ActualUsableCapacityAh", "unit": "Ah", "scale": 0.01},
        0x9809: {"name": "AssistMode", "unit": "ID", "scale": 1.0},
        0x9818: {"name": "TotalDistance", "unit": "km", "scale": 0.001},
        0x9819: {"name": "DriveUnitHours", "unit": "h", "scale": 1.0},
        0x982D: {"name": "Speed", "unit": "km/h", "scale": 0.01},
        0x9857: {"name": "RemainingRanges", "unit": "km", "type": "varint_list"},
        0x985A: {"name": "Cadence", "unit": "rpm", "scale": 0.5},
        0x985B: {"name": "HumanPower", "unit": "W", "scale": 1.0},
        0x985D: {"name": "MotorPower", "unit": "W", "scale": 1.0},
        0xA243: {"name": "TripTotalEnergy", "unit": "Wh", "scale": 0.05},
        0xA246: {"name": "TripSpeed", "unit": "km/h", "scale": 0.01},
        0xA247: {"name": "TripMaxSpeed", "unit": "km/h", "scale": 0.01},
        0xA248: {"name": "TripCadence", "unit": "rpm", "scale": 0.5},
        0xA249: {"name": "TripMaxCadence", "unit": "rpm", "scale": 0.5},
        0xA24A: {"name": "TripHumanPower", "unit": "W", "scale": 1.0},
        0xA24B: {"name": "TripMaxHumanPower", "unit": "W", "scale": 1.0},
        0xA251: {"name": "TripPedalEnergy", "unit": "kcal", "scale": 1.0},
        0xA252: {"name": "TripDistPerMode", "unit": "m", "type": "varint_list"},
        0xA254: {"name": "TripHumanPercentage", "unit": "%", "scale": 1.0},
    }

    def __init__(self):
        self.raw_data = [] # List of (timestamp, hex_string)
        self.parsed_messages = []
        self.stats = defaultdict(lambda: {
            "count": 0,
            "values": [],
            "timestamps": [],
            "raw_payloads": set(),
            "monotonic": True,
            "type_counts": Counter()
        })
        self._last_ts = 0

    def decode_varint(self, data, start_idx=0):
        """Decode a protobuf-style varint from a list of integers."""
        value = 0
        shift = 0
        consumed = 0
        for i in range(start_idx, len(data)):
            b = data[i]
            value |= (b & 0x7F) << shift
            consumed += 1
            if not (b & 0x80):
                break
            shift += 7
        return value, consumed

    def decode_string(self, data, start_idx=0):
        """Decode a length-delimited string (Tag 0x0A)."""
        if start_idx >= len(data): return None, 0
        if data[start_idx] == 0x0A:
            if start_idx + 1 >= len(data): return None, 1
            length = data[start_idx + 1]
            content = data[start_idx + 2 : start_idx + 2 + length]
            try:
                return "".join(map(chr, content)), 2 + length
            except:
                return content.hex(), 2 + length
        return None, 0

    def parse_hex_line(self, line):
        """Extract timestamp and hex from a line. Format can be 'ts, hex' or just 'hex'."""
        line = line.strip()
        if not line: return None
        
        if ',' in line:
            parts = line.split(',', 1)
            try:
                ts = int(parts[0].strip())
                hex_str = parts[1].strip().replace('-', '').replace(' ', '')
                self._last_ts = ts
                return ts, hex_str
            except ValueError:
                pass
        
        # Fallback: whole line is hex, use incrementing counter if no ts available
        hex_str = line.replace('-', '').replace(' ', '')
        if all(c in '0123456789ABCDEFabcdef' for c in hex_str):
            self._last_ts += 100 # Fake 100ms interval
            return self._last_ts, hex_str
        
        return None

    def load_file(self, file_path):
        print(f"Loading {file_path}...")
        self._current_file = file_path
        with open(file_path, 'r') as f:
            for line in f:
                parsed = self.parse_hex_line(line)
                if parsed:
                    ts, hex_str = parsed
                    self.raw_data.append((ts, hex_str))
        print(f"Loaded {len(self.raw_data)} hex records.")

    def process(self):
        for ts, hex_str in self.raw_data:
            try:
                bytes_data = bytes.fromhex(hex_str)
            except ValueError:
                continue

            idx = 0
            while idx < len(bytes_data):
                if idx + 2 > len(bytes_data): break
                
                start_byte = bytes_data[idx]
                if start_byte not in [0x30, 0x10]:
                    idx += 1
                    continue
                
                length = bytes_data[idx+1]
                msg_total_len = length + 2
                if idx + msg_total_len > len(bytes_data): break
                
                msg_bytes = bytes_data[idx : idx + msg_total_len]
                
                # Minimum length for a message with an ID is 2+2=4 bytes
                if len(msg_bytes) < 4:
                    idx += msg_total_len
                    continue
                    
                msg_id = (msg_bytes[2] << 8) | msg_bytes[3]
                msg_id_str = f"0x{msg_id:04X}"
                
                data_type = msg_bytes[4] if len(msg_bytes) > 4 else 0x08
                payload = msg_bytes[5:]
                
                decoded_val = None
                topic_info = self.KNOWN_TOPICS.get(msg_id, {})
                
                # Decoding logic based on type or ID
                if topic_info.get("type") == "usage_record":
                    # 108C logic
                    p_idx = 7 if len(msg_bytes) > 7 and msg_bytes[4] == 0xC0 else 4 # Heuristic based on BoschParser.kt
                    if p_idx < len(msg_bytes) and msg_bytes[p_idx] == 0x0A:
                        sub_len = msg_bytes[p_idx+1]
                        sub_idx = p_idx + 2
                        end_sub = sub_idx + sub_len
                        vals = {}
                        while sub_idx < end_sub and sub_idx < len(msg_bytes):
                            tag = msg_bytes[sub_idx]
                            sub_idx += 1
                            val, consumed = self.decode_varint(msg_bytes, sub_idx)
                            vals[tag] = val
                            sub_idx += consumed
                        decoded_val = vals # Dictionary of tags (0x08: dist, 0x10: energy)
                elif topic_info.get("type") == "varint_list":
                    # A252 logic
                    if len(msg_bytes) > 5 and msg_bytes[4] == 0x0A:
                        sub_len = msg_bytes[5]
                        sub_idx = 6
                        end_sub = sub_idx + sub_len
                        vals = []
                        while sub_idx < end_sub and sub_idx < len(msg_bytes):
                            val, consumed = self.decode_varint(msg_bytes, sub_idx)
                            vals.append(val)
                            sub_idx += consumed
                        decoded_val = vals
                elif topic_info.get("type") == "string_list":
                    # 180C logic
                    vals = []
                    s_idx = 4
                    while s_idx < len(msg_bytes) - 1:
                        s, consumed = self.decode_string(msg_bytes, s_idx)
                        if s: 
                            vals.append(s)
                            s_idx += consumed
                        else:
                            s_idx += 1
                    decoded_val = vals
                elif topic_info.get("type") == "string" or data_type == 0x0A:
                    s, _ = self.decode_string(msg_bytes, 4)
                    if s is None: # Fallback search for string
                        for i in range(len(msg_bytes)-1):
                            s, _ = self.decode_string(msg_bytes, i)
                            if s: break
                    decoded_val = s
                elif data_type == 0x08: # Varint
                    decoded_val, _ = self.decode_varint(msg_bytes, 5)
                    if "scale" in topic_info:
                        decoded_val *= topic_info["scale"]
                
                # Clean up IEEE 754 precision noise from float scaling
                def round_floats(val):
                    if isinstance(val, float):
                        return round(val, 4)
                    elif isinstance(val, dict):
                        return {k: round_floats(v) for k, v in val.items()}
                    elif isinstance(val, list):
                        return [round_floats(v) for v in val]
                    return val
                decoded_val = round_floats(decoded_val)
                
                # Update statistics and handle collections (lists/dicts) for plotting
                def update_stat(sid_str, val, timestamp):
                    s = self.stats[sid_str]
                    s["count"] += 1
                    s["type_counts"][data_type] += 1
                    s["raw_payloads"].add(payload.hex())
                    if isinstance(val, (int, float)):
                        if s["values"] and val < s["values"][-1]:
                            s["monotonic"] = False
                        s["values"].append(val)
                        s["timestamps"].append(timestamp)

                if decoded_val is not None:
                    if isinstance(decoded_val, (int, float)):
                        update_stat(msg_id_str, decoded_val, ts)
                    elif isinstance(decoded_val, list):
                        for i, v in enumerate(decoded_val):
                            sub_id = f"{msg_id:04X}_{i}"
                            sub_name = f"{topic_info.get('name', 'Unknown')}_{i}"
                            if sub_id not in self.KNOWN_TOPICS:
                                self.KNOWN_TOPICS[sub_id] = {"name": sub_name, "unit": topic_info.get("unit", "Value")}
                            update_stat(sub_id, v, ts)
                    elif isinstance(decoded_val, dict):
                        for k, v in decoded_val.items():
                            sub_id = f"{msg_id:04X}_{k}"
                            sub_name = f"{topic_info.get('name', 'Unknown')}_{k}"
                            if sub_id not in self.KNOWN_TOPICS:
                                self.KNOWN_TOPICS[sub_id] = {"name": sub_name, "unit": topic_info.get("unit", "Value")}
                            update_stat(sub_id, v, ts)
                    
                self.parsed_messages.append({
                    "ts": ts,
                    "id": msg_id_str,
                    "name": topic_info.get("name", "Unknown"),
                    "value": decoded_val,
                    "unit": topic_info.get("unit", ""),
                    "raw": msg_bytes.hex()
                })
                
                idx += msg_total_len

    def print_summary(self):
        print("\n" + "="*80)
        print(f"{'ID':<8} | {'Name':<18} | {'Count':<6} | {'Types':<10} | {'Unique Payloads':<8} | {'Notes'}")
        print("-" * 80)
        for sid_str in sorted(self.stats.keys()):
            stat = self.stats[sid_str]
            # Try to get topic info from string ID or partial string ID
            topic = None
            if sid_str in self.KNOWN_TOPICS:
                topic = self.KNOWN_TOPICS[sid_str]
            else:
                try:
                    base_id = int(sid_str.split('_')[0], 16)
                    topic = self.KNOWN_TOPICS.get(base_id, {})
                except:
                    topic = {}

            name = topic.get("name", "Unknown")
            types = ",".join([f"0x{t:02X}" for t in stat["type_counts"] if t is not None])
            
            notes = []
            if len(stat["raw_payloads"]) == 1:
                notes.append("Static")
            else:
                if stat["monotonic"] and len(stat["values"]) > 1:
                    notes.append("Monotonic")
                if len(stat["values"]) > 0:
                    notes.append("Changing")
                else:
                    notes.append("Complex/Unknown")
            
            note_str = ", ".join(notes)
            print(f"{sid_str:<8} | {name:<18} | {stat['count']:<6} | {types:<10} | {len(stat['raw_payloads']):<15} | {note_str}")
        print("="*80 + "\n")

    def plot(self):
        if not HAS_MATPLOTLIB:
            print("Matplotlib not found. Skipping plots.")
            return

        # Filter for topics with numeric values and variation
        plot_topics = []
        for msg_id, stat in self.stats.items():
            if len(stat["values"]) > 1 and len(set(stat["values"])) > 1:
                plot_topics.append(msg_id)

        if not plot_topics:
            print("No changing numeric data found for plotting.")
            return

        num_plots = len(plot_topics)
        cols = 2
        rows = (num_plots + cols - 1) // cols
        
        plt.figure(figsize=(15, 4 * rows))
        for i, sid_str in enumerate(sorted(plot_topics)):
            plt.subplot(rows, cols, i + 1)
            stat = self.stats[sid_str]
            # Try to get topic info
            topic = None
            if sid_str in self.KNOWN_TOPICS:
                topic = self.KNOWN_TOPICS[sid_str]
            else:
                try:
                    base_id = int(sid_str.split('_')[0], 16)
                    topic = self.KNOWN_TOPICS.get(base_id, {})
                except:
                    topic = {}
            
            # Normalize timestamps to seconds from start
            t0 = stat["timestamps"][0]
            times = [(t - t0) / 1000.0 for t in stat["timestamps"]]
            
            plt.plot(times, stat["values"], '.-', label=sid_str)
            plt.title(f"{topic.get('name', 'Unknown')} ({sid_str})")
            plt.xlabel("Time (s)")
            plt.ylabel(topic.get("unit", "Value"))
            plt.grid(True, alpha=0.3)
            
        plt.tight_layout()
        plot_path = os.path.join(os.path.dirname(self._current_file), f"{os.path.basename(self._current_file)}_plot.png")
        plt.savefig(plot_path)
        print(f"Plot saved to {plot_path}")

    def export_csv(self, output_path):
        if not self.parsed_messages: return
        keys = self.parsed_messages[0].keys()
        with open(output_path, 'w', newline='', encoding='utf-8') as f:
            writer = csv.DictWriter(f, fieldnames=keys)
            writer.writeheader()
            for msg in self.parsed_messages:
                # Convert complex values to JSON string for CSV compatibility
                row = msg.copy()
                if isinstance(row["value"], (dict, list)):
                    row["value"] = json.dumps(row["value"])
                writer.writerow(row)
        print(f"Exported data to {output_path}")

def main():
    if len(sys.argv) < 2:
        print("Usage: python hex_analyser.py <extracted_hex_file.txt>")
        return

    file_path = sys.argv[1]
    if not os.path.exists(file_path):
        print(f"Error: {file_path} not found.")
        return

    analyser = BoschHexAnalyser()
    analyser.load_file(file_path)
    analyser.process()
    analyser.print_summary()
    
    # Export
    base = os.path.splitext(file_path)[0]
    analyser.export_csv(f"{base}_processed.csv")
    
    # Plot
    analyser.plot()

if __name__ == "__main__":
    main()
