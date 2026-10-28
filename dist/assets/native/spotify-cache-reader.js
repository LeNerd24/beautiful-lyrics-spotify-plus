const factorySignature = "ff8304d1fd7b0ca9fc6f0da9fa670ea9";
let orbit, enabled, record, timer, stopped = false, factoryCalls = 0, completedCalls = 0, readErrors = 0;
const hooks = [];
const observedReaders = new Set();
let rangeCalls = 0, postNoop = null, postBarrier = null, noopPosted = false, noopTarget = "work";
let captureResource = null;
let cacheApi = null, cacheBytes = 0, copyingCache = false;
const cacheReaders = new Map();
let cacheReadEntries = 0, cacheOriginalEntries = 0, cacheEligibleEntries = 0;
const cacheWaitReasons = new Set();
let captureSongCipher = null, cipherCaptured = false;
const cipherInputs = new Map();
let captureCacheFile = null, cacheManagerWindow = null;
let nativeWindowModule = null;

function cacheWait(reason, fields = {}) {
    if (cacheWaitReasons.has(reason)) return;
    cacheWaitReasons.add(reason);
    emit("cache-copy-wait", { reason, ...fields });
}

function allowed() { return !stopped && enabled() !== 0; }

function readable(address, size) {
    if (!address || address.isNull()) return false;
    const range = Process.findRangeByAddress(address);
    return !!range && range.protection.includes("r") && address.add(size).compare(range.base.add(range.size)) <= 0;
}

function pointer(address) { return readable(address, 8) ? address.readPointer() : ptr(0); }

function codeOffset(address) {
    return address.compare(orbit.base) >= 0 && address.compare(orbit.base.add(orbit.size)) < 0 ? address.sub(orbit.base).toString() : "external";
}

function emit(phase, fields = {}) {
    const message = Memory.allocUtf8String(JSON.stringify({ phase, factoryCalls, completedCalls, readErrors, rangeCalls, ...fields }));
    record(message);
}

function equalBytes(first, second, size, secondOwned = false) {
    if (!readable(first, size) || (!secondOwned && !readable(second, size))) return null;
    for (let i = 0; i < size; i++) if (first.add(i).readU8() !== second.add(i).readU8()) return false;
    return true;
}

function installCacheFileCapture(bridge, options) {
    const bytes = address => Array.from(new Uint8Array(address.readByteArray(16)), value => value.toString(16).padStart(2, "0")).join("");
    for (const [offset, signature] of [[0x1471628, "ffc301d1fd7b01a9fc6f02a9fa6703a9"], [0x1472260, "fe57bea9f44f01a9f30300aa000840f9"], [0x14722a0, "092043290801010b1f05096b081c00b9"], [0x1471e94, "fe0f1cf8f85f01a9f65702a9f44f03a9"]]) {
        if (bytes(orbit.base.add(offset)) !== signature) throw new Error("Owned cache transform identity mismatch");
    }
    const capture = new NativeFunction(bridge.getExportByName("lyrics_spotify_audio_file_capture"), "int", ["uint64", "pointer", "pointer", "pointer"], options);
    let captured = false;
    return function(backend, candidate) {
        if (captured || !allowed()) return;
        const epoch = cacheApi.epoch();
        if (cacheApi.matches(epoch, candidate.ids, candidate.ids.add(20)) === 0) return;
        if (!readable(backend, 0x64) || codeOffset(pointer(backend)) !== "0x15e8188") return;
        const manager = pointer(backend.add(0x28)), metadata = pointer(backend.add(0x30));
        if (!readable(manager, 0x321) || manager.add(0x320).readU8() !== 0 || pointer(backend.add(0x18)).compare(manager.add(0x1d8)) !== 0) return;
        if (!readable(metadata, 0x50) || pointer(metadata.add(0x48)).compare(backend) !== 0 || metadata.add(0x28).readU8() === 0 || equalBytes(metadata, candidate.ids, 20, true) !== true) return;
        const length = Number(metadata.add(0x20).readU64().toString()), blocks = Math.ceil(length / 16384), flags = metadata.add(0x18).readU32();
        if (!Number.isSafeInteger(length) || length <= 0 || length > 16 * 1024 * 1024 || length !== candidate.length) return;
        if (backend.add(0x4c).readU32() !== blocks) { cacheWait("file-cache-incomplete", { cachedBlocks: backend.add(0x4c).readU32(), requiredBlocks: blocks }); return; }
        const header = backend.add(0x50).readU32(), extra = backend.add(0x58).readU32(), base = (flags & 2) !== 0 ? 0 : header + extra;
        if (header !== 64 + blocks * 4 || base + length > 0x7fffffff || backend.add(0x38).readS32() < 0) return;
        const checksums = pointer(backend.add(0x40)).add((flags & 2) !== 0 ? 0 : 64);
        if (!readable(checksums, blocks * 4)) return;
        for (let block = 0; block < blocks; block++) if (checksums.add(block * 4).readU32() === 0xffffffff) return;
        const primitive = manager.add(0x218), wrapper = manager.add(0x258), schedule = pointer(primitive.add(8));
        if ((flags & 8) === 0) {
            if (codeOffset(pointer(primitive)) !== "0x161e4d8" || codeOffset(pointer(wrapper)) !== "0x161e530" || pointer(wrapper.add(8)).compare(primitive) !== 0 || wrapper.add(0x18).readU32() !== 16) return;
            if (codeOffset(pointer(pointer(primitive).add(0x28))) !== "0x1471628" || codeOffset(pointer(pointer(wrapper).add(0x38))) !== "0x1472260" || codeOffset(pointer(pointer(wrapper).add(0x40))) !== "0x14722a0" || codeOffset(pointer(pointer(wrapper).add(0x20))) !== "0x1471e94") return;
            if (primitive.add(0x10).readU32() !== 60 || ![10, 12, 14].includes(primitive.add(0x18).readU32()) || primitive.add(0x14).readU8() > 1 || !readable(schedule, 240)) return;
        }
        const owned = Memory.alloc(352), ownedChecksums = Memory.alloc(blocks * 4), copies = [];
        const copy = (source, destination, size) => { const value = new Uint8Array(source.readByteArray(size)); copies.push(value); destination.writeByteArray(value); };
        try {
            owned.writeByteArray(new Uint8Array(352));
            owned.writeS32(backend.add(0x38).readS32());
            owned.add(4).writeU32(flags); owned.add(8).writeU64(length);
            owned.add(16).writeU32(header); owned.add(20).writeU32(extra);
            owned.add(33).writeU8(metadata.add(0x14).readU8()); owned.add(34).writeU16(metadata.add(0x16).readU16());
            copy(manager.add(0x200), owned.add(36), 20);
            if ((flags & 8) === 0) {
                owned.add(24).writeU32(60); owned.add(28).writeU32(primitive.add(0x18).readU32()); owned.add(32).writeU8(primitive.add(0x14).readU8());
                copy(schedule, owned.add(56), 240);
            }
            copy(checksums, ownedChecksums, blocks * 4);
            owned.add(296).writePointer(ownedChecksums); owned.add(304).writeU32(blocks);
            for (const [offset, target] of [[312, 0x161e4d8], [320, 0x161e530], [328, 0x1472260], [336, 0x14722a0], [344, 0x1471e94]]) owned.add(offset).writePointer(orbit.base.add(target));
            if (!allowed() || cacheApi.matches(epoch, candidate.ids, candidate.ids.add(20)) === 0) return;
            captured = capture(epoch, candidate.ids, candidate.ids.add(20), owned) === 1;
            if (captured) emit("owned-cache-file-ready", { bytes: length, completeBlocks: blocks, networkRequested: false, contentsLogged: false });
            else cacheWait("owned-file-capture-rejected");
        } finally {
            owned.writeByteArray(new Uint8Array(352));
            ownedChecksums.writeByteArray(new Uint8Array(blocks * 4));
            for (const value of copies) value.fill(0);
        }
    };
}

function installNativeCacheWindows(bridge, options) {
    const bytes = address => Array.from(new Uint8Array(address.readByteArray(16)), value => value.toString(16).padStart(2, "0")).join("");
    const signatures = [[0x155e040, "300600d011e245f910022f9120021fd6"], [0x122fc74, "fe57bea9f44f01a9c81d009008a13d91"], [0xb9ced8, "3f1c00724000005402000014c0035fd6"], [0x10ec34c, "ff0301d1fe5702a9f44f03a955d03bd5"], [0x10edb10, "fe0f1ef8f44f01a922700f94742242f9"], [0x10ee234, "fd7bbaa9fc6f01a9fa6702a9f85f03a9"], [0x1471628, "ffc301d1fd7b01a9fc6f02a9fa6703a9"], [0x1472260, "fe57bea9f44f01a9f30300aa000840f9"], [0x14722a0, "092043290801010b1f05096b081c00b9"], [0x1471e94, "fe0f1cf8f85f01a9f65702a9f44f03a9"]];
    for (const [offset, signature] of signatures) if (bytes(orbit.base.add(offset)) !== signature) throw new Error("Native window identity mismatch");
    const bind = new NativeFunction(bridge.getExportByName("lyrics_spotify_audio_window_bind"), "int", ["uint64", "pointer", "pointer", "pointer", "pointer", "uint64", "pointer"], options);
    const clear = new NativeFunction(bridge.getExportByName("lyrics_spotify_audio_window_clear"), "void", [], options);
    const status = new NativeFunction(bridge.getExportByName("lyrics_spotify_audio_window_status"), "int", [], options);
    nativeWindowModule = new CModule(`
#include <gum/guminterceptor.h>
extern void manager_unlock(void *mutex);
extern void process_unlock(void *mutex, void *return_address, guint32 guard);
extern void destroy_manager(void *manager);
extern void destroy_process(void *process);
void on_manager(GumInvocationContext *ic) { manager_unlock(gum_invocation_context_get_nth_argument(ic, 0)); }
void on_process(GumInvocationContext *ic) { process_unlock(gum_invocation_context_get_nth_argument(ic, 0), gum_invocation_context_get_return_address(ic), (guint32) (gsize) gum_invocation_context_get_nth_argument(ic, 1)); }
void on_destroy_manager(GumInvocationContext *ic) { destroy_manager(gum_invocation_context_get_nth_argument(ic, 0)); }
void on_destroy_process(GumInvocationContext *ic) { destroy_process(gum_invocation_context_get_nth_argument(ic, 0)); }
`, {
        manager_unlock: bridge.getExportByName("lyrics_spotify_audio_window_manager_unlock"),
        process_unlock: bridge.getExportByName("lyrics_spotify_audio_window_process_unlock"),
        destroy_manager: bridge.getExportByName("lyrics_spotify_audio_window_destroy_manager"),
        destroy_process: bridge.getExportByName("lyrics_spotify_audio_window_destroy_process")
    });
    for (const [offset, callback] of [[0x155e040, nativeWindowModule.on_manager], [0xb9ced8, nativeWindowModule.on_process], [0x122fc74, nativeWindowModule.on_destroy_manager], [0x10ec34c, nativeWindowModule.on_destroy_process]]) hooks.push(Interceptor.attach(orbit.base.add(offset), { onEnter: callback }));
    return {
        observe(manager, process, candidate) {
            if (!allowed()) return;
            const epoch = cacheApi.epoch();
            if (cacheApi.matches(epoch, candidate.ids, candidate.ids.add(20)) === 0) return;
            bind(epoch, manager, process, candidate.ids, candidate.ids.add(20), candidate.length, orbit.base);
        },
        counts() { return { nativeWindowStatus: status() }; },
        clear
    };
}

function installCacheCopy(bridge, options) {
    const bytes = address => Array.from(new Uint8Array(address.readByteArray(16)), value => value.toString(16).padStart(2, "0")).join("");
    if (bytes(orbit.base.add(0x1231d44)) !== "ffc304d1fd7b0da9fc6f0ea9fa670fa9" || bytes(orbit.base.add(0x1232dac)) !== "fe5fbda9f65701a9f44f02a9286080b9") throw new Error("Cache read identity mismatch");
    cacheApi = {
        epoch: new NativeFunction(bridge.getExportByName("lyrics_spotify_audio_epoch_current"), "uint64", [], options),
        matches: new NativeFunction(bridge.getExportByName("lyrics_spotify_audio_cache_matches"), "int", ["uint64", "pointer", "pointer"], options),
        copy: new NativeFunction(bridge.getExportByName("lyrics_spotify_audio_cache_copy"), "int", ["uint64", "pointer", "pointer", "uint64"], options)
    };
    const fileMode = new NativeFunction(bridge.getExportByName("lyrics_spotify_audio_cache_file_mode"), "int", [], options)() !== 0;
    if (fileMode) {
        cacheManagerWindow = installNativeCacheWindows(bridge, options);
        captureSongCipher = { nativeWindow: true };
    }
    if (!fileMode && new NativeFunction(bridge.getExportByName("lyrics_spotify_audio_cache_decode_mode"), "int", [], options)() !== 0) {
        const transform = orbit.base.add(0x10ee234);
        if (bytes(transform) !== "fd7bbaa9fc6f01a9fa6702a9f85f03a9") throw new Error("Song transform identity mismatch");
        const destroyProcess = orbit.base.add(0x10ec34c);
        if (bytes(destroyProcess) !== "ff0301d1fe5702a9f44f03a955d03bd5") throw new Error("Decode process destructor identity mismatch");
        captureSongCipher = new NativeFunction(bridge.getExportByName("lyrics_spotify_audio_cache_cipher_capture"), "int", ["uint64", "pointer", "pointer", "pointer", "pointer"], options);
        const getter = orbit.base.add(0x10edb10), unlock = orbit.base.add(0xb9ced8);
        if (bytes(getter) !== "fe0f1ef8f44f01a922700f94742242f9" || bytes(unlock) !== "3f1c00724000005402000014c0035fd6") throw new Error("Decode process snapshot identity mismatch");
        hooks.push(Interceptor.attach(destroyProcess, { onEnter(args) { cipherInputs.delete(args[0].add(0x60).toString()); } }));
        function captureProtected(state, process) {
            if (!allowed() || cipherCaptured) return;
            const candidate = cipherInputs.get(state.toString());
            if (!candidate || codeOffset(pointer(process)) !== "0x15ccad8") return;
            const epoch = cacheApi.epoch();
            if (cacheApi.matches(epoch, candidate.ids, candidate.ids.add(20)) === 0) return;
            if (!readable(state, 0x310) || state.readU8() !== 1 || state.add(0xc).readU8() === 0) { cacheWait("protected-song-state-unavailable"); return; }
            const owned = Memory.alloc(0x310);
            let prefix = null;
            try {
                prefix = new Uint8Array(state.readByteArray(0x310));
                owned.writeByteArray(prefix);
                cipherCaptured = captureSongCipher(epoch, candidate.ids, candidate.ids.add(20), owned, transform) === 1;
                if (cipherCaptured) emit("owned-song-cipher-ready", { protectedMode: true, sourceMatched: true, contentsLogged: false });
            } finally {
                owned.writeByteArray(new Uint8Array(0x310));
                if (prefix) prefix.fill(0);
            }
        }
        hooks.push(Interceptor.attach(transform, { onEnter(args) {
            try { captureProtected(args[0], args[0].sub(ptr(0x60))); }
            catch (error) { readErrors++; emit("song-cipher-observation-error"); }
        } }));
        hooks.push(Interceptor.attach(unlock, { onEnter(args) {
            if (this.returnAddress.compare(orbit.base.add(0x10edb2c)) !== 0 || (args[1].toUInt32() & 255) === 0) return;
            try {
                const process = args[0].sub(ptr(8));
                captureProtected(process.add(0x60), process);
            } catch (error) { readErrors++; emit("song-cipher-snapshot-error"); }
        } }));
    }
    if (fileMode) return;
    hooks.push(Interceptor.attach(orbit.base.add(0x1232dac), {
        onEnter(args) {
            if (copyingCache || !allowed()) return;
            cacheReadEntries++;
            if (this.returnAddress.compare(orbit.base.add(0x1231db0)) !== 0) return;
            cacheOriginalEntries++;
            const candidate = cacheReaders.get(args[1].toString());
            if (!candidate) return;
            const epoch = cacheApi.epoch();
            if (cacheApi.matches(epoch, candidate.ids, candidate.ids.add(20)) === 0) return;
            cacheEligibleEntries++;
            this.cacheRead = { backend: args[1], error: args[0], candidate, epoch };
        },
        onLeave() {
            if (copyingCache || !allowed() || !this.cacheRead) return;
            const { backend, error, candidate, epoch } = this.cacheRead;
            try {
                if (!readable(error, 4) || error.readS32() !== 0 || codeOffset(pointer(backend)) !== "0x15e8188" || codeOffset(pointer(pointer(backend).add(0x50))) !== "0x1231d44") return;
                if (captureCacheFile) { captureCacheFile(backend, candidate); return; }
                const metadata = pointer(backend.add(0x30));
                if (!readable(metadata, 0x29) || metadata.add(0x28).readU8() === 0) { cacheWait("length-unavailable"); return; }
                if (equalBytes(metadata, candidate.ids, 20, true) !== true) { cacheWait("cache-key-mismatch"); return; }
                const length = Number(metadata.add(0x20).readU64().toString()), blocks = Math.ceil(length / 16384), flags = metadata.add(0x18).readU32();
                if (!Number.isSafeInteger(length) || length <= 0 || length > 16 * 1024 * 1024 || length !== candidate.length) { cacheWait("length-rejected"); return; }
                if (backend.add(0x4c).readU32() !== blocks) { cacheWait("cache-incomplete", { cachedBlocks: backend.add(0x4c).readU32(), requiredBlocks: blocks }); return; }
                const headerBytes = backend.add(0x50).readU32(), prefix = (flags & 2) !== 0 ? 0 : (headerBytes + backend.add(0x58).readU32()) | 0;
                if (headerBytes !== 64 + blocks * 4 || prefix < 0 || prefix + length > 0x7fffffff) return;
                const checksums = pointer(backend.add(0x40)).add((flags & 2) !== 0 ? 0 : 64);
                if (!readable(checksums, blocks * 4)) return;
                for (let block = 0; block < blocks; block++) if (checksums.add(block * 4).readU32() === 0xffffffff) return;
                if (cacheApi.matches(epoch, candidate.ids, candidate.ids.add(20)) === 0) return;
                copyingCache = true;
                const result = cacheApi.copy(epoch, backend, orbit.base.add(0x1231d44), length);
                if (result === 3) cacheBytes = length;
                if (result !== 0) emit("cache-copy-result", { status: result, bytes: result === 3 ? length : 0, decoded: false, networkRequested: false });
            } catch (error) { readErrors++; emit("cache-copy-observation-error"); }
            finally { copyingCache = false; }
        }
    }));
}

rpc.exports = {
    init(stage, parameters = {}) {
        const bridge = Process.enumerateModules().find(module => {
            if (module.name !== "liblyricsaudio.so") return false;
            const active = module.findExportByName("lyrics_spotify_audio_active");
            return active !== null && new NativeFunction(active, "int", [], { exceptions: "propagate" })() !== 0;
        });
        if (!bridge) throw new Error("Active audit bridge missing");
        const options = { exceptions: "propagate" };
        enabled = new NativeFunction(bridge.getExportByName("lyrics_spotify_audio_active"), "int", [], options);
        record = new NativeFunction(bridge.getExportByName("lyrics_spotify_audio_record"), "void", ["pointer"], options);
        if (new NativeFunction(bridge.getExportByName("lyrics_spotify_audio_resource_mode"), "int", [], options)() !== 0) captureResource = new NativeFunction(bridge.getExportByName("lyrics_spotify_audio_capture_resource"), "int", ["int", "pointer", "pointer"], options);
        noopTarget = parameters.queueTarget === "core" ? "core" : "work";
        if (parameters.queueBarrier === true) {
            noopTarget = "core";
            postBarrier = new NativeFunction(bridge.getExportByName("lyrics_spotify_audio_post_barrier"), "int", ["pointer", "pointer", "pointer", "pointer"], options);
        }
        if (parameters.queueNoop === true) postNoop = new NativeFunction(bridge.getExportByName("lyrics_spotify_audio_post_noop"), "int", ["pointer", "pointer", "pointer"], options);
        orbit = Process.getModuleByName("liborbit-jni-spotify.so");
        const entry = orbit.base.add(0xc58d70);
        const signature = Array.from(new Uint8Array(entry.readByteArray(16)), value => value.toString(16).padStart(2, "0")).join("");
        if (signature !== factorySignature) { emit("audit-signature-rejected"); return; }
        if (captureResource && new NativeFunction(bridge.getExportByName("lyrics_spotify_audio_cache_mode"), "int", [], options)() !== 0) installCacheCopy(bridge, options);
        hooks.push(Interceptor.attach(entry, {
            onEnter(args) {
                if (!allowed()) return;
                factoryCalls++;
                try {
                    const sp = this.context.sp;
                    this.audit = { result: this.context.x8, descriptor: args[1], context: args[2], executor: args[7], flags: [args[3].toUInt32(), args[4].toUInt32(), args[5].toUInt32(), args[6].toUInt32()], caller: codeOffset(this.returnAddress), thread: this.threadId, callbacksPresent: !pointer(sp).isNull() && !pointer(sp.add(16)).isNull(), telemetryPresent: !pointer(sp.add(8)).isNull() };
                    emit("factory-enter", { caller: this.audit.caller, thread: this.audit.thread, flags: this.audit.flags, callbacksPresent: this.audit.callbacksPresent, telemetryPresent: this.audit.telemetryPresent });
                } catch (error) { readErrors++; }
            },
            onLeave() {
                if (!allowed() || !this.audit) return;
                try {
                    const input = this.audit, reader = pointer(input.result);
                    if (reader.isNull()) { emit("factory-return", { caller: input.caller, returnedReader: false }); return; }
                    const expectedReader = pointer(reader).compare(orbit.base.add(0x1586a50)) === 0;
                    if (!expectedReader || !readable(reader.add(0x98), 0x100)) { emit("factory-return", { caller: input.caller, returnedReader: true, expectedReader }); return; }
                    const descriptor = reader.add(0x98);
                    const copiedFlags = [0xb0, 0xb8, 0xbc, 0xe4].map(displacement => descriptor.add(displacement).readU32());
                    completedCalls++;
                    emit("factory-return", { caller: input.caller, thread: input.thread, returnedReader: true, expectedReader: true, flagsMatch: copiedFlags.every((value, index) => value === input.flags[index]), contextMatches: equalBytes(descriptor.add(0xc0), input.context, 16), executorMatches: pointer(reader.add(0x30)).compare(input.executor) === 0, sourceVariant: descriptor.add(0x70).readU32() });
                } catch (error) { readErrors++; emit("factory-observation-error"); }
            }
        }));
        const rangeEntry = orbit.base.add(0x10e598c);
        const rangeSignature = Array.from(new Uint8Array(rangeEntry.readByteArray(16)), value => value.toString(16).padStart(2, "0")).join("");
        if (rangeSignature === "ff4307d1fdbb00f9fe6f18a9fa6719a9") {
            hooks.push(Interceptor.attach(rangeEntry, { onEnter(args) {
                if (!allowed()) return;
                rangeCalls++;
                try {
                    const owner = args[0], reader = args[1];
                    if (pointer(owner).compare(orbit.base.add(0x15cc350)) !== 0 || pointer(reader).compare(orbit.base.add(0x1586a50)) !== 0) return;
                    if (captureResource && readable(reader.add(0x98), 0x78) && readable(owner.add(0x148), 24) && reader.add(0x108).readU32() === 0 && owner.add(0x148).readU32() === 4) {
                        const selector = reader.add(0xa0).readS32(), owned = Memory.alloc(36);
                        let fileId = null, trackGid = null;
                        try {
                            fileId = new Uint8Array(reader.add(0xa4).readByteArray(20));
                            trackGid = new Uint8Array(owner.add(0x150).readByteArray(16));
                            owned.writeByteArray(fileId); owned.add(20).writeByteArray(trackGid);
                            if (cacheWaitReasons.size === 0) {
                                cacheWaitReasons.add("owned-range-proof");
                                emit("owned-id-range-proof", { ownedRangeVisible: readable(owned, 20), untrustedRangeMatch: equalBytes(reader.add(0xa4), owned, 20), ownedMatch: equalBytes(reader.add(0xa4), owned, 20, true) });
                            }
                            captureResource(selector, owned, owned.add(20));
                            if (cacheApi) {
                                const source = pointer(reader.add(0x38)), backend = pointer(source.add(0x18));
                                const previous = cacheReaders.get(backend.toString());
                                if (previous && (equalBytes(reader.add(0xa4), previous.ids, 20, true) !== true || equalBytes(owner.add(0x150), previous.ids.add(20), 16, true) !== true)) {
                                    for (const [input, candidate] of cipherInputs) if (candidate === previous) cipherInputs.delete(input);
                                    previous.ids.writeByteArray(new Uint8Array(36));
                                    cacheReaders.delete(backend.toString());
                                }
                                if (!backend.isNull() && cacheReaders.size < 32 && !cacheReaders.has(backend.toString()) && readable(source.add(0x28), 9) && source.add(0x30).readU8() !== 0) {
                                    const ids = Memory.alloc(36);
                                    ids.writeByteArray(fileId); ids.add(20).writeByteArray(trackGid);
                                    cacheReaders.set(backend.toString(), { ids, length: Number(source.add(0x28).readU64().toString()) });
                                }
                                const candidate = cacheReaders.get(backend.toString()), process = pointer(owner.add(0x90));
                                if (cacheManagerWindow && candidate && codeOffset(pointer(backend)) === "0x15e8188") cacheManagerWindow.observe(pointer(backend.add(0x28)), process, candidate);
                                if (captureSongCipher && candidate && cipherInputs.size < 64 && codeOffset(pointer(process)) === "0x15ccad8") cipherInputs.set(process.add(0x60).toString(), candidate);
                            }
                        } finally {
                            owned.writeByteArray(new Uint8Array(36));
                            if (fileId) fileId.fill(0);
                            if (trackGid) trackGid.fill(0);
                        }
                    }
                    const id = reader.toString();
                    if (observedReaders.has(id) || observedReaders.size >= 32) return;
                    observedReaders.add(id);
                    const factory = pointer(owner.add(0xd0));
                    const sourceState = pointer(reader.add(0x38)), backend = pointer(sourceState.add(0x18)), backendTable = pointer(backend);
                    const providerTable = pointer(pointer(factory.add(0x30))), stateReadable = readable(sourceState.add(0x28), 17), lengthKnown = stateReadable && sourceState.add(0x30).readU8() !== 0;
                    const process = pointer(owner.add(0x90));
                    emit("current-cipher-mode", { cachedInitializationAvailable: readable(owner.add(0x5c8), 1) ? owner.add(0x5c8).readU8() !== 0 : null, expectedProcess: codeOffset(pointer(process)) === "0x15ccad8", protectedMode: readable(process.add(0x60), 13) ? process.add(0x60).readU8() !== 0 : null, ready: readable(process.add(0x6c), 1) ? process.add(0x6c).readU8() !== 0 : null });
                    emit("cache-backend-interface", { readerNumber: observedReaders.size, backendPresent: !backend.isNull(), backendVtable: codeOffset(backendTable), coverageMethod: codeOffset(pointer(backendTable.add(0x40))), readMethod: codeOffset(pointer(backendTable.add(0x50))), intervalMethod: codeOffset(pointer(backendTable.add(0x20))), providerVtable: codeOffset(providerTable), providerOpen: codeOffset(pointer(providerTable.add(0x10))), lengthKnown, fileLength: lengthKnown ? sourceState.add(0x28).readU64().toString() : null, fullFlag: stateReadable ? sourceState.add(0x38).readU8() !== 0 : null });
                    if (codeOffset(backendTable) === "0x15e8188" && readable(backend.add(0x30), 0x2c)) {
                        const metadata = pointer(backend.add(0x30));
                        if (readable(metadata.add(0x18), 4)) {
                            const flags = metadata.add(0x18).readU32();
                            emit("cache-layout-mode", { readerNumber: observedReaders.size, flags, cacheTransformRequired: (flags & 8) === 0, headerBytes: backend.add(0x50).readU32(), extraBytes: backend.add(0x58).readU32(), cachedBlocks: backend.add(0x4c).readU32(), fileIdMatchesReader: readable(reader.add(0x108), 4) && reader.add(0x108).readU32() === 0 ? equalBytes(metadata, reader.add(0xa4), 20) : null, physicalPrefixBytes: (flags & 2) !== 0 ? 0 : (backend.add(0x50).readU32() + backend.add(0x58).readU32()) | 0 });
                        }
                    }
                    const work = pointer(factory.add(8)), callback = pointer(owner.add(0x3a8)), core = pointer(owner.add(0x3a0));
                    emit("reader-executors", { thread: this.threadId, caller: codeOffset(this.returnAddress), readerNumber: observedReaders.size, factoryMatches: pointer(reader.add(0x20)).compare(factory) === 0, workMatches: pointer(reader.add(0x28)).compare(work) === 0, callbackMatches: pointer(reader.add(0x30)).compare(callback) === 0, workAndCallbackSame: work.compare(callback) === 0, workVtable: codeOffset(pointer(work)), workSubmit: codeOffset(pointer(pointer(work).add(0x20))), callbackVtable: codeOffset(pointer(callback)), callbackSubmit: codeOffset(pointer(pointer(callback).add(0x20))), coreAndWorkSame: core.compare(work) === 0, coreVtable: codeOffset(pointer(core)), coreSubmit: codeOffset(pointer(pointer(core).add(0x20))), sourceVariant: readable(reader.add(0x108), 4) ? reader.add(0x108).readU32() : null });
                    const destination = noopTarget === "core" ? core : work;
                    if ((postNoop || postBarrier) && !noopPosted && !destination.isNull() && pointer(reader.add(0x28)).compare(work) === 0 && codeOffset(pointer(destination)) === "0x15fe948" && codeOffset(pointer(pointer(destination).add(0x20))) === "0x1346838") {
                        const submit = orbit.base.add(0x1491128), literal = orbit.base.add(0x44d838);
                        const bytes = address => Array.from(new Uint8Array(address.readByteArray(16)), value => value.toString(16).padStart(2, "0")).join("");
                        if (bytes(submit) !== "ff8301d1fe5704a9f44f05a91c1d0194" || bytes(orbit.base.add(0x1346838)) !== "ff4301d1fe4f04a953d03bd5681640f9") throw new Error("Queue identity mismatch");
                        if (!readable(literal, 8) || literal.readUtf8String(7) !== "unknown" || literal.add(7).readU8() !== 0) throw new Error("Queue metadata literal mismatch");
                        if (postBarrier && core.compare(work) === 0) throw new Error("Barrier requires a separate work context");
                        if (postBarrier && (codeOffset(pointer(pointer(work).add(0x18))) !== "0x134681c" || bytes(orbit.base.add(0x134681c)) !== "fe0f1ff8e80301aa2b4706943eca0094")) throw new Error("Sync queue identity mismatch");
                        noopPosted = true;
                        const result = postBarrier ? postBarrier(core, work, submit, literal) : postNoop(destination, submit, literal);
                        emit(postBarrier ? "queue-barrier-return" : "queue-noop-return", { result, target: noopTarget, submittedFromThread: this.threadId });
                    }
                } catch (error) { readErrors++; }
            } }));
        } else emit("range-signature-rejected");
        if (!cacheApi) {
            const lower = orbit.base.add(0x1232dac), bytes = Array.from(new Uint8Array(lower.readByteArray(16)), value => value.toString(16).padStart(2, "0")).join("");
            if (bytes === "fe5fbda9f65701a9f44f02a9286080b9") hooks.push(Interceptor.attach(lower, { onEnter() {
                if (!allowed()) return;
                cacheReadEntries++;
                if (this.returnAddress.compare(orbit.base.add(0x1231db0)) === 0) cacheOriginalEntries++;
            } }));
        }
        timer = setInterval(() => { if (allowed()) emit("audit-observing", { cacheReadEntries, cacheOriginalEntries, cacheEligibleEntries, ...(cacheManagerWindow ? cacheManagerWindow.counts() : {}) }); }, 10000);
        emit("audit-armed");
    },
    dispose() {
        stopped = true;
        if (timer !== undefined) clearInterval(timer);
        for (const hook of hooks) hook.detach();
        hooks.length = 0;
        for (const candidate of cacheReaders.values()) candidate.ids.writeByteArray(new Uint8Array(36));
        cacheReaders.clear();
        cipherInputs.clear();
        if (cacheManagerWindow) cacheManagerWindow.clear();
        emit("audit-disposed");
    }
};
function installCacheManagerWindow(onWindow) {
    const managers = new Map();
    let observing = false, windows = 0, matches = 0;
    const unlock = orbit.base.add(0x155e040), destroy = orbit.base.add(0x122fc74);
    const signature = address => Array.from(new Uint8Array(address.readByteArray(16)), value => value.toString(16).padStart(2, "0")).join("");
    if (signature(unlock) !== "300600d011e245f910022f9120021fd6" || signature(destroy) !== "fe57bea9f44f01a9c81d009008a13d91") throw new Error("Cache manager window identity mismatch");
    hooks.push(Interceptor.attach(destroy, { onEnter(args) { managers.delete(args[0].toString()); } }));
    hooks.push(Interceptor.attach(unlock, { onEnter(args) {
        if (observing || !allowed()) return;
        const manager = args[0].sub(ptr(0x1d8)), entry = managers.get(manager.toString());
        if (!entry) return;
        observing = true;
        try {
            const candidate = entry.candidate, epoch = cacheApi.epoch();
            if (cacheApi.matches(epoch, candidate.ids, candidate.ids.add(20)) === 0 || !readable(manager, 0x321) || codeOffset(pointer(manager)) !== "0x15e7f68" || manager.add(0x320).readU8() !== 0) return;
            windows++;
            const begin = pointer(manager.add(8)), end = pointer(manager.add(0x10)), capacity = pointer(manager.add(0x18));
            if (begin.isNull() || end.compare(begin) < 0 || capacity.compare(end) < 0) return;
            const bytes = Number(end.sub(begin).toString());
            if (!Number.isSafeInteger(bytes) || bytes <= 0 || bytes % 8 !== 0 || bytes > 16384 * 8 || !readable(begin, bytes)) return;
            let selected = null;
            for (let offset = 0; offset < bytes; offset += 8) {
                const metadata = pointer(begin.add(offset));
                if (!readable(metadata, 0x58) || equalBytes(metadata, candidate.ids, 20, true) !== true || metadata.add(0x28).readU8() === 0 || metadata.add(0x20).readU64().toString() !== String(candidate.length)) continue;
                const backend = pointer(metadata.add(0x48));
                if (!readable(backend, 0x80) || codeOffset(pointer(backend)) !== "0x15e8188" || pointer(backend.add(0x18)).compare(args[0]) !== 0 || pointer(backend.add(0x28)).compare(manager) !== 0 || pointer(backend.add(0x30)).compare(metadata) !== 0) continue;
                if (selected) return;
                selected = backend;
            }
            if (!selected || cacheApi.matches(epoch, candidate.ids, candidate.ids.add(20)) === 0) return;
            matches++;
            if (!entry.reported) { entry.reported = true; emit("cache-manager-window", { windows, matches, metadataOnly: true }); }
            onWindow(selected, candidate);
        } catch (error) { emit("cache-manager-window-error", { metadataOnly: true }); }
        finally { observing = false; }
    } }));
    return {
        observe(manager, candidate) {
            if (!allowed() || !manager || manager.isNull() || !candidate || !candidate.ids || !Number.isSafeInteger(candidate.length) || candidate.length <= 0 || candidate.length > 16 * 1024 * 1024) return false;
            const key = manager.toString(), existing = managers.get(key);
            if (!existing && managers.size >= 32) return false;
            if (!existing || existing.candidate !== candidate) managers.set(key, { candidate, reported: false });
            return true;
        },
        counts() { return { windows, matches }; },
        clear() { managers.clear(); }
    };
}



