#include <jni.h>
#include <arm_neon.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <new>
#include <vector>

namespace {
constexpr int fft_size = 6144, radix_size = 2048, hop = 1024, bins = 2048, times = 32, samples = 31744, margin = 3072;
constexpr double pi = 3.14159265358979323846;

struct spectral {
    std::array<int, radix_size> reverse;
    std::vector<float> hann, normalizer, radix_twiddle, twiddle, twiddle_two, input, frame, transformed, spectrum, pcm, right_input, right_pcm;
    std::array<std::vector<float>, 3> split;
    std::array<std::vector<float>, 11> stages;

    spectral() : hann(fft_size), normalizer(samples), radix_twiddle(radix_size), twiddle(2 * fft_size), twiddle_two(2 * fft_size), input(samples), frame(2 * fft_size), transformed(2 * fft_size), spectrum(4 * bins * times), pcm(samples), right_input(samples), right_pcm(samples) {
        for (int i = 0; i < fft_size; i++) {
            hann[i] = static_cast<float>(0.5 - 0.5 * std::cos(2 * pi * i / fft_size));
            twiddle[2 * i] = static_cast<float>(std::cos(-2 * pi * i / fft_size));
            twiddle[2 * i + 1] = static_cast<float>(std::sin(-2 * pi * i / fft_size));
        }
        for (int i = 0; i < radix_size; i++) {
            int value = i, reversed = 0;
            for (int bit = 0; bit < 11; bit++) { reversed = 2 * reversed + (value & 1); value >>= 1; }
            reverse[i] = reversed;
            if (i < radix_size / 2) {
                radix_twiddle[2 * i] = static_cast<float>(std::cos(-2 * pi * i / radix_size));
                radix_twiddle[2 * i + 1] = static_cast<float>(std::sin(-2 * pi * i / radix_size));
            }
        }
        for (auto& part : split) part.resize(2 * radix_size);
        for (int k = 0; k < fft_size; k++) {
            twiddle_two[2 * k] = twiddle[2 * ((2 * k) % fft_size)];
            twiddle_two[2 * k + 1] = twiddle[2 * ((2 * k) % fft_size) + 1];
        }
        for (int stage = 0, length = 2; length <= radix_size; stage++, length *= 2) {
            stages[stage].resize(length);
            for (int j = 0; j < length / 2; j++) {
                int at = 2 * j * (radix_size / length);
                stages[stage][2 * j] = radix_twiddle[at];
                stages[stage][2 * j + 1] = radix_twiddle[at + 1];
            }
        }
        for (int t = 0; t < times; t++) {
            for (int j = 0; j < fft_size; j++) {
                int k = t * hop + j - margin;
                if (k >= 0 && k < samples) normalizer[k] += hann[j] * hann[j];
            }
        }
    }

    void transform() {
        for (int r = 0; r < 3; r++) {
            auto& data = split[r];
            for (int j = 0; j < radix_size; j++) {
                data[2 * reverse[j]] = frame[2 * (3 * j + r)];
                data[2 * reverse[j] + 1] = frame[2 * (3 * j + r) + 1];
            }
            for (int stage = 0, length = 2; length <= radix_size; stage++, length *= 2) {
                int half = length / 2;
                const float* weights = stages[stage].data();
                for (int start = 0; start < radix_size; start += length) {
                    int j = 0;
                    for (; j + 4 <= half; j += 4) {
                        int a = 2 * (start + j), b = 2 * (start + j + half);
                        auto lhs = vld2q_f32(data.data() + a), rhs = vld2q_f32(data.data() + b), w = vld2q_f32(weights + 2 * j);
                        auto re = vsubq_f32(vmulq_f32(rhs.val[0], w.val[0]), vmulq_f32(rhs.val[1], w.val[1]));
                        auto im = vaddq_f32(vmulq_f32(rhs.val[0], w.val[1]), vmulq_f32(rhs.val[1], w.val[0]));
                        float32x4x2_t sum = {{vaddq_f32(lhs.val[0], re), vaddq_f32(lhs.val[1], im)}};
                        float32x4x2_t difference = {{vsubq_f32(lhs.val[0], re), vsubq_f32(lhs.val[1], im)}};
                        vst2q_f32(data.data() + a, sum);
                        vst2q_f32(data.data() + b, difference);
                    }
                    for (; j < half; j++) {
                        int a = 2 * (start + j), b = 2 * (start + j + half), w = 2 * j;
                        float re = data[b] * weights[w] - data[b + 1] * weights[w + 1];
                        float im = data[b] * weights[w + 1] + data[b + 1] * weights[w];
                        float ar = data[a], ai = data[a + 1];
                        data[a] = ar + re;
                        data[a + 1] = ai + im;
                        data[b] = ar - re;
                        data[b + 1] = ai - im;
                    }
                }
            }
        }
        for (int k = 0; k < fft_size; k += 4) {
            int at = 2 * (k % radix_size);
            auto y0 = vld2q_f32(split[0].data() + at), y1 = vld2q_f32(split[1].data() + at), y2 = vld2q_f32(split[2].data() + at);
            auto w = vld2q_f32(twiddle.data() + 2 * k), w2 = vld2q_f32(twiddle_two.data() + 2 * k);
            auto a = vsubq_f32(vmulq_f32(y1.val[0], w.val[0]), vmulq_f32(y1.val[1], w.val[1]));
            auto b = vaddq_f32(vmulq_f32(y1.val[0], w.val[1]), vmulq_f32(y1.val[1], w.val[0]));
            auto c = vsubq_f32(vmulq_f32(y2.val[0], w2.val[0]), vmulq_f32(y2.val[1], w2.val[1]));
            auto d = vaddq_f32(vmulq_f32(y2.val[0], w2.val[1]), vmulq_f32(y2.val[1], w2.val[0]));
            float32x4x2_t result = {{vaddq_f32(vaddq_f32(y0.val[0], a), c), vaddq_f32(vaddq_f32(y0.val[1], b), d)}};
            vst2q_f32(transformed.data() + 2 * k, result);
        }
    }
};
}

extern "C" JNIEXPORT jlong JNICALL Java_com_lenerd_lyricsnative_sing_NativeSpectralTransform_create(JNIEnv* env, jclass type) {
    try {
        return reinterpret_cast<jlong>(new spectral());
    } catch (const std::bad_alloc&) {
        env->ThrowNew(env->FindClass("java/lang/OutOfMemoryError"), "Cannot allocate spectral transform");
        return 0;
    }
}

extern "C" JNIEXPORT void JNICALL Java_com_lenerd_lyricsnative_sing_NativeSpectralTransform_destroy(JNIEnv* env, jclass type, jlong handle) {
    delete reinterpret_cast<spectral*>(handle);
}

extern "C" JNIEXPORT jfloatArray JNICALL Java_com_lenerd_lyricsnative_sing_NativeSpectralTransform_forward(JNIEnv* env, jclass type, jlong handle, jfloatArray left, jfloatArray right) {
    auto& state = *reinterpret_cast<spectral*>(handle);
    env->GetFloatArrayRegion(left, 0, samples, state.input.data());
    env->GetFloatArrayRegion(right, 0, samples, state.right_input.data());
    if (env->ExceptionCheck()) return nullptr;
    std::fill(state.spectrum.begin(), state.spectrum.end(), 0);
    for (int t = 0; t < times; t++) {
        int offset = t * hop - margin, j = 0;
        if (offset >= 0 && offset + fft_size <= samples) {
            for (; j < fft_size; j += 4) {
                auto window = vld1q_f32(state.hann.data() + j);
                float32x4x2_t result = {{vmulq_f32(vld1q_f32(state.input.data() + offset + j), window), vmulq_f32(vld1q_f32(state.right_input.data() + offset + j), window)}};
                vst2q_f32(state.frame.data() + 2 * j, result);
            }
        }
        for (; j < fft_size; j++) {
            int k = offset + j;
            if (k < 0) k = -k;
            if (k >= samples) k = 2 * samples - 2 - k;
            state.frame[2 * j] = state.input[k] * state.hann[j];
            state.frame[2 * j + 1] = state.right_input[k] * state.hann[j];
        }
        // Two real channels share one complex FFT; conjugate symmetry recovers each spectrum.
        state.transform();
        for (int f = 3; f < bins; f++) {
            int positive = 2 * f, negative = 2 * (fft_size - f);
            float re = state.transformed[positive], im = state.transformed[positive + 1];
            float mirror_re = state.transformed[negative], mirror_im = state.transformed[negative + 1];
            state.spectrum[f * times + t] = (re + mirror_re) * 0.5f;
            state.spectrum[(bins + f) * times + t] = (im - mirror_im) * 0.5f;
            state.spectrum[(2 * bins + f) * times + t] = (im + mirror_im) * 0.5f;
            state.spectrum[(3 * bins + f) * times + t] = (mirror_re - re) * 0.5f;
        }
    }
    jfloatArray output = env->NewFloatArray(state.spectrum.size());
    if (output) env->SetFloatArrayRegion(output, 0, state.spectrum.size(), state.spectrum.data());
    return output;
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_com_lenerd_lyricsnative_sing_NativeSpectralTransform_inverse(JNIEnv* env, jclass type, jlong handle, jfloatArray spectrum) {
    auto& state = *reinterpret_cast<spectral*>(handle);
    env->GetFloatArrayRegion(spectrum, 0, state.spectrum.size(), state.spectrum.data());
    if (env->ExceptionCheck()) return nullptr;
    std::fill(state.pcm.begin(), state.pcm.end(), 0);
    std::fill(state.right_pcm.begin(), state.right_pcm.end(), 0);
    for (int t = 0; t < times; t++) {
        std::fill(state.frame.begin(), state.frame.end(), 0);
        for (int f = 0; f < bins; f++) {
            float left_re = state.spectrum[f * times + t], left_im = state.spectrum[(bins + f) * times + t];
            float right_re = state.spectrum[(2 * bins + f) * times + t], right_im = state.spectrum[(3 * bins + f) * times + t];
            state.frame[2 * f] = f == 0 ? left_re : left_re - right_im;
            state.frame[2 * f + 1] = f == 0 ? -right_re : -left_im - right_re;
            if (f > 0) {
                state.frame[2 * (fft_size - f)] = left_re + right_im;
                state.frame[2 * (fft_size - f) + 1] = left_im - right_re;
            }
        }
        state.transform();
        int offset = t * hop - margin, begin = std::max(0, -offset), end = std::min(fft_size, samples - offset), j = begin;
        for (; j + 4 <= end; j += 4) {
            auto values = vld2q_f32(state.transformed.data() + 2 * j);
            auto window = vld1q_f32(state.hann.data() + j);
            auto left = vmulq_f32(vmulq_n_f32(values.val[0], 1.0f / fft_size), window);
            auto right = vmulq_f32(vmulq_n_f32(values.val[1], -1.0f / fft_size), window);
            vst1q_f32(state.pcm.data() + offset + j, vaddq_f32(vld1q_f32(state.pcm.data() + offset + j), left));
            vst1q_f32(state.right_pcm.data() + offset + j, vaddq_f32(vld1q_f32(state.right_pcm.data() + offset + j), right));
        }
        for (; j < end; j++) {
            state.pcm[offset + j] += (state.transformed[2 * j] * (1.0f / fft_size)) * state.hann[j];
            state.right_pcm[offset + j] += (state.transformed[2 * j + 1] * (-1.0f / fft_size)) * state.hann[j];
        }
    }
    jobjectArray output = env->NewObjectArray(2, env->FindClass("[F"), nullptr);
    if (!output) return nullptr;
    for (int c = 0; c < 2; c++) {
        auto& pcm = c == 0 ? state.pcm : state.right_pcm;
        for (int i = 0; i < samples; i += 4) vst1q_f32(pcm.data() + i, vdivq_f32(vld1q_f32(pcm.data() + i), vmaxq_f32(vld1q_f32(state.normalizer.data() + i), vdupq_n_f32(1e-8f))));
        jfloatArray channel = env->NewFloatArray(samples);
        if (!channel) return nullptr;
        env->SetFloatArrayRegion(channel, 0, samples, pcm.data());
        env->SetObjectArrayElement(output, c, channel);
        env->DeleteLocalRef(channel);
    }
    return output;
}

extern "C" JNIEXPORT jint JNICALL Java_com_lenerd_lyricsnative_generation_StreamingResampler_resample(JNIEnv* env, jclass type, jfloatArray input, jdoubleArray phases, jint width, jint phase_count, jint radius, jlong base, jlong received, jlong produced, jint input_rate, jint output_rate, jdouble step, jdouble half_width, jboolean last, jfloatArray output, jint offset, jint capacity) {
    auto* samples = env->GetFloatArrayElements(input, nullptr);
    if (!samples) return 0;
    auto* weights = env->GetDoubleArrayElements(phases, nullptr);
    if (!weights) {
        env->ReleaseFloatArrayElements(input, samples, JNI_ABORT);
        return 0;
    }
    std::array<float, 4096> result;
    int count = 0;
    for (; count < capacity; count++) {
        int64_t position = produced + count;
        if (!last && std::floor(position * step + half_width) >= received) break;
        const double* phase = weights + (position % phase_count) * width;
        int64_t begin = position * input_rate / output_rate - radius;
        int from = static_cast<int>(std::max<int64_t>(0, base - begin));
        int to = static_cast<int>(std::min<int64_t>(width, received - begin));
        int at = static_cast<int>(begin - base + from);
        double sum = 0;
        for (int i = from; i < to; i++) sum += samples[at++] * phase[i];
        result[count] = static_cast<float>(sum);
    }
    env->ReleaseDoubleArrayElements(phases, weights, JNI_ABORT);
    env->ReleaseFloatArrayElements(input, samples, JNI_ABORT);
    if (count) env->SetFloatArrayRegion(output, offset, count, result.data());
    return count;
}
