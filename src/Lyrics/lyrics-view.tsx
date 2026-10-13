import React, { useRef, useState } from 'react';
import { Text, ScrollView, View } from 'spotifyplus/react';
import { TransformedLyrics } from './lyric-utilities';
import { useAnimatedProps, usePlaybackClock } from 'spotifyplus/react/Animated';
import { LyricsFontFamily } from '../theme/fonts';
import ScrollingLyrics from './scrolling-lyrics';
import { AnimationStyle } from '../Entities/animation-style';
import { LyricsInteractionProps, LyricsViewport } from '../Components/player-chrome';
import { LyricsPresentation, linePresentation } from './presentation';

interface Props extends LyricsInteractionProps {
    lyrics: TransformedLyrics | undefined;
    animationStyle: AnimationStyle;
    presentation?: LyricsPresentation;
}

const LyricsView = ({ lyrics, ...props }: Props) => {
    const playbackMs = usePlaybackClock({ unit: 'ms' });
    if (!lyrics) return null;

    if (lyrics.Type === 'Static') {
        return <StaticLyrics lyrics={lyrics} {...props} />;
    }

    return <SyncedLyricsView lyrics={lyrics} playbackMs={playbackMs} {...props} />;
};

const StaticLyrics = ({ lyrics, controlsOpacity, controlsHeight, onRevealControls, presentation }: Props) => {
    const previousOffset = useRef(0);
    const mask = useAnimatedProps(() => {
        'worklet';

        return { controlsMask: [controlsOpacity.value, controlsHeight.value] as const };
    });

    if (lyrics?.Type !== 'Static') return null;

    return (
        <LyricsViewport style={{ flex: 1 }} animatedProps={mask} onPress={onRevealControls}>
            <ScrollView style={{ flex: 1 }} onScroll={event => {
                const offset = event.y;
                if (offset < previousOffset.current - 1) onRevealControls();
                previousOffset.current = offset;
            }}>
                {lyrics.Lines.map((line, index) => {
                    const display = linePresentation(line.Text, line.TranslatedText, line.RomanizedText, presentation);
                    return <View key={index} onPress={onRevealControls} style={{ paddingHorizontal: 28, paddingVertical: 14 }}>
                        <Text style={{ fontFamily: LyricsFontFamily, fontSize: 32, color: '#BFFFFFFF' }}>{display.lead}</Text>
                        {display.secondary && <Text style={{ fontFamily: LyricsFontFamily, fontSize: 18, color: '#BFFFFFFF', marginTop: 3 }}>{display.secondary}</Text>}
                        {display.pronunciation && <Text style={{ fontFamily: LyricsFontFamily, fontSize: 18, color: '#BFFFFFFF', marginTop: 3 }}>{display.pronunciation}</Text>}
                    </View>;
                })}
            </ScrollView>
        </LyricsViewport>);
};

const SyncedLyricsView = ({ lyrics, ...props }: React.ComponentProps<typeof ScrollingLyrics>) => {
    const [identity, setIdentity] = useState({ lyrics, generation: 0 });
    if (identity.lyrics !== lyrics) {
        setIdentity({ lyrics, generation: identity.generation + 1 });
    }

    return <ScrollingLyrics key={identity.generation} lyrics={lyrics} {...props} />;
};

export default LyricsView;
