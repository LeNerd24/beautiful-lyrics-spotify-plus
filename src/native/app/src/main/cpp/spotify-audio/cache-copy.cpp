#include "cache-copy.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <utility>

extern "C" uint64_t lyrics_spotify_audio_epoch_current(void);
#ifdef READER_PROBE_CACHE_TEST
extern "C" void lyrics_spotify_audio_cache_test_wiped(const uint8_t *, size_t);
#endif

namespace {
    constexpr int64_t maxBytes = 16 * 1024 * 1024, blockBytes = 16384;
    thread_local bool copying = false;

    void wipe(uint8_t *data, size_t size) noexcept {
        auto *destination = static_cast<volatile uint8_t *>(data);
        for (size_t i = 0; i < size; i++) destination[i] = 0;
#ifdef READER_PROBE_CACHE_TEST
        if (size) lyrics_spotify_audio_cache_test_wiped(data, size);
#endif
    }

    bool epochMatches(uint64_t epoch) { return (epoch & 1) && lyrics_spotify_audio_epoch_current() == epoch; }

    struct Job {
        uint64_t epoch;
        std::array<uint8_t, 20> fileId;
        std::array<uint8_t, 16> gid;
        std::atomic<bool> cancelled{false}, attempted{false};
        std::atomic<int> status{READER_CACHE_ARMED};
        std::atomic<uint64_t> bytes{0};
        std::mutex mutex;
        lyrics_spotify_audio::CacheSnapshot snapshot;

        Job(uint64_t value, const uint8_t *file, const uint8_t *track) : epoch(value) {
            std::memcpy(fileId.data(), file, fileId.size());
            std::memcpy(gid.data(), track, gid.size());
        }

        ~Job() {
            wipe(fileId.data(), fileId.size());
            wipe(gid.data(), gid.size());
        }
        bool eligible() const { return !cancelled.load() && epochMatches(epoch); }
    };

    std::mutex jobsMutex;
    std::shared_ptr<Job> active;

    std::shared_ptr<Job> jobFor(uint64_t epoch) {
        std::lock_guard lock(jobsMutex);
        return active && active->epoch == epoch ? active : nullptr;
    }

    struct CopyGuard {
        CopyGuard() { copying = true; }
        ~CopyGuard() { copying = false; }
    };
}

namespace lyrics_spotify_audio {
    CacheSnapshot::CacheSnapshot(size_t size) : bytes_(size ? new uint8_t[size]() : nullptr), size_(size) { }
    CacheSnapshot::CacheSnapshot(CacheSnapshot &&other) noexcept : bytes_(std::move(other.bytes_)), size_(std::exchange(other.size_, 0)) { }
    CacheSnapshot &CacheSnapshot::operator=(CacheSnapshot &&other) noexcept {
        if (this != &other) {
            clear();
            bytes_ = std::move(other.bytes_);
            size_ = std::exchange(other.size_, 0);
        }
        return *this;
    }
    CacheSnapshot::~CacheSnapshot() { clear(); }
    void CacheSnapshot::clear() noexcept {
        if (bytes_) wipe(bytes_.get(), size_);
        bytes_.reset();
        size_ = 0;
    }

    CacheSnapshot cache_take(uint64_t epoch) {
        auto job = jobFor(epoch);
        if (!job) return {};
        std::lock_guard lock(job->mutex);
        if (!job->eligible() || job->status.load() != READER_CACHE_READY) return {};
        CacheSnapshot result = std::move(job->snapshot);
        job->status.store(READER_CACHE_CONSUMED);
        if (!job->eligible()) return {};
        return result;
    }
}

extern "C" int lyrics_spotify_audio_cache_arm(uint64_t epoch, const uint8_t *fileId20, const uint8_t *gid16) {
    if (!fileId20 || !gid16 || !epochMatches(epoch)) return 0;
    std::shared_ptr<Job> replacement;
    try {
        replacement = std::make_shared<Job>(epoch, fileId20, gid16);
    } catch (...) {
        return 0;
    }
    std::shared_ptr<Job> previous;
    {
        std::lock_guard lock(jobsMutex);
        if (!epochMatches(epoch)) return 0;
        if (active) active->cancelled.store(true);
        previous = std::move(active);
        active = std::move(replacement);
    }
    return 1;
}

extern "C" int lyrics_spotify_audio_cache_matches(uint64_t epoch, const uint8_t *fileId20, const uint8_t *gid16) {
    if (!fileId20 || !gid16) return 0;
    auto job = jobFor(epoch);
    return job && job->eligible() && std::memcmp(job->fileId.data(), fileId20, 20) == 0 && std::memcmp(job->gid.data(), gid16, 16) == 0;
}

extern "C" int lyrics_spotify_audio_cache_copy(uint64_t epoch, void *backend, void *readMethod, int64_t totalLength) {
    if (copying) return 0;
    auto job = jobFor(epoch);
    if (!job || !job->eligible() || job->attempted.exchange(true)) return 0;
    if (!backend || !readMethod) {
        job->status.store(READER_CACHE_FAILED);
        return READER_CACHE_FAILED;
    }
    if (totalLength <= 0 || totalLength > maxBytes) {
        job->status.store(READER_CACHE_LENGTH_REJECTED);
        return READER_CACHE_LENGTH_REJECTED;
    }
    CopyGuard guard;
    job->status.store(READER_CACHE_COPYING);
    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(500);
    try {
        lyrics_spotify_audio::CacheSnapshot buffer(static_cast<size_t>(totalLength));
        auto read = reinterpret_cast<bool (*)(void *, int64_t, void *, int64_t)>(readMethod);
        for (int64_t position = 0; position < totalLength;) {
            if (!job->eligible()) {
                job->status.store(READER_CACHE_CANCELLED);
                return READER_CACHE_CANCELLED;
            }
            if (std::chrono::steady_clock::now() >= deadline) {
                job->status.store(READER_CACHE_TIMED_OUT);
                return READER_CACHE_TIMED_OUT;
            }
            int64_t length = std::min(blockBytes, totalLength - position);
            bool success = read(backend, position, buffer.data() + position, length);
            if (!job->eligible()) {
                job->status.store(READER_CACHE_CANCELLED);
                return READER_CACHE_CANCELLED;
            }
            if (std::chrono::steady_clock::now() >= deadline) {
                job->status.store(READER_CACHE_TIMED_OUT);
                return READER_CACHE_TIMED_OUT;
            }
            if (!success) {
                job->status.store(READER_CACHE_FAILED);
                return READER_CACHE_FAILED;
            }
            position += length;
            job->bytes.store(static_cast<uint64_t>(position));
        }
        std::lock_guard lock(job->mutex);
        if (!job->eligible()) {
            job->status.store(READER_CACHE_CANCELLED);
            return READER_CACHE_CANCELLED;
        }
        job->snapshot = std::move(buffer);
        job->status.store(READER_CACHE_READY);
        return READER_CACHE_READY;
    } catch (...) {
        job->status.store(job->eligible() ? READER_CACHE_FAILED : READER_CACHE_CANCELLED);
        return job->status.load();
    }
}

extern "C" int lyrics_spotify_audio_cache_copying(void) { return copying ? 1 : 0; }
extern "C" int lyrics_spotify_audio_cache_status(uint64_t epoch) {
    auto job = jobFor(epoch);
    return !job ? READER_CACHE_IDLE : !job->eligible() ? READER_CACHE_CANCELLED : job->status.load();
}
extern "C" uint64_t lyrics_spotify_audio_cache_bytes(uint64_t epoch) {
    auto job = jobFor(epoch);
    return job && job->eligible() ? job->bytes.load() : 0;
}
extern "C" void lyrics_spotify_audio_cache_clear(void) {
    std::shared_ptr<Job> previous;
    {
        std::lock_guard lock(jobsMutex);
        if (active) active->cancelled.store(true);
        previous = std::move(active);
    }
}
