globalThis.LyricsSpotifyAudioResolver = (() => {
    const SIMPLE_INIT = [0xd100c3ff, 0xf9000bfe, 0xa9024ff4];
    const PROTECTED_INIT = [0xf81b0ffd, 0xa90167fe, 0xa9025ff8, 0xa90357f6, 0xa9044ff4, 0xd14007ff, 0xd10043ff];
    const SIMPLE_TRANSFORM = [0xd10183ff, 0xf90013fe, 0xa9035ff8, 0xa90457f6, 0xa9054ff4];
    const PROTECTED_TRANSFORM = [0xa9ba7bfd, 0xa9016ffc, 0xa90267fa, 0xa9035ff8, 0xa90457f6, 0xa9054ff4, 0xd14007ff, 0xd10043ff];
    const CTOR = [0xf81e0ffe, 0xa9014ff4];

    function resolve(orbit) {
        if (Process.arch !== "arm64" || Process.pointerSize !== 8) throw new Error("Spotify audio requires an arm64 process");
        const ranges = orbit.enumerateRanges("r-x").map(range => ({ start: offset(range.base), end: offset(range.base) + range.size }));
        if (!ranges.length) throw new Error("Spotify audio executable ranges unavailable");

        function offset(pointer) { return Number(pointer.sub(orbit.base).toString()); }
        function executable(address, length = 4) { return Number.isSafeInteger(address) && address % 4 === 0 && ranges.some(range => address >= range.start && address + length <= range.end); }
        function word(address) {
            if (!executable(address)) throw new Error("Spotify audio candidate left executable memory");
            return orbit.base.add(address).readU32();
        }
        function sequence(address, expected) { return executable(address, expected.length * 4) && expected.every((value, index) => word(address + index * 4) === value); }
        function block(address, length) {
            if (!executable(address, length)) return [];
            return Array.from({ length: length / 4 }, (_, index) => word(address + index * 4));
        }
        function unique(values, role) {
            const choices = [...new Set(values)];
            if (choices.length !== 1) throw new Error("Spotify audio " + role + " resolution " + (choices.length ? "ambiguous" : "unsupported"));
            return choices[0];
        }
        function find(pattern, accept = () => true) {
            const matches = [];
            for (const range of ranges) {
                for (const match of Memory.scanSync(orbit.base.add(range.start), range.end - range.start, pattern)) {
                    const address = offset(match.address);
                    if (address % 4 === 0 && accept(address)) matches.push(address);
                    if (matches.length > 512) throw new Error("Spotify audio fingerprint exceeded its candidate limit");
                }
            }
            return matches;
        }
        function pattern(words) { return words.map(value => [0, 8, 16, 24].map(shift => ((value >>> shift) & 255).toString(16).padStart(2, "0")).join(" ")).join(" "); }
        function isCall(value) { return value >>> 26 === 0x25; }
        function isJump(value) { return value >>> 26 === 5; }
        function branch(address) {
            const value = word(address), immediate = value & 0x3ffffff;
            if (!isCall(value) && !isJump(value)) return null;
            const target = address + (immediate & 0x2000000 ? immediate - 0x4000000 : immediate) * 4;
            return executable(target) ? target : null;
        }
        function calls(address, length, expand = false) {
            const result = new Set();
            for (let position = address; position < address + length; position += 4) {
                if (!executable(position) || !isCall(word(position))) continue;
                const target = branch(position);
                if (target === null) continue;
                result.add(target);
                if (!expand) continue;
                for (let instruction = target; instruction < target + 40 && executable(instruction); instruction += 4) {
                    const value = word(instruction);
                    if (isJump(value)) { const destination = branch(instruction); if (destination !== null) result.add(destination); break; }
                    if (value === 0xd65f03c0 || isCall(value)) break;
                }
            }
            return result;
        }
        function returningBody(address, maximum = 768) {
            const body = [];
            for (let position = address; position < address + maximum && executable(position); position += 4) {
                const value = word(position);
                body.push(value);
                if (value === 0xd65f03c0) return body;
            }
            return [];
        }
        function nearby(start, end, signature) {
            const result = [];
            for (let address = start; address < end; address += 4) if (sequence(address, signature)) result.push(address);
            return result;
        }
        function constructorBefore(anchor) { return unique(nearby(Math.max(0, anchor - 96), anchor, CTOR), "constructor entry"); }
        function firstReturn(constructor) {
            for (let address = constructor; address < constructor + 192; address += 4) if (word(address) === 0xd65f03c0) return address;
            throw new Error("Spotify audio constructor cleanup unavailable");
        }

        const factories = find(pattern([0xf94006a0, 0x710002df, 0x1a9f07f5])).filter(start => {
            if (!executable(start, 32) || !isCall(word(start + 12)) || !isCall(word(start + 20))) return false;
            const result = word(start + 16), presence = word(start + 24), absent = word(start + 28);
            if (((result & 0xffc003ff) >>> 0) !== 0x910003e8 || ((presence & 0xffc003ff) >>> 0) !== 0x394003e8 || ((absent & 0xff00001f) >>> 0) !== 0x34000008) return false;
            const payload = ((presence >>> 10) & 4095) - ((result >>> 10) & 4095);
            if (payload !== 24 && payload !== 28) return false;
            const dispatch = branch(start + 12), invoke = branch(start + 20);
            if (dispatch === null || invoke === null || !sequence(invoke, [0x2a1403e1, 0xd61f0120]) || word(dispatch) !== 0xf9400008 || word(dispatch + 8) !== 0xd65f03c0) return false;
            const slot = word(dispatch + 4);
            return ((slot & 0xffc003ff) >>> 0) === 0xf9400109 && ((slot >>> 10) & 4095) >= 5 && ((slot >>> 10) & 4095) <= 32;
        });
        const factory = unique(factories, "recording factory"), caller = factory + 24;
        const packet = ((word(caller) >>> 10) & 4095) - ((word(caller - 8) >>> 10) & 4095);
        const factoryCalls = calls(caller + 8, 128, true);
        const candidates = [];
        const relationships = [];

        function addCipher(constructor, initialize, mode) {
            const protectedState = mode !== 1;
            const transformSignature = protectedState ? PROTECTED_TRANSFORM : SIMPLE_TRANSFORM;
            const transforms = nearby(initialize + 32, initialize + 768, transformSignature).filter(address => {
                const body = returningBody(address);
                if (protectedState) return body.filter(value => value === 0x52805d02).length >= 2;
                return body.includes(0x39416008) && body.includes(0xf9402a68) && body.includes(0x39016269);
            });
            const transform = unique(transforms, "cipher transform");
            const body = block(initialize, transform - initialize);
            const ready = mode === 1 ? 100 : mode === 2 ? 12 : 8;
            if (mode === 1) {
                if (!body.includes(0x3901926a) || !body.includes(0xb9006268)) throw new Error("Spotify audio simple initialization layout rejected");
                if (![...calls(initialize, transform - initialize, true)].some(target => factoryCalls.has(target))) throw new Error("Spotify audio factory initialization relationship rejected");
            } else {
                if (body.filter(value => value === 0x52805d02).length < 2 || !body.includes(0x3cc0c2c0) || !body.includes(0x3dc002c1) || !body.includes(mode === 2 ? 0x39003274 : 0x39002274)) throw new Error("Spotify audio protected initialization layout rejected");
                if (packet !== 28) throw new Error("Spotify audio protected packet layout rejected");
                const initializationCalls = calls(initialize, transform - initialize), transformCalls = calls(transform, returningBody(transform).length * 4);
                if ([...initializationCalls].filter(target => transformCalls.has(target)).length < 3) throw new Error("Spotify audio protected transform relationship rejected");
                if (mode === 2) {
                    const related = new Set([...calls(initialize, transform - initialize, true), ...calls(constructor, firstReturn(constructor) - constructor, true)]);
                    const links = [...related].filter(target => factoryCalls.has(target));
                    if (!links.length) throw new Error("Spotify audio protected factory relationship rejected");
                    relationships.push({ role: "factory cipher component", targets: links });
                } else {
                    const consumers = find("ff ?? ?? 39 ff ?? ?? 39 ff ?? ?? 39 00 01 80 3d ?? ?? ?? f9 ?? ?? ?? 9?", address => {
                        if (!executable(address - 16, 64)) return false;
                        const clear = word(address), base = (clear >>> 10) & 4095;
                        if (((clear & 0xffc003ff) >>> 0) !== 0x390003ff || word(address + 4) !== ((clear + (4 << 10)) >>> 0) || word(address + 8) !== ((clear + (8 << 10)) >>> 0)) return false;
                        if (word(address - 16) !== ((0x910003e8 | (base << 10)) >>> 0) || word(address - 12) !== ((0x910003e0 | (base << 10)) >>> 0) || word(address - 8) !== 0x910bd108 || ((word(address - 4) & 0xffc003ff) >>> 0) !== 0x91000261) return false;
                        const tail = word(address + 16);
                        if (((tail & 0xffc003ff) >>> 0) !== 0xf90003ff || ((tail >>> 10) & 4095) * 8 !== base + 776 || !isCall(word(address + 20)) || branch(address + 20) !== initialize) return false;
                        const begin = word(address + 24), end = word(address + 28);
                        if (((begin & 0xffc003ff) >>> 0) !== 0xf9400262 || ((end & 0xffc003ff) >>> 0) !== 0xf9400268 || ((end >>> 10) & 4095) !== ((begin >>> 10) & 4095) + 1) return false;
                        const prepare = branch(address + 32);
                        return prepare !== null && isCall(word(address + 32)) && sequence(prepare, [(0x910003e0 | (base << 10)) >>> 0, 0xaa1f03e1, 0xd65f03c0]) && word(address + 36) === 0xcb020104 && word(address + 40) === 0xaa0203e3 && isCall(word(address + 44)) && branch(address + 44) === transform;
                    });
                    relationships.push({ role: "native packet and buffer consumer", address: unique(consumers, "protected packet consumer") });
                }
            }
            let destructor = 0;
            if (constructor) {
                const cleanup = firstReturn(constructor);
                const primitive = branch(cleanup + (mode === 1 ? 8 : 12));
                if (primitive === null || !isCall(word(cleanup + (mode === 1 ? 8 : 12)))) throw new Error("Spotify audio constructor cleanup relationship rejected");
                if (mode === 1) {
                    const destructors = find("fe 4f bf a9 ?? ?? ?? 9? ?? ?? ?? 9? e0 03 13 aa fe 4f c1 a8 ?? ?? ?? 1?", address => {
                        if (!isCall(word(address + 4)) || !isCall(word(address + 8)) || !isJump(word(address + 20)) || branch(address + 20) !== primitive) return false;
                        const helper = branch(address + 4), instructions = helper === null ? [] : block(helper, 24);
                        return instructions.includes(0xaa0003f3) && instructions.includes(0x91008000);
                    });
                    destructor = unique(destructors, "simple cipher destructor");
                } else {
                    const ownedOffsets = block(constructor, cleanup - constructor).filter(value => ((value & 0xffc00000) >>> 0) === 0x91000000).map(value => (value >>> 10) & 4095).filter(value => value === 792 || value === 816);
                    const owned = unique(ownedOffsets, "owned cipher component");
                    const destructors = find("fe 4f bf a9 f3 03 00 aa ?? ?? ?? 91 ?? ?? ?? 9? 60 42 0c 91 fe 4f c1 a8 ?? ?? ?? 1?", address => {
                        return word(address + 8) === ((0x91000000 | (owned << 10)) >>> 0) && isCall(word(address + 12)) && word(address + 16) === 0x910c4260 && word(address + 20) === 0xa8c14ffe && isJump(word(address + 24)) && branch(address + 24) === primitive;
                    });
                    destructor = unique(destructors, "protected cipher destructor");
                }
            }
            candidates.push([constructor, initialize, transform, destructor, caller, ready, packet, mode]);
        }

        for (const anchor of find(pattern([0x3901427f, 0x3901627f, 0x3901827f, 0x3901927f]))) {
            const constructor = constructorBefore(anchor), cleanup = firstReturn(constructor);
            if (cleanup < anchor || cleanup > anchor + 32) continue;
            const initialize = unique(nearby(cleanup + 4, cleanup + 160, SIMPLE_INIT), "simple initializer entry");
            addCipher(constructor, initialize, 1);
        }
        for (const anchor of find(pattern([0x3900101f, 0x3900201f, 0x3900301f, 0xf9017c1f, 0xf901841f, 0xf901801f]))) {
            const constructor = constructorBefore(anchor), preceding = block(constructor, anchor - constructor);
            if (!preceding.includes(0x39000001)) continue;
            const cleanup = firstReturn(constructor);
            const initialize = unique(nearby(cleanup + 4, cleanup + 192, PROTECTED_INIT), "protected initializer entry");
            addCipher(constructor, initialize, 2);
        }
        if (!candidates.length) {
            for (const initialize of find(pattern(PROTECTED_INIT))) {
                const body = block(initialize, 280);
                if (body.filter(value => value === 0x52805d02).length < 2 || !body.includes(0x39002274) || !body.includes(0xb9000276)) continue;
                addCipher(0, initialize, 3);
            }
        }
        if (candidates.length !== 1) throw new Error("Spotify audio cipher resolution " + (candidates.length ? "ambiguous" : "unsupported"));
        const values = candidates[0];
        for (const address of values.slice(0, 5)) if (address && !executable(address, 32)) throw new Error("Spotify audio candidate is not executable");
        const anchors = values.slice(0, 5).filter(Boolean).map(address => [address, Array.from(new Uint8Array(orbit.base.add(address).readByteArray(32)), value => value.toString(16).padStart(2, "0")).join("")]);
        return { values, getter: 0, captureAtReturn: true, anchors, relationships, version: "semantic AArch64 recording ABI " + values[7] };
    }

    return { resolve };
})();
