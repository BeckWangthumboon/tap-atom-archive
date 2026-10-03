#include <Arduino.h>
#include "ButtonDebouncer.h"
#include <BLE2902.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <atomic>

// ATOM Lite: built-in button is active-low GPIO39 with a board pull-up.
constexpr uint8_t BUTTON_PIN = 39;
constexpr uint32_t DEBOUNCE_MS = 35;
constexpr char SERVICE_UUID[] = "b8b10001-64df-4f6d-b7d1-86a6e72f8d21";
constexpr char EVENT_UUID[] = "b8b10002-64df-4f6d-b7d1-86a6e72f8d21";

BLECharacteristic* events;
std::atomic<bool> connected{false};
std::atomic<bool> advertiseAgain{false};
ButtonDebouncer button(DEBOUNCE_MS);
uint32_t sequence = 0;

// Protocol v1: version, pressed (0/1), uint32 edge sequence in little endian.
void publishState(bool notify) {
    uint8_t payload[] = {
        1, static_cast<uint8_t>(button.pressed()),
        static_cast<uint8_t>(sequence), static_cast<uint8_t>(sequence >> 8),
        static_cast<uint8_t>(sequence >> 16), static_cast<uint8_t>(sequence >> 24),
    };
    events->setValue(payload, sizeof(payload));
    if (notify && connected.load()) events->notify();
}

class ConnectionCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer*) override {
        connected.store(true);
        Serial.println("BLE CONNECTED");
    }
    void onDisconnect(BLEServer*) override {
        connected.store(false);
        advertiseAgain.store(true);
        Serial.println("BLE DISCONNECTED");
    }
};

void setup() {
    Serial.begin(115200);
    pinMode(BUTTON_PIN, INPUT);
    button.reset(digitalRead(BUTTON_PIN) == LOW, millis());
    BLEDevice::init("BackButton ATOM");
    auto* server = BLEDevice::createServer();
    server->setCallbacks(new ConnectionCallbacks());
    auto* service = server->createService(SERVICE_UUID);
    events = service->createCharacteristic(
        EVENT_UUID, BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_NOTIFY);
    events->addDescriptor(new BLE2902());
    publishState(false);
    service->start();
    auto* advertising = BLEDevice::getAdvertising();
    advertising->addServiceUUID(SERVICE_UUID);
    advertising->setScanResponse(true);
    BLEDevice::startAdvertising();
    Serial.printf("BACK BUTTON READY protocol=1 gpio=%u debounce=%lu ms\n", BUTTON_PIN,
                  static_cast<unsigned long>(DEBOUNCE_MS));
    Serial.println("BLE ADVERTISING: BackButton ATOM");
}

void loop() {
    if (advertiseAgain.exchange(false)) {
        BLEDevice::startAdvertising();
        Serial.println("BLE ADVERTISING: BackButton ATOM");
    }
    const bool pressed = digitalRead(BUTTON_PIN) == LOW;
    const uint32_t now = millis();
    if (button.update(pressed, now)) {
        ++sequence;
        publishState(true);
        Serial.printf("BUTTON %s seq=%lu\n", button.pressed() ? "PRESS" : "RELEASE",
                      static_cast<unsigned long>(sequence));
    }
    delay(5);
}
