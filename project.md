# Back Button

A personal experiment: a programmable physical interface for AI on mobile. Inspired by Wispr Flow, the idea is to put a consistent, tactile trigger under the index finger on the back of a phone. Start with tap-to-toggle dictation and explore other AI actions once the interaction works.

## First experience

Press the rear physical button once to start recording, feel a vibration when the microphone is ready, speak, and press again to stop and transcribe. Initially show the result in our app; later insert it into the focused text field in another app. A floating indicator shows recording/processing status and can be tapped during early testing. The physical button is the intended everyday control.

## Working decisions

| Area | Decision |
| --- | --- |
| Platform | Android first; native Kotlin app with Jetpack Compose for the small setup/status UI. |
| Development host | MacBook initially; eventually explore a VM with remote hardware access. |
| Cross-app integration | Investigate accessibility-based insertion first so the existing keyboard stays usable; retain a small overlay for feedback and testing. |
| Test phone | Samsung Galaxy S23+ (SM-S916U), running Android 16. One UI version is unconfirmed. |
| Hardware | Existing M5Stack ATOM Lite, USB-powered initially. Mounting and battery design come later. |
| Button firmware | Keep it simple: debounce and send press/release events through a custom BLE service. Interpret gestures on the phone. |
| Phone responsibilities | BLE connection, microphone capture, recording feedback, transcription requests, and eventual text insertion or actions. |
| Speech recognition | Reuse an existing service/model. Try Fish Audio first; keep the transcription provider replaceable. |
| AI cleanup | Optional later step for punctuation/filler removal, preserving meaning. Raw transcription comes first. |

## Speech-to-text

Fish Audio supports both speech generation and recognition. Its [speech-to-text API](https://docs.fish.audio/api-reference/endpoint/openapi-v1/speech-to-text) accepts recorded audio at `POST /v1/asr`. First verify API access and whether the existing credits apply, then test short recordings for accuracy and turnaround time. This initial route requires internet access and sends recorded audio to Fish Audio.

Offline recognition is a realistic later experiment: [whisper.cpp provides an Android example](https://github.com/ggml-org/whisper.cpp/tree/master/examples/whisper.android) recommending tiny or base models. Benchmark on the actual phone before choosing an offline default; acceptable speed, accuracy, heat, and battery use are unproven for our use case.

## Development and Android constraints

Use the physical Samsung as the primary test device for the ATOM connection, microphone access, cross-app insertion, grip, haptics, reconnection, and Samsung background behavior. The emulator is optional for UI, simulated button events, and transcription integration; current tooling includes [simulated Bluetooth support](https://developer.android.com/studio/run/emulator-networking).

The main feasibility risk is starting the microphone while another app is open. A BLE event or companion-device association does not automatically bypass [Android's background microphone restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). A visible overlay is not a blanket microphone exemption either. Test on-screen and BLE triggers separately, including after idle time and service restart.

[Wispr Flow's Android setup](https://docs.wisprflow.ai/articles/8858845757) confirms the observed design: keep the existing keyboard, show a floating bubble, and use an accessibility service to detect focused text fields and insert dictation. Setup includes display-over-other-apps and microphone permissions, accessibility access, and background/battery configuration. Its controls support tap-to-toggle and hold-to-talk. These public docs establish behavior and permissions, not its exact internal microphone lifecycle.

Use that interaction as our reference. Investigate Android accessibility text/paste actions, preserving existing text and cursor placement; keep manual paste as a fallback. A custom keyboard is a fallback architecture, not the first choice. Start with tap-to-toggle; hold-to-talk can follow. Develop on stock Android with a locally installed debug app; an Android fork is outside scope.

## Current implementation

The app now supports on-screen start/stop recording, readiness vibration, an elapsed timer, local playback, and deletion. It keeps one AAC/M4A clip in app-private storage and stops capture/playback when the activity pauses, including leaving the app, locking, or rotation. Microphone permission is requested on first use; recording requires a fresh tap after granting permission. Tapping Stop now sends the saved M4A clip to Fish Audio (`transcribe-1-pro`) and displays copyable text. Interrupted recording does not start an upload. The API key is provisioned from the ignored `.env` file into app-private storage over ADB; it is not included in the APK. Hardware control is still pending.

Verified on the Samsung over wireless ADB: installation and launch succeeded, and the user confirmed recording and playback work well. Build and Android lint passed with zero errors and two existing dependency-update warnings. Permission denial, interruptions, and saved-clip persistence remain to be verified on-device.

Fish API access was verified separately using a short synthetic M4A speech clip: the API returned HTTP 200 and the expected transcript. The transcription build passes with zero lint errors; the user verified recording → Stop → transcript on the Samsung. The transcript appeared correctly on the phone, and no Android runtime crash was logged.

## Milestones

1. **App foundation:** build and launch the Kotlin/Compose starter on the Samsung via USB debugging.
2. **Foreground dictation:** tap once to record in our visible app, tap again to stop and transcribe with Fish Audio, display the result. Signal readiness only after capture starts.
3. **Cross-app feasibility:** validate accessibility insertion and an on-screen recording control on the Samsung. Check existing text, cursor placement, and focus changes; document setup and restrictions.
4. **Physical control:** connect ATOM press/release events to the same toggle behavior. Verify reconnection and stop safely on disconnect, then try a rear mount and measure latency, errors, accidental presses, and comfort.

## Open checks and later scope

Confirm the One UI version in Settings → About phone / Software information, plus ongoing Fish API credit availability. Choose the background recording approach after device testing.

Later possibilities: offline transcription, optional LLM cleanup, configurable gestures, app actions, a smaller battery-powered button, and iOS investigation. The first prototype is complete when physical tap-to-toggle dictation reliably produces a transcript in our Android app; cross-app use is the next milestone.
