package com.lenerd.lyricsnative.generation;

import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

final class RemoteAudioFileLookup {
    static AudioFilesPayload.Selection fetch(SpotifyPlusContext context, String uri) throws Exception {
        Object query = SpotifyReflection.call(context.getHooks().findClass("spotify.mdata.proto.Mdata$LocalExtensionQuery"), "q");
        Object kind = null;
        for (Object value : context.getHooks().findClass("p.ijx").getEnumConstants())
            if (((Enum<?>) value).name().equals("AUDIO_FILES")) kind = value;
        if (kind == null) throw new IOException("Audio file metadata unavailable");
        SpotifyReflection.call(query, "r", kind);
        SpotifyReflection.call(query, "q", uri);
        Object request = SpotifyReflection.call(context.getHooks().findClass("spotify.mdata.proto.Mdata$LocalBatchedEntityRequest"), "o");
        SpotifyReflection.call(request, "m", query);
        Object core = Class.forName("com.lenerd.spotifyplus.module.hooks.PlayerHook", false, RemoteAudioFileLookup.class.getClassLoader()).getField("core").get(null);
        Object client = core.getClass().getField("a").get(core);
        Object single = SpotifyReflection.call(client, "callSingle", "spotify.mdata_esperanto.proto.MetadataService", "Fetch", SpotifyReflection.call(request, "build"));
        byte[] response = (byte[]) SpotifyReflection.call(SpotifyReflection.call(single, "timeout", 15L, TimeUnit.SECONDS), "blockingGet");
        try {
            if (response == null || response.length > 262144) throw new IOException("Invalid audio metadata response");
            Object result = SpotifyReflection.call(context.getHooks().findClass("spotify.mdata.proto.Mdata$LocalBatchedExtensionResponse"), "n", response);
            for (Object extension : (Iterable<?>) SpotifyReflection.call(result, "getExtensionList")) {
                if (SpotifyReflection.call(extension, "getExtensionKind") != kind) continue;
                for (Object entity : (Iterable<?>) SpotifyReflection.call(extension, "n")) {
                    if (!uri.equals(SpotifyReflection.call(entity, "n"))) continue;
                    if (((Number) SpotifyReflection.call(SpotifyReflection.call(entity, "p"), "getStatusCode")).intValue() != 200) throw new IOException("Audio metadata rejected");
                    Object any = SpotifyReflection.call(entity, "o");
                    String type = (String) SpotifyReflection.call(any, "q");
                    if (!type.endsWith("/spotify.extendedmetadata.audiofiles.AudioFilesExtensionResponse")) throw new IOException("Unexpected audio metadata type");
                    byte[] payload = (byte[]) SpotifyReflection.call(SpotifyReflection.call(any, "r"), "s");
                    try {
                        return AudioFilesPayload.select(payload);
                    } finally {
                        Arrays.fill(payload, (byte) 0);
                    }
                }
            }
            throw new IOException("Current recording audio metadata missing");
        } finally {
            if (response != null) Arrays.fill(response, (byte) 0);
        }
    }

    private RemoteAudioFileLookup() { }
}
