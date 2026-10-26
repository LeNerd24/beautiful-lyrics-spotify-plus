#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif
int lyrics_spotify_audio_window_bind(uint64_t epoch, void *manager, void *process, const uint8_t *ownedFileId, const uint8_t *ownedGid, uint64_t length, void *moduleBase);
void lyrics_spotify_audio_window_manager_unlock(void *mutex);
void lyrics_spotify_audio_window_process_unlock(void *mutex, void *returnAddress, uint32_t guard);
void lyrics_spotify_audio_window_destroy_manager(void *manager);
void lyrics_spotify_audio_window_destroy_process(void *process);
void lyrics_spotify_audio_window_clear(void);
int lyrics_spotify_audio_window_status(void);
#ifdef __cplusplus
}
#endif
