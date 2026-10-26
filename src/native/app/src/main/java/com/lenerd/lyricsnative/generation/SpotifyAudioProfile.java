package com.lenerd.lyricsnative.generation;

final class SpotifyAudioProfile {
    static String active(String source, String marker) {
        return source + "\n;(() => { const profile = rpc.exports; rpc.exports = { async init(stage, parameters) { await profile.init(stage, parameters); File.writeAllText(" + quote(marker) + ", 'ready'); }, async dispose() { if (profile.dispose) await profile.dispose(); } }; })();\n";
    }

    static String inactive(String marker) { return "rpc.exports = { init() { File.writeAllText(" + quote(marker) + ", 'ready'); } };\n"; }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '"' || c == '\\') result.append('\\').append(c);
            else if (c < 32 || c == '\u2028' || c == '\u2029') result.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int)c));
            else result.append(c);
        }
        return result.append('"').toString();
    }

    private SpotifyAudioProfile() { }
}
