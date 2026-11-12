import type { ViewStyle } from 'spotifyplus/react';

export function nativeFadeStyle(opacity: number): ViewStyle & { alpha: number } {
    'worklet';

    return { alpha: opacity };
}

/**
 * Static alpha for where a fade is headed. Listed after the animated style so it only shows through if the
 * animated alpha is ever dropped, keeping controls from staying touchable while invisible.
 */
export function restingFade(visible: boolean): ViewStyle & { alpha: number } {
    return { alpha: visible ? 1 : 0 };
}
