let hook = null;

rpc.exports = {
    init() {
        const orbit = Process.getModuleByName("liborbit-jni-spotify.so");
        if (Process.arch !== "arm64" || Process.pointerSize !== 8) throw new Error("Spotify audio requires an arm64 process");
        const resolver = globalThis.LyricsSpotifyAudioResolver;
        if (!resolver || typeof resolver.resolve !== "function") throw new Error("Spotify audio fingerprint resolver unavailable");
        const profile = resolver.resolve(orbit);
        for (const [offset, expected] of profile.anchors) {
            const actual = Array.from(new Uint8Array(orbit.base.add(offset).readByteArray(expected.length / 2)), byte => byte.toString(16).padStart(2, "0")).join("");
            if (actual !== expected) throw new Error("Spotify native audio changed during fingerprint resolution");
        }
        console.log("Lyrics remote audio profile: " + profile.version);
        const options = { scheduling: "exclusive", exceptions: "propagate" };
        const bridge = Process.enumerateModules().find(module => {
            const active = module.findExportByName("lyrics_spotify_remote_bridge_active");
            return active !== null && new NativeFunction(active, "int", [], options)() === 1;
        });
        if (!bridge) throw new Error("Remote cipher owner unavailable");
        const epoch = new NativeFunction(bridge.getExportByName("lyrics_spotify_remote_bridge_epoch"), "uint64", [], options);
        const configure = new NativeFunction(bridge.getExportByName("lyrics_spotify_remote_bridge_configure_profile"), "int", ["pointer", "ulong", "pointer"], options);
        const capture = new NativeFunction(bridge.getExportByName("lyrics_spotify_remote_cipher_capture"), "int", ["uint64", "uint32", "pointer", "pointer"], options);
        const captureResult = (owner, caller) => {
            const range = Process.findRangeByAddress(owner.output);
            const readable = !!range && range.protection.includes("r") && owner.output.add(profile.values[6] + 1).compare(range.base.add(range.size)) <= 0;
            if (readable) capture(owner.epoch, owner.handle, caller, owner.output);
        };
        const caller = orbit.base.add(profile.values[4]);
        const presenceOffset = (caller.readU32() >>> 10) & 4095;
        hook = Interceptor.attach(caller, function() {
            const currentEpoch = epoch();
            if (currentEpoch.toString() === "0") return;
            captureResult({ epoch: currentEpoch, handle: this.context.x20.toUInt32(), output: this.context.sp.add(presenceOffset - profile.values[6]) }, caller);
        });
        const configuration = Memory.alloc(8 * profile.values.length);
        for (let index = 0; index < profile.values.length; index++) configuration.add(index * 8).writeU64(profile.values[index]);
        if (configure(orbit.base, orbit.size, configuration) !== 1) { hook.detach(); hook = null; throw new Error("Remote cipher configuration rejected"); }
    },
    dispose() { if (hook) { hook.detach(); hook = null; } }
};
