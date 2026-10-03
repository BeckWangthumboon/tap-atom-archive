#!/usr/bin/env python3
"""Show ATOM Lite USB press/release events without resetting it on open."""
import argparse
import serial

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--port', required=True)
args = parser.parse_args()
connection = serial.Serial(port=None, baudrate=115200, timeout=0.25)
connection.dtr = False
connection.rts = False
connection.port = args.port
connection.open()
print('Listening for button presses. Press Ctrl+C to stop.', flush=True)
try:
    while True:
        line = connection.readline()
        if line:
            print(line.decode('utf-8', errors='replace').rstrip(), flush=True)
except KeyboardInterrupt:
    pass
finally:
    connection.close()
