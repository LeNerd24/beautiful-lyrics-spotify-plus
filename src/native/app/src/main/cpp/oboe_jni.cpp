#include "oboe-ring.h"
#include "oboe-resolver.h"
#include "resident-shadowhook.h"
#include <jni.h>
#include <shadowhook.h>
#include <android/log.h>
#include <link.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <vector>

namespace {
    using Callback = int (*)(void *, void *, int32_t);
    std::mutex lifecycle;
    std::unique_ptr<OboeRing> ring;
    OboeLayout layout{};
    ShadowHookApi shadowApi{};
    Callback original = nullptr;
    void *hook = nullptr, *streamOwner = nullptr;
    jclass anchor = nullptr;
    std::atomic<bool> enabled{false};
    std::atomic<uint64_t> callbacks{0};
    std::atomic<int64_t> sourceEnd{-1};
    std::atomic_flag processing = ATOMIC_FLAG_INIT;
    int streamRate = 0, streamEncoding = 0;
    thread_local int depth = 0;
    uint64_t callbackEpoch = 0;
    int tailFrames = -1;

    int callback(void *stream, void *audio, int32_t frames) {
        struct Scope {
            Scope() { depth++; }
            ~Scope() { depth--; }
        } scope;
        if (depth > 1 || !enabled.load(std::memory_order_acquire) || !ring || !audio || frames <= 0 || frames > 16384) return original(stream, audio, frames);
        if (processing.test_and_set(std::memory_order_acquire)) return original(stream, audio, frames);
        uint64_t epoch = ring->generation();
        if (callbackEpoch != epoch) {
            callbackEpoch = epoch;
            tailFrames = -1;
            sourceEnd.store(-1, std::memory_order_release);
        }
        int result = tailFrames >= 0 ? 0 : original(stream, audio, frames);
        auto field = [stream](uint32_t offset) { int value; std::memcpy(&value, static_cast<char *>(stream) + offset, sizeof(value)); return value; };
        int rate = field(layout.rateOffset), channels = field(layout.channelOffset), format = field(layout.formatOffset), direction = field(layout.directionOffset);
        if ((rate == 44100 || rate == 48000) && channels == 2 && (format == 1 || format == 2) && direction == 0) {
            int encoding = format == 1 ? 2 : 4;
            if (streamOwner != stream || streamRate != rate || streamEncoding != encoding) {
                streamOwner = stream;
                streamRate = rate;
                streamEncoding = encoding;
                ring->configure(rate, encoding, rate * 12 / 10);
                callbackEpoch = ring->generation();
                tailFrames = -1;
                sourceEnd.store(-1, std::memory_order_release);
            }
            if (result == 1 && tailFrames < 0) {
                OboeRing::Stats stats = ring->statistics();
                sourceEnd.store(stats.generation == ring->generation() ? static_cast<int64_t>(stats.captured) : 0, std::memory_order_release);
                tailFrames = rate * 12 / 10;
            }
            if (tailFrames >= 0) {
                if (tailFrames == 0) {
                    processing.clear(std::memory_order_release);
                    return 1;
                }
                std::memset(audio, 0, frames * (encoding == 4 ? 8 : 4));
                ring->process(audio, frames);
                tailFrames = std::max(0, tailFrames - frames);
                result = 0;
            } else if (result == 0) ring->process(audio, frames);
            callbacks.fetch_add(1, std::memory_order_relaxed);
        }
        processing.clear(std::memory_order_release);
        return result;
    }

    int findOrbit(dl_phdr_info *info, size_t, void *found) {
        if (!info->dlpi_name || !std::strstr(info->dlpi_name, "liborbit-jni-spotify.so")) return 0;
        *static_cast<bool *>(found) = lyrics_resolve_oboe(info->dlpi_addr, info->dlpi_phdr, info->dlpi_phnum, layout);
        return 1;
    }
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_lenerd_lyricsnative_sing_OboeSingNative_install(JNIEnv *env, jclass type) {
    std::lock_guard lock(lifecycle);
    if (hook) return JNI_TRUE;
    bool found = false;
    dl_iterate_phdr(findOrbit, &found);
    if (!found) {
        __android_log_print(ANDROID_LOG_ERROR, "SpotifySing", "OBOE_RESOLVER_REJECTED callback layout is unsupported");
        return JNI_FALSE;
    }
    if (!lyrics_shadowhook_api(shadowApi)) {
        __android_log_print(ANDROID_LOG_ERROR, "SpotifySing", "OBOE_HOOK_INIT_FAILED resident hook engine unavailable");
        return JNI_FALSE;
    }
    int error = shadowApi.init(SHADOWHOOK_MODE_UNIQUE, false);
    if (error != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "SpotifySing", "OBOE_HOOK_INIT_FAILED error=%d", error);
        return JNI_FALSE;
    }
    if (!ring) ring = std::make_unique<OboeRing>();
    if (!anchor) anchor = static_cast<jclass>(env->NewGlobalRef(type));
    if (!anchor) return JNI_FALSE;
    hook = shadowApi.hook(reinterpret_cast<void *>(layout.callback), reinterpret_cast<void *>(callback), reinterpret_cast<void **>(&original));
    if (!hook) {
        __android_log_print(ANDROID_LOG_ERROR, "SpotifySing", "OBOE_HOOK_FAILED error=%d", shadowApi.error());
        return JNI_FALSE;
    }
    __android_log_print(ANDROID_LOG_INFO, "SpotifySing", "OBOE_NATIVE_INSTALLED callback=%p rate_offset=%u channels_offset=%u format_offset=%u resident_hook_engine=%d engine=%p", reinterpret_cast<void *>(layout.callback), layout.rateOffset, layout.channelOffset, layout.formatOffset, shadowApi.initialized, reinterpret_cast<void *>(shadowApi.init));
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL Java_com_lenerd_lyricsnative_sing_OboeSingNative_enable(JNIEnv *, jclass, jboolean value) {
    bool previous = enabled.exchange(value == JNI_TRUE, std::memory_order_acq_rel);
    if (ring && previous != (value == JNI_TRUE)) ring->reset();
}

extern "C" JNIEXPORT void JNICALL Java_com_lenerd_lyricsnative_sing_OboeSingNative_gain(JNIEnv *, jclass, jfloat value) { if (ring) ring->setGain(value); }
extern "C" JNIEXPORT void JNICALL Java_com_lenerd_lyricsnative_sing_OboeSingNative_reset(JNIEnv *, jclass) { if (ring) ring->reset(); }

extern "C" JNIEXPORT jint JNICALL Java_com_lenerd_lyricsnative_sing_OboeSingNative_read(JNIEnv *env, jclass, jfloatArray samples, jlongArray metadata) {
    if (!ring || !samples || !metadata || env->GetArrayLength(metadata) < 2) return 0;
    int capacity = std::min(8192, env->GetArrayLength(samples) / 2);
    std::array<float, 16384> buffer;
    uint64_t generation = 0, start = 0;
    int count = ring->drain(buffer.data(), capacity, generation, start);
    if (count <= 0) return 0;
    jlong values[] = {static_cast<jlong>(generation), static_cast<jlong>(start)};
    env->SetFloatArrayRegion(samples, 0, count * 2, buffer.data());
    env->SetLongArrayRegion(metadata, 0, 2, values);
    return env->ExceptionCheck() ? 0 : count;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_lenerd_lyricsnative_sing_OboeSingNative_write(JNIEnv *env, jclass, jfloatArray samples, jint frames, jlong generation, jlong start) {
    if (!ring || !samples || frames <= 0 || frames > 8192 || env->GetArrayLength(samples) < frames * 2 || generation <= 0 || start < 0) return JNI_FALSE;
    std::array<float, 16384> buffer;
    env->GetFloatArrayRegion(samples, 0, frames * 2, buffer.data());
    if (env->ExceptionCheck()) return JNI_FALSE;
    return ring->publish(buffer.data(), frames, generation, start) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlongArray JNICALL Java_com_lenerd_lyricsnative_sing_OboeSingNative_state(JNIEnv *env, jclass) {
    OboeRing::Stats stats = ring ? ring->statistics() : OboeRing::Stats{};
    jlong values[] = {static_cast<jlong>(stats.generation), ring ? ring->rate() : 0, ring ? ring->encoding() : 0, static_cast<jlong>(stats.captured), static_cast<jlong>(stats.startup), static_cast<jlong>(stats.processed), static_cast<jlong>(stats.fallback), static_cast<jlong>(stats.dropped), static_cast<jlong>(callbacks.load(std::memory_order_relaxed)), static_cast<jlong>(sourceEnd.load(std::memory_order_acquire))};
    jlongArray result = env->NewLongArray(10);
    if (result) env->SetLongArrayRegion(result, 0, 10, values);
    return result;
}

extern "C" JNIEXPORT void JNICALL Java_com_lenerd_lyricsnative_sing_OboeSingNative_close(JNIEnv *, jclass) {
    std::lock_guard lock(lifecycle);
    enabled.store(false, std::memory_order_release);
    if (ring) ring->reset();
    if (hook) {
        int result = shadowApi.unhook(hook);
        __android_log_print(result == 0 ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR, "SpotifySing", "OBOE_NATIVE_UNHOOK result=%d", result);
        if (result == 0) hook = nullptr;
    }
}
