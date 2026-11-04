#include "remote-song-cipher.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cstring>
#include <limits>
#include <memory>
#include <mutex>
#include <utility>
#ifndef READER_PROBE_REMOTE_CIPHER_TEST
#include <unistd.h>
#include <sys/syscall.h>
#endif

namespace {
    constexpr size_t packetBytes = 28, stateBytes = 0x370, chunkBytes = 65536, maximumBytes = 16 * 1024 * 1024;
    using Constructor = void (*)(void *, uint8_t);
    using Initialize = void (*)(void *, const void *);
    using Transform = void (*)(void *, uint64_t, void *, const void *, uint64_t);
    using Destructor = void (*)(void *);
    struct Methods {
        Constructor construct = nullptr;
        Initialize initialize = nullptr;
        Transform transform = nullptr;
        Destructor destroy = nullptr;
        uintptr_t caller = 0;
        size_t packetSize = packetBytes, readyOffset = 12;
        uint32_t mode = 2;
    };

    void wipe(uint8_t *bytes, size_t length) noexcept {
        auto *target = static_cast<volatile uint8_t *>(bytes);
        for (size_t i = 0; i < length; i++) target[i] = 0;
#ifdef READER_PROBE_REMOTE_CIPHER_TEST
        lyrics_spotify_remote_cipher_test_wiped(bytes, length);
#endif
    }

    uint32_t threadId() {
#ifdef READER_PROBE_REMOTE_CIPHER_TEST
        return lyrics_spotify_remote_cipher_test_thread();
#else
        return static_cast<uint32_t>(syscall(SYS_gettid));
#endif
    }

    struct Packet {
        std::array<uint8_t, packetBytes> bytes{};
        ~Packet() { wipe(bytes.data(), bytes.size()); }
    };

    struct Context {
        alignas(16) std::array<uint8_t, stateBytes> bytes{};
        Destructor destroy;
        bool constructed = false;
        explicit Context(Destructor method) : destroy(method) { }
        bool finish() noexcept {
            bool success = true;
            if (constructed) {
                constructed = false;
                try {
                    if (destroy) destroy(bytes.data());
                } catch (...) {
                    success = false;
                }
            }
            wipe(bytes.data(), bytes.size());
            return success;
        }
        ~Context() { finish(); }
    };

    struct Job {
        uint64_t epoch;
        uint32_t handle, thread;
        Methods methods;
        Packet packet;
        std::atomic<bool> cancelled{false};
        std::atomic<int> status{READER_REMOTE_ARMED};
        std::mutex mutex;
        Job(uint64_t value, uint32_t id, uint32_t tid, Methods functions) : epoch(value), handle(id), thread(tid), methods(functions) { }
    };

    std::mutex jobsMutex;
    Methods configured;
    std::shared_ptr<Job> active;
    std::atomic<uint32_t> captureThread{0};

    std::shared_ptr<Job> jobFor(uint64_t epoch) {
        std::lock_guard lock(jobsMutex);
        return active && active->epoch == epoch ? active : nullptr;
    }

    bool methodsValid(const Methods &methods) { return methods.initialize && methods.transform && methods.caller && (methods.mode == 3 || (methods.construct && methods.destroy)); }

    bool disjoint(const uint8_t *input, uint8_t *output, size_t length) {
        uintptr_t source = reinterpret_cast<uintptr_t>(input), destination = reinterpret_cast<uintptr_t>(output);
        if (source > std::numeric_limits<uintptr_t>::max() - length || destination > std::numeric_limits<uintptr_t>::max() - length) return false;
        return source + length <= destination || destination + length <= source;
    }
}

extern "C" int lyrics_spotify_remote_cipher_configure_profile(uintptr_t moduleBase, size_t moduleSize, const uintptr_t *profile) {
    if (!moduleBase || moduleBase % 4096 || moduleSize < 4096 || moduleSize > 128 * 1024 * 1024 || !profile || moduleBase > std::numeric_limits<uintptr_t>::max() - moduleSize) return 0;
    const uintptr_t constructor = profile[0], initialize = profile[1], transform = profile[2], destructor = profile[3], caller = profile[4], ready = profile[5], packet = profile[6], mode = profile[7];
    if ((mode != 1 && mode != 2 && mode != 3) || (packet != 24 && packet != 28) || ready != (mode == 1 ? 100 : mode == 2 ? 12 : 8) || !initialize || !transform || !caller) return 0;
    for (size_t index = 0; index < 5; index++) if (profile[index] >= moduleSize || profile[index] % 4) return 0;
    if ((mode != 3 && (!constructor || !destructor)) || (mode == 3 && (constructor || destructor))) return 0;
    std::lock_guard lock(jobsMutex);
    if (active) return 0;
    configured = {constructor ? reinterpret_cast<Constructor>(moduleBase + constructor) : nullptr, reinterpret_cast<Initialize>(moduleBase + initialize), reinterpret_cast<Transform>(moduleBase + transform), destructor ? reinterpret_cast<Destructor>(moduleBase + destructor) : nullptr, moduleBase + caller, packet, ready, static_cast<uint32_t>(mode)};
    return 1;
}

extern "C" int lyrics_spotify_remote_cipher_arm(uint64_t epoch, uint32_t handle, uint32_t expectedThread) {
    if (!epoch || !expectedThread) return 0;
    try {
        std::lock_guard lock(jobsMutex);
        if (active || !methodsValid(configured)) return 0;
        active = std::make_shared<Job>(epoch, handle, expectedThread, configured);
        captureThread.store(expectedThread, std::memory_order_release);
        return 1;
    } catch (...) {
        return 0;
    }
}

extern "C" int lyrics_spotify_remote_cipher_capture(uint64_t epoch, uint32_t handle, uintptr_t originalLR, const void *optional29) {
    uint32_t tid = threadId();
    if (!optional29 || !tid || captureThread.load(std::memory_order_acquire) != tid) return 0;
    auto job = jobFor(epoch);
    if (!job || job->handle != handle || job->thread != tid || job->methods.caller != originalLR || job->cancelled.load()) return 0;
    std::lock_guard lock(job->mutex);
    if (job->cancelled.load() || job->status.load() != READER_REMOTE_ARMED) return 0;
    const auto *optional = static_cast<const uint8_t *>(optional29);
    if (optional[job->methods.packetSize] != 1) return 0;
    std::memcpy(job->packet.bytes.data(), optional, job->methods.packetSize);
    {
        std::lock_guard publish(jobsMutex);
        if (active != job || job->cancelled.load()) {
            wipe(job->packet.bytes.data(), packetBytes);
            return 0;
        }
        job->status.store(READER_REMOTE_READY);
        captureThread.store(0, std::memory_order_release);
    }
    return 1;
}

extern "C" int lyrics_spotify_remote_cipher_status(uint64_t epoch) {
    auto job = jobFor(epoch);
    if (!job) return READER_REMOTE_IDLE;
    return job->cancelled.load() ? READER_REMOTE_CANCELLED : job->status.load();
}

extern "C" int lyrics_spotify_remote_cipher_decrypt(uint64_t epoch, const uint8_t *input, size_t length, uint8_t *output, size_t capacity) {
    if (!input || !output || !length || length > maximumBytes || capacity < length || !disjoint(input, output, length)) return 0;
    auto job = jobFor(epoch);
    if (!job || job->cancelled.load()) {
        wipe(output, length);
        return 0;
    }
    int expected = READER_REMOTE_READY;
    if (!job->status.compare_exchange_strong(expected, READER_REMOTE_DECRYPTING)) return 0;
    Packet packet;
    Context context(job->methods.destroy);
    bool success = false;
    try {
        {
            std::lock_guard lock(job->mutex);
            std::memcpy(packet.bytes.data(), job->packet.bytes.data(), packetBytes);
            wipe(job->packet.bytes.data(), packetBytes);
        }
        if (!job->cancelled.load()) {
            if (job->methods.construct) job->methods.construct(context.bytes.data(), 1);
            context.constructed = true;
            if (!job->cancelled.load()) job->methods.initialize(context.bytes.data(), packet.bytes.data());
            wipe(packet.bytes.data(), packetBytes);
            if (!job->cancelled.load() && context.bytes[job->methods.readyOffset] != 0 && (job->methods.mode != 2 || context.bytes[0] == 1)) {
                size_t position = 0;
                while (position < length && !job->cancelled.load()) {
                    size_t count = std::min(chunkBytes, length - position);
                    job->methods.transform(context.bytes.data(), static_cast<uint64_t>(position), output + position, input + position, static_cast<uint64_t>(count));
                    position += count;
                }
                success = position == length && !job->cancelled.load();
            }
        }
    } catch (...) {
        success = false;
    }
    if (!context.finish() || job->cancelled.load()) success = false;
    if (!success) wipe(output, length);
    job->status.store(job->cancelled.load() ? READER_REMOTE_CANCELLED : success ? READER_REMOTE_CONSUMED : READER_REMOTE_FAILED);
    return success ? 1 : 0;
}

extern "C" void lyrics_spotify_remote_cipher_clear(void) {
    std::shared_ptr<Job> previous;
    {
        std::lock_guard lock(jobsMutex);
        captureThread.store(0, std::memory_order_release);
        if (active) active->cancelled.store(true);
        previous = std::move(active);
    }
}

#ifdef READER_PROBE_REMOTE_CIPHER_TEST
extern "C" void lyrics_spotify_remote_cipher_test_methods(Constructor construct, Initialize initialize, Transform transform, Destructor destroy) {
    std::lock_guard lock(jobsMutex);
    if (!active) configured = {construct, initialize, transform, destroy, 0x10afd3ac};
}
#endif
