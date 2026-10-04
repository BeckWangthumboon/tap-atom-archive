# First working prototype: ATOM v1

This repository contains the first working implementation: the ATOM Lite firmware, documented BLE interface, reusable Android button layer, example dictation client, tests, and setup documentation. The plan is to archive the repository for portfolio purposes when development moves to the next hardware implementation. It is not archived yet.

## Baseline

- Hardware: USB-powered M5Stack ATOM Lite, using its active-low GPIO39 switch with 35 ms debounce. Battery, rear mounting, and enclosure design were undecided.
- Interface: a custom BLE service with readable/notifiable version-1 button state and a 32-bit edge sequence number. The device implements no microphone, dictation policy, or AI service.
- Example client: Android app `dev.backbutton`, displayed as Tap, supporting tap-to-toggle and hold-to-talk, phone audio capture, Fish Audio transcription, cross-app text insertion, and copying/recovery.
- Device experience: the user verified the core interactions on the Samsung Galaxy S23+, including LINE and browser fields, and considered the initial UX settled. Everyday reliability still needed exploration.

This is a historical starting point, not a requirement to retain the ATOM or the first protocol forever. Hardware revisions can retain BLE v1 or introduce a documented successor when needed. Build and test results for the baseline do not verify later revisions.

Ignored local files, including API keys, firmware factory backups, captures, and build outputs, are not part of the repository. Firmware backup handling remains in [the firmware setup guide](../../firmware/README.md#factory-backup).
