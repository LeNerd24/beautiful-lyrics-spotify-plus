#pragma once

#include <stdint.h>

#ifdef __cplusplus
#include "cache-copy.h"
extern "C" {
#endif

int lyrics_spotify_audio_cache_cipher_capture(uint64_t epoch, const uint8_t *fileId20, const uint8_t *gid16, const void *ownedStatePrefix, void *transformMethod);
int lyrics_spotify_audio_cache_cipher_ready(uint64_t epoch);
void lyrics_spotify_audio_cache_cipher_clear(void);

#ifdef __cplusplus
}

namespace lyrics_spotify_audio {
    CacheSnapshot cache_decrypt(uint64_t epoch);
    CacheSnapshot cache_decrypt_input(uint64_t epoch, CacheSnapshot input);
}
#endif
