#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <android/log.h>

static void *gadget;
static jclass anchor;
extern void lyrics_spotify_audio_resources_clear(void);
extern void lyrics_spotify_audio_cache_clear(void);
extern void lyrics_spotify_audio_cache_cipher_clear(void);
extern void lyrics_spotify_audio_file_clear(void);
extern void lyrics_spotify_audio_window_clear(void);
extern uint64_t lyrics_spotify_audio_epoch_current(void);
extern uint64_t lyrics_spotify_audio_epoch_begin(void);
extern void lyrics_spotify_audio_epoch_end(void);
extern int lyrics_spotify_audio_epoch_end_if(uint64_t);

__attribute__((visibility("default"))) int lyrics_spotify_audio_active(void) { return lyrics_spotify_audio_epoch_current() & 1; }
__attribute__((visibility("default"))) int lyrics_spotify_audio_resource_mode(void) { return lyrics_spotify_audio_active(); }
__attribute__((visibility("default"))) int lyrics_spotify_audio_cache_mode(void) { return lyrics_spotify_audio_active(); }
__attribute__((visibility("default"))) int lyrics_spotify_audio_cache_decode_mode(void) { return lyrics_spotify_audio_active(); }
__attribute__((visibility("default"))) int lyrics_spotify_audio_cache_file_mode(void) { return lyrics_spotify_audio_active(); }
__attribute__((visibility("default"))) void lyrics_spotify_audio_record(const char *message) {
    if (message) __android_log_print(ANDROID_LOG_INFO, "LyricsSpotifyAudio", "%s", message);
}

static void clear(void) {
    lyrics_spotify_audio_window_clear();
    lyrics_spotify_audio_cache_clear();
    lyrics_spotify_audio_cache_cipher_clear();
    lyrics_spotify_audio_file_clear();
    lyrics_spotify_audio_resources_clear();
}

JNIEXPORT jboolean JNICALL Java_com_lenerd_lyricsnative_generation_SpotifyAudioNative_start(JNIEnv *env, jclass type, jstring path) {
    if (lyrics_spotify_audio_active() || !path) return JNI_FALSE;
    const char *name = (*env)->GetStringUTFChars(env, path, NULL);
    if (!name) return JNI_FALSE;
    if (!anchor) anchor = (*env)->NewGlobalRef(env, type);
    if (!anchor || !lyrics_spotify_audio_epoch_begin()) {
        if (anchor) {
            (*env)->DeleteGlobalRef(env, anchor);
            anchor = NULL;
        }
        (*env)->ReleaseStringUTFChars(env, path, name);
        return JNI_FALSE;
    }
    clear();
    if (!gadget) gadget = dlopen(name, RTLD_NOW | RTLD_LOCAL);
    (*env)->ReleaseStringUTFChars(env, path, name);
    if (gadget) return JNI_TRUE;
    lyrics_spotify_audio_epoch_end();
    clear();
    (*env)->DeleteGlobalRef(env, anchor);
    anchor = NULL;
    __android_log_print(ANDROID_LOG_ERROR, "LyricsSpotifyAudio", "Audio runtime load failed: %s", dlerror());
    return JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_lenerd_lyricsnative_generation_SpotifyAudioNative_deactivateEpoch(JNIEnv *env, jclass type, jlong expected) {
    if (lyrics_spotify_audio_epoch_end_if((uint64_t) expected)) clear();
}

JNIEXPORT void JNICALL Java_com_lenerd_lyricsnative_generation_SpotifyAudioNative_stop(JNIEnv *env, jclass type) {
    lyrics_spotify_audio_epoch_end();
    clear();
}
