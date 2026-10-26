import React from 'react';
import { CommonViewProps, Text, View } from 'spotifyplus/react';
import { Lyrics } from '../Types/lyrics-types';

export const CREDITS_ESTIMATED_HEIGHT = 56;

const joinNames = (names: string[]) => {
    return names.length <= 1 ? names.join('') : `${names.slice(0, -1).join(', ')} & ${names[names.length - 1]}`;
};

const providerCredit = (lyrics: Lyrics): string | undefined => {
    if (lyrics.Type === 'Syllable' && (lyrics.ai || lyrics.GenerationId)) return 'Generated with AI';
    if (lyrics.source) return `Provided by ${lyrics.source}`;

    return undefined;
};

export const hasCredits = (lyrics: Lyrics): boolean => { return (lyrics.SongWriters?.length ?? 0) > 0 || providerCredit(lyrics) !== undefined; };

const LyricsCredits = ({ lyrics, style }: { lyrics: Lyrics; style?: CommonViewProps['style'] }) => {
    const writers = lyrics.SongWriters ?? [];
    const provider = providerCredit(lyrics);

    if (!writers.length && !provider) return null;

    return (
        <View pointerEvents='none' style={style}>
            {writers.length > 0 && <Text style={{ color: '#80FFFFFF', fontSize: 14 }}>{`Written by ${joinNames(writers)}`}</Text>}
            {provider && <Text style={{ color: '#80FFFFFF', fontSize: 12, marginTop: writers.length > 0 ? 4 : 0 }}>{provider}</Text>}
        </View>
    );
};

export default LyricsCredits;
