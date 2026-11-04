import type { SpotifyTrack } from 'spotifyplus/entities';

export function waitForCurrentTrack(read: () => SpotifyTrack | null | undefined, signal: AbortSignal, expectedUri?: string, onWaiting?: () => void): Promise<SpotifyTrack> {
    return new Promise((resolve, reject) => {
        let timer: ReturnType<typeof setTimeout> | undefined;
        const started = Date.now();
        let waiting = false;
        const cleanup = () => {
            clearTimeout(timer);
            signal.removeEventListener('abort', abort);
        };
        const abort = () => {
            cleanup();
            reject(signal.reason ?? new Error('Track request cancelled'));
        };
        const check = () => {
            if (signal.aborted) return abort();
            try {
                const track = read();
                if (track?.id && track.title && (!expectedUri || track.uri === expectedUri)) {
                    cleanup();
                    resolve(track);
                    return;
                }
            } catch (_) { }
            if (!waiting && Date.now() - started >= 10000) {
                waiting = true;
                onWaiting?.();
            }
            timer = setTimeout(check, waiting ? 1000 : 100);
        };
        signal.addEventListener('abort', abort, { once: true });
        check();
    });
}
