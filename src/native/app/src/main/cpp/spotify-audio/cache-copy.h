#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
#include <memory>
extern "C" {
#endif

enum ReaderProbeCacheStatus { READER_CACHE_IDLE = 0,
    READER_CACHE_ARMED = 1,
    READER_CACHE_COPYING = 2,
    READER_CACHE_READY = 3,
    READER_CACHE_CONSUMED = 4,
    READER_CACHE_FAILED = -1,
    READER_CACHE_CANCELLED = -2,
    READER_CACHE_LENGTH_REJECTED = -3,
    READER_CACHE_TIMED_OUT = -4 };

int lyrics_spotify_audio_cache_arm(uint64_t epoch, const uint8_t *fileId20, const uint8_t *gid16);
int lyrics_spotify_audio_cache_matches(uint64_t epoch, const uint8_t *fileId20, const uint8_t *gid16);
int lyrics_spotify_audio_cache_copy(uint64_t epoch, void *backend, void *readMethod, int64_t totalLength);
int lyrics_spotify_audio_cache_copying(void);
int lyrics_spotify_audio_cache_status(uint64_t epoch);
uint64_t lyrics_spotify_audio_cache_bytes(uint64_t epoch);
void lyrics_spotify_audio_cache_clear(void);

#ifdef __cplusplus
}

namespace lyrics_spotify_audio {
    class CacheSnapshot {
        std::unique_ptr<uint8_t[]> bytes_;
        size_t size_ = 0;

    public: CacheSnapshot() = default;
        explicit CacheSnapshot(size_t size);
        CacheSnapshot(CacheSnapshot &&other) noexcept;
        CacheSnapshot &operator=(CacheSnapshot &&other) noexcept;
        CacheSnapshot(const CacheSnapshot &) = delete;
        CacheSnapshot &operator=(const CacheSnapshot &) = delete;
        ~CacheSnapshot();
        uint8_t *data() { return bytes_.get(); }
        const uint8_t *data() const { return bytes_.get(); }
        size_t size() const { return size_; }
        void clear() noexcept;
    };

    CacheSnapshot cache_take(uint64_t epoch);
}
#endif
