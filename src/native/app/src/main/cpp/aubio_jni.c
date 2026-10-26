#include <jni.h>
#include <math.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "types.h"
#include "fvec.h"
#include "cvec.h"
#include "fmat.h"
#include "spectral/phasevoc.h"
#include "spectral/filterbank.h"
#include "spectral/filterbank_mel.h"
#include "tempo/tempo.h"

typedef struct {
    aubio_pvoc_t *pvoc;
    aubio_filterbank_t *filterbank;
    fvec_t *input, *bands, *previous;
    cvec_t *grain;
    smpl_t *sorted;
    smpl_t loudest;
    uint_t hop, count, started;
} envelope_handle;

typedef struct {
    aubio_tempo_t *tempo;
    fvec_t *input, *output;
    uint_t hop;
} tempo_handle;

static int compare(const void *a, const void *b) {
    smpl_t x = *(const smpl_t *) a, y = *(const smpl_t *) b;
    return (x > y) - (x < y);
}

JNIEXPORT jlong JNICALL Java_com_lenerd_lyricsnative_generation_Aubio_newEnvelope(JNIEnv *env, jclass type, jint window, jint hop, jint rate, jint bands, jfloat minimumHz, jfloat maximumHz) {
    envelope_handle *handle = calloc(1, sizeof(envelope_handle));
    handle->pvoc = new_aubio_pvoc((uint_t) window, (uint_t) hop);
    handle->filterbank = new_aubio_filterbank((uint_t) bands, (uint_t) window);

    if (!handle->pvoc || !handle->filterbank || aubio_filterbank_set_mel_coeffs(handle->filterbank, (smpl_t) rate, minimumHz, maximumHz) != 0) {
        if (handle->pvoc) del_aubio_pvoc(handle->pvoc);
        if (handle->filterbank) del_aubio_filterbank(handle->filterbank);
        free(handle);
        return 0;
    }

    aubio_filterbank_set_power(handle->filterbank, 2);

    handle->input = new_fvec((uint_t) hop);
    handle->grain = new_cvec((uint_t) window);
    handle->bands = new_fvec((uint_t) bands);
    handle->previous = new_fvec((uint_t) bands);
    handle->sorted = calloc((size_t) bands, sizeof(smpl_t));
    handle->hop = (uint_t) hop;
    handle->count = (uint_t) bands;
    handle->loudest = -INFINITY;
    return (jlong) (intptr_t) handle;
}

JNIEXPORT void JNICALL Java_com_lenerd_lyricsnative_generation_Aubio_processEnvelope(JNIEnv *env, jclass type, jlong pointer, jfloatArray samples, jint hops, jfloatArray values) {
    envelope_handle *handle = (envelope_handle *) (intptr_t) pointer;
    jfloat *input = (*env)->GetFloatArrayElements(env, samples, NULL);
    jfloat *output = (*env)->GetFloatArrayElements(env, values, NULL);

    for (jint h = 0; h < hops; h++) {
        memcpy(handle->input->data, input + h * handle->hop, handle->hop * sizeof(smpl_t));

        aubio_pvoc_do(handle->pvoc, handle->input, handle->grain);
        aubio_filterbank_do(handle->filterbank, handle->grain, handle->bands);

        for (uint_t b = 0; b < handle->count; b++) {
            smpl_t db = 10 * log10f(fmaxf(handle->bands->data[b], 1e-10f));
            handle->bands->data[b] = db;
            if (db > handle->loudest) handle->loudest = db;
        }

        for (uint_t b = 0; b < handle->count; b++) {
            smpl_t db = fmaxf(handle->bands->data[b], handle->loudest - 80);
            smpl_t rise = handle->started ? db - handle->previous->data[b] : 0;

            handle->sorted[b] = rise > 0 ? rise : 0;
            handle->previous->data[b] = db;
        }

        handle->started = 1;
        qsort(handle->sorted, handle->count, sizeof(smpl_t), compare);

        uint_t middle = handle->count / 2;
        output[h] = handle->count % 2 ? handle->sorted[middle] : (handle->sorted[middle - 1] + handle->sorted[middle]) / 2;
    }

    (*env)->ReleaseFloatArrayElements(env, values, output, 0);
    (*env)->ReleaseFloatArrayElements(env, samples, input, JNI_ABORT);
}

JNIEXPORT void JNICALL Java_com_lenerd_lyricsnative_generation_Aubio_deleteEnvelope(JNIEnv *env, jclass type, jlong pointer) {
    envelope_handle *handle = (envelope_handle *) (intptr_t) pointer;
    if (!handle) return;

    del_aubio_pvoc(handle->pvoc);
    del_aubio_filterbank(handle->filterbank);
    del_fvec(handle->input);
    del_cvec(handle->grain);
    del_fvec(handle->bands);
    del_fvec(handle->previous);
    free(handle->sorted);
    free(handle);
}

JNIEXPORT jlong JNICALL Java_com_lenerd_lyricsnative_generation_Aubio_newTempo(JNIEnv *env, jclass type, jstring method, jint window, jint hop, jint rate) {
    const char *name = (*env)->GetStringUTFChars(env, method, NULL);
    aubio_tempo_t *tempo = new_aubio_tempo(name, (uint_t) window, (uint_t) hop, (uint_t) rate);
    (*env)->ReleaseStringUTFChars(env, method, name);
    if (!tempo) return 0;

    tempo_handle *handle = calloc(1, sizeof(tempo_handle));
    handle->tempo = tempo;
    handle->input = new_fvec((uint_t) hop);
    handle->output = new_fvec(2);
    handle->hop = (uint_t) hop;
    return (jlong) (intptr_t) handle;
}

JNIEXPORT jint JNICALL Java_com_lenerd_lyricsnative_generation_Aubio_processTempo(JNIEnv *env, jclass type, jlong pointer, jfloatArray samples, jint hops, jlongArray beats) {
    tempo_handle *handle = (tempo_handle *) (intptr_t) pointer;
    jfloat *input = (*env)->GetFloatArrayElements(env, samples, NULL);
    jlong *found = (*env)->GetLongArrayElements(env, beats, NULL);
    jint count = 0;

    for (jint h = 0; h < hops; h++) {
        memcpy(handle->input->data, input + h * handle->hop, handle->hop * sizeof(smpl_t));

        aubio_tempo_do(handle->tempo, handle->input, handle->output);
        if (handle->output->data[0] != 0) found[count++] = (jlong) aubio_tempo_get_last(handle->tempo);
    }

    (*env)->ReleaseLongArrayElements(env, beats, found, 0);
    (*env)->ReleaseFloatArrayElements(env, samples, input, JNI_ABORT);
    return count;
}

JNIEXPORT jfloat JNICALL Java_com_lenerd_lyricsnative_generation_Aubio_tempoBpm(JNIEnv *env, jclass type, jlong pointer) {
    return aubio_tempo_get_bpm(((tempo_handle *) (intptr_t) pointer)->tempo);
}

JNIEXPORT void JNICALL Java_com_lenerd_lyricsnative_generation_Aubio_deleteTempo(JNIEnv *env, jclass type, jlong pointer) {
    tempo_handle *handle = (tempo_handle *) (intptr_t) pointer;
    if (!handle) return;

    del_aubio_tempo(handle->tempo);
    del_fvec(handle->input);
    del_fvec(handle->output);
    free(handle);
}
