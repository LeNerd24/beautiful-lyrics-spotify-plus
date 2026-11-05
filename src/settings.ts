import { SpotifyPlus } from 'spotifyplus';
import { useEffect, useState } from 'react';

export interface LyricsSettings {
    translationLanguage: string;
    swapTranslatedLines: boolean;
    hideOriginalLyrics: boolean;
    pronunciationAsLead: boolean;
}

const defaults: LyricsSettings = { translationLanguage: 'en', swapTranslatedLines: false, hideOriginalLyrics: false, pronunciationAsLead: true };
let current = { ...defaults, ...SpotifyPlus.Platform.Storage.get<Partial<LyricsSettings>>('lyrics.translation.settings') };
const listeners = new Set<(value: LyricsSettings) => void>();
const items = [
    { id: 'translationLanguage', type: 'text' as const, label: 'Translate lyrics into', description: 'The language code for the language you want to translate songs into.', value: current.translationLanguage, placeholder: 'en', maxLength: 20 },
    { id: 'swapTranslatedLines', type: 'toggle' as const, label: 'Make translations the lead lyrics', description: 'Show the original lyrics in smaller text underneath when translations are enabled.', value: current.swapTranslatedLines },
    { id: 'hideOriginalLyrics', type: 'toggle' as const, label: 'Hide original lyrics', description: 'Show only the translated lead lyrics when translations are enabled.', value: current.hideOriginalLyrics },
    { id: 'pronunciationAsLead', type: 'toggle' as const, label: 'Make pronunciation the lead lyrics', description: 'Replace the original lyrics with their pronunciation when pronunciation is enabled, instead of showing it underneath.', value: current.pronunciationAsLead },
];

SpotifyPlus.Settings.registerSetting({ title: 'Lyrics translation', items });
SpotifyPlus.Events.on('settings.changed', (payload: unknown) => {
    if (!payload || typeof payload !== 'object' || !('id' in payload) || !('value' in payload)) return;

    const setting = payload as { id: string; value: unknown };
    const item = items.find(item => item.id === setting.id);

    if (!item || typeof setting.value !== typeof item.value) return;

    item.value = setting.value as never;
    current = { ...current, [setting.id]: setting.value };

    SpotifyPlus.Platform.Storage.set('lyrics.translation.settings', current);
    listeners.forEach(listener => listener(current));
});

export function useLyricsSettings(): LyricsSettings {
    const [settings, setSettings] = useState(current);

    useEffect(() => {
        listeners.add(setSettings);
        return () => {
            listeners.delete(setSettings);
        };
    }, []);

    return settings;
}

const autoGenerateKey = 'lyrics.generation.auto';
let autoGenerate = SpotifyPlus.Platform.Storage.get(autoGenerateKey) === true;
const autoGenerateListeners = new Set<(value: boolean) => void>();
const generationItems = [
    { id: 'autoGenerateLyrics', type: 'toggle' as const, label: 'Automatically generate lyrics', description: 'When a song only has line by line lyrics, start generating word synced lyrics a few seconds after it starts playing with the lyrics open.', value: autoGenerate },
];

SpotifyPlus.Settings.registerSetting({ title: 'Lyrics generation', items: generationItems });
SpotifyPlus.Events.on('settings.changed', (payload: unknown) => {
    if (!payload || typeof payload !== 'object' || !('id' in payload) || !('value' in payload)) return;

    const setting = payload as { id: string; value: unknown };
    if (setting.id !== 'autoGenerateLyrics' || typeof setting.value !== 'boolean') return;

    generationItems[0].value = setting.value;
    autoGenerate = setting.value;

    SpotifyPlus.Platform.Storage.set(autoGenerateKey, autoGenerate);
    autoGenerateListeners.forEach(listener => listener(autoGenerate));
});

export function useAutoGenerate(): boolean {
    const [value, setValue] = useState(autoGenerate);

    useEffect(() => {
        autoGenerateListeners.add(setValue);
        return () => {
            autoGenerateListeners.delete(setValue);
        };
    }, []);

    return value;
}

export type LyricsPreference = 'translation' | 'pronunciation';
export interface LyricsPreferences {
    translation: boolean;
    pronunciation: boolean
}

const preferenceKeys: Record<LyricsPreference, string> = {
    translation: 'lyrics.translation.enabled', pronunciation: 'lyrics.pronunciation.enabled',
};

let preferences: LyricsPreferences = {
    translation: SpotifyPlus.Platform.Storage.get(preferenceKeys.translation) === true,
    pronunciation: SpotifyPlus.Platform.Storage.get(preferenceKeys.pronunciation) === true,
};

const preferenceListeners = new Set<(value: LyricsPreferences) => void>();

export function setLyricsPreference(kind: LyricsPreference, enabled: boolean) {
    SpotifyPlus.Platform.Storage.set(preferenceKeys[kind], enabled);

    preferences = { ...preferences, [kind]: enabled };
    preferenceListeners.forEach(listener => listener(preferences));
}

export function useLyricsPreferences(): LyricsPreferences {
    const [value, setValue] = useState(preferences);

    useEffect(() => {
        preferenceListeners.add(setValue);
        return () => {
            preferenceListeners.delete(setValue);
        };
    }, []);

    return value;
}
