# Project context

A personal experiment in a physical interface for AI on mobile: a button on the back of a phone, within reach of the index finger. Dictation is the first use case; the hardware interaction is the main focus.

## Direction

- Start on Android and use the existing keyboard. Support both tap-to-toggle and hold-to-talk, with feedback when the microphone is ready.
- Keep the passive recording indicator compact and near the keyboard when present so it does not obstruct other app controls. The physical button controls recording, including when no field is selected; floating recovery offers only × and Copy.
- Use Fish Audio as the main speech-to-text provider for prototype testing. Later, experiment with other existing providers or local models. Avoid reinventing speech recognition; treat it as a supporting layer beneath the hardware interaction.
- Use the physical Samsung for hardware, haptics, grip, and everyday behavior. Emulator checks complement phone testing.

## Current baseline

Recording, Fish Audio transcription, Bluetooth button control, compact cross-app dictation, hold-to-talk, and recording without a selected input field are implemented. The user has verified the core interactions on the Samsung, including LINE and browser text fields. Everyday reliability and hardware ergonomics still need exploration.

## Documentation

- [README.md](README.md): development commands and phone setup.
- [firmware/README.md](firmware/README.md): physical hardware, flashing, and recovery.
- [roadmap.md](roadmap.md): future work to discuss and prioritize.

The code is the source of truth for implementation details. Keep progress notes brief. Important context intended for future agents must be reviewed by the user before handoff.
