# Back Button

A personal Android experiment: a physical BLE button on the back of a phone for AI dictation. See [project.md](project.md) for scope and decisions.

## Current state

This repository contains a Kotlin / Jetpack Compose app with on-screen microphone recording and playback. Audio stays in app-private storage on the phone; there is no network upload. Transcription, overlays, accessibility insertion, and BLE are not implemented yet.

Tap **Start recording**, allow microphone access on first use, then tap **Start recording** again. A short vibration signals that capture has started. Tap **Stop recording**, then **Play recording** to listen. Only the latest clip is kept; starting another recording replaces it after microphone capture starts successfully. **Delete recording** removes it. Leaving the app, rotating the phone, or locking it stops recording and playback. Very short recordings may be discarded if Android cannot finalize the audio file.

For device verification, test permission denial and retry, a five-second recording and playback, repeated recordings, deletion, app relaunch with a saved clip, and leaving or locking the phone while recording.

## Build

Use JDK 17 or 21, Android SDK Platform 35, and Build Tools 35.0.0. The checked-in Gradle wrapper selects the build tool version; use it instead of a system `gradle` command.

Open the repository in Android Studio, or set `ANDROID_HOME` to your SDK directory and run:

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

Alternatively, put `sdk.dir=/absolute/path/to/android-sdk` in the ignored `local.properties` file. This starter uses the API 35 toolchain already installed on the development machine; reassess the target SDK before the background-service feasibility tests. The minimum supported device version is Android 13.

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

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
2. Verify local recording/playback on the Samsung, then add Fish Audio transcription.
3. Prove accessibility insertion and an overlay control across apps, including microphone lifecycle restrictions.
4. Add ATOM Lite BLE events to the same recording controls and validate background/reconnection behavior.

Keep provider keys out of source control. The firmware will live under `firmware/` once implementation starts.
