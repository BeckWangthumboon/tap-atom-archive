# Back Button

A personal Android experiment: a physical BLE button on the back of a phone for AI dictation. See [project.md](project.md) for scope and decisions.

## Current state

This repository contains a Kotlin / Jetpack Compose app with on-screen and ATOM Lite Bluetooth recording controls, playback, and Fish Audio transcription. Stopping normally uploads the saved clip to Fish Audio and displays a transcript with a Copy button. Audio and the latest transcript are kept in app-private storage. Overlays and accessibility insertion are not implemented yet.

Tap **Start recording**, allow microphone access on first use, then tap **Start recording** again. A short vibration signals that capture has started. Tap **Stop recording**, to upload and transcribe, then **Play recording** to listen. You can also tap **Transcribe recording** to send an existing clip, or **Retry transcription** after a failure. Only the latest clip is kept; starting another recording replaces it after microphone capture starts successfully. **Delete recording** removes the clip and transcript. Leaving the app, rotating the phone, or locking it stops recording and playback without starting an upload. An upload you already started can continue while the app is backgrounded or rotated; force-stopping the app interrupts it. Very short recordings may be discarded if Android cannot finalize the audio file.

For device verification, test permission denial and retry, a five-second recording and playback, repeated recordings, deletion, app relaunch with a saved clip, and leaving or locking the phone while recording.

## Physical button

Keep the ATOM Lite powered over USB and Bluetooth enabled on the phone. In the app, tap **Connect button** and allow **Nearby devices** access. Once **Button connected** appears, press the large ATOM button once to record, speak, and press again to stop and transcribe. Release and long holds do not toggle recording. Presses received are shown on screen; presses during transcription are ignored.

This first Bluetooth version works while the app is open. Leaving or locking the phone stops capture and disconnects the button; reopening the app reconnects to the remembered ATOM. A connection loss or missing event stops an active recording without uploading it. An unexpected disconnect gets one automatic reconnection attempt; tap **Connect button** to retry if it fails. **Disconnect button** forgets the remembered device. Connecting while the button is held does not start recording: a fresh press is required.

## Build

Use JDK 17 or 21, Android SDK Platform 35, and Build Tools 35.0.0. The checked-in Gradle wrapper selects the build tool version; use it instead of a system `gradle` command.

Open the repository in Android Studio, or set `ANDROID_HOME` to your SDK directory and run:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Alternatively, put `sdk.dir=/absolute/path/to/android-sdk` in the ignored `local.properties` file. This starter uses the API 35 toolchain already installed on the development machine; reassess the target SDK before the background-service feasibility tests. The minimum supported device version is Android 13.

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
4. Prove accessibility insertion and an overlay control across apps, including microphone lifecycle restrictions.

Keep provider keys out of source control. ATOM Lite firmware lives under [`firmware/`](firmware/README.md), with USB press/release logging and the BLE event service used by the Android app.
