#pragma once

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum ReaderProbeRemoteCipherStatus { READER_REMOTE_IDLE = 0,
    READER_REMOTE_ARMED = 1,
    READER_REMOTE_READY = 2,
    READER_REMOTE_DECRYPTING = 3,
    READER_REMOTE_CONSUMED = 4,
    READER_REMOTE_FAILED = -1,
    READER_REMOTE_CANCELLED = -2 };

int lyrics_spotify_remote_cipher_configure_profile(uintptr_t moduleBase, size_t moduleSize, const uintptr_t *profile);
int lyrics_spotify_remote_cipher_arm(uint64_t epoch, uint32_t handle, uint32_t threadId);

int lyrics_spotify_remote_cipher_capture(uint64_t epoch, uint32_t handle, uintptr_t originalLR, const void *optional29);
int lyrics_spotify_remote_cipher_status(uint64_t epoch);
int lyrics_spotify_remote_cipher_decrypt(uint64_t epoch, const uint8_t *input, size_t length, uint8_t *output, size_t capacity);
void lyrics_spotify_remote_cipher_clear(void);

#ifdef READER_PROBE_REMOTE_CIPHER_TEST
void lyrics_spotify_remote_cipher_test_methods(void (*construct)(void *, uint8_t), void (*initialize)(void *, const void *), void (*transform)(void *, uint64_t, void *, const void *, uint64_t), void (*destroy)(void *));
uint32_t lyrics_spotify_remote_cipher_test_thread(void);
void lyrics_spotify_remote_cipher_test_wiped(const uint8_t *, size_t);
#endif

#ifdef __cplusplus
}
#endif
