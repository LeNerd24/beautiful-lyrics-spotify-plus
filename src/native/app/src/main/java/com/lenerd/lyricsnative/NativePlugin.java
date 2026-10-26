package com.lenerd.lyricsnative;

import com.lenerd.spotifyplus.sdk.SpotifyPlusPlugin;
import com.lenerd.spotifyplus.sdk.SpotifyPlusRegistry;
import com.lenerd.spotifyplus.sdk.spotify.SpotifyPlusContext;

public class NativePlugin implements SpotifyPlusPlugin {
    private final com.lenerd.lyricsnative.sing.SingEngine sing = new com.lenerd.lyricsnative.sing.SingEngine();
    private final com.lenerd.lyricsnative.translation.TranslationBridge translation = new com.lenerd.lyricsnative.translation.TranslationBridge();
    private final com.lenerd.lyricsnative.generation.LyricsGenerator generator = new com.lenerd.lyricsnative.generation.LyricsGenerator();

    @Override
    public void register(SpotifyPlusRegistry registry, SpotifyPlusContext context) {
        translation.register(context);
        generator.register(context);
        sing.register(registry, context);

        registry.registerComponent(new com.lenerd.lyricsnative.sing.SingControl(sing));
        registry.registerComponent(new GradientText());
        registry.registerComponent(new AnimatedBackground());

        registry.registerComponent(new LyricCanvas());
        registry.registerComponent(new LyricRow());
        registry.registerComponent(new LyricMotion());
        registry.registerComponent(new LyricsTranslationIcon());

        registry.registerComponent(new LyricsChrome.Icon());
        registry.registerComponent(new LyricsChrome.Title());
        registry.registerComponent(new LyricsChrome.Inset());
        registry.registerComponent(new LyricsChrome.Viewport());
        registry.registerComponent(new LyricsChrome.Seek());
        registry.registerComponent(new LyricsChrome.SheetWindow());
        registry.registerComponent(new LyricsChrome.Sheet());
    }

    @Override
    public void onUnload() {
        translation.close();
        generator.close();
        sing.onUnload();
    }
}
