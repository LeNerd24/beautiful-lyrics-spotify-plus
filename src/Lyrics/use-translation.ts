
import { useEffect, useMemo, useState } from 'react';
import { TransformedLyrics } from './lyric-utilities';
import { addPronunciation, addTranslations } from './translation-service';
import { createAbortController } from './platform-utils';
import { sameLanguage, supportsPronunciation } from './languages';
import { setLyricsPreference, useLyricsPreferences } from '../settings';

interface Completed { source: TransformedLyrics; target?: string; lyrics: TransformedLyrics }

const errorMessage = (error: unknown, fallback: string) => error && typeof error === 'object' &&
    'message' in error && typeof error.message === 'string' ? error.message : fallback;

export function useTranslation(source: TransformedLyrics | undefined, target: string | undefined) {
    const preferences = useLyricsPreferences();
    const translation = preferences.translation && !!source && !!target && !sameLanguage(source.language, target);
    const pronunciation = preferences.pronunciation && !!source && supportsPronunciation(source.language);

    const [translating, setTranslating] = useState(false);
    const [romanizing, setRomanizing] = useState(false);
    const [translationError, setTranslationError] = useState<string>();
    const [pronunciationError, setPronunciationError] = useState<string>();
    const [translated, setTranslated] = useState<Completed>();
    const [pronounced, setPronounced] = useState<Completed>();

    useEffect(() => {
        setTranslationError(undefined);
        setTranslating(false);

        if (!source || !target || !translation || (translated?.source === source && translated.target === target)) return;

        const controller = createAbortController();
        setTranslating(true);

        void addTranslations(source, target, controller.signal).then(lyrics => {
            if (!controller.signal.aborted) setTranslated({ source, target, lyrics });
        }).catch(error => {
            if (!controller.signal.aborted) setTranslationError(errorMessage(error, 'Unable to translate lyrics. Please try again.'));
        }).finally(() => {
            if (!controller.signal.aborted) setTranslating(false);
        });

        return () => controller.abort();
    }, [source, target, translation]);

    useEffect(() => {
        setPronunciationError(undefined);
        setRomanizing(false);

        if (!source || !pronunciation || pronounced?.source === source) return;

        const controller = createAbortController();
        setRomanizing(true);

        void addPronunciation(source, controller.signal).then(lyrics => {
            if (!controller.signal.aborted) setPronounced({ source, lyrics });
        }).catch(error => {
            if (!controller.signal.aborted) setPronunciationError(errorMessage(error, 'Unable to load pronunciation. Please try again.'));
        }).finally(() => {
            if (!controller.signal.aborted) setRomanizing(false);
        });

        return () => controller.abort();
    }, [source, pronunciation]);

    const lyrics = useMemo(() => {
        if (!source) return undefined;

        const translatedLyrics = translated?.source === source && translated.target === target ? translated.lyrics : source;
        const pronouncedLyrics = pronounced?.source === source ? pronounced.lyrics : undefined;

        if (!pronouncedLyrics) return translatedLyrics;

        if (translatedLyrics.Type === 'Static' && pronouncedLyrics.Type === 'Static') {
            return {
                ...translatedLyrics,
                Lines: translatedLyrics.Lines.map((line, i) => ({ ...line, RomanizedText: pronouncedLyrics.Lines[i].RomanizedText }))
            };
        }

        if (translatedLyrics.Type === 'Line' && pronouncedLyrics.Type === 'Line') {
            return {
                ...translatedLyrics,
                Content: translatedLyrics.Content.map((line, i) => {
                    const other = pronouncedLyrics.Content[i];

                    return line.Type === 'Vocal' && other.Type === 'Vocal' ? { ...line, RomanizedText: other.RomanizedText } : line;
                })
            };
        }

        if (translatedLyrics.Type === 'Syllable' && pronouncedLyrics.Type === 'Syllable') {
            return {
                ...translatedLyrics,
                Content: translatedLyrics.Content.map((line, i) => {
                    const other = pronouncedLyrics.Content[i];

                    return line.Type === 'Vocal' && other.Type === 'Vocal' ? { ...line, Lead: { ...line.Lead, Pronunciation: other.Lead.Pronunciation } } : line;
                })
            };
        }

        return translatedLyrics;
    }, [source, target, translated, pronounced]);

    return {
        lyrics,
        translation,
        pronunciation,
        translating,
        romanizing,
        error: translationError ?? pronunciationError,
        toggleTranslation: () => setLyricsPreference('translation', !preferences.translation),
        togglePronunciation: () => setLyricsPreference('pronunciation', !preferences.pronunciation)
    };
}
