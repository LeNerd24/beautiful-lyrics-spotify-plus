import { SpotifyPlus } from 'spotifyplus';

export interface SingState { vocals: number; enabled: boolean; ready: boolean; timingOffsetMs: number; model: 'uvr'; status: string; }

export const Sing = {
    setTimingOffset(ms: number) {
        if (!Number.isFinite(ms)) throw new Error('Timing offset must be finite');
        SpotifyPlus.Events.emit('lyrics.sing.setTimingOffset', Math.round(Math.max(-250, Math.min(250, ms))));
    },

    requestState() { SpotifyPlus.Events.emit('lyrics.sing.requestState', null); },

    subscribe(listener: (state: SingState) => void) {
        const receive = (payload: unknown) => listener(payload as SingState);
        SpotifyPlus.Events.on('lyrics.sing.state', receive);

        return () => SpotifyPlus.Events.off('lyrics.sing.state', receive);
    }
};
