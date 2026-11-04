#pragma once

#include <dlfcn.h>
#include <link.h>
#include <stddef.h>
#include <string.h>

struct LyricsResidentGadget {
    const char *path;
    int found;
};

static int lyrics_gadget_match(struct dl_phdr_info *info, size_t size, void *data) {
    (void) size;
    struct LyricsResidentGadget *gadget = (struct LyricsResidentGadget *) data;
    if (!info->dlpi_name || strcmp(info->dlpi_name, gadget->path) != 0) return 0;
    gadget->found = 1;
    return 1;
}

static int lyrics_gadget_reuse_or_load(const char *path) {
    if (!path || path[0] != '/') return 0;
    struct LyricsResidentGadget gadget = {path, 0};
    dl_iterate_phdr(lyrics_gadget_match, &gadget);
    return gadget.found || dlopen(path, RTLD_NOW | RTLD_LOCAL) != NULL;
}
