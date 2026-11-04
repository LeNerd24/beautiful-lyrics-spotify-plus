#include <jni.h>
#include <dlfcn.h>

JNIEXPORT jstring JNICALL Java_com_lenerd_lyricsnative_sing_QnnRuntime_dspError(JNIEnv *env, jclass type) {
    void *library = dlopen("libcdsprpc.so", RTLD_NOW | RTLD_LOCAL);
    if (library) {
        dlclose(library);
        return NULL;
    }
    const char *error = dlerror();
    return (*env)->NewStringUTF(env, error ? error : "libcdsprpc.so could not be loaded");
}
