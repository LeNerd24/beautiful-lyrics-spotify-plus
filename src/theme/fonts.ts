import { FontFamilySource } from 'spotifyplus/react';
import { SpotifyPlus } from 'spotifyplus';

export type LyricsFont = 'apple' | 'beautiful' | 'spotify' | 'system';

const fonts: Record<LyricsFont, FontFamilySource> = {
    apple: SpotifyPlus.Assets.fontFamily([{ source: 'assets/fonts/apple-music.ttf', weight: 500 }]),
    beautiful: SpotifyPlus.Assets.fontFamily([{ source: 'assets/fonts/beautiful-lyrics.ttf', weight: 500 }]),
    spotify: SpotifyPlus.Assets.fontFamily([{ source: 'assets/fonts/spotify-mix.ttf', weight: 500 }]),
    system: 'sans-serif-medium',
};

export const lyricsFontFamily = (font: LyricsFont): FontFamilySource => fonts[font] ?? fonts.apple;
