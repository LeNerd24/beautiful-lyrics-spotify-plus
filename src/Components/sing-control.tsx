
import React, { useCallback, useEffect, useRef, useState } from 'react';
import { CommonViewProps, createNativeComponent } from 'spotifyplus/react';
import { SpotifyPlus } from 'spotifyplus';
import Animated, { useAnimatedProps, useFrameCallback, useSharedValue } from 'spotifyplus/react/Animated';
import { Sing, SingState } from '../Entities/sing';

const NativeSingControl = Animated.createAnimatedComponent(createNativeComponent<CommonViewProps & {
    controlsVisible: boolean; reducedMotion: boolean;
    iconAnimation?: readonly number[];
}>('LyricsSingControl'));

export function SingControl({ visible, reducedMotion, onHold, onRelease }: { visible: boolean; reducedMotion: boolean; onHold: () => void; onRelease: () => void; }) {
    const [active, setActive] = useState(false);
    const wasActive = useRef(false);
    const elapsed = useSharedValue(0);
    const started = useSharedValue(-1);
    const moving = useSharedValue(false);

    const frame = useFrameCallback(useCallback(info => {
        'worklet';

        if (started.value < 0) started.value = info.timestamp - elapsed.value;
        elapsed.value = info.timestamp - started.value;
    }, [started, elapsed]), false);

    useEffect(() => {
        const receive = (state: SingState) => setActive(state.enabled);
        const unsubscribe = Sing.subscribe(receive);

        Sing.requestState();

        return unsubscribe;
    }, []);

    useEffect(() => {
        const activating = active && !wasActive.current;
        wasActive.current = active;

        moving.value = active && visible && !reducedMotion;
        elapsed.value = activating ? 0 : 420;
        started.value = -1;

        frame.setActive(active && visible && !reducedMotion);
    }, [active, visible, reducedMotion, elapsed, started, moving, frame]);

    const iconProps = useAnimatedProps(() => {
        'worklet';

        if (!moving.value) return { iconAnimation: [1, 1, 1, 1, 1, 1, 1, 0, 0, 0] };

        const ms = elapsed.value;

        if (ms < 420) {
            const eased = (1 - Math.cos(Math.PI * ms / 420)) / 2;
            const scale = 1 + .24 * eased;

            return { iconAnimation: [1, scale, 1 - eased, scale, 1 - eased, scale, 1 - eased, 8 * eased, -8 * eased, 8 * eased] };
        }

        const loop = (ms - 420) % 2000;
        const values = [1 + .035 * (1 - Math.cos(loop / 2000 * Math.PI * 2)) / 2];
        const rotations: number[] = [];

        for (let i = 0; i < 3; i++) {
            const phase = (loop - i * 160) / 1400;
            const t = Math.max(0, Math.min(1, phase));
            const eased = t * t * (3 - 2 * t);
            const fadeIn = Math.min(1, t / .16);
            const fadeOut = Math.max(0, Math.min(1, (1 - t) / .65));

            values.push(.3 + .97 * eased, phase <= 0 || phase >= 1 ? 0 : fadeIn * fadeOut);
            rotations.push((i === 1 ? -1 : 1) * (-8 + 16 * eased));
        }

        return { iconAnimation: [...values, ...rotations] };
    });

    useEffect(() => {
        let held = false;

        const interaction = (payload: unknown) => {
            const active = payload === true;
            if (active === held) return;

            held = active;

            if (active) onHold();
            else onRelease();
        };

        SpotifyPlus.Events.on('lyrics.sing.interaction', interaction);

        return () => {
            SpotifyPlus.Events.off('lyrics.sing.interaction', interaction);
            if (held) onRelease();
        };
    }, [onHold, onRelease]);

    return <NativeSingControl controlsVisible={visible} reducedMotion={reducedMotion} animatedProps={iconProps} style={{ width: 62, height: 144 }} />;
}