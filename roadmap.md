# Roadmap

A place to discuss and prioritize future work with the user. Keep completed-work history out of this file.

## Immediate work

- **UX first today:** review the current experience on the phone with the user, then clean up confusing, awkward, or unfinished elements. Aim for a good, coherent, usable experience—roughly 80–90% of the desired quality—without pursuing perfect polish.
- Review both surfaces: one main app for configuration, and the passive floating indicator and transcript recovery used in other apps. Detailed UX choices will follow the user's review.
- Improve reliability: Bluetooth reconnection, interrupted recordings, idle/background behavior, and physical-button dictation across apps. Clean up code where it helps these changes or the UX work.
- After the UX review, discuss moving beyond the ATOM prototype: what the next button needs and how it could integrate into a phone case. Discussion can happen now; hardware work depends on choosing and obtaining materials.

## Future work

### Likely

- Move beyond the ATOM to hardware better suited to everyday phone use, after discussing requirements and choosing components.
- Test integrating the button into a phone case once the user has a case and the necessary materials. Evaluate finger reach, comfort, and accidental presses.

### Optional

- Explore existing local speech-to-text models or multiple providers. Fish Audio remains the testing default; avoid reinventing speech recognition.
- Pursue exceptional UX polish after the core experience is good and useful. This is separate from the immediate cleanup.
- Consider other AI actions, configurable gestures, or transcript cleanup later, if the user wants them.
