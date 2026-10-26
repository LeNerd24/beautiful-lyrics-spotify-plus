import React, { useEffect, useSyncExternalStore } from 'react';
import { SpotifyPlus, UIComponentProps } from 'spotifyplus';
import LyricsSheet from './lyrics-sheet';

let opened = false;
let lastOpenedInstance: string | undefined;
const listeners = new Set<() => void>();

const subscribe = (listener: () => void) => {
    listeners.add(listener);
    return () => { listeners.delete(listener); };
};

const snapshot = () => opened;
let mountedOverlays = 0;
export const isLyricsOpen = () => opened && mountedOverlays > 0;
const close = () => {
    opened = false;

    for (const listener of listeners) {
        listener();
    }
};

export function openLyrics() {
    if (mountedOverlays === 0 && !SpotifyPlus.Navigation.open('spotify:now-playing', { target: 'spotify' })) {
        SpotifyPlus.toast('Could not open the lyrics.');
        return;
    }

    opened = true;
    for (const listener of listeners) listener();
}

export function LyricsOpenTrigger({ context }: UIComponentProps) {
    useEffect(() => {
        if (lastOpenedInstance === context.instanceId) return;
        lastOpenedInstance = context.instanceId;

        if (!SpotifyPlus.Navigation.back()) {
            SpotifyPlus.toast('Could not open the lyrics overlay.');
            return;
        }

        opened = true;
        for (const listener of listeners) listener();
    }, [context.instanceId]);

    return null;
}

export function LyricsOverlay() {
    const visible = useSyncExternalStore(subscribe, snapshot);
    useEffect(() => {
        mountedOverlays++;
        return () => { mountedOverlays--; };
    }, []);
    return visible ? <LyricsSheet onClosed={close} /> : null;
}
