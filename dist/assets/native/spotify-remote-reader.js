let hook = null;

rpc.exports = {
    init() {
        const orbit = Process.getModuleByName("liborbit-jni-spotify.so");
        const signatures = [[0x10ee078, "fe0f1ef8f44f01a90411109489290090"], [0x10ee0ec, "fd0f1bf8fe6701a9f85f02a9f65703a9"], [0x10ee234, "fd7bbaa9fc6f01a9fa6702a9f85f03a9"], [0x10e6964, "fe4fbfa9f30300aa00c00c913b2d0e94"], [0xbc1500, "ff4303d1fe6709a9f85f0aa9f6570ba9"]];
        for (const [offset, expected] of signatures) {
            const actual = Array.from(new Uint8Array(orbit.base.add(offset).readByteArray(16)), byte => byte.toString(16).padStart(2, "0")).join("");
            if (actual !== expected) throw new Error("Remote cipher native identity mismatch");
        }
        const options = { scheduling: "exclusive", exceptions: "propagate" };
        const bridge = Process.enumerateModules().find(module => {
            const active = module.findExportByName("lyrics_spotify_remote_bridge_active");
            return active !== null && new NativeFunction(active, "int", [], options)() === 1;
        });
        if (!bridge) throw new Error("Remote cipher owner unavailable");
        const epoch = new NativeFunction(bridge.getExportByName("lyrics_spotify_remote_bridge_epoch"), "uint64", [], options);
        const configure = new NativeFunction(bridge.getExportByName("lyrics_spotify_remote_bridge_configure"), "int", ["pointer"], options);
        const capture = new NativeFunction(bridge.getExportByName("lyrics_spotify_remote_cipher_capture"), "int", ["uint64", "uint32", "pointer", "pointer"], options);
        hook = Interceptor.attach(orbit.base.add(0xbc1500), {
            onEnter(args) {
                this.epoch = epoch();
                this.handle = args[1].toUInt32();
                this.output = this.context.x8;
                this.caller = this.returnAddress;
            },
            onLeave() {
                if (this.epoch.toString() === "0" || !this.caller.equals(orbit.base.add(0xafd3ac))) return;
                const range = Process.findRangeByAddress(this.output);
                if (range && range.protection.includes("r") && this.output.add(29).compare(range.base.add(range.size)) <= 0) capture(this.epoch, this.handle, this.caller, this.output);
            }
        });
        if (configure(orbit.base) !== 1) { hook.detach(); hook = null; throw new Error("Remote cipher configuration rejected"); }
    },
    dispose() { if (hook) { hook.detach(); hook = null; } }
};
