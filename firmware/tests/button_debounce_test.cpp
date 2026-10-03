#include "../src/ButtonDebouncer.h"
#include <assert.h>
#include <stdio.h>

int main() {
    ButtonDebouncer button(35);
    button.reset(false, 0);
    // A bouncing contact followed by a long hold yields exactly one press.
    assert(!button.update(true, 10));
    assert(!button.update(false, 13));
    assert(!button.update(true, 17));
    assert(!button.update(false, 20));
    assert(!button.update(true, 24));
    assert(!button.update(true, 58));
    assert(button.update(true, 59));
    assert(button.pressed());
    assert(!button.update(true, 5000));
    // Release bounce produces one release, with no duplicate transition.
    assert(!button.update(false, 5010));
    assert(!button.update(true, 5012));
    assert(!button.update(false, 5015));
    assert(button.update(false, 5050));
    assert(!button.pressed());
    assert(!button.update(false, 6000));
    // A button held at boot is a baseline, not a fabricated press.
    button.reset(true, 10);
    assert(!button.update(true, 100));
    assert(!button.update(false, 101));
    assert(button.update(false, 136));
    // millis() rollover must not break edge detection after weeks of uptime.
    button.reset(false, 0xfffffff0);
    assert(!button.update(true, 0xfffffff5));
    assert(!button.update(true, 23));
    assert(button.update(true, 24));
    assert(button.pressed());
    puts("PASS: bounce, hold, release, boot baseline, and timer rollover");
}
