# Back Button

A personal experiment: a programmable physical interface for AI on mobile. Inspired by Wispr Flow, the idea is to put a consistent, tactile trigger under the index finger on the back of a phone. Start with tap-to-toggle dictation and explore other AI actions once the interaction works.

## First experience

Press the rear physical button once to start recording, feel a vibration when the microphone is ready, speak, and press again to stop and transcribe. Show the result in our app; with cross-app dictation enabled, also insert it into the original unchanged text field in a supported app. A floating indicator shows recording/processing status and can be tapped during early testing. The physical button is the intended everyday control.

## Working decisions

| Area | Decision |
| --- | --- |
| Platform | Android first; native Kotlin app with Jetpack Compose for the small setup/status UI. |
| Development host | MacBook initially; eventually explore a VM with remote hardware access. |
| Cross-app integration | Use Android 13+ accessibility input connections for insertion, retaining the existing keyboard and a compact accessibility overlay. |
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

Use that interaction as our reference. Use Android accessibility input connections, preserving existing text and cursor placement; keep manual copying as a fallback. A custom keyboard is a fallback architecture, not the first choice. Start with tap-to-toggle; hold-to-talk can follow. Develop on stock Android with a locally installed debug app; an Android fork is outside scope.

## Next software experience

The first cross-app targets are LINE's message composer and a normal browser text field. Keep the existing keyboard. When an eligible input is focused and the keyboard is open, show a compact, low-profile control immediately above the keyboard. Avoid a large floating circle that covers app controls or intercepts taps outside the control itself. The user's reference in LINE Keep Memo shows Wispr Flow's control overlapping the browser's Temu shortcut and label. Use this as an obstruction test: keep the overlay narrow, near the keyboard edge, and repositionable. An overlay still occupies screen space, so verify its position with real app controls. Focusing an input makes the control available; it does not start microphone capture.

Develop this in two steps:

1. **Cross-app tap-to-toggle:** first prove microphone capture while LINE or the browser is foreground, then connect the compact overlay and text insertion. Tap to start, tap Stop to finish and transcribe, and insert at the intended cursor or selection while preserving surrounding text. Keep the physical ATOM toggle connected to the same session. Handle focus changes so a delayed result is not inserted into the wrong field.
2. **Hold-to-talk:** add press-and-hold recording with release to stop and transcribe, while retaining tap-to-toggle. Verify gesture cancellation, microphone readiness, and one transcription per completed recording. Hold-to-talk is not implemented yet.

Use [Wispr Flow's setup docs](https://docs.wisprflow.ai/articles/8858845757) as a reference for microphone, display-over-other-apps, accessibility, and Samsung background settings. Its [bubble sizing docs](https://docs.wisprflow.ai/articles/2807859589-customize-flow-bubble-size-and-shrink-behavior-on-android) also describe resizing and automatic shrinking. These describe public behavior and setup, not the internal service architecture. Choose our implementation from Android documentation and tests on the Samsung; prove cross-app microphone behavior before polishing the overlay. Apply app-specific battery settings if needed rather than assuming every recovery instruction is required.

## Current implementation

The app now supports on-screen and ATOM Lite BLE start/stop recording, readiness vibration, an elapsed timer, local playback, and deletion. It keeps one AAC/M4A clip in app-private storage. Recordings made in the setup app stop when that activity pauses; cross-app recordings use an explicitly enabled ready foreground service. Locking stops recording and playback without uploading. Microphone permission is requested on first use; recording requires a fresh tap or press after granting permission. Stopping normally sends the saved M4A clip to Fish Audio (`transcribe-1-pro`) and displays copyable text. Interrupted recording does not start an upload. The API key is provisioned from the ignored `.env` file into app-private storage over ADB; it is not included in the APK.

Verified on the Samsung over wireless ADB: installation and launch succeeded, and the user confirmed recording and playback work well. Build and Android lint passed with zero errors and two existing dependency-update warnings. Permission denial, interruptions, and saved-clip persistence remain to be verified on-device.

Fish API access was verified separately using a short synthetic M4A speech clip: the API returned HTTP 200 and the expected transcript. The transcription build passes with zero lint errors; the user verified recording → Stop → transcript on the Samsung. The transcript appeared correctly on the phone, and no Android runtime crash was logged.

ATOM Lite hardware was confirmed from the purchase link and USB chip inspection (ESP32-PICO-D4). The Mac detected its FTDI USB serial interface through the user's USB-C cable. The factory flash was backed up locally before replacement. Firmware under `firmware/` now logs debounced press/release edges over USB and advertises a custom BLE read/notify service. The firmware builds and uploads successfully, and the USB listener received its readiness message. Native debounce checks pass for switch bounce, holds, release, boot baseline, and timer rollover. During physical testing, the Mac received seven complete press/release pairs with consecutive sequence numbers 1–14 and no unmatched or repeated-state edges. Actual hold durations were not logged.

The Android Bluetooth build is installed on the Samsung. It scans for the firmware service, subscribes to notifications, reads an initial baseline, and routes fresh press edges to the same recording toggle as the screen control. It remembers the connected device, reconnects when the app resumes, and makes one automatic attempt after an unexpected disconnection. Duplicate and stale notifications cannot toggle recording again; missing events or a lost connection stop capture without uploading. Background button control is now implemented through the cross-app ready service, but is not yet verified on the Samsung. Build and lint pass with zero lint errors, and six unit tests cover held-button connection, duplicate/stale events, missed edges, reconnect/reboot baselines, sequence rollover, and malformed packets. Android logs confirm connection and physical press/release reception. The user verified physical press → recording with vibration → second press → transcript on the Samsung. Sending the app to Home and reopening it produced another successful connection without an extra press event. Signal loss during capture and automatic reconnect after an unexpected disconnection remain to be verified on-device.

The cross-app implementation shares one microphone/transcription/BLE session between the setup screen and services. A microphone foreground service is enabled from the visible app before leaving it, with a persistent ready notification and Turn off action. It does not capture audio until a user starts a recording. It is not automatically restarted after process death; enable it again from the setup screen.

An accessibility service uses Android 13+ [`InputMethod.AccessibilityInputConnection`](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection) to insert at the editor's selection while retaining the existing keyboard. The allowed apps are LINE, Chrome, and Samsung Internet. A 112 × 40 dp draggable Record/Stop control appears above the keyboard and intercepts only its own touch area. It uses `TYPE_ACCESSIBILITY_OVERLAY`, so no separate display-over-other-apps permission is required. Hold-to-talk is still deferred.

The original editor generation, surrounding text, and selection are captured locally on Start and checked when the transcript arrives. Switching fields interrupts capture without uploading; editor, text, or cursor changes prevent delayed insertion and retain the transcript for copying. Password editors are excluded. Surrounding text is neither persisted nor uploaded. The implementation commits only the dictated replacement through the input connection, preserving the rest of the field and never submitting a message or form. Setup instructions are in [README.md](README.md#cross-app-dictation-setup-on-samsung).

Verification so far: build and lint pass, and 14 JVM tests cover BLE events and text insertion/spacing/selection guards. On an API 37 emulator, the control starts microphone capture with Chrome foreground, Stop saves the M4A, dragging does not record, and closing the keyboard hides the idle control. One opt-in Chrome instrumentation test also passes: inserting a synthetic transcript replaces selected text without losing surrounding text, moved cursors and changed fields reject delayed results, password editors cannot be captured, and switching fields during recording finalizes the clip without transcription. The update is now installed on the Samsung at home. Its Fish key was retained, the user enabled accessibility and cross-app dictation, and both services are running, with the ready service foreground under microphone and connected-device types. The user confirmed the compact control appears and that all three requested interactions work: Record → Stop inserts a transcript directly into the LINE composer, browser insertion at a cursor preserves existing text, and changing fields during capture stops recording without inserting into the new field. The browser package was not specified. Samsung idle/background behavior and physical cross-app presses still need confirmation. The emulator has no Fish key.

## Milestones

1. **App foundation:** build and launch the Kotlin/Compose starter on the Samsung via USB debugging.
2. **Foreground dictation:** tap once to record in our visible app, tap again to stop and transcribe with Fish Audio, display the result. Signal readiness only after capture starts.
3. **Cross-app feasibility:** validate accessibility insertion and an on-screen recording control on the Samsung. Check existing text, cursor placement, and focus changes; document setup and restrictions.
4. **Physical control:** connect ATOM press/release events to the same toggle behavior. Verify reconnection and stop safely on disconnect, then try a rear mount and measure latency, errors, accidental presses, and comfort.

## Open checks and later scope

Confirm the One UI version in Settings → About phone / Software information, plus ongoing Fish API credit availability. Verify the ready foreground service after idle time and Samsung background management.

Later possibilities: offline transcription, optional LLM cleanup, configurable gestures, app actions, a smaller battery-powered button, and iOS investigation. The first prototype is now verified: physical tap-to-toggle dictation produces a transcript in our Android app, and the user confirmed it continues to work during further testing. Cross-app tap-to-toggle dictation is implemented and user-verified in LINE and a browser on the Samsung; mounting, battery power, and a smaller enclosure are the next hardware experiments.
