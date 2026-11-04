import React, { useEffect, useRef } from 'react';
import { CommonViewProps, Text, TouchableOpacity, View, SliderProps, createNativeComponent } from 'spotifyplus/react';
import Animated, { SharedValue, useAnimatedProps, useSharedValue } from 'spotifyplus/react/Animated';
import { SpotifyPlus } from 'spotifyplus';
import { GestureDetector } from 'spotifyplus/react/Gesture';
import { formatPlaybackTime } from './player-model';

export interface LyricsInteractionProps {
    controlsShown: SharedValue<boolean>; controlsOpacity: SharedValue<number>; controlsHeight: SharedValue<number>; alignPosition?: number; lineBlur?: number;
    onRevealControls: () => void; onInteractionStart: () => void; onInteractionEnd: () => void; onSeekInteraction: () => void;
}

export const LyricsIcon = createNativeComponent<CommonViewProps & { name: 'heart' | 'more' | 'previous' | 'play' | 'pause' | 'next'; filled?: boolean }>('LyricsIcon');
export const LyricsInset = createNativeComponent<CommonViewProps & { edge: 'top' | 'bottom' | 'left' | 'right' }>('LyricsInset');
export const LyricsWindow = createNativeComponent<CommonViewProps & { landscape?: boolean }>('LyricsWindow');
export const LyricsSheetRoot = Animated.createAnimatedComponent(createNativeComponent<CommonViewProps & { headerHeight?: number }>('LyricsSheetRoot'));
export const LyricsTitle = createNativeComponent<CommonViewProps & { text: string; centered?: boolean }>('LyricsTitle');
export const LyricsViewport = Animated.createAnimatedComponent(createNativeComponent<CommonViewProps & { controlsMask?: readonly [opacity: number, height: number]; }>('LyricsViewport'));

const AnimatedSlider = Animated.createAnimatedComponent(createNativeComponent<SliderProps>('LyricsSeekBar'));
const AnimatedText = Animated.createAnimatedComponent(Text);

export function ChromeButton({ name, label, onPress, filled = false, compact = false, dense = false, gesture }: { name: React.ComponentProps<typeof LyricsIcon>['name']; label: string; onPress: () => void; filled?: boolean; compact?: boolean; dense?: boolean; gesture?: React.ComponentProps<typeof GestureDetector>['gesture']; }) {
    const large = name === 'play' || name === 'pause';
    const iconSize = compact ? 19 : dense ? (large ? 30 : 26) : large ? 40 : 34;
    const button = (
        <TouchableOpacity accessibilityLabel={label} onPress={onPress} activeOpacity={0.6} style={{ width: compact ? 44 : dense ? 56 : 76, height: compact || dense ? 44 : 72, justifyContent: 'center', alignItems: 'center' }}>
            <View style={{ width: compact ? 28 : dense ? 32 : 42, height: compact ? 28 : dense ? 32 : 42, borderRadius: compact ? 14 : 0, backgroundColor: compact ? '#26FFFFFF' : '#00FFFFFF', alignItems: 'center', justifyContent: 'center' }}>
                <LyricsIcon name={name} filled={filled} style={{ width: iconSize, height: iconSize }} />
            </View>
        </TouchableOpacity>);

    return gesture ? <GestureDetector gesture={gesture}>{button}</GestureDetector> : button;
}

export function PlayerControls({ playbackMs, durationMs, paused, landscape = false, onInteract, onHold, onRelease }: { playbackMs: SharedValue<number>; durationMs: number; paused: boolean; landscape?: boolean; onInteract: () => void; onHold: () => void; onRelease: () => void; }) {
    const scrubbing = useSharedValue(false);
    const preview = useSharedValue(0);
    const releaseRef = useRef(onRelease);
    releaseRef.current = onRelease;

    useEffect(() => () => {
        if (scrubbing.value) {
            scrubbing.value = false;
            releaseRef.current();
        }
    }, [scrubbing, landscape]);

    const progressProps = useAnimatedProps(() => {
        'worklet';
        return { progress: Math.max(0, Math.min(durationMs, scrubbing.value ? preview.value : playbackMs.value)) };
    });

    const elapsedProps = useAnimatedProps(() => {
        'worklet';
        return { text: formatPlaybackTime(scrubbing.value ? preview.value : playbackMs.value) };
    });

    const slider = (
        <AnimatedSlider accessibilityLabel="Playback position" min={0} max={Math.max(1, durationMs)}
            progressTintColor="#CFFFFFFF" progressBackgroundTintColor="#40FFFFFF" thumbTintColor="#00FFFFFF"
            splitTrack={false} style={{ width: '100%', height: 28 }} animatedProps={progressProps}
            onSlidingStart={value => { preview.value = value; scrubbing.value = true; onHold(); }}
            onValueChange={value => { preview.value = value; }}
            onSlidingComplete={value => {
                try { SpotifyPlus.Player.seek(value); } finally { scrubbing.value = false; onRelease(); }
            }} />);

    const elapsed = <AnimatedText animatedProps={elapsedProps} text="0:00" style={{ fontSize: 11, color: '#99FFFFFF', minWidth: 32 }} />;
    const duration = <Text style={{ fontSize: 11, color: '#99FFFFFF', minWidth: 32, textAlign: 'right' }}>{formatPlaybackTime(durationMs)}</Text>;
    const transport = <>
        <ChromeButton name="previous" label="Previous track" dense={landscape} onPress={() => { onInteract(); SpotifyPlus.Player.skipPrevious(); }} />
        <ChromeButton name={paused ? 'play' : 'pause'} label={paused ? 'Play' : 'Pause'} dense={landscape}
            onPress={() => { onInteract(); SpotifyPlus.Player.togglePlay(); }} />
        <ChromeButton name="next" label="Next track" dense={landscape} onPress={() => { onInteract(); SpotifyPlus.Player.skipNext(); }} />
    </>;

    if (landscape) {
        return (
            <View style={{ paddingHorizontal: 16 }} onPress={onInteract}>
                {slider}

                <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingHorizontal: 15 }}>
                    {elapsed}
                    <View style={{ flexDirection: 'row', alignItems: 'center' }}>{transport}</View>
                    {duration}
                </View>

                <LyricsInset edge="bottom" style={{ width: '100%' }} />
            </View>);
    }

    return (
        <View style={{ paddingHorizontal: 28, paddingTop: 18, paddingBottom: 38 }} onPress={onInteract}>
            {slider}

            <View style={{ flexDirection: 'row', justifyContent: 'space-between', paddingHorizontal: 1, marginTop: 0 }}>
                {elapsed}
                {duration}
            </View>

            <View style={{ flexDirection: 'row', justifyContent: 'space-around', alignItems: 'center', marginTop: 22 }}>
                {transport}
            </View>

            <LyricsInset edge="bottom" style={{ width: '100%' }} />
        </View>);
}
