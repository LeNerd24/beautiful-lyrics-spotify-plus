import React, { useCallback, useEffect, useSyncExternalStore } from 'react';
import { Text, View } from 'spotifyplus/react';
import Animated, { useAnimatedStyle, useReducedMotion, useSharedValue, withTiming } from 'spotifyplus/react/Animated';
import { GenerationState, generationStore } from '../Lyrics/generation-state';

export function TrackGenerationProgress({ trackId }: { trackId?: string }) {
    const state = useSyncExternalStore(generationStore.subscribe, () => generationStore.get(trackId));
    return state && state.status !== 'complete' ? <GenerationProgress key={state.sessionId} state={state} /> : null;
}

export default function GenerationProgress({ state }: { state: GenerationState }) {
    const total = Math.max(0, state.total), completed = Math.max(0, Math.min(total, state.completed));
    const fraction = total > 0 ? completed / total : 0;
    const fill = useSharedValue(fraction);
    const reducedMotion = useReducedMotion();
    useEffect(() => {
        fill.value = withTiming(fraction, { duration: reducedMotion ? 0 : 220 });
    }, [fraction, reducedMotion, fill]);

    const fillStyle = useAnimatedStyle(useCallback(() => {
        'worklet';
        return { width: `${Math.max(0, Math.min(1, fill.value)) * 100}%` };
    }, [fill]));
    const message = state.status === 'working' && state.stage === 'generating' ? 'Generating lyrics' : state.message;

    return <View style={{ paddingHorizontal: 28, paddingBottom: 12 }}>
        <Text style={{ color: '#BFFFFFFF', fontSize: 13, marginBottom: total > 0 ? 8 : 0 }}>{message}</Text>
        {total > 0 && <View accessibilityLabel={`${completed} of ${total} lyric lines generated`} style={{ flexDirection: 'row', alignItems: 'center' }}>
            <Text style={{ color: '#DFFFFFFF', fontSize: 12, minWidth: 26 }}>{completed}</Text>
            <View style={{ flex: 1, height: 3, borderRadius: 2, backgroundColor: '#30FFFFFF', overflow: 'hidden', marginHorizontal: 8 }}>
                <Animated.View style={[{ height: 3, borderRadius: 2, backgroundColor: '#E6FFFFFF' }, fillStyle]} />
            </View>
            <Text style={{ color: '#BFFFFFFF', fontSize: 12, minWidth: 26, textAlign: 'right' }}>{total}</Text>
        </View>}
    </View>;
}
