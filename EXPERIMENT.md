# Record-anywhere dictation

Originally tested on `experiment/record-anywhere`, then accepted for `main`. This behavior builds on the text-free status and recovery controls.

- Physical clicks and holds control capture even without a keyboard or text field.
- Capture continues through ordinary input restarts, field changes, and app switches. These invalidate the original insertion target, so the result is offered for copying.
- A field available when capture begins remains an insertion candidate only while its context stays unchanged.
- No target means no automatic insertion. Completing a recording offers × on the left and Copy on the right. Dismiss keeps Last transcript.
- Locking, disabling dictation, a lost button connection, and password/PIN fields retain their stop/block behavior.
- Without a keyboard, the indicator floats near the bottom of the safe screen area. Tight keyboard layouts use the top edge as a fallback.
- `TapDictation` logs record lifecycle events without audio, transcript text, field contents, or API keys.

The package ID stays `dev.backbutton` so app updates retain the phone configuration and only one app owns the physical button. The visible name is **tap**.

A local pre-experiment APK was saved in the experiment worktree for rollback:

```sh
adb -s SERIAL install -r captures/ux-review/baseline-before-experiment.apk
```

After an app update, open tap and enable dictation again.

Validation: 39 unit tests pass; debug build and lint pass (two existing dependency-version warnings); both opt-in native emulator tests pass, including no-keyboard capture, continued capture through input/app changes, recovery taps, and rotation. Emulator completions are synthetic and make no provider requests; the experimental build was installed on the Samsung for manual testing before adoption.
