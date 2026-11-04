#include "../../main/cpp/oboe-ring.h"
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <thread>
#include <vector>

static void check(bool condition, const char *message) {
    if (condition) return;
    std::fprintf(stderr, "FAIL: %s\n", message);
    std::abort();
}

static float source(uint64_t position, int channel) { return static_cast<float>(1000 + ((position * 13 + channel * 311) % 9000)) / 32768.0f; }

static void put(unsigned char *data, int encoding, float value) {
    if (encoding == 4) std::memcpy(data, &value, 4);
    else {
        int16_t sample = static_cast<int16_t>(std::lround(value * 32768.0f));
        std::memcpy(data, &sample, 2);
    }
}

static float get(const unsigned char *data, int encoding) {
    if (encoding == 4) {
        float value;
        std::memcpy(&value, data, 4);
        return value;
    }
    int16_t value;
    std::memcpy(&value, data, 2);
    return value / 32768.0f;
}

static void chronology(int rate, int encoding) {
    auto ring = std::make_unique<OboeRing>();
    constexpr int delay = 4096, block = 257;
    check(ring->configure(rate, encoding, delay), "supported configuration");
    ring->setGain(0);
    int sampleBytes = encoding == 4 ? 4 : 2, frameBytes = sampleBytes * 2;
    std::vector<unsigned char> pcm(block * frameBytes);
    std::vector<float> capture(block * 2);
    uint64_t total = OboeRing::CAPACITY * 2 + 137, written = 0;
    float removal = 0, increment = 1.0f / (rate * 0.03f);
    while (written < total) {
        int frames = static_cast<int>(std::min<uint64_t>(block, total - written));
        for (int i = 0; i < frames; i++) for (int c = 0; c < 2; c++) put(pcm.data() + (i * 2 + c) * sampleBytes, encoding, source(written + i, c));
        check(ring->process(pcm.data(), frames), "callback process");
        for (int i = 0; i < frames; i++) {
            uint64_t position = written + i;
            if (position >= delay) removal += std::clamp(1 - removal, -increment, increment);
            for (int c = 0; c < 2; c++) {
                float expected = position < delay ? 0 : source(position - delay, c) * (1 - 0.75f * removal);
                unsigned char quantized[4];
                put(quantized, encoding, expected);
                float tolerance = encoding == 4 ? 4e-8f : 1.0f / 32768;
                check(std::abs(get(pcm.data() + (i * 2 + c) * sampleBytes, encoding) - get(quantized, encoding)) <= tolerance, "delayed chronology, gain ramp and processed samples across wrap");
            }
        }
        uint64_t epoch = 0, start = 0;
        check(ring->drain(capture.data(), block, epoch, start) == frames, "worker drain exact frame count");
        check(start == written, "worker chronology");
        for (int i = 0; i < frames; i++) for (int c = 0; c < 2; c++) {
            check(capture[i * 2 + c] == source(written + i, c), "worker receives unchanged originals");
            capture[i * 2 + c] *= 0.25f;
        }
        check(ring->publish(capture.data(), frames, epoch, start), "publish instrumental");
        written += frames;
    }
    auto stats = ring->statistics();
    check(stats.captured == total && stats.startup == delay, "exact capture and startup delay length");
    check(stats.processed == total - delay && stats.fallback == 0 && stats.dropped == 0, "worker keeps up without fallback or drops");
}

static void fallbackAndReset() {
    auto ring = std::make_unique<OboeRing>();
    check(!ring->configure(22050, 4, 20) && !ring->configure(44100, 3, 20) && !ring->configure(44100, 4, OboeRing::CAPACITY), "unsupported configurations rejected");
    check(ring->configure(44100, 4, 20), "configure fallback stream");
    ring->setGain(0);
    std::vector<float> pcm(200), capture(200);
    for (int i = 0; i < 100; i++) for (int c = 0; c < 2; c++) pcm[i * 2 + c] = source(i, c);
    check(ring->process(pcm.data(), 100), "callback without worker output");
    for (int i = 0; i < 100; i++) for (int c = 0; c < 2; c++) check(pcm[i * 2 + c] == (i < 20 ? 0 : source(i - 20, c)), "late estimate uses same-index original");
    check(ring->statistics().fallback == 80, "fallback counted");
    uint64_t oldEpoch = 0, start = 0;
    check(ring->drain(capture.data(), 100, oldEpoch, start) == 100 && start == 0, "old generation drained");
    ring->reset();
    check(!ring->publish(capture.data(), 100, oldEpoch, 0), "reset rejects stale inference before callback resumes");
    uint64_t epoch = 0;
    check(ring->drain(capture.data(), 100, epoch, start) == 0, "reset hides old capture");
    std::fill(pcm.begin(), pcm.end(), 0.5f);
    check(ring->process(pcm.data(), 100), "callback adopts reset");
    for (int i = 0; i < 100; i++) for (int c = 0; c < 2; c++) check(pcm[i * 2 + c] == (i < 20 ? 0 : 0.5f), "reset has no stale audio");
    check(ring->drain(capture.data(), 100, epoch, start) == 100 && start == 0 && epoch != oldEpoch, "new generation starts at zero");
    check(!ring->publish(capture.data(), 100, oldEpoch, 0), "late stale inference remains rejected");
    check(!ring->publish(capture.data(), 101, epoch, 0), "publish cannot extend beyond captured audio");
}

static void overrun() {
    auto ring = std::make_unique<OboeRing>();
    ring->configure(48000, 4, 4096);
    std::vector<float> pcm(2048), capture(2048);
    uint64_t total = OboeRing::CAPACITY + 8192;
    uint64_t written = 0;
    while (written < total) {
        int frames = static_cast<int>(std::min<uint64_t>(1024, total - written));
        for (int i = 0; i < frames; i++) for (int c = 0; c < 2; c++) pcm[i * 2 + c] = source(written + i, c);
        check(ring->process(pcm.data(), frames), "overrun callback remains nonblocking");
        written += frames;
    }
    uint64_t epoch = 0, start = 0;
    check(ring->drain(capture.data(), 1024, epoch, start) == 1024 && start == total - OboeRing::CAPACITY, "overrun skips only expired inputs");
    for (int i = 0; i < 1024; i++) for (int c = 0; c < 2; c++) check(capture[i * 2 + c] == source(start + i, c), "overrun data keeps correct index");
    check(ring->statistics().dropped == 8192, "overrun reports dropped frames");
}

static void concurrency() {
    auto ring = std::make_unique<OboeRing>();
    ring->configure(48000, 4, 4096);
    ring->setGain(0);
    std::atomic<bool> finished{false};
    std::thread worker([&] {
        std::vector<float> capture(2048);
        while (!finished.load(std::memory_order_acquire)) {
            uint64_t epoch = 0, start = 0;
            int frames = ring->drain(capture.data(), 1024, epoch, start);
            if (!frames) { std::this_thread::yield(); continue; }
            for (int i = 0; i < frames; i++) for (int c = 0; c < 2; c++) {
                check(capture[i * 2 + c] == source(start + i, c), "concurrent capture never tears or misattributes samples");
                capture[i * 2 + c] *= 0.25f;
            }
            ring->publish(capture.data(), frames, epoch, start);
        }
    });
    std::vector<float> pcm(514);
    uint64_t total = OboeRing::CAPACITY * 3, written = 0;
    while (written < total) {
        int frames = static_cast<int>(std::min<uint64_t>(257, total - written));
        for (int i = 0; i < frames; i++) for (int c = 0; c < 2; c++) pcm[i * 2 + c] = source(written + i, c);
        check(ring->process(pcm.data(), frames), "concurrent callback");
        for (int i = 0; i < frames; i++) for (int c = 0; c < 2; c++) {
            float expected = written + i < 4096 ? 0 : source(written + i - 4096, c);
            float actual = pcm[i * 2 + c];
            check(std::isfinite(actual) && actual >= expected * 0.25f - 1e-7f && actual <= expected + 1e-7f, "concurrent output stays within exact-index original/instrumental mix");
        }
        written += frames;
        std::this_thread::yield();
    }
    finished.store(true, std::memory_order_release);
    worker.join();
}

int main() {
    for (int rate : {44100, 48000}) for (int encoding : {2, 4}) chronology(rate, encoding);
    fallbackAndReset();
    overrun();
    concurrency();
    std::puts("PASS: Oboe ring PCM16/float 44.1/48 kHz; exact delayed chronology; gain ramp; worker publication; fallback; wrap; reset; stale inference; overrun; concurrent callback/worker");
}
