#include "cache-file.h"
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <climits>
#include <cstring>
#include <fcntl.h>
#include <memory>
#include <mutex>
#include <string>
#include <sys/stat.h>
#include <unistd.h>
#include <utility>
#include <vector>

#ifdef READER_PROBE_CACHE_FILE_TEST
extern "C" void lyrics_spotify_audio_cache_file_test_wiped(const uint8_t *, size_t);
extern "C" void lyrics_spotify_audio_cache_file_test_read(void);
#endif

namespace {
    constexpr size_t blockBytes = 16384, maxBytes = 16 * 1024 * 1024;
    using Reset = void (*)(void *, const void *, uint32_t);
    using Seek = void (*)(void *, uint32_t);
    using Transform = void (*)(void *, void *, const void *, uint32_t);

    void wipe(void *data, size_t size) noexcept {
        auto *destination = static_cast<volatile uint8_t *>(data);
        for (size_t i = 0; i < size; i++) destination[i] = 0;
#ifdef READER_PROBE_CACHE_FILE_TEST
        if (size) lyrics_spotify_audio_cache_file_test_wiped(static_cast<const uint8_t *>(data), size);
#endif
    }

    template <size_t N>
    struct Bytes {
        alignas(16) std::array<uint8_t, N> value{};
        ~Bytes() { wipe(value.data(), N); }
    };

    template <size_t N>
    struct Words {
        std::array<uint32_t, N> value{};
        ~Words() { wipe(value.data(), sizeof(value)); }
    };

    struct InputCopy {
        CacheFileInput value{};
        ~InputCopy() { wipe(&value, sizeof(value)); }
    };

    uint32_t rotate(uint32_t value, unsigned count) { return (value << count) | (value >> (32 - count)); }
    uint32_t be32(const uint8_t *bytes) { return (uint32_t(bytes[0]) << 24) | (uint32_t(bytes[1]) << 16) | (uint32_t(bytes[2]) << 8) | bytes[3]; }

    std::string suffixFor(const CacheFileInput &input, const uint8_t *fileId) {
        Bytes<64> block;
        Bytes<20> digest;
        std::memcpy(block.value.data(), input.salt, 20);
        std::memcpy(block.value.data() + 20, fileId, 20);
        block.value[40] = input.category;
        block.value[42] = uint8_t(input.generation);
        block.value[43] = uint8_t(input.generation >> 8);
        block.value[44] = 0x80;
        block.value[62] = 1;
        block.value[63] = 0x60;
        Words<80> ownedWords;
        auto &words = ownedWords.value;
        for (size_t i = 0; i < 16; i++) words[i] = be32(block.value.data() + 4 * i);
        for (size_t i = 16; i < 80; i++) words[i] = rotate(words[i - 3] ^ words[i - 8] ^ words[i - 14] ^ words[i - 16], 1);
        Words<5> working;
        working.value = {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476, 0xc3d2e1f0};
        auto &a = working.value[0], &b = working.value[1], &c = working.value[2], &d = working.value[3], &e = working.value[4];
        for (size_t i = 0; i < 80; i++) {
            uint32_t f = i < 20 ? ((b & c) | (~b & d)) : i < 40 ? (b ^ c ^ d) : i < 60   ? ((b & c) | (b & d) | (c & d)) : (b ^ c ^ d);
            uint32_t k = i < 20 ? 0x5a827999 : i < 40 ? 0x6ed9eba1 : i < 60   ? 0x8f1bbcdc : 0xca62c1d6;
            uint32_t next = rotate(a, 5) + f + e + k + words[i];
            e = d;
            d = c;
            c = rotate(b, 30);
            b = a;
            a = next;
        }
        Words<5> result;
        result.value = {a + 0x67452301, b + 0xefcdab89, c + 0x98badcfe, d + 0x10325476, e + 0xc3d2e1f0};
        for (size_t i = 0; i < 20; i++) digest.value[i] = uint8_t(result.value[i / 4] >> (24 - (i % 4) * 8));
        std::string hex;
        constexpr char alphabet[] = "0123456789abcdef";
        for (uint8_t byte : digest.value) {
            hex += alphabet[byte >> 4];
            hex += alphabet[byte & 15];
        }
        std::string suffix = "/" + hex.substr(0, 2) + "/" + hex + ".file";
        return suffix;
    }

    uint32_t adler(const uint8_t *bytes, size_t length, uint32_t seed) {
        uint32_t a = seed & 65535, b = seed >> 16;
        for (size_t i = 0; i < length; i++) {
            a = (a + bytes[i]) % 65521;
            b = (b + a) % 65521;
        }
        return (b << 16) | a;
    }

    template <typename T, size_t N>
    void store(std::array<uint8_t, N> &bytes, size_t offset, T value) { std::memcpy(bytes.data() + offset, &value, sizeof(value)); }

    struct Cipher {
        Bytes<32> primitive;
        Bytes<240> schedule;
        Bytes<48> wrapper;
        Bytes<16> counter;
        Bytes<16> seed;

        Cipher(const CacheFileInput &input, const uint8_t *fileId) {
            std::memcpy(schedule.value.data(), input.schedule, 240);
            store(primitive.value, 0, input.primitiveVtable);
            store(primitive.value, 8, schedule.value.data());
            store(primitive.value, 0x10, input.scheduleWords);
            primitive.value[0x14] = input.mode;
            store(primitive.value, 0x18, input.rounds);
            store(wrapper.value, 0, input.wrapperVtable);
            store(wrapper.value, 8, primitive.value.data());
            store(wrapper.value, 0x10, counter.value.data());
            store(wrapper.value, 0x18, uint32_t(16));
            store(wrapper.value, 0x1c, uint32_t(16));
            std::memcpy(seed.value.data(), fileId, 16);
            seed.value[0] ^= input.category;
            seed.value[2] ^= uint8_t(input.generation >> 8);
            seed.value[3] ^= uint8_t(input.generation);
        }
    };

    struct Job {
        uint64_t epoch;
        std::array<uint8_t, 20> fileId;
        std::array<uint8_t, 16> gid;
        CacheFileInput input;
        std::vector<uint8_t> checksums;
        int fd = -1;
        std::atomic<bool> cancelled{false}, claimed{false};
        std::atomic<int> status{1};

        Job(uint64_t value, const uint8_t *file, const uint8_t *track, const CacheFileInput &source) : epoch(value), input(source) {
            std::memcpy(fileId.data(), file, fileId.size());
            std::memcpy(gid.data(), track, gid.size());
            input.fd = -1;
            input.checksumsBE = nullptr;
        }

        ~Job() {
            if (fd >= 0) close(fd);
            wipe(fileId.data(), fileId.size());
            wipe(gid.data(), gid.size());
            wipe(&input, sizeof(input));
            wipe(checksums.data(), checksums.size());
        }

        bool eligible() const { return !cancelled.load() && lyrics_spotify_audio_cache_matches(epoch, fileId.data(), gid.data()); }
        uint64_t base() const { return (input.flags & 2) ? 0 : uint64_t(input.headerBytes) + input.extraBytes; }
    };

    struct ReadCleanup {
        Job &job;
        ~ReadCleanup() {
            if (job.fd >= 0) {
                close(job.fd);
                job.fd = -1;
            }
            wipe(&job.input, sizeof(job.input));
            wipe(job.checksums.data(), job.checksums.size());
        }
    };

    std::mutex jobsMutex;
    std::shared_ptr<Job> active;
    uint64_t revision = 0;

    std::shared_ptr<Job> jobFor(uint64_t epoch) {
        std::lock_guard lock(jobsMutex);
        return active && active->epoch == epoch ? active : nullptr;
    }

    bool validInput(const CacheFileInput &input) {
        if (input.fd < 0 || !input.length || input.length > maxBytes) return false;
        uint64_t count = (input.length + blockBytes - 1) / blockBytes;
        if (input.checksumCount != count || !input.checksumsBE || input.headerBytes != 64 + 4 * count) return false;
        uint64_t base = (input.flags & 2) ? 0 : uint64_t(input.headerBytes) + input.extraBytes;
        if (base + input.length > INT32_MAX) return false;
        if (input.flags & 8) return true;
        return input.scheduleWords == 60 && (input.rounds == 10 || input.rounds == 12 || input.rounds == 14) && input.mode <= 1 && input.primitiveVtable && input.wrapperVtable && input.resetMethod && input.seekMethod && input.transformMethod;
    }

    bool regularExtent(const struct stat &state, uint64_t extent) { return S_ISREG(state.st_mode) && state.st_size >= 0 && uint64_t(state.st_size) >= extent; }
    bool sameFile(const struct stat &before, const struct stat &after) { return before.st_dev == after.st_dev && before.st_ino == after.st_ino && before.st_size == after.st_size && before.st_mtim.tv_sec == after.st_mtim.tv_sec && before.st_mtim.tv_nsec == after.st_mtim.tv_nsec && before.st_ctim.tv_sec == after.st_ctim.tv_sec && before.st_ctim.tv_nsec == after.st_ctim.tv_nsec; }
}

extern "C" int lyrics_spotify_audio_file_capture(uint64_t epoch, const uint8_t *fileId20, const uint8_t *gid16, const CacheFileInput *ownedInput) {
    if (!ownedInput || !lyrics_spotify_audio_cache_matches(epoch, fileId20, gid16)) return 0;
    uint64_t expectedRevision;
    {
        std::lock_guard lock(jobsMutex);
        if (active && active->epoch == epoch) return 0;
        expectedRevision = revision;
    }
    try {
        InputCopy source;
        std::memcpy(&source.value, ownedInput, sizeof(CacheFileInput));
        if (!validInput(source.value) || !lyrics_spotify_audio_cache_matches(epoch, fileId20, gid16)) return 0;
        auto job = std::make_shared<Job>(epoch, fileId20, gid16, source.value);
        job->checksums.assign(source.value.checksumsBE, source.value.checksumsBE + 4 * source.value.checksumCount);
        for (size_t i = 0; i < job->checksums.size(); i += 4)
            if (be32(job->checksums.data() + i) == UINT32_MAX) return 0;
        if (!job->eligible()) return 0;
        job->fd = fcntl(source.value.fd, F_DUPFD_CLOEXEC, 0);
        if (!job->eligible() || job->fd < 0) return 0;
        struct stat state{};
        if (!job->eligible()) return 0;
        int statResult = fstat(job->fd, &state);
        if (!job->eligible() || statResult != 0 || !regularExtent(state, job->base() + job->input.length)) return 0;
        std::string fdPath = "/proc/self/fd/" + std::to_string(job->fd);
        std::array<char, 4096> path{};
        if (!job->eligible()) return 0;
        ssize_t pathLength = readlink(fdPath.c_str(), path.data(), path.size());
        if (!job->eligible() || pathLength <= 0 || size_t(pathLength) == path.size()) return 0;
        std::string suffix = suffixFor(job->input, job->fileId.data());
        std::string actual(path.data(), size_t(pathLength));
        if (actual.size() < suffix.size() || actual.compare(actual.size() - suffix.size(), suffix.size(), suffix) != 0 || actual[0] != '/') return 0;
        std::shared_ptr<Job> previous;
        {
            std::lock_guard lock(jobsMutex);
            if (revision != expectedRevision || !job->eligible()) return 0;
            if (active) active->cancelled.store(true);
            previous = std::move(active);
            active = std::move(job);
            revision++;
        }
        return 1;
    } catch (...) {
        return 0;
    }
}

extern "C" int lyrics_spotify_audio_file_ready(uint64_t epoch) {
    auto job = jobFor(epoch);
    return job && !job->claimed.load() && job->eligible();
}
extern "C" int lyrics_spotify_audio_file_status(uint64_t epoch) {
    auto job = jobFor(epoch);
    return job ? job->status.load() : 0;
}

extern "C" void lyrics_spotify_audio_file_clear(void) {
    std::shared_ptr<Job> previous;
    {
        std::lock_guard lock(jobsMutex);
        if (active) active->cancelled.store(true);
        previous = std::move(active);
        revision++;
    }
}

namespace lyrics_spotify_audio {
    CacheSnapshot cache_file_read(uint64_t epoch) {
        auto job = jobFor(epoch);
        if (!job || !job->eligible() || job->claimed.exchange(true)) return {};
        job->status.store(2);
        ReadCleanup cleanup{*job};
        auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
        auto allowed = [&] {
            return job->eligible() && std::chrono::steady_clock::now() < deadline;
        };
        auto failure = [&](int status) {
            job->status.store(status);
            return CacheSnapshot{};
        };
        try {
            struct stat before{}, after{};
            if (!allowed()) return failure(-4);
            int statResult = fstat(job->fd, &before);
            if (!allowed() || statResult != 0 || !regularExtent(before, job->base() + job->input.length)) return failure(-2);
            job->status.store(3);
            CacheSnapshot output(size_t(job->input.length));
            Bytes<blockBytes> raw;
            Bytes<26> prefix;
            std::memcpy(prefix.value.data(), job->fileId.data(), 20);
            prefix.value[20] = job->input.category;
            Cipher cipher(job->input, job->fileId.data());
            for (size_t position = 0; position < output.size();) {
                size_t length = std::min(blockBytes, output.size() - position);
                uint64_t physical = job->base() + position;
                if (!allowed()) return failure(-4);
#ifdef READER_PROBE_CACHE_FILE_TEST
                lyrics_spotify_audio_cache_file_test_read();
#endif
                ssize_t count = pread(job->fd, raw.value.data(), length, off_t(physical));
                if (!allowed() || count != ssize_t(length)) return failure(-3);
                job->status.store(4);
                uint8_t *destination = output.data() + position;
                if (job->input.flags & 8) {
                    std::memcpy(destination, raw.value.data(), length);
                } else {
                    if (!allowed()) return failure(-4);
                    reinterpret_cast<Reset>(job->input.resetMethod)(cipher.wrapper.value.data(), cipher.seed.value.data(), 16);
                    if (!allowed()) return failure(-4);
                    reinterpret_cast<Seek>(job->input.seekMethod)(cipher.wrapper.value.data(), uint32_t(physical));
                    if (!allowed()) return failure(-4);
                    reinterpret_cast<Transform>(job->input.transformMethod)(cipher.wrapper.value.data(), destination, raw.value.data(), uint32_t(length));
                    if (!allowed()) return failure(-4);
                }
                wipe(raw.value.data(), raw.value.size());
                uint32_t index = uint32_t(position / blockBytes);
                for (size_t i = 0; i < 4; i++) prefix.value[22 + i] = uint8_t(index >> (24 - 8 * i));
                uint32_t checksum = adler(destination, length, adler(prefix.value.data(), prefix.value.size(), 1));
                if (checksum != be32(job->checksums.data() + 4 * index)) return failure(-5);
                position += length;
            }
            if (!allowed()) return failure(-4);
            statResult = fstat(job->fd, &after);
            if (!allowed() || statResult != 0 || !regularExtent(after, job->base() + job->input.length) || !sameFile(before, after)) return failure(-6);
            job->status.store(8);
            return output;
        } catch (...) {
            return failure(-7);
        }
    }
}
