# Android dictation prototype

An Android app and physical Bluetooth button for dictation. See [project.md](project.md) for intent, [roadmap.md](roadmap.md) for future work, and [firmware/README.md](firmware/README.md) for hardware setup.

## Build and install

Use JDK 17 or 21, Android SDK Platform 35, and Build Tools 35.0.0. Open the repository in Android Studio, or set `ANDROID_HOME` to your SDK directory. Alternatively, set `sdk.dir=/absolute/path/to/android-sdk` in the ignored `local.properties` file. Use the checked-in Gradle wrapper:

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Connect the intended phone through USB debugging or paired wireless debugging. Get its current serial from `adb devices -l`; wireless debugging addresses and ports can change. Select the device explicitly:

```sh
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL shell am start -n dev.backbutton/.MainActivity
```

## Fish Audio key

Fish Audio is the current speech-to-text provider. Configure its key in **tap → Settings → API key**. For development, you can also put `FISH_AUDIO_KEY=your_key` in the ignored root `.env` file and provision it after installing:

```sh
python3 scripts/provision_fish_key.py --serial SERIAL
```

The script sends the key to app-private storage without printing it. Reprovision after uninstalling the app or clearing its data. Keep keys out of commits, APKs, and logs. Transcription needs internet access and Fish API credits; recorded audio is sent to Fish Audio.

## Samsung setup

The test phone is a Samsung Galaxy S23+ (SM-S916U), running Android 16. Its One UI version is unconfirmed. The installed app appears as **tap**.

1. In **Settings → Text insertion**, open **Installed apps → tap dictation**. Enable the service and accept the prompt. An accessibility shortcut is unnecessary. If Android blocks this sideloaded app, open its app info, select **Allow restricted settings** from the menu if offered, and return to Accessibility.
2. Return to tap, allow microphone access, configure your **API key**, and select **Permissions → Can dictate in other apps** to finish setup or enable dictation; it shows **Ready** when active. Allowing notifications is recommended so the ready notification and Turn off action are visible.
3. Power the ATOM over USB, enable Bluetooth, open **Bluetooth button**, choose **Connect**, and allow **Nearby devices** access. Wait for **Connected**.
4. Click the physical button to start and click again to finish, or hold until the readiness vibration, speak, and release. A selected text field and visible keyboard are optional. The slim waveform shows recording and processing and accepts no taps. It is hidden while idle and disappears after successful insertion. Red signals an error; details appear in settings. When insertion is unavailable, × and Copy let you dismiss or copy the floating result without showing transcript text.

Keep the existing keyboard selected. No separate **Display over other apps** permission is required. Open the app and enable cross-app dictation again after a process restart, force stop, or phone restart. If Samsung kills the ready service during use, try the app-specific **Battery → Unrestricted** setting.

Automatic insertion depends on the original editor exposing a usable Android input connection and its field, text, and selection staying unchanged. Recording continues through ordinary field and app changes; the result is offered for copying instead of insertion. Password and PIN fields block or interrupt capture. The floating result has × on the left and Copy on the right. Both hide the result while keeping **Last transcript** in settings. Its × hides it without deleting the text; **Last transcript → View** reopens it. The previous transcript stays available until a new nonempty transcription succeeds. Dictation does not press Send or submit a form.

The indicator sits above the keyboard and moves above a nearby growing composer. Floating keyboards use their exposed window bounds. Without a keyboard, it sits near the bottom of the safe screen area; keyboard layouts with too little room above them use the top edge instead.

## Device checks

Use the physical Samsung to check dictation in everyday apps, physical-button clicks and holds, microphone-level feedback, portrait/landscape positioning, idle/background behavior, locking, Bluetooth loss/reconnection, and interruptions. Also check permission denial, API-key configuration, and last-transcript copying/dismissal/recovery. Historical test results are not a substitute for testing the current build.

## Optional editor integration test

Use an emulator or dedicated test device without a Fish key, with Chrome set up and the app's accessibility service enabled. This test uses synthetic transcripts and a separate native editor fixture. It grants microphone permission and temporarily enables dictation; avoid running it against personal browser fields.

Start the fixture server in a separate terminal:

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory tests/fixtures
```

Then build, install, and run on the intended test device:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s SERIAL install -r app/build/outputs/apk/debug/app-debug.apk
adb -s SERIAL install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s SERIAL reverse tcp:8765 tcp:8765
adb -s SERIAL shell am instrument -w -e browserFixture true -e class dev.backbutton.ChromeInsertionTest#insertionPreservesTextAndRejectsChangedTargets dev.backbutton.test/androidx.test.runner.AndroidJUnitRunner
```

For native-only record-anywhere, status, and recovery checks (no browser server needed), use:

```sh
adb -s SERIAL shell am instrument -w -e nativeFixture true -e class 'dev.backbutton.ChromeInsertionTest#recordAnywhereKeepsCaptureAcrossInputChangesAndOffersRecovery,dev.backbutton.ChromeInsertionTest#nativeStatusIsPassiveAnchoredAndHiddenWhileIdle' dev.backbutton.test/androidx.test.runner.AndroidJUnitRunner
```
