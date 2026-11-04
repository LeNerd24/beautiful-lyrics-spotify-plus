package com.lenerd.lyricsnative.generation;

import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

final class RemoteAudioFileLookup {
    static AudioFilesPayload.Selection fetch(SpotifyPlusContext context, String uri) throws Exception {
        Class<?> queryType = context.getHooks().findClass("spotify.mdata.proto.Mdata$LocalExtensionQuery");
        int kind = SpotifyAudioApi.enumNumber(queryType, "AUDIO_FILES");
        byte[] request = SpotifyAudioApi.message(1, SpotifyAudioApi.message(1, kind, 2, uri));
        Object single = SpotifyReflection.call(SpotifyAudioApi.transport(context), "callSingle", "spotify.mdata_esperanto.proto.MetadataService", "Fetch", request);
        byte[] response = (byte[]) SpotifyReflection.call(SpotifyReflection.call(single, "timeout", 15L, TimeUnit.SECONDS), "blockingGet");
        try {
            if (response == null || response.length > 262144) throw new IOException("Invalid audio metadata response");
            Object result = SpotifyAudioApi.parse(context.getHooks().findClass("spotify.mdata.proto.Mdata$LocalBatchedExtensionResponse"), response);
            for (Object extension : (Iterable<?>) SpotifyAudioApi.field(result, "extension_")) {
                if (((Number) SpotifyAudioApi.field(extension, "extensionKind_")).intValue() != kind) continue;
                for (Object entity : (Iterable<?>) SpotifyAudioApi.field(extension, "entityExtension_")) {
                    if (!uri.equals(SpotifyAudioApi.field(entity, "entityUri_"))) continue;
                    if (((Number) SpotifyAudioApi.field(SpotifyAudioApi.field(entity, "header_"), "statusCode_")).intValue() != 200) throw new IOException("Audio metadata rejected");
                    Object any = SpotifyAudioApi.field(entity, "extensionData_");
                    String type = (String) SpotifyAudioApi.field(any, "typeUrl_");
                    if (!type.endsWith("/spotify.extendedmetadata.audiofiles.AudioFilesExtensionResponse")) throw new IOException("Unexpected audio metadata type");
                    byte[] payload = (byte[]) bytes(SpotifyAudioApi.field(any, "value_"));
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

    private static byte[] bytes(Object value) throws Exception {
        java.lang.reflect.Method selected = null;
        for (java.lang.reflect.Method method : value.getClass().getMethods()) {
            if (method.getReturnType() != byte[].class || method.getParameterCount() != 0 || java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
            if (selected != null && !selected.getName().equals(method.getName())) throw new IOException("Ambiguous protobuf bytes");
            selected = method;
        }
        if (selected == null) throw new IOException("Protobuf bytes unavailable");
        return (byte[]) selected.invoke(value);
    }

    private RemoteAudioFileLookup() { }
}
