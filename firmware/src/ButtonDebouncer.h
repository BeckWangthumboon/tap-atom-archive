#pragma once
#include <stdint.h>

class ButtonDebouncer {
public:
    explicit ButtonDebouncer(uint32_t intervalMs) : intervalMs_(intervalMs) {}

    void reset(bool pressed, uint32_t now) {
        raw_ = stable_ = pressed;
        changedAt_ = now;
    }

    bool update(bool pressed, uint32_t now) {
        if (pressed != raw_) {
            raw_ = pressed;
            changedAt_ = now;
        }
        if (raw_ != stable_ && static_cast<uint32_t>(now - changedAt_) >= intervalMs_) {
            stable_ = raw_;
            return true;
        }
        return false;
    }

    bool pressed() const { return stable_; }

private:
    uint32_t intervalMs_;
    uint32_t changedAt_ = 0;
    bool raw_ = false;
    bool stable_ = false;
};
