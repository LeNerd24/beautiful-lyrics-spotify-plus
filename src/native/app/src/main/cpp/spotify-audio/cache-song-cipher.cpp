#include "cache-song-cipher.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <cstring>
#include <memory>
#include <mutex>
#include <utility>

#ifdef READER_PROBE_SONG_CIPHER_TEST
extern "C" void lyrics_spotify_audio_cache_cipher_test_wiped(const uint8_t *, size_t);
#endif

namespace {
    constexpr size_t prefixBytes = 0x310, stateBytes = 0x370, chunkBytes = 65536, maxBytes = 16 * 1024 * 1024;
    using Transform = void (*)(void *, uint64_t, void *, const void *, uint64_t);

    void wipe(uint8_t *data, size_t size) noexcept {
        auto *destination = static_cast<volatile uint8_t *>(data);
        for (size_t i = 0; i < size; i++) destination[i] = 0;
#ifdef READER_PROBE_SONG_CIPHER_TEST
        lyrics_spotify_audio_cache_cipher_test_wiped(data, size);
#endif
    }

    struct State {
        alignas(16) std::array<uint8_t, stateBytes> bytes{};
        ~State() { wipe(bytes.data(), bytes.size()); }
    };

    struct Job {
        uint64_t epoch;
        std::array<uint8_t, 20> fileId;
        std::array<uint8_t, 16> gid;
        State state;
        Transform transform;
        std::atomic<bool> cancelled{false}, claimed{false};
        std::mutex mutex;

        Job(uint64_t value, const uint8_t *file, const uint8_t *track, const void *prefix, void *method) : epoch(value), transform(reinterpret_cast<Transform>(method)) {
            std::memcpy(fileId.data(), file, fileId.size());
            std::memcpy(gid.data(), track, gid.size());
            std::memcpy(state.bytes.data(), prefix, prefixBytes);
        }

        ~Job() {
            wipe(fileId.data(), fileId.size());
            wipe(gid.data(), gid.size());
        }
        bool eligible() const { return !cancelled.load() && lyrics_spotify_audio_cache_matches(epoch, fileId.data(), gid.data()); }
    };

    std::mutex jobsMutex;
    std::shared_ptr<Job> active;

    std::shared_ptr<Job> jobFor(uint64_t epoch) {
        std::lock_guard lock(jobsMutex);
        return active && active->epoch == epoch ? active : nullptr;
    }
}

extern "C" int lyrics_spotify_audio_cache_cipher_capture(uint64_t epoch, const uint8_t *fileId20, const uint8_t *gid16, const void *ownedStatePrefix, void *transformMethod) {
    if (!ownedStatePrefix || !transformMethod || !lyrics_spotify_audio_cache_matches(epoch, fileId20, gid16)) return 0;
    std::shared_ptr<Job> previous;
    try {
        std::lock_guard lock(jobsMutex);
        if (!lyrics_spotify_audio_cache_matches(epoch, fileId20, gid16) || (active && active->epoch == epoch)) return 0;
        const auto *prefix = static_cast<const uint8_t *>(ownedStatePrefix);
        if (prefix[0] != 1 || prefix[0xc] == 0) return 0;
        auto replacement = std::make_shared<Job>(epoch, fileId20, gid16, prefix, transformMethod);
        if (!replacement->eligible()) return 0;
        if (active) active->cancelled.store(true);
        previous = std::move(active);
        active = std::move(replacement);
        return 1;
    } catch (...) {
        return 0;
    }
}

extern "C" int lyrics_spotify_audio_cache_cipher_ready(uint64_t epoch) {
    auto job = jobFor(epoch);
    return job && !job->claimed.load() && job->eligible();
}

extern "C" void lyrics_spotify_audio_cache_cipher_clear(void) {
    std::shared_ptr<Job> previous;
    {
        std::lock_guard lock(jobsMutex);
        if (active) active->cancelled.store(true);
        previous = std::move(active);
    }
}

namespace lyrics_spotify_audio {
    CacheSnapshot cache_decrypt(uint64_t epoch) {
        if (!lyrics_spotify_audio_cache_cipher_ready(epoch)) return {};
        return cache_decrypt_input(epoch, cache_take(epoch));
    }

    CacheSnapshot cache_decrypt_input(uint64_t epoch, CacheSnapshot input) {
        auto job = jobFor(epoch);
        if (!job || !job->eligible() || job->claimed.exchange(true)) return {};
        State local;
        Transform transform = nullptr;
        try {
            {
                std::lock_guard lock(job->mutex);
                if (!job->eligible()) return {};
                std::memcpy(local.bytes.data(), job->state.bytes.data(), stateBytes);
                transform = job->transform;
                wipe(job->state.bytes.data(), stateBytes);
                job->transform = nullptr;
            }
            if (!transform || !job->eligible()) return {};
            if (!input.size() || input.size() > maxBytes || !job->eligible()) return {};
            CacheSnapshot output(input.size());
            for (size_t position = 0; position < input.size();) {
                if (!job->eligible()) return {};
                size_t length = std::min(chunkBytes, input.size() - position);
                transform(local.bytes.data(), static_cast<uint64_t>(position), output.data() + position, input.data() + position, static_cast<uint64_t>(length));
                if (!job->eligible()) return {};
                position += length;
            }
            if (!job->eligible()) return {};
            return output;
        } catch (...) {
            return {};
        }
    }
}
