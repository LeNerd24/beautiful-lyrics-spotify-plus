package com.lenerd.lyricsnative.generation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class RootTrackFiles {
    private RootTrackFiles() { }

    static Set<String> parse(byte[] body, String trackId) throws IOException {
        if (body == null || body.length > 262_144) throw new IOException("Track metadata exceeds limit");
        try {
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(body)).toString();
            JSONObject root = new JSONObject(json);
            String gid = root.optString("gid", "");
            String expected = SpotifyResolverCall.trackPath(trackId).substring("metadata/4/track/".length()).split("\\?", 2)[0];
            if (!gid.matches("[0-9a-fA-F]{32}") || !gid.equalsIgnoreCase(expected)) throw new IOException("Metadata root recording does not match request");
            JSONArray files = root.optJSONArray("file");
            if (files == null || files.length() == 0 || files.length() > 64) throw new IOException("Current recording has no supported file list");
            HashSet<String> result = new HashSet<>();
            for (int i = 0; i < files.length(); i++) {
                String id = files.getJSONObject(i).getString("file_id");
                if (!id.matches("[0-9a-fA-F]{40}")) throw new IOException("Unsupported metadata file identifier");
                result.add(id.toLowerCase(java.util.Locale.ROOT));
            }
            return Set.copyOf(result);
        } catch (JSONException | CharacterCodingException error) {
            throw new IOException("Invalid track metadata response");
        }
    }

    static String hex(byte[] value) {
        StringBuilder result = new StringBuilder(value.length * 2);
        for (byte item : value) result.append(Character.forDigit((item & 255) >>> 4, 16)).append(Character.forDigit(item & 15, 16));
        return result.toString();
    }
}
