import { SpotifyPlus } from 'spotifyplus';

export interface TranslationResult { sourceText: string; text: string; detectedLanguage: string }
interface Reply { requestId: string; translations: TranslationResult[]; error?: string; started?: boolean }
let sequence = 0;

export function translateNative(lines: string[], target: string, signal: AbortSignal): Promise<TranslationResult[]> {
    signal.throwIfAborted();
    if (!lines.length) return Promise.resolve([]);

    const requestId = Date.now() + ':' + ++sequence;

    return new Promise((resolve, reject) => {
        const emitCancel = () => {
            try {
                void Promise.resolve(SpotifyPlus.Events.emit('lyrics.translation.cancel', { requestId })).catch(() => { });
            } catch (_) { }
        };

        const cleanup = () => {
            clearTimeout(timeout);
            clearTimeout(startTimeout);

            signal.removeEventListener('abort', cancel);
            SpotifyPlus.Events.off('lyrics.translation.result', receive);
        };

        const cancel = () => { cleanup(); emitCancel(); reject(signal.reason); };
        const receive = (payload: unknown) => {
            const reply = payload as Reply | undefined;

            if (reply?.requestId !== requestId) return;
            if (reply.started) { clearTimeout(startTimeout); return; }

            cleanup();

            if (reply.error) {
                reject(new Error(reply.error));
            } else if (!Array.isArray(reply.translations)) {
                reject(new Error('Native translation returned an unexpected response.'));
            } else {
                resolve(reply.translations);
            }
        };

        const timeout = setTimeout(() => {
            cleanup();
            emitCancel();
            reject(new Error('Native translation did not respond. Reload the extension with its updated native APK.'));
        }, 10000 + Math.ceil(lines.length / 6) * 5000);

        const startTimeout = setTimeout(() => {
            cleanup();
            emitCancel();

            reject(new Error('Native translation is unavailable. The updated native APK must load before translation can start.'));
        }, 5000);

        SpotifyPlus.Events.on('lyrics.translation.result', receive);
        signal.addEventListener('abort', cancel, { once: true });

        try {
            void Promise.resolve(SpotifyPlus.Events.emit('lyrics.translation.request', { requestId, lines, target })).catch(error => {
                cleanup();
                reject(error);
            });
        } catch (error) { cleanup(); reject(error); }
    });
}
