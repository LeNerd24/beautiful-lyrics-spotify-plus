#include "remote-song-cipher.h"
#include "../resident-gadget.h"
#include <jni.h>
#include <dlfcn.h>
#include <atomic>
#include <mutex>
#include <vector>
#include <unistd.h>
#include <sys/syscall.h>

namespace {
    std::mutex runtimeMutex;
    bool runtimeLoaded = false;
    jclass anchor = nullptr;
    std::atomic<uint64_t> currentEpoch{0};
    std::atomic<int> configured{0};
    struct Bytes {
        std::vector<uint8_t> value;
        explicit Bytes(size_t size) : value(size) { }
        ~Bytes() {
            volatile uint8_t *data = value.data();
            for (size_t i = 0; i < value.size(); i++) data[i] = 0;
        }
    };
}

extern "C" uint64_t lyrics_spotify_remote_bridge_epoch() { return currentEpoch.load(); }
extern "C" int lyrics_spotify_remote_bridge_active() { return currentEpoch.load() != 0; }
extern "C" int lyrics_spotify_remote_bridge_configure_profile(uintptr_t base, size_t size, const uintptr_t *profile) {
    int result = lyrics_spotify_remote_cipher_configure_profile(base, size, profile);
    configured.store(result);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_lenerd_lyricsnative_generation_RemoteAudioNative_start(JNIEnv *env, jclass type, jstring path, jlong epoch) {
    std::lock_guard lock(runtimeMutex);
    if (currentEpoch.load() != 0 || epoch <= 0) return JNI_FALSE;
    if (!anchor) anchor = static_cast<jclass>(env->NewGlobalRef(type));
    if (!anchor) return JNI_FALSE;
    const char *name = env->GetStringUTFChars(path, nullptr);
    if (!name) return JNI_FALSE;
    currentEpoch.store(static_cast<uint64_t>(epoch));
    configured.store(0);
    if (!runtimeLoaded) runtimeLoaded = lyrics_gadget_reuse_or_load(name);
    env->ReleaseStringUTFChars(path, name);
    if (runtimeLoaded) return JNI_TRUE;
    currentEpoch.store(0);
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_lenerd_lyricsnative_generation_RemoteAudioNative_ready(JNIEnv *, jclass) { return currentEpoch.load() != 0 && configured.load() == 1; }
extern "C" JNIEXPORT jboolean JNICALL Java_com_lenerd_lyricsnative_generation_RemoteAudioNative_arm(JNIEnv *, jclass, jlong epoch, jint handle) { return epoch > 0 && currentEpoch.load() == static_cast<uint64_t>(epoch) && configured.load() == 1 && lyrics_spotify_remote_cipher_arm(epoch, static_cast<uint32_t>(handle), static_cast<uint32_t>(syscall(SYS_gettid))) == 1; }
extern "C" JNIEXPORT jint JNICALL Java_com_lenerd_lyricsnative_generation_RemoteAudioNative_status(JNIEnv *, jclass, jlong epoch) { return lyrics_spotify_remote_cipher_status(epoch); }
extern "C" JNIEXPORT void JNICALL Java_com_lenerd_lyricsnative_generation_RemoteAudioNative_clear(JNIEnv *, jclass) {
    currentEpoch.store(0);
    lyrics_spotify_remote_cipher_clear();
}

extern "C" JNIEXPORT jbyteArray JNICALL Java_com_lenerd_lyricsnative_generation_RemoteAudioNative_decrypt(JNIEnv *env, jclass, jlong epoch, jbyteArray input) {
    if (!input || epoch <= 0 || currentEpoch.load() != static_cast<uint64_t>(epoch)) return nullptr;
    jsize size = env->GetArrayLength(input);
    if (size <= 0 || size > 16 * 1024 * 1024) return nullptr;
    try {
        Bytes encrypted(size), clear(size);
        env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte *>(encrypted.value.data()));
        if (env->ExceptionCheck() || lyrics_spotify_remote_cipher_decrypt(epoch, encrypted.value.data(), size, clear.value.data(), size) != 1 || currentEpoch.load() != static_cast<uint64_t>(epoch)) return nullptr;
        jbyteArray result = env->NewByteArray(size);
        if (result) env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte *>(clear.value.data()));
        return result;
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_lenerd_lyricsnative_generation_RemoteAudioNative_stop(JNIEnv *, jclass) {
    std::lock_guard lock(runtimeMutex);
    currentEpoch.store(0);
    lyrics_spotify_remote_cipher_clear();
    configured.store(0);
}
