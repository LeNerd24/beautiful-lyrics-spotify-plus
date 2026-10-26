#include <array>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <jni.h>

extern "C" std::uint64_t lyrics_spotify_audio_epoch_current(void);
extern "C" int lyrics_spotify_audio_epoch_matches(std::uint64_t);
extern "C" int lyrics_spotify_audio_resource_mode(void);

namespace {
    void wipe(void *memory, std::size_t size) {
        auto *bytes = static_cast<volatile std::uint8_t *>(memory);
        while (size--) *bytes++ = 0;
    }

    std::mutex resourceMutex;
    std::array<std::array<std::uint8_t, 40>, 32> resources{};
    std::uint64_t resourceEpoch;
    std::size_t resourceCount;
}

extern "C" void lyrics_spotify_audio_resources_clear(void) {
    std::lock_guard lock(resourceMutex);
    wipe(resources.data(), sizeof(resources));
    resourceCount = 0;
    resourceEpoch = 0;
}

extern "C" __attribute__((visibility("default"))) int lyrics_spotify_audio_capture_resource(std::int32_t selector, const void *fileId, const void *trackGid) {
    const auto epoch = lyrics_spotify_audio_epoch_current();
    if (!fileId || !trackGid || !lyrics_spotify_audio_resource_mode() || !lyrics_spotify_audio_epoch_matches(epoch)) return 0;
    std::lock_guard lock(resourceMutex);
    if (!lyrics_spotify_audio_resource_mode() || !lyrics_spotify_audio_epoch_matches(epoch)) return 0;
    if (resourceEpoch != epoch) {
        wipe(resources.data(), sizeof(resources));
        resourceCount = 0;
        resourceEpoch = epoch;
    }
    for (std::size_t i = 0; i < resourceCount; i++) {
        if (std::memcmp(resources[i].data(), &selector, sizeof(selector)) == 0 && std::memcmp(resources[i].data() + 4, fileId, 20) == 0 && std::memcmp(resources[i].data() + 24, trackGid, 16) == 0) return 0;
    }
    if (resourceCount == resources.size()) return 0;
    std::memcpy(resources[resourceCount].data(), &selector, sizeof(selector));
    std::memcpy(resources[resourceCount].data() + 4, fileId, 20);
    std::memcpy(resources[resourceCount].data() + 24, trackGid, 16);
    resourceCount++;
    return 1;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_lenerd_lyricsnative_generation_SpotifyAudioNative_epoch(JNIEnv *, jclass) { return lyrics_spotify_audio_epoch_current(); }

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_lenerd_lyricsnative_generation_SpotifyAudioNative_resources(JNIEnv *env, jclass) {
    std::lock_guard lock(resourceMutex);
    const auto size = lyrics_spotify_audio_epoch_matches(resourceEpoch) ? resourceCount * 40 : 0;
    jbyteArray result = env->NewByteArray(size);
    if (result && size) env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte *>(resources.data()));
    return result;
}
