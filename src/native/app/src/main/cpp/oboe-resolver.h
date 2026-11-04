#pragma once

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <limits>
#include <link.h>
#include <utility>
#include <vector>

struct OboeLayout {
    uintptr_t callback = 0;
    uint32_t rateOffset = 0, channelOffset = 0, formatOffset = 0, directionOffset = 0;
};

namespace lyrics_oboe_detail {
    struct Range {
        uintptr_t start, end;
        bool executable;
    };

    struct Reference {
        uintptr_t start, end, target;
    };

    inline bool add(uintptr_t left, uintptr_t right, uintptr_t &result) {
        if (right > std::numeric_limits<uintptr_t>::max() - left) return false;
        result = left + right;
        return true;
    }

    inline bool relative(uintptr_t base, int64_t offset, uintptr_t &result) {
        if (offset >= 0) return add(base, static_cast<uintptr_t>(offset), result);
        uint64_t magnitude = static_cast<uint64_t>(-(offset + 1)) + 1;
        if (magnitude > base) return false;
        result = base - magnitude;
        return true;
    }

    class Image {
        std::vector<Range> ranges;
        std::vector<uintptr_t> functions;

    public:
        bool contains(uintptr_t address, size_t length, bool executable = false) const {
            uintptr_t end;
            if (!add(address, length, end)) return false;
            for (const Range &range : ranges) if ((!executable || range.executable) && address >= range.start && end <= range.end) return true;
            return false;
        }

        template <typename T> bool read(uintptr_t address, T &value, bool executable = false) const {
            if (!contains(address, sizeof(T), executable)) return false;
            std::memcpy(&value, reinterpret_cast<const void *>(address), sizeof(T));
            return true;
        }

        bool initialize(uintptr_t base, const ElfW(Phdr) *headers, size_t count) {
            if (!base || !headers || !count || count > 4096) return false;
            uintptr_t unwind = 0;
            size_t unwindSize = 0;
            for (size_t index = 0; index < count; index++) {
                const auto &header = headers[index];
                uintptr_t start, end;
                if (!add(base, header.p_vaddr, start) || !add(start, header.p_memsz, end)) return false;
                if (header.p_type == PT_LOAD && (header.p_flags & PF_R) && header.p_memsz > 0) ranges.push_back({start, end, (header.p_flags & PF_X) != 0});
                if (header.p_type == PT_GNU_EH_FRAME) {
                    if (unwind || header.p_memsz > 32 * 1024 * 1024) return false;
                    unwind = start;
                    unwindSize = header.p_memsz;
                }
            }
            if (!unwind || unwindSize < 12 || !contains(unwind, unwindSize)) return false;
            uint32_t encoding, entries;
            if (!read(unwind, encoding) || encoding != 0x3B031B01 || !read(unwind + 8, entries) || !entries || entries > (unwindSize - 12) / 8) return false;
            functions.reserve(entries);
            for (uint32_t index = 0; index < entries; index++) {
                int32_t delta;
                uintptr_t function;
                if (!read(unwind + 12 + static_cast<uintptr_t>(index) * 8, delta) || !relative(unwind, delta, function) || !contains(function, 4, true)) return false;
                if (!functions.empty() && function <= functions.back()) return false;
                functions.push_back(function);
            }
            return true;
        }

        std::vector<uintptr_t> strings(const char *text) const {
            size_t length = std::strlen(text) + 1;
            std::vector<uintptr_t> result;
            for (const Range &range : ranges) {
                if (range.end - range.start < length) continue;
                uintptr_t cursor = range.start;
                while (cursor <= range.end - length) {
                    const void *found = std::memchr(reinterpret_cast<const void *>(cursor), text[0], range.end - length - cursor + 1);
                    if (!found) break;
                    uintptr_t address = reinterpret_cast<uintptr_t>(found);
                    if (std::memcmp(found, text, length) == 0) result.push_back(address);
                    cursor = address + 1;
                }
            }
            std::sort(result.begin(), result.end());
            result.erase(std::unique(result.begin(), result.end()), result.end());
            return result;
        }

        bool function(uintptr_t address, std::pair<uintptr_t, uintptr_t> &result) const {
            auto end = std::upper_bound(functions.begin(), functions.end(), address);
            if (end == functions.begin() || end == functions.end()) return false;
            result = {*(end - 1), *end};
            return contains(result.first, result.second - result.first, true);
        }

        std::vector<Reference> references(const std::vector<uintptr_t> &targets) const {
            std::vector<Reference> result;
            if (targets.empty()) return result;
            std::vector<uintptr_t> pages;
            for (uintptr_t target : targets) pages.push_back(target & ~uintptr_t(4095));
            std::sort(pages.begin(), pages.end());
            pages.erase(std::unique(pages.begin(), pages.end()), pages.end());
            for (const Range &range : ranges) {
                if (!range.executable || range.end - range.start < 24) continue;
                uintptr_t aligned;
                if (!add(range.start, 3, aligned)) continue;
                aligned &= ~uintptr_t(3);
                for (uintptr_t address = aligned; address <= range.end - 24; address += 4) {
                    uint32_t instruction;
                    std::memcpy(&instruction, reinterpret_cast<const void *>(address), 4);
                    if ((instruction & 0x9F000000) != 0x90000000) continue;
                    int64_t immediate = ((instruction >> 5) & 0x7FFFF) * 4 | ((instruction >> 29) & 3);
                    if (immediate & (1 << 20)) immediate -= 1 << 21;
                    uintptr_t page;
                    if (!relative(address & ~uintptr_t(4095), immediate * 4096, page) || !std::binary_search(pages.begin(), pages.end(), page)) continue;
                    uint32_t reg = instruction & 31;
                    for (size_t distance = 1; distance < 6; distance++) {
                        uint32_t following;
                        std::memcpy(&following, reinterpret_cast<const void *>(address + distance * 4), 4);
                        if ((following & 0xFF000000) != 0x91000000 || ((following >> 5) & 31) != reg) continue;
                        uintptr_t target;
                        uint64_t offset = ((following >> 10) & 4095) * (following & (1 << 22) ? 4096 : 1);
                        if (!add(page, offset, target) || std::find(targets.begin(), targets.end(), target) == targets.end()) continue;
                        result.push_back({address, address + distance * 4, target});
                    }
                }
            }
            return result;
        }
    };
}

inline bool lyrics_resolve_oboe(uintptr_t moduleBase, const ElfW(Phdr) *phdr, size_t phnum, OboeLayout &result) {
    using namespace lyrics_oboe_detail;
    result = {};
    Image image;
    if (!image.initialize(moduleBase, phdr, phnum) || image.strings("OboeVersion1.9.3").size() != 1) return false;
    auto disabled = image.references(image.strings("AudioStream::%s() called with data callback disabled!"));
    auto names = image.references(image.strings("fireDataCallback"));
    std::vector<std::pair<uintptr_t, uintptr_t>> callbacks;
    for (const Reference &reference : disabled) {
        std::pair<uintptr_t, uintptr_t> bounds;
        if (!image.function(reference.start, bounds)) continue;
        for (const Reference &name : names) {
            if (name.start < bounds.first || name.end >= bounds.second) continue;
            if (std::find(callbacks.begin(), callbacks.end(), bounds) == callbacks.end()) callbacks.push_back(bounds);
        }
    }
    if (callbacks.size() != 1) return false;
    auto callback = callbacks[0];
    if (callback.second - callback.first < 64 || callback.second - callback.first > 1024) return false;
    bool loadAcquire = false, storeRelease = false, callbackLoad = false;
    uint32_t last = 0;
    for (uintptr_t address = callback.first; address < callback.second; address += 4) {
        uint32_t word;
        if (!image.read(address, word, true)) return false;
        loadAcquire |= (word & 0xFFFFFC00) == 0x08DFFC00;
        storeRelease |= (word & 0xFFFFFC00) == 0x089FFC00;
        callbackLoad |= (word & 0xFFC00000) == 0xF9400000 && ((word >> 10) & 4095) == 1;
        last = word;
    }
    if (!loadAcquire || !storeRelease || !callbackLoad || last != 0xD65F03C0) return false;
    auto diagnostics = image.references(image.strings("AudioStreamOpenSLES::open() chans=%d, rate=%d"));
    if (diagnostics.size() != 1) return false;
    const Reference &diagnostic = diagnostics[0];
    std::pair<uintptr_t, uintptr_t> bounds;
    if (!image.function(diagnostic.start, bounds)) return false;
    std::vector<uint32_t> channels, formats;
    uintptr_t searchStart = diagnostic.start - bounds.first > 64 ? diagnostic.start - 64 : bounds.first;
    for (uintptr_t address = searchStart; address < diagnostic.start; address += 4) {
        uint32_t word;
        if (!image.read(address, word, true)) return false;
        if ((word & 0xFFC00000) != 0x29400000 || (word & 31) != 3 || ((word >> 10) & 31) != 4 || ((word >> 5) & 31) != 0) continue;
        int32_t offset = (word >> 15) & 127;
        if (offset & 64) offset -= 128;
        if (offset >= 0) channels.push_back(offset * 4);
    }
    uintptr_t searchEnd;
    if (!add(diagnostic.end, 32, searchEnd)) return false;
    searchEnd = std::min(searchEnd, bounds.second);
    for (uintptr_t address = diagnostic.end + 4; address < searchEnd; address += 4) {
        uint32_t word;
        if (!image.read(address, word, true)) return false;
        if ((word & 0xFFC00000) == 0xB9400000 && ((word >> 5) & 31) == 19) formats.push_back(((word >> 10) & 4095) * 4);
    }
    std::sort(channels.begin(), channels.end());
    channels.erase(std::unique(channels.begin(), channels.end()), channels.end());
    std::sort(formats.begin(), formats.end());
    formats.erase(std::unique(formats.begin(), formats.end()), formats.end());
    if (channels.size() != 1 || formats.size() != 1 || channels[0] != 0x3C || formats[0] != 0x58) return false;
    result.callback = callback.first;
    result.channelOffset = channels[0];
    result.rateOffset = channels[0] + 4;
    result.formatOffset = formats[0];
    result.directionOffset = formats[0] + 4;
    return true;
}
