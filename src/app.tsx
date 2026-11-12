import React, { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore } from 'react';
import { CommonViewProps, Image, createNativeComponent, Text, View } from 'spotifyplus/react';
import Animated, { measure, useAnimatedStyle, useFrameCallback, usePlaybackClock, useReducedMotion, useSharedValue, withTiming } from 'spotifyplus/react/Animated';
import { SpotifyTrack } from 'spotifyplus/entities';
import { ExtensionSetting, SpotifyPlus } from 'spotifyplus';
import { createLyricsTransformer, TransformedLyrics, transformLyrics } from './Lyrics/lyric-utilities';
import LyricsView from './Lyrics/lyrics-view';
import LyricsSkeleton from './Components/lyrics-skeleton';
import { AnimationStyle } from './Entities/animation-style';
import { ChromeButton, LyricsInset, LyricsTitle, PlayerControls } from './Components/player-chrome';
import { CONTROLS_FADE_MS, ControlsVisibility, LANDSCAPE_ACTIONS_HEIGHT, LINE_BLUR_KEY, LyricsViewportSize, isLandscape, landscapeLayout, lineBlurRadius, lineBlurSetting } from './Components/player-model';
import { LANDSCAPE_ALIGN_POSITION } from './Lyrics/scroll-model';
import { nativeFadeStyle, restingFade } from './Components/native-fade';
import { GestureDetector } from 'spotifyplus/react/Gesture';
import { SingControl } from './Components/sing-control';
import TranslationMenu from './Components/translation-menu';
import { useAutoGenerate, useLyricsSettings } from './settings';
import { sameLanguage, supportsPronunciation, targetLanguage } from './Lyrics/languages';
import { useTranslation } from './Lyrics/use-translation';
import { generationStore, setGenerationSource, startGeneration } from './Lyrics/generation-state';
import { TrackGenerationProgress } from './Components/generation-progress';
import { waitForCurrentTrack } from './Lyrics/current-track';
import { createAbortController } from './Lyrics/platform-utils';
import { getDisplaySettings, useDisplaySettings } from './display-settings';
import { Lyrics } from './Types/lyrics-types';

const AUTO_GENERATE_DELAY_MS = 5000;

const AnimatedBackground = createNativeComponent<CommonViewProps & { imageUrl?: string }>('AnimatedBackground');

const App = ({ headerRef, headerGesture, viewport }: { headerRef?: React.RefObject<View | null>; headerGesture?: React.ComponentProps<typeof GestureDetector>['gesture']; viewport?: LyricsViewportSize } = {}) => {
    const landscape = !!viewport && isLandscape(viewport);
    const wide = landscape ? landscapeLayout(viewport) : undefined;
    const displaySettings = useDisplaySettings();
    const [providerLyrics, setProviderLyrics] = useState<{ trackId: string; raw: Lyrics; transformed: TransformedLyrics; interludeDuration: number }>();
    const loadedLyrics = useMemo(() => {
        if (!providerLyrics || providerLyrics.interludeDuration === displaySettings.interludeDuration) return providerLyrics?.transformed;
        return transformLyrics(providerLyrics.raw, displaySettings.interludeDuration);
    }, [providerLyrics, displaySettings.interludeDuration]);
    const [track, setTrack] = useState<SpotifyTrack>();
    const generationLyrics = useSyncExternalStore(generationStore.subscribe, () => generationStore.getLyrics(track?.id));
    const transformGeneration = useMemo(() => createLyricsTransformer(), []);
    const lyrics = useMemo(() => generationLyrics ? transformGeneration(generationLyrics) : loadedLyrics, [generationLyrics, loadedLyrics, transformGeneration]);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>();
    const [songRevision, setSongRevision] = useState(0);
    const expectedTrackUri = useRef<string | undefined>(undefined);
    const [paused, setPaused] = useState(() => SpotifyPlus.Player.getState().isPaused);
    const [localPlayback, setLocalPlayback] = useState(false);
    const [liked, setLiked] = useState(false);
    const [animationStyle, setAnimationStyle] = useState<AnimationStyle>(() => SpotifyPlus.Platform.Storage.get('lyrics-animation-style') === 'apple' ? 'apple' : 'beautiful');
    const [lineBlur, setLineBlur] = useState(() => lineBlurSetting(SpotifyPlus.Platform.Storage.get(LINE_BLUR_KEY)));
    const [controlsVisible, setControlsVisible] = useState(paused);
    const translationSettings = useLyricsSettings();
    const autoGenerate = useAutoGenerate();
    let target: string | undefined;

    try {
        target = targetLanguage(translationSettings.translationLanguage);
    } catch (_) { }

    const translated = useTranslation(lyrics, target);
    const presentation = useMemo(() => ({ ...translationSettings, translation: translated.translation, pronunciation: translated.pronunciation }), [translationSettings, translated.translation, translated.pronunciation]);
    const canTranslate = !!lyrics && !(lyrics.Type === 'Syllable' && lyrics.Partial) && (!target || !sameLanguage(lyrics.language, target));

    const likedRef = useRef(false);
    const panel = useRef<View | null>(null);
    const densityProbe = useRef<View | null>(null);

    const controlsShown = useSharedValue(paused);
    const controlsOpacity = useSharedValue(paused ? 1 : 0);
    const controlsHeight = useSharedValue(210);
    const actionsHeight = useSharedValue(0);

    const playbackMs = usePlaybackClock({ unit: 'ms' });
    const reducedMotion = useReducedMotion();

    const panelMeasure = useMemo(() => ({ last: -1 }), []);
    const visibility = useMemo(() => new ControlsVisibility(paused, visible => {
        controlsShown.value = visible;
        setControlsVisible(visible);
    }), [controlsShown]);

    const updateLiked = useCallback((value: boolean) => { likedRef.current = value; setLiked(value); }, []);
    const revealControls = useCallback(() => visibility.reveal(), [visibility]);
    const holdControls = useCallback(() => visibility.hold(), [visibility]);
    const releaseControls = useCallback(() => visibility.release(), [visibility]);
    const holdSingControls = useCallback(() => { visibility.hold(); visibility.reveal(); }, [visibility]);
    const seekInteraction = useCallback(() => visibility.interact(), [visibility]);

    useEffect(() => {
        controlsOpacity.value = withTiming(controlsVisible ? 1 : 0, { duration: reducedMotion ? 0 : CONTROLS_FADE_MS });
    }, [controlsVisible, controlsOpacity, reducedMotion]);

    useEffect(() => { actionsHeight.value = landscape ? LANDSCAPE_ACTIONS_HEIGHT : 0; }, [landscape, actionsHeight]);
    useEffect(() => { visibility.setPaused(paused); }, [paused, visibility]);
    useLayoutEffect(() => {
        visibility.activate();
        return () => visibility.dispose();
    }, [visibility]);

    useEffect(() => {
        let retry: ReturnType<typeof setTimeout> | undefined;

        const updateDevice = () => {
            clearTimeout(retry);

            try {
                setLocalPlayback(SpotifyPlus.Connect.getCurrentDevice()?.isLocal === true);
            } catch (_) {
                setLocalPlayback(false);
                retry = setTimeout(updateDevice, 500);
            }
        };

        SpotifyPlus.Events.on('deviceChanged', updateDevice);
        updateDevice();

        return () => {
            clearTimeout(retry);
            SpotifyPlus.Events.off('deviceChanged', updateDevice);
        };
    }, []);

    useEffect(() => {
        const playPause = (event: { isPaused: boolean }) => { visibility.setPaused(event.isPaused); setPaused(event.isPaused); };
        const songChanged = (event: { uri?: string | null }) => {
            expectedTrackUri.current = event.uri ?? undefined;
            setSongRevision(value => value + 1);
        };

        SpotifyPlus.Events.on('playPause', playPause);
        SpotifyPlus.Events.on('songChanged', songChanged);

        return () => {
            SpotifyPlus.Events.off('playPause', playPause);
            SpotifyPlus.Events.off('songChanged', songChanged);
        };
    }, [visibility]);

    useEffect(() => {
        const settingChanged = (payload: unknown) => {
            const setting = payload as ExtensionSetting | undefined;

            if (setting?.id === 'lyrics-animation-style' && setting.type === 'select') {
                setAnimationStyle(setting.value === 'apple' ? 'apple' : 'beautiful');
            }

            if (setting?.id === LINE_BLUR_KEY && setting.type === 'slider') setLineBlur(lineBlurSetting(setting.value));
        };

        const styleChanged = (style: unknown) => setAnimationStyle(style === 'apple' ? 'apple' : 'beautiful');
        SpotifyPlus.Events.on('lyrics.animationStyleChanged', styleChanged);
        SpotifyPlus.Events.on('settings.changed', settingChanged);

        return () => {
            SpotifyPlus.Events.off('settings.changed', settingChanged);
            SpotifyPlus.Events.off('lyrics.animationStyleChanged', styleChanged);
        };
    }, []);

    const disableAi = displaySettings.disableAi;

    useEffect(() => {
        let cancelled = false;
        const controller = createAbortController();

        const loadLyrics = async () => {
            setLoading(true); setError(undefined); setProviderLyrics(undefined); setTrack(undefined);

            try {
                const current = await waitForCurrentTrack(() => SpotifyPlus.Player.getCurrentTrack(), controller.signal, expectedTrackUri.current, () => {
                    if (cancelled) return;
                    setLoading(false);
                    setError('Waiting for the current track…');
                });
                if (cancelled) return;

                setLoading(true); setError(undefined);
                setTrack(current);

                try {
                    updateLiked(SpotifyPlus.Library.isLiked(current));
                } catch (e) {
                    console.log('Could not read liked state', String(e)); updateLiked(false);
                }

                const response = await fetch(`https://spotifyplus-api.devon-shoutz.workers.dev/api/lyrics/${current.id}${disableAi ? '?ai=0' : ''}`, { signal: controller.signal });
                if (!response.ok) throw new Error('Lyrics are unavailable for this song');

                const raw = await response.json() as Lyrics;
                const interludeDuration = getDisplaySettings().interludeDuration;
                const transformed = transformLyrics(raw, interludeDuration);
                if (cancelled) return;

                setProviderLyrics({ trackId: current.id, raw, transformed, interludeDuration });
            } catch (e) {
                if (!cancelled) setError((e as Error).message);
            } finally {
                if (!cancelled) setLoading(false);
            }
        };

        loadLyrics();

        return () => { cancelled = true; controller.abort(); };
    }, [songRevision, updateLiked, disableAi]);

    useEffect(() => {
        if (providerLyrics && loadedLyrics) setGenerationSource(providerLyrics.trackId, loadedLyrics);
    }, [providerLyrics, loadedLyrics]);

    useEffect(() => {
        const trackId = track?.id;
        if (!autoGenerate || !trackId || loadedLyrics?.Type !== 'Line' || generationStore.get(trackId)) return;

        let unsubscribe: (() => void) | undefined;

        const attempt = () => {
            if (SpotifyPlus.Player.getCurrentTrack()?.id !== trackId || generationStore.get(trackId)) return unsubscribe?.();
            if (generationStore.busy()) return;

            unsubscribe?.();
            startGeneration(trackId);
        };

        const timer = setTimeout(() => {
            unsubscribe = generationStore.subscribe(attempt);
            attempt();
        }, AUTO_GENERATE_DELAY_MS);

        return () => {
            clearTimeout(timer);
            unsubscribe?.();
        };
    }, [autoGenerate, track?.id, loadedLyrics]);

    useFrameCallback(useCallback(frame => {
        'worklet';

        if (frame.timestamp - panelMeasure.last < 250) return;
        panelMeasure.last = frame.timestamp;

        const size = measure(panel), probe = measure(densityProbe);
        if (size && probe && probe.width > 0 && size.height > 0) controlsHeight.value = size.height / (probe.width / 100) + actionsHeight.value;
    }, [actionsHeight, controlsHeight, panelMeasure]));

    // Stable updaters keep these styles registered across renders instead of being torn down and re-registered each time
    const panelStyle = useAnimatedStyle(useCallback(() => {
        'worklet';

        return nativeFadeStyle(controlsOpacity.value);
    }, [controlsOpacity]));
    const singStyle = useAnimatedStyle(useCallback(() => {
        'worklet';
        return { bottom: Math.max(0, controlsHeight.value - actionsHeight.value - 4), ...nativeFadeStyle(controlsOpacity.value) };
    }, [actionsHeight, controlsHeight, controlsOpacity]));
    const actionsStyle = useAnimatedStyle(useCallback(() => {
        'worklet';
        return { bottom: controlsHeight.value - actionsHeight.value + 2, ...nativeFadeStyle(controlsOpacity.value) };
    }, [actionsHeight, controlsHeight, controlsOpacity]));
    const controlsFade = restingFade(controlsVisible);
    const infoStyle = useAnimatedStyle(() => {
        'worklet';
        const panelHeight = controlsHeight.value - actionsHeight.value;
        return { translateY: -controlsOpacity.value * panelHeight / 2 };
    });

    const toggleLike = () => {
        if (!track) return;

        try {
            const next = !likedRef.current;
            if (next) SpotifyPlus.Library.like(track); else SpotifyPlus.Library.unlike(track);

            updateLiked(next);
        } catch (e) { SpotifyPlus.toast('Could not update liked songs'); }
    };

    const openContextMenu = async () => {
        try {
            await SpotifyPlus.ContextMenu.openNowPlaying();
        } catch (e) {
            if (track) {
                try { await SpotifyPlus.ContextMenu.open(track); } catch (_) { SpotifyPlus.toast('Could not open the song menu'); }
            }
        } finally {
            if (track) {
                try { updateLiked(SpotifyPlus.Library.isLiked(track)); } catch (_) { }
            }
        }
    };

    const draggableHeader = (element: React.ReactElement) => headerGesture ? <GestureDetector gesture={headerGesture}>{element}</GestureDetector> : element;

    const header = wide ? (
        <View onPress={revealControls} style={{ width: wide.infoWidth, position: 'relative' }}>
            <Animated.View style={[{ flex: 1, alignItems: 'center', justifyContent: 'center' }, infoStyle]}>
                {track && <>
                    <Image source={{ uri: track.album.image }} onPress={revealControls} style={{ width: wide.artSize, height: wide.artSize, borderRadius: 6 }} />
                    <LyricsTitle text={track.title} centered accessibilityLabel={track.title} style={{ width: wide.infoWidth - 40, height: 22, fontSize: 15, fontWeight: '600', color: '#FFFFFF', marginTop: 10 }} />
                    <Text style={{ width: wide.infoWidth - 40, color: '#BFFFFFFF', fontSize: 13, marginTop: 1, textAlign: 'center', includeFontPadding: false }} numberOfLines={1}>{track.artist}</Text>
                </>}
            </Animated.View>
        </View>
    ) : (
        <View ref={headerRef}>
            <LyricsInset edge='top' style={{ width: '100%' }} />

            {draggableHeader(<View style={{ alignItems: 'center', paddingTop: 8, paddingBottom: 18 }} onPress={revealControls}>
                <View style={{ width: 32, height: 3, borderRadius: 2, backgroundColor: '#66FFFFFF' }} />
            </View>)}

            {track && <View style={{ paddingHorizontal: 24, paddingBottom: 24, flexDirection: 'row', alignItems: 'center' }}>
                {draggableHeader(<Image source={{ uri: track.album.image }} onPress={revealControls} style={{ width: 64, height: 64, borderRadius: 5 }} />)}
                {draggableHeader(
                    <View onPress={revealControls} style={{ flex: 1, minWidth: 0, marginLeft: 12, marginRight: 4, overflow: 'hidden' }}>
                        <LyricsTitle text={track.title} accessibilityLabel={track.title} style={{ width: '100%', height: 24, fontSize: 17, fontWeight: '600', color: '#FFFFFF' }} />
                        <Text style={{ color: '#BFFFFFFF', fontSize: 14, marginTop: 2, includeFontPadding: false }} numberOfLines={1}>{track.artist}</Text>
                    </View>)}

                <ChromeButton name='heart' label={liked ? 'Unlike song' : 'Like song'} compact filled={liked} onPress={toggleLike} gesture={headerGesture} />
                <ChromeButton name='more' label='Now Playing context menu' compact onPress={openContextMenu} gesture={headerGesture} />
            </View>}
        </View>);

    return (
        <View style={{ flex: 1, position: 'relative' }}>
            <AnimatedBackground imageUrl={track?.album.image} style={{ position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, elevation: 0 }} />

            <View style={{ flex: 1, flexDirection: 'row' }}>
                <LyricsInset edge='left' style={{ height: '100%' }} />

                <View style={{ flex: 1, minWidth: 0, position: 'relative' }}>
                    <View style={{ flex: 1, flexDirection: landscape ? 'row' : 'column' }}>
                        {headerGesture ? <GestureDetector gesture={headerGesture}>{header}</GestureDetector> : header}

                        <View style={{ flex: 1, minWidth: 0 }}>
                            <TrackGenerationProgress trackId={track?.id} />

                            <View style={{ flex: 1, position: 'relative' }}>
                                <View ref={densityProbe} pointerEvents='none' style={{ position: 'absolute', top: 0, width: 100, height: 0 }} />

                                {lyrics ? (
                                    <LyricsView key={`${track?.id}:${generationLyrics?.GenerationId ?? 'provider'}`} lyrics={translated.lyrics} presentation={presentation} animationStyle={animationStyle} alignPosition={landscape ? LANDSCAPE_ALIGN_POSITION : undefined} lineBlur={lineBlurRadius(lineBlur)} controlsShown={controlsShown} controlsOpacity={controlsOpacity} controlsHeight={controlsHeight} onRevealControls={revealControls} onInteractionStart={holdControls} onInteractionEnd={releaseControls} onSeekInteraction={seekInteraction} />)
                                    : <View onPress={revealControls} style={{ flex: 1, justifyContent: 'center', paddingHorizontal: 28 }}>
                                        {loading ? <LyricsSkeleton /> : <Text style={{ fontSize: 18, color: '#BFFFFFFF' }}>{error}</Text>}
                                    </View>}
                            </View>
                        </View>
                    </View>

                    <Animated.View ref={panel} pointerEvents={controlsVisible ? 'auto' : 'none'} style={[{ position: 'absolute', left: 0, right: 0, bottom: 0, elevation: 30 }, panelStyle, controlsFade]}>
                        <PlayerControls key={track?.uri} landscape={landscape} playbackMs={playbackMs} durationMs={track?.durationMs ?? 0} paused={paused} onInteract={revealControls} onHold={() => { holdControls(); revealControls(); }} onRelease={releaseControls} />
                    </Animated.View>

                    {landscape && track && <Animated.View pointerEvents={controlsVisible ? 'auto' : 'none'} style={[{ position: 'absolute', right: 12, flexDirection: 'row', elevation: 31 }, actionsStyle, controlsFade]}>
                        <ChromeButton name='heart' label={liked ? 'Unlike song' : 'Like song'} compact filled={liked} onPress={toggleLike} />
                        <ChromeButton name='more' label='Now Playing context menu' compact onPress={openContextMenu} />
                    </Animated.View>}

                    {localPlayback && <Animated.View pointerEvents={controlsVisible ? 'auto' : 'none'} style={[{ position: 'absolute', right: landscape ? 96 : 22, elevation: 31 }, singStyle, controlsFade]}>
                        <SingControl visible={controlsVisible} reducedMotion={reducedMotion} onHold={holdSingControls} onRelease={releaseControls} />
                    </Animated.View>}

                    {canTranslate && <TranslationMenu key={`${track?.uri}:${target}`} supported={supportsPronunciation(lyrics!.language)}
                        controlsVisible={controlsVisible} controlsOpacity={controlsOpacity} controlsHeight={controlsHeight}
                        actionsHeight={actionsHeight} actionsRight={landscape ? (localPlayback ? 152 : 106) : undefined}
                        reducedMotion={reducedMotion} onHold={holdSingControls} onRelease={releaseControls}
                        translation={translated.translation} pronunciation={translated.pronunciation}
                        translating={translated.translating} romanizing={translated.romanizing}
                        error={!target ? 'Enter a supported translation language code in settings, such as en or fr.' : translated.error}
                        onTranslation={translated.toggleTranslation} onPronunciation={translated.togglePronunciation} />}
                </View>

                <LyricsInset edge='right' style={{ height: '100%' }} />
            </View>
        </View>);
};

export default App;
