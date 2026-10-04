# Project context

A general-purpose physical input system for mobile applications: a button on the back of a phone, within reach of the index finger, and a documented BLE interface. The hardware provides presses and releases; each client decides their meaning. Dictation is the first proof-of-concept client, with voice and agent applications among the potential uses.

## Direction

- Keep firmware, the documented BLE protocol, Android button integration, and the example client layered within one repository. Hardware can change; BLE v1 documents the first working interface, and incompatible changes can introduce a new version.
- The example client starts on Android and uses the existing keyboard. Support both tap-to-toggle and hold-to-talk, with feedback when the microphone is ready. Gesture meaning belongs to the client.
- Keep the passive recording indicator compact and near the keyboard when present so it does not obstruct other app controls. The physical button controls recording, including when no field is selected; floating recovery offers only × and Copy.
- Use Fish Audio as the example client's speech-to-text provider for prototype testing. It is not a core project dependency. Other clients can use other services or handle input for entirely different purposes. Avoid reinventing speech recognition.
- Use the physical Samsung for hardware, haptics, grip, and everyday behavior. Emulator checks complement phone testing.

## Current baseline

Recording, Fish Audio transcription, Bluetooth button control, compact cross-app dictation, hold-to-talk, and recording without a selected input field are implemented. The user has verified the core interactions on the Samsung, including LINE and browser text fields. The user considers the current UX settled; the next focus is hardware design. Everyday reliability and hardware ergonomics still need exploration.

This repository is the first implementation and is intended to become a portfolio archive as development moves to the next hardware version; see [the prototype note](docs/prototypes/atom-v1.md). It is not archived yet. `button-android` owns generic BLE input, and the example app owns recording and transcription. After extraction, build, unit tests, lint, and the two native emulator integration checks passed; BLE connected on the Samsung, and the user confirmed the refactored tap/hold interaction works. Future builds still need device checks.

## Documentation

- [README.md](README.md): development commands and phone setup.
- [firmware/README.md](firmware/README.md): physical hardware, flashing, and recovery.
- [protocol/ble-v1.md](protocol/ble-v1.md): first device interface, independent of the ATOM board and dictation behavior.
- [button-android/README.md](button-android/README.md): reusable Android input integration and host responsibilities.
- [roadmap.md](roadmap.md): future work to discuss and prioritize.

The code is the source of truth for implementation details. Keep progress notes brief. Important context intended for future agents must be reviewed by the user before handoff.
