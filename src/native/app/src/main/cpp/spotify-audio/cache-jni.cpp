#include <jni.h>
#include <cstdint>
#include "cache-copy.h"
#include "cache-song-cipher.h"
#include "cache-file.h"

extern "C" std::uint64_t lyrics_spotify_audio_epoch_current(void);
extern "C" int lyrics_spotify_audio_cache_file_mode(void);

namespace {
    void wipe(void *memory, std::size_t length) {
        auto *bytes = static_cast<volatile std::uint8_t *>(memory);
        while (length--) *bytes++ = 0;
    }
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_lenerd_lyricsnative_generation_SpotifyAudioNative_armCache(JNIEnv *env, jclass, jlong epoch, jbyteArray fileId, jbyteArray trackGid) {
    if (!fileId || !trackGid || env->GetArrayLength(fileId) != 20 || env->GetArrayLength(trackGid) != 16) return JNI_FALSE;
    std::uint8_t file[20]{}, gid[16]{};
    env->GetByteArrayRegion(fileId, 0, 20, reinterpret_cast<jbyte *>(file));
    env->GetByteArrayRegion(trackGid, 0, 16, reinterpret_cast<jbyte *>(gid));
    const bool accepted = !env->ExceptionCheck() && lyrics_spotify_audio_cache_status(epoch) == READER_CACHE_IDLE && lyrics_spotify_audio_cache_arm(epoch, file, gid);
    wipe(file, sizeof(file));
    wipe(gid, sizeof(gid));
    return accepted ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL Java_com_lenerd_lyricsnative_generation_SpotifyAudioNative_cacheStatus(JNIEnv *, jclass, jlong epoch) { return lyrics_spotify_audio_cache_status(epoch); }
extern "C" JNIEXPORT jlong JNICALL Java_com_lenerd_lyricsnative_generation_SpotifyAudioNative_cacheBytes(JNIEnv *, jclass, jlong epoch) { return lyrics_spotify_audio_cache_bytes(epoch); }
extern "C" JNIEXPORT jboolean JNICALL Java_com_lenerd_lyricsnative_generation_SpotifyAudioNative_cacheCipherReady(JNIEnv *, jclass, jlong epoch) { return lyrics_spotify_audio_cache_cipher_ready(epoch) ? JNI_TRUE : JNI_FALSE; }
extern "C" JNIEXPORT jboolean JNICALL Java_com_lenerd_lyricsnative_generation_SpotifyAudioNative_cacheFileReady(JNIEnv *, jclass, jlong epoch) { return lyrics_spotify_audio_file_ready(epoch) ? JNI_TRUE : JNI_FALSE; }

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_lenerd_lyricsnative_generation_SpotifyAudioNative_decodedCache(JNIEnv *env, jclass, jlong epoch) {
    auto snapshot = lyrics_spotify_audio_cache_file_mode() ? lyrics_spotify_audio::cache_decrypt_input(epoch, lyrics_spotify_audio::cache_file_read(epoch)) : lyrics_spotify_audio::cache_decrypt(epoch);
    if (!snapshot.size() || lyrics_spotify_audio_epoch_current() != static_cast<std::uint64_t>(epoch)) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(snapshot.size()));
    if (!result) return nullptr;
    if (lyrics_spotify_audio_epoch_current() != static_cast<std::uint64_t>(epoch)) {
        env->DeleteLocalRef(result);
        return nullptr;
    }
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(snapshot.size()), reinterpret_cast<const jbyte *>(snapshot.data()));
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(result);
        return nullptr;
    }
    if (lyrics_spotify_audio_epoch_current() != static_cast<std::uint64_t>(epoch)) {
        jbyte zeros[4096]{};
        for (std::size_t position = 0; position < snapshot.size(); position += sizeof(zeros)) {
            const auto count = snapshot.size() - position < sizeof(zeros) ? snapshot.size() - position : sizeof(zeros);
            env->SetByteArrayRegion(result, static_cast<jsize>(position), static_cast<jsize>(count), zeros);
        }
        env->DeleteLocalRef(result);
        return nullptr;
    }
    return result;
}
