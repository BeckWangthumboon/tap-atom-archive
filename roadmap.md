# Roadmap

A place to discuss and prioritize future work with the user. Keep completed-work history out of this file.

## Immediate work

- **Hardware discussion next:** define the next button beyond the ATOM prototype: placement and finger reach, click/hold feel, accidental-press protection, acceptable size and thickness, mounting or phone-case integration, and power/charging. Choose components and obtain materials after agreeing on these requirements.
- Build a physical mockup on a phone case to check grip, reach, placement, thickness, click feel, and accidental presses before committing to the electronics or enclosure. Use the findings to choose the board, switch, battery, charging, and mounting approach.
- Use the example dictation client to evaluate successive hardware prototypes. Preserve the documented BLE v1 interface where useful; document a new version if hardware experiments require incompatible interface changes.
- Continue everyday reliability checks: Bluetooth reconnection, interrupted recordings, idle/background behavior, and physical-button dictation across apps. Address issues as they appear.

## Future work

### Likely

- Move beyond the ATOM to hardware better suited to everyday phone use, after discussing requirements and choosing components.
- Test integrating the button into a phone case once the user has a case and the necessary materials. Evaluate finger reach, comfort, and accidental presses.

### Optional

- Explore existing local speech-to-text models or multiple providers in the example client. Fish Audio remains its testing default; avoid reinventing speech recognition.
- Add another client or direct app integration when there is a concrete use case. Evolve the button library and protocol from those needs.
- Revisit additional UX polish if everyday use reveals a need.
- Consider other AI actions, configurable gestures, or transcript cleanup later, if the user wants them.
