import { LyricsSettings } from '../settings';

export interface LyricsPresentation extends LyricsSettings {
    translation: boolean;
    pronunciation: boolean;
}

export function linePresentation(original: string, translated: string | undefined, pronunciation: string | undefined, options?: LyricsPresentation) {
    const translation = options?.translation ? translated : undefined;
    const hide = !!translation && !!options?.hideOriginalLyrics;
    const swap = !!translation && (!!options?.swapTranslatedLines || hide);

    return {
        lead: swap ? translation! : original,
        secondary: translation && !hide ? swap ? original : translation : undefined,
        pronunciation: options?.pronunciation && !hide ? pronunciation : undefined,
    };
}
