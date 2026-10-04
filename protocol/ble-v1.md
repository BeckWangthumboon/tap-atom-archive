# Button BLE interface v1

This documents the first working interface, implemented by the ATOM Lite firmware and the Android button library. It is a prototype contract independent of board, case, microphone, transcription provider, and application behavior. A different device may implement it. Incompatible wire-format or event-semantics changes need a documented successor version; clients must reject unsupported versions rather than interpreting them as button events.

## Discovery and GATT

The device is a BLE peripheral/GATT server; the phone is a central/GATT client. Advertising includes the service UUID. Discover by service UUID, not by the display name `BackButton ATOM`, which is specific to the initial hardware.

| Item | Value | Behavior |
| --- | --- | --- |
| Button service | `b8b10001-64df-4f6d-b7d1-86a6e72f8d21` | Advertised custom service. |
| State/event characteristic | `b8b10002-64df-4f6d-b7d1-86a6e72f8d21` | Read current state; receive notifications for debounced edges. No writes. |
| Client Characteristic Configuration Descriptor (CCCD) | `00002902-0000-1000-8000-00805f9b34fb` | Write `01 00` to enable notifications. |

The first firmware tracks one active client; simultaneous app connections and routing among clients are not defined. It does not configure authenticated pairing or bonding. The Android library remembers a successfully connected device address in app-private preferences; that selection is not BLE bonding. Explicit disconnect forgets the saved address so a replacement device can be selected.

## Packet format

Reads and notifications use the same six-byte payload. All bytes are unsigned.

| Offset | Length | Meaning |
| --- | --- | --- |
| 0 | 1 | Version: exactly `01`. |
| 1 | 1 | Debounced switch state: `00` released, `01` pressed. Other values are invalid. |
| 2 | 4 | Unsigned 32-bit edge sequence, least-significant byte first. |

Examples:

```text
01 00 00 00 00 00  released, sequence 0
01 01 01 00 00 00  pressed, sequence 1
01 00 02 00 00 00  released, sequence 2
01 01 00 01 00 00  pressed, sequence 256
```

The firmware sets sequence 0 and reads the physical switch at boot, including if already held. Each debounced state transition increments the sequence modulo 2^32, including transitions while disconnected. A hold emits one press and one release, with no repeat events. The initial ATOM implementation uses 35 ms debounce; gesture thresholds are client policy, not part of the packet.

The readable value is the latest state. Notifications carry updates while connected; there is no replay queue, application acknowledgement, or event timestamp in v1. Long press and double press are not separate device events. Clients may derive them from edges; their timing will include BLE/phone scheduling latency.

## Connection initialization

The Android reference client:

1. Connects, discovers the service, and enables local notifications.
2. Writes the CCCD and waits for success.
3. Reads the characteristic, validates it, and stores it as the baseline.
4. Marks the connection ready and accepts subsequent notifications.

A read establishes state; it must not create a synthetic press. Notifications before readiness are ignored by the reference client. If the initial state is held, its eventual release may be reported, but a client must not treat it as completion of a press it never accepted. The dictation client waits for a fresh press. Reconnection discards previous sequence tracking and establishes a new baseline; device reboot can reset the counter.

## Event continuity

For a validated notification, calculate `distance = (nextSequence - previousSequence) modulo 2^32`.

- Distance 0, or greater than `0x7fffffff`: ignore a duplicate/old packet without updating the baseline.
- Distance 1 with a changed pressed state: update the baseline and emit press or release.
- Any other forward distance, or a forward packet with unchanged state: update the baseline, report interrupted continuity, and do not emit an edge for that packet. The next valid edge can be accepted.
- Wrong length, unsupported version, or invalid state byte: reject the signal. The Android connection closes and reports failure.

Sequence numbers detect a gap only when a later packet arrives; they cannot guarantee delivery or immediately detect a lost final release. A disconnected link is a separate interruption. Clients should cancel any gesture that depends on continuous input and wait for fresh input. The example client cancels capture without uploading the interrupted clip; other clients choose their own response.

## Host lifecycle and current limits

The Android library reports generic callbacks and connection status on the main thread. The host supplies runtime permissions and decides when to resume/pause listening, including any background service. `pause()` closes the link but retains device selection; `close()` permanently disposes that connection instance. The host should cancel its own active gesture before intentionally pausing/closing. `onSignalLost` reports a gap, connection/setup failure, or explicit disconnect; it can occur without an active gesture.

The current reference client uses 15-second setup timeouts and one automatic scan attempt 1.5 seconds after losing a ready connection while listening is enabled. Failed scans/setups otherwise need an explicit retry. These are current client choices, not requirements on other implementations. Persistent background reconnection, power management, battery reporting, multiple buttons, and additional protocol versions remain future work.
