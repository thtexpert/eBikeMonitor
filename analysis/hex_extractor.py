#!/usr/bin/env python3
"""
nRF Connect Hex Message Extractor

This script extracts hex messages and timestamps from nRF Connect log files.
It looks for lines containing: "(0x) [hex-data]" received
"""

import re
import sys
import os

def extract_hex_messages(input_file, output_file):
    """
    Extract hex messages from nRF Connect (Android) or iOS log file.
    
    Args:
        input_file: Path to the input log file
        output_file: Path to the output text file for hex messages
    
    Returns:
        int: Number of hex messages extracted
    """
    
    # Pattern to match Android lines with hex messages and timestamps
    # Matches: HH:MM:SS.mmm ... (0x) [HEX-DATA] ... received
    android_pattern = r'([0-9]{2}):([0-9]{2}):([0-9]{2})\.([0-9]{3}).*?\(0x\)\s+([A-F0-9\-]+)"?\s+received'
    
    # Pattern to match iPhone lines with hex messages and timestamps
    # Matches: [HH:MM:SS.mmm] ... "[HEX-DATA]" value received
    iphone_pattern = r'([0-9]{2}):([0-9]{2}):([0-9]{2})\.([0-9]{3}).*?Application:\s+"([A-F0-9\s]+)"\s+value\s+received'
    
    hex_messages = []
    
    try:
        with open(input_file, 'r', encoding='utf-8') as file:
            for line_num, line in enumerate(file, 1):
                match = re.search(android_pattern, line, re.IGNORECASE)
                if not match:
                    match = re.search(iphone_pattern, line, re.IGNORECASE)
                
                if match:
                    hour = int(match.group(1))
                    minute = int(match.group(2))
                    second = int(match.group(3))
                    ms = int(match.group(4))
                    
                    # Calculate total milliseconds from start of day
                    timestamp_ms = ms + 1000 * (second + 60 * (minute + 60 * hour))
                    
                    # Unify formatting: uppercase, remove spaces/dashes, format with dashes every byte
                    raw_hex = match.group(5)
                    clean_hex = raw_hex.replace('-', '').replace(' ', '').upper()
                    hex_data = '-'.join(clean_hex[i:i+2] for i in range(0, len(clean_hex), 2))
                    
                    # Print timestamp and hex to console
                    print(f"{timestamp_ms}, {hex_data}")
                    hex_messages.append(hex_data)
                    
                    if len(hex_messages) <= 10:
                        # (Already printed above)
                        pass
                    elif len(hex_messages) == 11:
                        print("... (continuing extraction)")
        
        # Write extracted results to output file
        with open(output_file, 'w', encoding='utf-8') as file:
            for msg in hex_messages:
                file.write(msg + '\n')
        
        print(f"\nExtraction complete!")
        print(f"Found {len(hex_messages)} hex messages")
        print(f"Output saved to: {output_file}")
        
        return len(hex_messages)
    
    except FileNotFoundError:
        print(f"Error: Could not find file '{input_file}'")
        return 0
    except Exception as e:
        print(f"Error processing file: {e}")
        return 0

def main():
    if len(sys.argv) < 2:
        print("Usage: python hex_extractor.py <input_file> [output_file]")
        sys.exit(1)
    
    # Determine output directory
    script_dir = os.path.dirname(os.path.abspath(__file__))
    output_dir = os.path.join(script_dir, "hex")
    
    # Ensure output directory exists
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)
    
    # Generate output filename if not provided
    input_file = sys.argv[1]
    if len(sys.argv) >= 3:
        output_file = sys.argv[2]
        # If output_file is just a name, put it in output_dir
        if not os.path.isabs(output_file) and os.sep not in output_file:
            output_file = os.path.join(output_dir, output_file)
    else:
        base_name = os.path.splitext(os.path.basename(input_file))[0]
        output_file = os.path.join(output_dir, f"{base_name}_hex.txt")
    
    if not os.path.exists(input_file):
        print(f"Error: Input file '{input_file}' does not exist.")
        sys.exit(1)
    
    print(f"Processing: {input_file}")
    print("-" * 50)
    
    count = extract_hex_messages(input_file, output_file)
    
    if count > 0:
        print(f"\n[OK] Successfully extracted {count} hex messages!")
    else:
        print("\n[FAIL] No hex messages found or extraction failed.")

if __name__ == "__main__":
    main()
