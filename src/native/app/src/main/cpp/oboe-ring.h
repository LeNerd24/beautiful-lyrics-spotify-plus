#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>

class OboeRing {
public:
    static constexpr uint32_t CAPACITY = 192000;
    struct Stats {
        uint64_t generation, captured, processed, fallback, startup, dropped;
    };

    bool configure(int rate, int encoding, int delayFrames) {
        if ((rate != 44100 && rate != 48000) || (encoding != 2 && encoding != 4) || delayFrames < 0 || delayFrames > static_cast<int>(CAPACITY / 2)) return false;
        configuration.store(static_cast<uint64_t>(rate) | (static_cast<uint64_t>(encoding) << 16) | (static_cast<uint64_t>(delayFrames) << 24), std::memory_order_release);
        reset();
        return true;
    }

    void reset() { requested.fetch_add(1, std::memory_order_acq_rel); }
    void setGain(float value) { gain.store(std::isfinite(value) ? std::clamp(value, 0.0f, 1.0f) : 1.0f, std::memory_order_relaxed); }
    int rate() const { return static_cast<int>(configuration.load(std::memory_order_acquire) & 65535); }
    int encoding() const { return static_cast<int>((configuration.load(std::memory_order_acquire) >> 16) & 255); }
    uint64_t generation() const { return requested.load(std::memory_order_acquire); }

    bool process(void *audio, int frames) {
        if (!audio || frames <= 0 || frames > static_cast<int>(CAPACITY / 4)) return false;
        uint64_t epoch = requested.load(std::memory_order_acquire);
        if (epoch != callbackGeneration) {
            uint64_t config = configuration.load(std::memory_order_acquire);
            callbackRate = static_cast<int>(config & 65535);
            callbackEncoding = static_cast<int>((config >> 16) & 255);
            delay = static_cast<uint32_t>(config >> 24);
            if (!callbackRate) return false;
            callbackGeneration = epoch;
            callbackPosition = 0;
            removal = 0;
            captured.store(0, std::memory_order_release);
            active.store(epoch, std::memory_order_release);
        }

        uint64_t separated = 0, late = 0, silent = 0;
        float increment = 1.0f / (callbackRate * 0.03f);
        float target = 1 - gain.load(std::memory_order_relaxed);
        auto *bytes = static_cast<unsigned char *>(audio);
        int frameBytes = callbackEncoding == 4 ? 8 : 4;

        for (int i = 0; i < frames; i++) {
            uint64_t position = callbackPosition++;
            float left = decode(bytes + i * frameBytes, callbackEncoding), right = decode(bytes + i * frameBytes + frameBytes / 2, callbackEncoding);
            write(original[position % CAPACITY], key(epoch, position), pack(left, right));
            if (position < delay) {
                encode(bytes + i * frameBytes, callbackEncoding, 0);
                encode(bytes + i * frameBytes + frameBytes / 2, callbackEncoding, 0);
                silent++;
                continue;
            }

            uint64_t output = position - delay, dry = 0, wet = 0;
            bool hasOriginal = read(original[output % CAPACITY], key(epoch, output), dry);
            bool hasProcessed = read(processed[output % CAPACITY], key(epoch, output), wet);
            float destination = hasProcessed ? target : 0;
            removal += std::clamp(destination - removal, -increment, increment);
            if (hasProcessed) separated++;
            else late++;
            float dryLeft = hasOriginal ? unpack(dry, 0) : 0, dryRight = hasOriginal ? unpack(dry, 1) : 0;
            float wetLeft = hasProcessed ? unpack(wet, 0) : dryLeft, wetRight = hasProcessed ? unpack(wet, 1) : dryRight;
            encode(bytes + i * frameBytes, callbackEncoding, dryLeft + removal * (wetLeft - dryLeft));
            encode(bytes + i * frameBytes + frameBytes / 2, callbackEncoding, dryRight + removal * (wetRight - dryRight));
        }

        captured.store(callbackPosition, std::memory_order_release);
        processedCount.fetch_add(separated, std::memory_order_relaxed);
        fallbackCount.fetch_add(late, std::memory_order_relaxed);
        startupCount.fetch_add(silent, std::memory_order_relaxed);
        return true;
    }

    int drain(float *stereo, int maxFrames, uint64_t &epoch, uint64_t &start) {
        if (!stereo || maxFrames <= 0) return 0;
        uint64_t current = active.load(std::memory_order_acquire);
        if (!current || current != requested.load(std::memory_order_acquire)) return 0;
        if (workerGeneration != current) {
            workerGeneration = current;
            workerPosition = 0;
        }
        uint64_t available = captured.load(std::memory_order_acquire);
        if (current != active.load(std::memory_order_acquire) || current != requested.load(std::memory_order_acquire)) return 0;
        if (available > workerPosition + CAPACITY) {
            droppedCount.fetch_add(available - CAPACITY - workerPosition, std::memory_order_relaxed);
            workerPosition = available - CAPACITY;
        }
        epoch = current;
        start = workerPosition;
        int count = static_cast<int>(std::min<uint64_t>(static_cast<uint64_t>(maxFrames), available - workerPosition));
        int taken = 0;
        for (; taken < count; taken++) {
            uint64_t value;
            if (!read(original[(workerPosition + taken) % CAPACITY], key(current, workerPosition + taken), value)) break;
            stereo[taken * 2] = unpack(value, 0);
            stereo[taken * 2 + 1] = unpack(value, 1);
        }
        if (current != requested.load(std::memory_order_acquire)) return 0;
        workerPosition += taken;
        return taken;
    }

    bool publish(const float *stereo, int frames, uint64_t epoch, uint64_t start) {
        if (!stereo || frames <= 0 || frames > static_cast<int>(CAPACITY) || epoch != requested.load(std::memory_order_acquire) || epoch != active.load(std::memory_order_acquire)) return false;
        uint64_t available = captured.load(std::memory_order_acquire);
        if (start > available || static_cast<uint64_t>(frames) > available - start || start + CAPACITY < available) return false;
        for (int i = 0; i < frames; i++) {
            if (epoch != requested.load(std::memory_order_acquire)) return false;
            write(processed[(start + i) % CAPACITY], key(epoch, start + i), pack(stereo[i * 2], stereo[i * 2 + 1]));
        }
        return epoch == requested.load(std::memory_order_acquire);
    }

    Stats statistics() const { return {active.load(std::memory_order_acquire), captured.load(std::memory_order_acquire), processedCount.load(std::memory_order_relaxed), fallbackCount.load(std::memory_order_relaxed), startupCount.load(std::memory_order_relaxed), droppedCount.load(std::memory_order_relaxed)}; }

private:
    struct Slot {
        std::atomic<uint64_t> tag{0}, samples{0};
    };
    static_assert(std::atomic<uint64_t>::is_always_lock_free);
    static_assert(std::atomic<float>::is_always_lock_free);
    std::array<Slot, CAPACITY> original{}, processed{};
    std::atomic<uint64_t> configuration{0}, requested{1}, active{0}, captured{0};
    std::atomic<uint64_t> processedCount{0}, fallbackCount{0}, startupCount{0}, droppedCount{0};
    std::atomic<float> gain{1};
    uint64_t callbackGeneration = 0, callbackPosition = 0, workerGeneration = 0, workerPosition = 0;
    uint32_t delay = 0;
    int callbackRate = 0, callbackEncoding = 0;
    float removal = 0;

    static uint64_t key(uint64_t epoch, uint64_t position) { return (epoch << 32) | ((position + 1) & 0xffffffff); }
    static void write(Slot &slot, uint64_t tag, uint64_t samples) {
        slot.tag.store(0, std::memory_order_seq_cst);
        slot.samples.store(samples, std::memory_order_seq_cst);
        slot.tag.store(tag, std::memory_order_seq_cst);
    }
    static bool read(const Slot &slot, uint64_t tag, uint64_t &samples) {
        if (slot.tag.load(std::memory_order_seq_cst) != tag) return false;
        samples = slot.samples.load(std::memory_order_seq_cst);
        return slot.tag.load(std::memory_order_seq_cst) == tag;
    }
    static uint64_t pack(float left, float right) {
        uint32_t a, b;
        std::memcpy(&a, &left, 4);
        std::memcpy(&b, &right, 4);
        return a | (static_cast<uint64_t>(b) << 32);
    }
    static float unpack(uint64_t samples, int channel) {
        uint32_t bits = static_cast<uint32_t>(samples >> (channel * 32));
        float value;
        std::memcpy(&value, &bits, 4);
        return std::isfinite(value) ? value : 0;
    }
    static float decode(const unsigned char *data, int encoding) {
        if (encoding == 4) {
            float value;
            std::memcpy(&value, data, 4);
            return std::isfinite(value) ? value : 0;
        }
        int16_t value;
        std::memcpy(&value, data, 2);
        return value / 32768.0f;
    }
    static void encode(unsigned char *data, int encoding, float value) {
        if (!std::isfinite(value)) value = 0;
        if (encoding == 4) std::memcpy(data, &value, 4);
        else {
            int16_t sample = static_cast<int16_t>(std::clamp(std::lround(value * 32768.0f), -32768L, 32767L));
            std::memcpy(data, &sample, 2);
        }
    }
};
