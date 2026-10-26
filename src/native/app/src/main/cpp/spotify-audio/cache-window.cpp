#include "cache-window.h"
#include "cache-file.h"
#include "cache-song-cipher.h"
#include <array>
#include <atomic>
#include <chrono>
#include <cstring>
#include <memory>
#include <mutex>
#include <vector>

namespace {
    void wipe(void *data, size_t size) {
        auto *bytes = static_cast<volatile uint8_t *>(data);
        while (size--) *bytes++ = 0;
    }
    template <typename T>
    T load(uintptr_t address) {
        T value;
        std::memcpy(&value, reinterpret_cast<const void *>(address), sizeof(value));
        return value;
    }
    struct Job {
        uint64_t epoch, length;
        uintptr_t base;
        std::atomic<uintptr_t> manager, process;
        std::array<uint8_t, 20> file;
        std::array<uint8_t, 16> gid;
        std::atomic<bool> cancelled{false}, fileCaptured{false}, cipherCaptured{false};
        std::atomic<int> managerStage{0}, processStage{0};
        Job(uint64_t value, uintptr_t managerValue, uintptr_t processValue, const uint8_t *fileValue, const uint8_t *gidValue, uint64_t lengthValue, uintptr_t baseValue) : epoch(value), length(lengthValue), base(baseValue), manager(managerValue), process(processValue) {
            std::memcpy(file.data(), fileValue, file.size());
            std::memcpy(gid.data(), gidValue, gid.size());
        }
        ~Job() {
            wipe(file.data(), file.size());
            wipe(gid.data(), gid.size());
        }
        bool eligible() const { return !cancelled.load() && lyrics_spotify_audio_cache_matches(epoch, file.data(), gid.data()); }
    };
    std::mutex jobsMutex;
    std::shared_ptr<Job> active;
    std::atomic<uintptr_t> managerMutex{0}, processMutex{0};
    thread_local bool observing = false;
    struct Guard {
        Guard() { observing = true; }
        ~Guard() { observing = false; }
    };
    std::shared_ptr<Job> current() {
        std::lock_guard lock(jobsMutex);
        return active;
    }
    struct Snapshot {
        CacheFileInput input{};
        std::vector<uint8_t> checksums;
        ~Snapshot() {
            wipe(&input, sizeof(input));
            wipe(checksums.data(), checksums.size());
        }
    };
    struct State {
        std::array<uint8_t, 0x310> bytes{};
        ~State() { wipe(bytes.data(), bytes.size()); }
    };

    bool backendFor(const Job &job, uintptr_t manager, uintptr_t metadata, uintptr_t backend, uintptr_t mutex) {
        return metadata && backend && load<uintptr_t>(backend) == job.base + 0x15e8188 && load<uintptr_t>(backend + 0x18) == mutex && load<uintptr_t>(backend + 0x28) == manager && load<uintptr_t>(backend + 0x30) == metadata;
    }
}

extern "C" int lyrics_spotify_audio_window_bind(uint64_t epoch, void *managerValue, void *processValue, const uint8_t *ownedFileId, const uint8_t *ownedGid, uint64_t length, void *moduleBase) {
    if (!managerValue || !processValue || !moduleBase || !length || length > 16 * 1024 * 1024 || !lyrics_spotify_audio_cache_matches(epoch, ownedFileId, ownedGid)) return 0;
    try {
        std::lock_guard lock(jobsMutex);
        if (!lyrics_spotify_audio_cache_matches(epoch, ownedFileId, ownedGid)) return 0;
        if (active && active->epoch == epoch) {
            if (active->length != length || active->base != reinterpret_cast<uintptr_t>(moduleBase) || std::memcmp(active->file.data(), ownedFileId, 20) || std::memcmp(active->gid.data(), ownedGid, 16)) return 0;
            active->manager.store(reinterpret_cast<uintptr_t>(managerValue));
            active->process.store(reinterpret_cast<uintptr_t>(processValue));
        } else {
            auto replacement = std::make_shared<Job>(epoch, reinterpret_cast<uintptr_t>(managerValue), reinterpret_cast<uintptr_t>(processValue), ownedFileId, ownedGid, length, reinterpret_cast<uintptr_t>(moduleBase));
            if (active) active->cancelled.store(true);
            active = std::move(replacement);
        }
        managerMutex.store(reinterpret_cast<uintptr_t>(managerValue) + 0x1d8);
        processMutex.store(reinterpret_cast<uintptr_t>(processValue) + 8);
        return 1;
    } catch (...) {
        return 0;
    }
}

extern "C" void lyrics_spotify_audio_window_manager_unlock(void *mutexValue) {
    const uintptr_t mutex = reinterpret_cast<uintptr_t>(mutexValue);
    if (!mutex || observing || managerMutex.load() != mutex) return;
    Guard guard;
    auto job = current();
    if (!job || job->fileCaptured.load() || !job->eligible()) return;
    job->managerStage.store(1);
    try {
        const uintptr_t manager = job->manager.load();
        if (!manager || manager + 0x1d8 != mutex || load<uintptr_t>(manager) != job->base + 0x15e7f68 || load<uint8_t>(manager + 0x320)) return;
        job->managerStage.store(2);
        const uintptr_t begin = load<uintptr_t>(manager + 8), end = load<uintptr_t>(manager + 0x10), capacity = load<uintptr_t>(manager + 0x18);
        if (!begin || end < begin || capacity < end || (end - begin) % 8 || end - begin > 16384 * 8) return;
        job->managerStage.store(3);
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(5);
        uintptr_t selected = 0, metadata = 0;
        for (uintptr_t position = begin; position < end; position += 8) {
            if ((position - begin) % 1024 == 0 && (std::chrono::steady_clock::now() >= deadline || !job->eligible())) return;
            const uintptr_t item = load<uintptr_t>(position);
            if (!item || std::memcmp(reinterpret_cast<const void *>(item), job->file.data(), 20) || !load<uint8_t>(item + 0x28) || load<uint64_t>(item + 0x20) != job->length) continue;
            const uintptr_t backend = load<uintptr_t>(item + 0x48);
            if (!backendFor(*job, manager, item, backend, mutex)) continue;
            if (selected) return;
            selected = backend;
            metadata = item;
        }
        if (!selected || !job->eligible()) return;
        job->managerStage.store(4);
        const uint32_t blocks = uint32_t((job->length + 16383) / 16384), flags = load<uint32_t>(metadata + 0x18), header = load<uint32_t>(selected + 0x50), extra = load<uint32_t>(selected + 0x58);
        if (load<uint32_t>(selected + 0x4c) != blocks || header != 64 + 4 * blocks || ((flags & 2) ? 0 : uint64_t(header) + extra) + job->length > INT32_MAX) return;
        job->managerStage.store(5);
        const uintptr_t checksums = load<uintptr_t>(selected + 0x40);
        if (!checksums || load<int32_t>(selected + 0x38) < 0) return;
        Snapshot owned;
        auto &input = owned.input;
        input.fd = load<int32_t>(selected + 0x38);
        input.flags = flags;
        input.length = job->length;
        input.headerBytes = header;
        input.extraBytes = extra;
        input.category = load<uint8_t>(metadata + 0x14);
        input.generation = load<uint16_t>(metadata + 0x16);
        std::memcpy(input.salt, reinterpret_cast<const void *>(manager + 0x200), 20);
        if (!(flags & 8)) {
            const uintptr_t primitive = manager + 0x218, wrapper = manager + 0x258, schedule = load<uintptr_t>(primitive + 8);
            input.scheduleWords = load<uint32_t>(primitive + 0x10);
            input.rounds = load<uint32_t>(primitive + 0x18);
            input.mode = load<uint8_t>(primitive + 0x14);
            if (load<uintptr_t>(primitive) != job->base + 0x161e4d8 || load<uintptr_t>(wrapper) != job->base + 0x161e530 || load<uintptr_t>(wrapper + 8) != primitive || load<uint32_t>(wrapper + 0x18) != 16 || !schedule || input.scheduleWords != 60 || input.mode > 1 || (input.rounds != 10 && input.rounds != 12 && input.rounds != 14)) return;
            if (load<uintptr_t>(job->base + 0x161e4d8 + 0x28) != job->base + 0x1471628 || load<uintptr_t>(job->base + 0x161e530 + 0x38) != job->base + 0x1472260 || load<uintptr_t>(job->base + 0x161e530 + 0x40) != job->base + 0x14722a0 || load<uintptr_t>(job->base + 0x161e530 + 0x20) != job->base + 0x1471e94) return;
            std::memcpy(input.schedule, reinterpret_cast<const void *>(schedule), 240);
        }
        owned.checksums.resize(blocks * 4);
        std::memcpy(owned.checksums.data(), reinterpret_cast<const void *>(checksums + ((flags & 2) ? 0 : 64)), owned.checksums.size());
        input.checksumsBE = owned.checksums.data();
        input.checksumCount = blocks;
        input.primitiveVtable = reinterpret_cast<void *>(job->base + 0x161e4d8);
        input.wrapperVtable = reinterpret_cast<void *>(job->base + 0x161e530);
        input.resetMethod = reinterpret_cast<void *>(job->base + 0x1472260);
        input.seekMethod = reinterpret_cast<void *>(job->base + 0x14722a0);
        input.transformMethod = reinterpret_cast<void *>(job->base + 0x1471e94);
        job->managerStage.store(6);
        if (job->eligible() && lyrics_spotify_audio_file_capture(job->epoch, job->file.data(), job->gid.data(), &input)) {
            job->fileCaptured.store(true);
            job->managerStage.store(7);
        }
    } catch (...) { }
}

extern "C" void lyrics_spotify_audio_window_process_unlock(void *mutexValue, void *returnAddress, uint32_t guardValue) {
    const uintptr_t mutex = reinterpret_cast<uintptr_t>(mutexValue);
    if (!(guardValue & 255) || !mutex || observing || processMutex.load() != mutex) return;
    Guard guard;
    auto job = current();
    if (!job || job->cipherCaptured.load() || reinterpret_cast<uintptr_t>(returnAddress) != job->base + 0x10edb2c || !job->eligible()) return;
    job->processStage.store(1);
    try {
        const uintptr_t process = job->process.load();
        if (!process || process + 8 != mutex || load<uintptr_t>(process) != job->base + 0x15ccad8 || load<uint8_t>(process + 0x60) != 1 || !load<uint8_t>(process + 0x6c)) return;
        State owned;
        std::memcpy(owned.bytes.data(), reinterpret_cast<const void *>(process + 0x60), owned.bytes.size());
        if (job->eligible() && lyrics_spotify_audio_cache_cipher_capture(job->epoch, job->file.data(), job->gid.data(), owned.bytes.data(), reinterpret_cast<void *>(job->base + 0x10ee234))) {
            job->cipherCaptured.store(true);
            job->processStage.store(2);
        }
    } catch (...) { }
}

extern "C" void lyrics_spotify_audio_window_destroy_manager(void *managerValue) {
    uintptr_t expected = reinterpret_cast<uintptr_t>(managerValue) + 0x1d8;
    if (managerMutex.compare_exchange_strong(expected, 0)) {
        auto job = current();
        if (job && job->manager.load() == reinterpret_cast<uintptr_t>(managerValue)) job->manager.store(0);
    }
}
extern "C" void lyrics_spotify_audio_window_destroy_process(void *processValue) {
    uintptr_t expected = reinterpret_cast<uintptr_t>(processValue) + 8;
    if (processMutex.compare_exchange_strong(expected, 0)) {
        auto job = current();
        if (job && job->process.load() == reinterpret_cast<uintptr_t>(processValue)) job->process.store(0);
    }
}
extern "C" void lyrics_spotify_audio_window_clear(void) {
    managerMutex.store(0);
    processMutex.store(0);
    std::shared_ptr<Job> previous;
    {
        std::lock_guard lock(jobsMutex);
        if (active) active->cancelled.store(true);
        previous = std::move(active);
    }
}
extern "C" int lyrics_spotify_audio_window_status(void) {
    auto job = current();
    return job ? 1 | (job->managerStage.load() << 8) | (job->processStage.load() << 16) : 0;
}
