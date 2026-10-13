import type { ViewStyle } from 'spotifyplus/react';

export function nativeFadeStyle(opacity: number): ViewStyle & { alpha: number } {
    'worklet';

    return { alpha: opacity };
}
