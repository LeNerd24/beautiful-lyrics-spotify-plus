import React, { useMemo } from 'react';
import { View, CommonViewProps } from 'spotifyplus/react';
import { SyllableList, SyllableVocal, SyllableVocalSet } from '../Types/lyrics-types';
import SyllableView from './syllable';
import LineView from './line';
import Spline from 'typescript-cubic-spline';
import { SharedValue } from 'spotifyplus/react/Animated';
import { LyricMotion } from '../Components/lyric-motion';
import { sampleMotion } from './native-motion';
import { AnimationStyle, appleFloatTrack } from './animation-style';
import { LyricsPresentation } from '../Lyrics/presentation';
import { isEmphasized } from '../Lyrics/word-alignment';

interface Props extends CommonViewProps {
    metadata: SyllableVocalSet; playbackMs: SharedValue<number>;
    animationStyle: AnimationStyle; presentation?: LyricsPresentation;
}

const wordStyle = { flexDirection: 'row', flexWrap: 'wrap', maxWidth: '100%' } as const;
const restingWordTrack = sampleMotion(0, 0, () => [0, 0, 0, 0, 1, 1]);
const scaleSpline = new Spline([0, 0.7, 1], [1, 1.03, 1]);
const yOffsetSpline = new Spline([0, 0.9, 1], [1 / 100, -1 / 60, 0]);
const glowSpline = new Spline([0, 0.15, 0.6, 1], [0, 1, 1, 0]);

const PendingVocalRow = React.memo(({ vocal, opposite, small, playbackMs }: { vocal: SyllableVocal; opposite: boolean; small: boolean; playbackMs: SharedValue<number> }) => {
    const line = useMemo(() => ({ Type: 'Vocal' as const, OppositeAligned: opposite, StartTime: vocal.StartTime, EndTime: vocal.EndTime, Text: vocal.Syllables[0]?.Text ?? '', RomanizedText: vocal.Syllables[0]?.RomanizedText }), [vocal, opposite]);
    return <View style={{ width: '100%', marginTop: small ? 3 : 0 }}><LineView line={line} playbackMs={playbackMs} small={small} inheritOpacity style={{ alignSelf: opposite ? 'flex-end' : 'flex-start' }} /></View>;
});

export const VocalRow = ({ syllables, playbackMs, animationStyle, small = false, opposite = false }: { syllables: SyllableList; playbackMs: SharedValue<number>; animationStyle: AnimationStyle; small?: boolean; opposite?: boolean; }) => {
    const words = useMemo(() => {
        const groups: SyllableList[] = [];
        let group: SyllableList = [];

        for (const syllable of syllables) {
            group.push(syllable);
            if (!syllable.IsPartOfWord) { groups.push(group); group = []; }
        }

        if (group.length) groups.push(group);

        return groups;
    }, [syllables]);

    const floatTracks = useMemo(() => animationStyle === 'apple' ? words.map(word => appleFloatTrack(word[0].StartTime, word[word.length - 1].EndTime, small ? 18 : 36, small)) : [], [animationStyle, small, words]);

    return <View style={{ width: '100%', flexWrap: 'wrap', flexDirection: 'row', justifyContent: opposite ? 'flex-end' : 'flex-start', marginTop: small ? 3 : 0 }}>
        {words.map((word, index) => <LyricMotion key={index} motionTrack={animationStyle === 'apple' ? floatTracks[index] : restingWordTrack} style={wordStyle}>
            {word.map((syllable, i) => (
                <SyllableView key={i} syllable={syllable} emphasized={isEmphasized(syllable)}
                    animationStyle={animationStyle} isLastWord={index === words.length - 1} isBackground={small}
                    playbackMs={playbackMs} scaleSpline={scaleSpline} yOffsetSpline={yOffsetSpline} glowSpline={glowSpline} />))}
        </LyricMotion>)}
    </View>;
};

const SyllableVocalLine = ({ metadata, playbackMs, animationStyle, presentation, style }: Props) => {
    const lines = [metadata.Lead, ...(metadata.Background ?? [])];

    const startMs = Math.min(...lines.map(v => v.StartTime)) * 1000;
    const endMs = Math.max(...lines.map(v => v.EndTime)) * 1000;

    const pronunciation = presentation?.pronunciation && metadata.Lead.Pronunciation?.length ? metadata.Lead.Pronunciation : undefined;
    const replaced = !!pronunciation && !!presentation?.pronunciationAsLead;
    const original = replaced ? pronunciation! : metadata.Lead.Syllables;

    const translated = presentation?.translation && metadata.Lead.Translation?.length ? metadata.Lead.Translation : undefined;
    const hide = !!translated && !!presentation?.hideOriginalLyrics;
    const swap = !!translated && (!!presentation?.swapTranslatedLines || hide);

    const row = (syllables: SyllableList, small: boolean, key: string) => <VocalRow key={key} syllables={syllables} playbackMs={playbackMs} animationStyle={animationStyle} small={small} opposite={metadata.OppositeAligned} />;

    return <LyricMotion opacityRange={[startMs, endMs]} style={[{ flex: 1, paddingLeft: metadata.OppositeAligned ? 35 : 25, paddingRight: metadata.OppositeAligned ? 25 : 35, alignItems: metadata.OppositeAligned ? 'flex-end' : 'flex-start' }, style]}>
        {metadata.Lead.Syllables.length > 0 && (metadata.Pending ? <PendingVocalRow key='pending-lead' vocal={metadata.Lead} opposite={metadata.OppositeAligned} small={false} playbackMs={playbackMs} /> : row(swap ? translated! : original, false, 'lead'))}
        {translated && !hide && row(swap ? original : translated, true, 'secondary')}
        {pronunciation && !replaced && !hide && row(pronunciation, true, 'pronunciation')}
        {!hide && metadata.Background?.map((v, i) => v.Pending ? <PendingVocalRow key={`pending-background-${i}`} vocal={v} opposite={metadata.OppositeAligned} small playbackMs={playbackMs} /> : row(v.Syllables, true, `background-${i}`))}
    </LyricMotion>;
};

export default React.memo(SyllableVocalLine);
