#pragma once

#include <stdint.h>

#ifdef __cplusplus
#include "cache-copy.h"
extern "C" {
#endif

typedef struct CacheFileInput {
    int32_t fd;
    uint32_t flags;
    uint64_t length;
    uint32_t headerBytes;
    uint32_t extraBytes;
    uint32_t scheduleWords;
    uint32_t rounds;
    uint8_t mode;
    uint8_t category;
    uint16_t generation;
    uint8_t salt[20];
    uint8_t schedule[240];
    const uint8_t *checksumsBE;
    uint32_t checksumCount;
    void *primitiveVtable;
    void *wrapperVtable;
    void *resetMethod;
    void *seekMethod;
    void *transformMethod;
} CacheFileInput;

#ifdef __cplusplus
static_assert(sizeof(CacheFileInput) == 352 && offsetof(CacheFileInput, schedule) == 56 && offsetof(CacheFileInput, checksumsBE) == 296 && offsetof(CacheFileInput, primitiveVtable) == 312, "Cache file snapshot ABI mismatch");
#endif

int lyrics_spotify_audio_file_capture(uint64_t epoch, const uint8_t *fileId20, const uint8_t *gid16, const CacheFileInput *ownedInput);
int lyrics_spotify_audio_file_ready(uint64_t epoch);
int lyrics_spotify_audio_file_status(uint64_t epoch);
void lyrics_spotify_audio_file_clear(void);

#ifdef __cplusplus
}

namespace lyrics_spotify_audio {
    CacheSnapshot cache_file_read(uint64_t epoch);
}
#endif
