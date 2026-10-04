# ATOM Lite button firmware

## Physical setup

The prototype uses an M5Stack ATOM Lite (ESP32-PICO-D4) and its built-in active-low button on GPIO39. USB powers the board and provides a serial port for programming and verifying presses. Phone control uses Bluetooth; no external button wires or phone USB connection are needed.

The development host is a MacBook. Both a direct USB-C connection and the user's multiport USB hub have provided working serial access. The board uses an FTDI USB serial interface; select its current `/dev/cu.*` port rather than assuming a saved path. Rear mounting, battery power, and enclosure design are still undecided.

## Build and flash

Install PlatformIO in an isolated Python environment, then build and flash the intended board:

```sh
python3 -m venv "$HOME/Library/Caches/back-button-tools/venv"
source "$HOME/Library/Caches/back-button-tools/venv/bin/activate"
python -m pip install -r firmware/requirements.txt
pio run -d firmware
pio run -d firmware -t upload --upload-port /dev/cu.YOUR_BOARD
```

The upload speed is deliberately 115200: the development Mac's FTDI connection failed at 460800, while 115200 worked for flash reads. Do not open a serial monitor during flashing.

To watch the USB signal (requires `pyserial`, included with PlatformIO):

```sh
python firmware/monitor_button.py --port /dev/cu.YOUR_BOARD
```

Press the large built-in button, release it, and look for:

```text
BUTTON PRESS seq=1
BUTTON RELEASE seq=2
```

Each debounced edge gets a new sequence number. The switch must remain stable for 35 ms, so a hold generates one press and one release, not repeated presses. The small reset switch restarts the board instead.

## Phone connection

Power the ATOM over USB and follow [the Android setup instructions](../README.md#samsung-setup). The phone interprets clicks and holds; this firmware has no microphone or transcription service. BLE identifiers and packet format live in `src/main.cpp` and the Android button client.

## Debounce checks

The native checks simulate a bouncing press, a long hold, release bounce, a button held during boot, and timer rollover:

```sh
c++ -std=c++11 -Wall -Wextra -Werror firmware/tests/button_debounce_test.cpp -o /tmp/back-button-debounce-test
/tmp/back-button-debounce-test
```

## Factory backup

The original 4 MiB flash backup is local under the Git-ignored `firmware/backups/`. To restore a confirmed backup to this same board using esptool:

```sh
python -m esptool --port /dev/cu.YOUR_BOARD --baud 115200 write-flash 0 firmware/backups/YOUR_FACTORY_BACKUP.bin
```

Hardware pin mapping and board configuration: [M5Stack ATOM Lite docs](https://docs.m5stack.com/en/core/Atom-Lite), [PlatformIO board docs](https://docs.platformio.org/en/latest/boards/espressif32/m5stack-atom.html).
