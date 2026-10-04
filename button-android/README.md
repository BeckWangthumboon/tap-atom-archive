# Android button integration

A small Android library for [button BLE v1](../protocol/ble-v1.md). It discovers the device, establishes a state baseline, validates ordered notifications, and reports press, release, and interrupted continuity. It has no dependency on the example app, Compose, recording, Fish Audio, or text insertion. The current minimum Android API is 33, matching the prototype client.

Within this repository, depend on it with:

```kotlin
implementation(project(":button-android"))
```

Construct `dev.backbutton.button.BleButtonConnection` with an Android context, `onPress`, `onRelease`, `onSignalLost`, and an optional `onStatusChanged` callback. Status is a typed `BleButtonConnection.Status`, with `connected` and `busy` convenience properties. All methods and callbacks use the main thread. Display text belongs to the host.

The library manifest declares BLE scan/connect permissions and optional BLE hardware; the host requests runtime Nearby devices access. Call `resume()` when listening is allowed, then `connect()` to select a device. A successful selection is saved in app-private preferences and reused on subsequent resumes. Call `disconnect()` to forget it and select a replacement. `pause()` retains selection, closes the connection, and stops retry work. `close()` disposes the instance permanently. Cancel host gestures before intentionally pausing or closing.

The host owns background-service lifecycle and application policy. For example, `app/` uses its existing dictation foreground service for listening across apps, adapts typed status in `PhysicalButtonClient`, and wires events into `DictationSession` in `BackButtonApplication`. An initial read never starts a gesture. On signal loss, cancel any action that requires continuous input; this callback can also report setup failure before any input was received.

This is an internal reusable module, not a published SDK. Current reconnection limits and event-loss behavior are documented in the protocol reference. Another client can use the module or implement the wire protocol itself.

Verify the module independently:

```sh
./gradlew :button-android:testDebugUnitTest :button-android:lintDebug
```
