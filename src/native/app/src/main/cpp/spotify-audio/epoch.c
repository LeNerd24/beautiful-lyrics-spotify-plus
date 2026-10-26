#include <stdint.h>
#include <stdatomic.h>

static _Atomic uint64_t generation;

__attribute__((visibility("default"))) uint64_t lyrics_spotify_audio_epoch_current(void) { return atomic_load(&generation); }
__attribute__((visibility("default"))) int lyrics_spotify_audio_epoch_matches(uint64_t expected) { return (expected & 1) && expected == atomic_load(&generation); }

uint64_t lyrics_spotify_audio_epoch_begin(void) {
    uint64_t expected = atomic_load(&generation);
    for (;;) {
        if ((expected & 1) || expected == UINT64_MAX - 1) return 0;
        if (atomic_compare_exchange_weak(&generation, &expected, expected + 1)) return expected + 1;
    }
}

void lyrics_spotify_audio_epoch_end(void) {
    uint64_t expected = atomic_load(&generation);
    while ((expected & 1) && !atomic_compare_exchange_weak(&generation, &expected, expected + 1)) { }
}

int lyrics_spotify_audio_epoch_end_if(uint64_t expected) {
    return (expected & 1) && atomic_compare_exchange_strong(&generation, &expected, expected + 1);
}
