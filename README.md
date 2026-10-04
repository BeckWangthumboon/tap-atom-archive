# Back Button

A personal Android experiment: a physical BLE button on the back of a phone for AI dictation. See [project.md](project.md) for scope and decisions.

## Current state

This repository contains a Kotlin / Jetpack Compose app with on-screen and ATOM Lite Bluetooth recording controls, playback, Fish Audio transcription, and a compact cross-app dictation control for LINE, Chrome, and Samsung Internet. Stopping normally uploads the saved clip to Fish Audio and displays a transcript with a Copy button. Cross-app recordings also insert the result at the original cursor or selection if the editor is still unchanged. Audio and the latest transcript are kept in app-private storage.

Tap **Start recording**, allow microphone access on first use, then tap **Start recording** again. A short vibration signals that capture has started. Tap **Stop recording** to upload and transcribe, then **Play recording** to listen. You can also tap **Transcribe recording** to send an existing clip, or **Retry transcription** after a failure. Only the latest clip is kept; starting another recording replaces it after microphone capture starts successfully. **Delete recording** removes the clip and transcript. Leaving the setup app or rotating it stops a recording made in that app, without uploading. Cross-app recording uses the ready service described below. Locking the phone stops recording and playback without uploading. An upload you already started can continue while the app is backgrounded or rotated; force-stopping the app interrupts it. Very short recordings may be discarded if Android cannot finalize the audio file.

For device verification, test permission denial and retry, a five-second recording and playback, repeated recordings, deletion, app relaunch with a saved clip, and leaving or locking the phone while recording.

## Physical button

Keep the ATOM Lite powered over USB and Bluetooth enabled on the phone. In the app, tap **Connect button** and allow **Nearby devices** access. Once **Button connected** appears, press the large ATOM button once to record, speak, and press again to stop and transcribe. Release and long holds do not toggle recording. Presses received are shown on screen; presses during transcription are ignored.

When cross-app dictation is enabled, the remembered ATOM stays connected while another app is open and controls the focused supported text field. Otherwise, leaving the app disconnects it; reopening the app reconnects to the remembered ATOM. Locking the phone stops capture and prevents a new recording; the enabled ready service can keep the Bluetooth connection. A connection loss or missing event stops an active recording without uploading it. An unexpected disconnect gets one automatic reconnection attempt; tap **Connect button** to retry if it fails. **Disconnect button** forgets the remembered device. Connecting while the button is held does not start recording: a fresh press is required.

## Cross-app dictation setup on Samsung

1. Open **Back Button**, tap **Accessibility settings**, then find **Installed apps → Back Button dictation**. Turn on the service and accept Android's accessibility prompt. Enable the service itself; an accessibility shortcut is unnecessary. If Android explicitly blocks this sideloaded app, open Back Button's app info and use **Allow restricted settings** in its menu, then return to Accessibility.
2. Return to Back Button and tap **Enable cross-app dictation**. Allow microphone access if prompted, then tap Enable again. **Allow status notifications** is recommended so the ready notification and its Turn off action remain visible.
3. Open a message composer in LINE or a text field in Chrome or Samsung Internet. With the keyboard open, a **112 × 40 dp** control appears just above it. Tap **Record**, speak, then tap **Stop**. Drag the control to reposition it; dragging does not start recording. The touch area covers only the control.
4. Optionally connect the ATOM in Back Button, then use its presses for the same start/stop action in other apps. Hold-to-talk is a later step.

Your existing keyboard stays selected. This uses Android's accessibility overlay and accessibility input connection, so a separate **Appear on top / Display over other apps** permission is unnecessary. The microphone remains off while the service is ready; a tap or button press starts capture. After a process restart, force stop, or phone restart, open Back Button and enable cross-app dictation again. If Samsung later kills the ready service, try Back Button's app-specific **Battery → Unrestricted** setting; global battery settings need not change.

Password fields are excluded. Switching editors stops an active cross-app recording without uploading. Moving the cursor or editing the field before transcription finishes prevents automatic insertion; the transcript remains available in Back Button to copy. Unsupported editors and fields that do not expose surrounding text need manual copying. Field context is checked locally at the start and finish and is neither stored nor sent to Fish Audio. Only the recorded audio is uploaded. Nothing presses Send or submits a form.

The ready foreground service is started from the visible setup app to satisfy Android's microphone restrictions. The user verified the control, direct transcript insertion in LINE, browser insertion preserving existing text, and interruption when switching fields on the Samsung. Idle/background behavior and physical cross-app presses still need verification.

## Build

Use JDK 17 or 21, Android SDK Platform 35, and Build Tools 35.0.0. The checked-in Gradle wrapper selects the build tool version; use it instead of a system `gradle` command.

Open the repository in Android Studio, or set `ANDROID_HOME` to your SDK directory and run:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Alternatively, put `sdk.dir=/absolute/path/to/android-sdk` in the ignored `local.properties` file. This app uses the API 35 toolchain installed on the development machine. The minimum supported device version is Android 13, including its accessibility input-connection API.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Fish Audio key (personal debug setup)

Put `FISH_AUDIO_KEY=your_key` in `.env` at the repository root (see `.env.example`). `.env` is already ignored; the key is never embedded in the APK. After installing the debug app on the intended phone, provision it over ADB:

```sh
python3 scripts/provision_fish_key.py --serial DEVICE_SERIAL
```

Use the exact serial from `adb devices -l` (for wireless debugging, this is the connection IP and port). The script reads `.env` without printing the secret and sends it through stdin into the app's private `files/fish-audio-key` file. Reprovision after uninstalling the app or clearing its data. This is a simple personal debug setup; there is no key-entry UI yet.

The app sends M4A directly to `https://api.fish.audio/v1/asr` using `model: transcribe-1-pro`, auto-detects language, skips timestamps/emotion cues, and strips inline speaker markers for dictation. Playback remains available during transcription; recording and deletion wait until the request finishes. Failures keep the clip for manual retry. Requests need internet access and Fish API credits. See the [Fish speech-to-text reference](https://docs.fish.audio/api-reference/endpoint/openapi-v1/speech-to-text).

## Connect the Samsung

Run these USB and ADB steps on the MacBook connected to the phone.

1. Open Settings → About phone → Software information. Note the model, Android, and One UI versions.
2. Tap **Build number** seven times and enter your device PIN if asked.
3. Return to Settings → Developer options and enable **USB debugging**.
4. Connect the unlocked phone to the MacBook using a data-capable USB cable.
5. Accept the **Allow USB debugging** prompt on the phone.
6. Run `adb devices -l`. The device should show `device`; `unauthorized` means the phone still needs approval.

Once exactly one intended device is connected:

```sh
./gradlew :app:installDebug
adb shell am start -n dev.backbutton/.MainActivity
```

With multiple devices, select the intended serial explicitly using `adb -s SERIAL` for each ADB command, including APK installation with `install -r`.

ADB supports app installation, logs, screenshots, and shell commands for test interaction. Device access must be enabled in the agent environment before agent-driven phone testing. The user handles the initial USB authorization and permission setup. No root or custom Android build is needed. See the [official device setup guide](https://developer.android.com/studio/run/device) and [ADB documentation](https://developer.android.com/tools/adb).

## Next steps

1. Completed: build and launch on the Samsung Galaxy S23+ over wireless ADB.
2. Completed: verify on-screen recording → Fish Audio transcription on the Samsung.
3. Completed: verify ATOM Bluetooth press → recording with vibration → second press → transcript on the Samsung, plus automatic reconnect after leaving and reopening the app. Check signal loss during capture next.
4. Completed: compact cross-app control and dictation insertion verified by the user in LINE and a browser on the Samsung, including preserving existing text and stopping on field changes. Idle time, locking, and physical cross-app presses remain open checks.

Keep provider keys out of source control. ATOM Lite firmware lives under [`firmware/`](firmware/README.md), with USB press/release logging and the BLE event service used by the Android app.

## Browser insertion test

`tests/fixtures/dictation.html` is a manual test page for empty text, existing text, selection replacement, field changes, and password exclusion. These pages never submit data. An opt-in instrumentation test uses `insertion.html` to exercise the real Chrome editor connection with a synthetic transcript, without a Fish key or audio upload. Build, lint, 14 JVM tests, and this browser test pass; the browser test was run on an API 37 emulator.

Use an emulator or dedicated test device with Chrome already set up. Enable Back Button's accessibility service first. This test grants microphone permission, rebinds the already-enabled accessibility service after instrumentation starts, and temporarily enables dictation; it is not intended to run against personal browser fields. Start the local fixture server in a separate terminal, then build, install, and run explicitly on that device:

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory tests/fixtures
```

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL reverse tcp:8765 tcp:8765
adb -s SERIAL shell am instrument -w -e browserFixture true -e class dev.backbutton.ChromeInsertionTest dev.backbutton.test/androidx.test.runner.AndroidJUnitRunner
```
