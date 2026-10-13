
import React, { useEffect, useRef, useState } from 'react';
import { Pressable, Image, createNativeComponent, CommonViewProps, Text, View } from 'spotifyplus/react';
import { SpotifyPlus, AndroidBackButtonEvent } from 'spotifyplus';
import Animated, { Easing, SharedValue, useAnimatedStyle, useSharedValue, withTiming } from 'spotifyplus/react/reanimated';
import { nativeFadeStyle } from './native-fade';

const VectorIcon = createNativeComponent<CommonViewProps & { name: 'globe' | 'subtitle' }>('LyricsTranslationIcon');
const translateIcon = SpotifyPlus.Assets.image('assets/images/translate.png');

export function TranslationIcon({ name }: { name: 'globe' | 'subtitle' | 'translate' }) {
    return name === 'translate'
        ? <Image pointerEvents="none" source={translateIcon} resizeMode="contain" style={{ width: 28, height: 28 }} />
        : <VectorIcon pointerEvents="none" name={name} style={{ width: 24, height: 24 }} />;
}

interface Props {
    controlsVisible: boolean; controlsOpacity: SharedValue<number>; controlsHeight: SharedValue<number>;
    reducedMotion: boolean; onHold: () => void; onRelease: () => void;
    supported: boolean; translation: boolean; pronunciation: boolean;
    translating: boolean; romanizing: boolean; error?: string;
    onTranslation: () => void; onPronunciation: () => void;
}

export default function TranslationMenu(props: Props) {
    const [visible, setVisible] = useState(false);
    const [open, setOpen] = useState(false);
    const progress = useSharedValue(0);
    const closeTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

    const close = () => {
        if (!open) return;

        setOpen(false);
        progress.value = withTiming(0, { duration: props.reducedMotion ? 0 : 160, easing: Easing.in(Easing.quad) });
        closeTimer.current = setTimeout(() => setVisible(false), props.reducedMotion ? 0 : 180);
    };

    const show = () => {
        if (closeTimer.current) clearTimeout(closeTimer.current);

        setVisible(true);
        setOpen(true);
        progress.value = withTiming(1, { duration: props.reducedMotion ? 0 : 240, easing: Easing.out(Easing.cubic) });
    };

    useEffect(() => () => {
        if (closeTimer.current) clearTimeout(closeTimer.current);
    }, []);

    useEffect(() => {
        if (!visible) return;

        const onBack = (event: AndroidBackButtonEvent) => { event.preventDefault(); close(); };

        SpotifyPlus.on('android.backPressed', onBack);

        return () => SpotifyPlus.off('android.backPressed', onBack);
    }, [visible, open]);

    useEffect(() => {
        if (visible) {
            props.onHold();
            return props.onRelease;
        }
    }, [visible, props.onHold, props.onRelease]);

    const controlsHeight = props.controlsHeight;
    const controlsOpacity = props.controlsOpacity;

    const buttonStyle = useAnimatedStyle(() => {
        'worklet';

        return { bottom: controlsHeight.value + 2, ...nativeFadeStyle(controlsOpacity.value) };
    });

    const animatedStyle = useAnimatedStyle(() => {
        'worklet';

        return {
            bottom: controlsHeight.value + 58,
            ...nativeFadeStyle(progress.value),
            transform: [{ translateY: (1 - progress.value) * 16 }, { scale: 0.92 + progress.value * 0.08 }]
        };
    });

    const row = (pronunciation: boolean) => {
        const active = pronunciation ? props.pronunciation : props.translation;
        const busy = pronunciation ? props.romanizing : props.translating;
        const disabled = !open || (pronunciation && !props.supported);
        const label = pronunciation ? 'Pronunciation' : 'Translation';

        return (
            <Pressable disabled={disabled} accessibilityLabel={`${active ? 'Hide' : 'Show'} ${label.toLowerCase()}`} onPress={pronunciation ? props.onPronunciation : props.onTranslation}
                style={({ pressed }) => ({
                    minHeight: 56,
                    flexDirection: 'row',
                    alignItems: 'center',
                    paddingHorizontal: 18,
                    borderRadius: 18,
                    backgroundColor: pressed ? '#25ffffff' : '#00000000',
                    opacity: disabled ? 0.5 : 1
                })}>
                <TranslationIcon name={pronunciation ? 'subtitle' : 'globe'} />
                <Text style={{ flex: 1, color: '#ffffff', fontSize: 17, marginLeft: 12 }}>
                    {busy ? `Loading ${label.toLowerCase()}…` : `${active ? 'Hide' : 'Show'} ${label}`}
                </Text>
                {active && <Text style={{ color: '#1ed760', fontSize: 20, marginLeft: 8 }}>✓</Text>}
            </Pressable>
        );
    };

    return (
        <View pointerEvents="box-none" style={{ position: 'absolute', top: 0, bottom: 0, left: 0, right: 0, elevation: 40 }}>
            <Animated.View pointerEvents={props.controlsVisible ? 'auto' : 'none'} style={[{ position: 'absolute', left: 28 }, buttonStyle]}>
                <Pressable accessibilityLabel="Lyrics translation options" onPress={show}
                    style={({ pressed }) => ({
                        width: 44,
                        height: 44,
                        borderRadius: 14,
                        alignItems: 'center',
                        justifyContent: 'center',
                        backgroundColor: pressed ? '#70ffffff' : '#26ffffff',
                        transform: [{ scale: pressed ? 0.95 : 1 }]
                    })}>
                    <TranslationIcon name="translate" />
                </Pressable>
            </Animated.View>

            {visible && (
                <View style={{ position: 'absolute', top: 0, bottom: 0, left: 0, right: 0 }}>
                    <Pressable accessibilityLabel="Close translation options" onPress={close} style={{ position: 'absolute', top: 0, bottom: 0, left: 0, right: 0 }} />

                    <Animated.View style={[{
                        position: 'absolute',
                        left: 24,
                        right: 24,
                        maxWidth: 360,
                        backgroundColor: '#ee2c2721',
                        borderRadius: 24,
                        paddingVertical: 6,
                        elevation: 42
                    }, animatedStyle]}>
                        {row(true)}
                        {row(false)}
                        {props.error && <Text style={{ color: '#ffcfbd', fontSize: 13, paddingHorizontal: 18, paddingBottom: 12 }}>{props.error}</Text>}
                    </Animated.View>
                </View>
            )}
        </View>
    );
}
