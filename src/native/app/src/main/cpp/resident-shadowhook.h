#pragma once

#include <shadowhook.h>
#include <cstdint>
#include <cstring>
#include <limits>
#include <link.h>
#include <vector>

struct ShadowHookApi {
    int (*init)(shadowhook_mode_t, bool) = nullptr;
    void *(*hook)(void *, void *, void **) = nullptr;
    int (*unhook)(void *) = nullptr;
    int (*error)() = nullptr;
    bool initialized = false;
};

namespace lyrics_shadowhook_detail {
    struct Range {
        uintptr_t start, end;
        bool executable;
    };

    inline bool add(uintptr_t left, uintptr_t right, uintptr_t &result) {
        if (right > std::numeric_limits<uintptr_t>::max() - left) return false;
        result = left + right;
        return true;
    }

    class Exports {
        uintptr_t base = 0, symbols = 0, strings = 0, sysv = 0, gnu = 0;
        size_t stringSize = 0;
        std::vector<Range> ranges;

        bool contains(uintptr_t address, size_t length, bool executable = false) const {
            uintptr_t end;
            if (!add(address, length, end)) return false;
            for (const Range &range : ranges) if ((!executable || range.executable) && address >= range.start && end <= range.end) return true;
            return false;
        }

        template <typename T> bool read(uintptr_t address, T &value) const {
            if (!contains(address, sizeof(T))) return false;
            std::memcpy(&value, reinterpret_cast<const void *>(address), sizeof(T));
            return true;
        }

        uintptr_t pointer(uintptr_t value) const {
            if (contains(value, 1)) return value;
            uintptr_t address;
            return add(base, value, address) && contains(address, 1) ? address : 0;
        }

        uintptr_t symbol(uint32_t index, const char *name) const {
            if (index > 1048576) return 0;
            uintptr_t address;
            ElfW(Sym) value;
            if (!add(symbols, static_cast<uintptr_t>(index) * sizeof(value), address) || !read(address, value)) return 0;
            size_t length = std::strlen(name) + 1;
            if (value.st_shndx == SHN_UNDEF || ELF64_ST_TYPE(value.st_info) != STT_FUNC || value.st_name > stringSize || length > stringSize - value.st_name) return 0;
            if (!add(strings, value.st_name, address) || !contains(address, length) || std::memcmp(reinterpret_cast<const void *>(address), name, length) != 0) return 0;
            if (!add(base, value.st_value, address) || !contains(address, 4, true)) return 0;
            return address;
        }

        uintptr_t fromGnu(const char *name) const {
            uint32_t buckets, first, bloom;
            if (!gnu || !read(gnu, buckets) || !read(gnu + 4, first) || !read(gnu + 8, bloom) || !buckets || buckets > 1048576 || first > 1048576 || !bloom || bloom > 1048576) return 0;
            uintptr_t bucketBase, chainBase, address;
            if (!add(gnu, 16 + static_cast<uintptr_t>(bloom) * sizeof(ElfW(Addr)), bucketBase) || !add(bucketBase, static_cast<uintptr_t>(buckets) * 4, chainBase)) return 0;
            uint32_t hash = 5381;
            for (const unsigned char *character = reinterpret_cast<const unsigned char *>(name); *character; character++) hash = hash * 33 + *character;
            uint32_t index;
            if (!add(bucketBase, static_cast<uintptr_t>(hash % buckets) * 4, address) || !read(address, index) || index < first) return 0;
            for (uint32_t steps = 0; steps < 1048576 && index <= 1048576; steps++, index++) {
                uint32_t entry;
                if (!add(chainBase, static_cast<uintptr_t>(index - first) * 4, address) || !read(address, entry)) return 0;
                if ((entry | 1U) == (hash | 1U)) {
                    uintptr_t result = symbol(index, name);
                    if (result) return result;
                }
                if (entry & 1) break;
            }
            return 0;
        }

        uintptr_t fromSysv(const char *name) const {
            uint32_t buckets, count;
            if (!sysv || !read(sysv, buckets) || !read(sysv + 4, count) || !buckets || buckets > 1048576 || !count || count > 1048576) return 0;
            uint32_t hash = 0;
            for (const unsigned char *character = reinterpret_cast<const unsigned char *>(name); *character; character++) {
                hash = (hash << 4) + *character;
                uint32_t high = hash & 0xF0000000;
                if (high) hash ^= high >> 24;
                hash &= ~high;
            }
            uintptr_t bucketBase, chainBase, address;
            if (!add(sysv, 8, bucketBase) || !add(bucketBase, static_cast<uintptr_t>(buckets) * 4, chainBase) || !add(bucketBase, static_cast<uintptr_t>(hash % buckets) * 4, address)) return 0;
            uint32_t index;
            if (!read(address, index)) return 0;
            for (uint32_t steps = 0; index && index < count && steps < count; steps++) {
                uintptr_t result = symbol(index, name);
                if (result) return result;
                if (!add(chainBase, static_cast<uintptr_t>(index) * 4, address) || !read(address, index)) return 0;
            }
            return 0;
        }

    public:
        bool initialize(const dl_phdr_info &info) {
            base = info.dlpi_addr;
            if (!base || !info.dlpi_phdr || !info.dlpi_phnum || info.dlpi_phnum > 4096) return false;
            uintptr_t dynamic = 0;
            size_t dynamicSize = 0;
            for (size_t index = 0; index < info.dlpi_phnum; index++) {
                const auto &header = info.dlpi_phdr[index];
                uintptr_t start, end;
                if (!add(base, header.p_vaddr, start) || !add(start, header.p_memsz, end)) return false;
                if (header.p_type == PT_LOAD && (header.p_flags & PF_R) && header.p_memsz) ranges.push_back({start, end, (header.p_flags & PF_X) != 0});
                if (header.p_type == PT_DYNAMIC) {
                    if (dynamic) return false;
                    dynamic = start;
                    dynamicSize = header.p_memsz;
                }
            }
            if (!dynamic || dynamicSize > 1048576 || !contains(dynamic, dynamicSize)) return false;
            bool terminated = false;
            for (size_t index = 0; index < dynamicSize / sizeof(ElfW(Dyn)); index++) {
                ElfW(Dyn) value;
                if (!read(dynamic + index * sizeof(value), value)) return false;
                if (value.d_tag == DT_NULL) {
                    terminated = true;
                    break;
                }
                if (value.d_tag == DT_SYMTAB) symbols = pointer(value.d_un.d_ptr);
                else if (value.d_tag == DT_STRTAB) strings = pointer(value.d_un.d_ptr);
                else if (value.d_tag == DT_STRSZ) stringSize = value.d_un.d_val;
                else if (value.d_tag == DT_HASH) sysv = pointer(value.d_un.d_ptr);
                else if (value.d_tag == DT_GNU_HASH) gnu = pointer(value.d_un.d_ptr);
                else if (value.d_tag == DT_SYMENT && value.d_un.d_val != sizeof(ElfW(Sym))) return false;
            }
            return terminated && symbols && strings && stringSize && stringSize <= 64 * 1024 * 1024 && contains(strings, stringSize) && (sysv || gnu);
        }

        uintptr_t find(const char *name) const {
            uintptr_t address = fromGnu(name);
            return address ? address : fromSysv(name);
        }
    };

    struct Selection {
        ShadowHookApi first;
        ShadowHookApi initialized;
    };

    inline int inspect(dl_phdr_info *info, size_t, void *opaque) {
        if (!info || !info->dlpi_name) return 0;
        const char *name = std::strrchr(info->dlpi_name, '/');
        name = name ? name + 1 : info->dlpi_name;
        if (std::strcmp(name, "libshadowhook.so") != 0) return 0;
        Exports exports;
        if (!exports.initialize(*info)) return 0;
        ShadowHookApi api;
        api.init = reinterpret_cast<decltype(api.init)>(exports.find("shadowhook_init"));
        api.hook = reinterpret_cast<decltype(api.hook)>(exports.find("shadowhook_hook_func_addr"));
        api.unhook = reinterpret_cast<decltype(api.unhook)>(exports.find("shadowhook_unhook"));
        api.error = reinterpret_cast<decltype(api.error)>(exports.find("shadowhook_get_errno"));
        auto initialized = reinterpret_cast<int (*)()>(exports.find("shadowhook_get_init_errno"));
        if (!api.init || !api.hook || !api.unhook || !api.error) return 0;
        api.initialized = initialized && initialized() == 0;
        auto &selection = *static_cast<Selection *>(opaque);
        if (!selection.first.init) selection.first = api;
        if (!initialized) return 1;
        if (api.initialized) {
            selection.initialized = api;
            return 1;
        }
        return 0;
    }
}

inline bool lyrics_shadowhook_api(ShadowHookApi &result) {
    lyrics_shadowhook_detail::Selection selection;
    dl_iterate_phdr(lyrics_shadowhook_detail::inspect, &selection);
    result = selection.initialized.init ? selection.initialized : selection.first;
    return result.init != nullptr;
}
