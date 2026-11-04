export const CONTROLS_IDLE_MS = 4000;
export const CONTROLS_FADE_MS = 240;
export const LANDSCAPE_ACTIONS_HEIGHT = 46;
export const LINE_BLUR_KEY = 'lyrics-line-blur';
export const DEFAULT_LINE_BLUR = 30;
const MAX_LINE_BLUR_RADIUS = 10;

export function lineBlurSetting(stored: unknown): number {
    return typeof stored === 'number' && Number.isFinite(stored) ? Math.max(0, Math.min(100, stored)) : DEFAULT_LINE_BLUR;
}

export function lineBlurRadius(setting: number): number {
    return lineBlurSetting(setting) / 100 * MAX_LINE_BLUR_RADIUS;
}

export interface LyricsViewportSize { width: number; height: number; }

export function isLandscape({ width, height }: LyricsViewportSize): boolean {
    return width > height;
}

export function landscapeLayout({ width, height }: LyricsViewportSize) {
    const infoWidth = Math.round(width * 0.38);

    return { infoWidth, artSize: Math.max(96, Math.min(infoWidth - 40, height - 164)) };
}

interface ControlsClock {
    setTimeout(callback: () => void, delay: number): ReturnType<typeof setTimeout>;
    clearTimeout(timer: ReturnType<typeof setTimeout>): void;
}

export class ControlsVisibility {
    private visible: boolean;
    private holds = 0;
    private timer: ReturnType<typeof setTimeout> | undefined;
    private disposed = false;

    constructor(private paused: boolean, private changed: (visible: boolean) => void, private clock: ControlsClock = { setTimeout, clearTimeout }) {
        this.visible = paused;
    }

    reveal() {
        if (this.disposed) return;
        if (!this.visible) { this.visible = true; this.changed(true); }
        this.schedule();
    }

    setPaused(paused: boolean) {
        if (this.disposed) return;
        this.paused = paused;
        if (paused) this.reveal(); else this.schedule();
    }

    hold() {
        if (this.disposed) return;
        this.holds += 1; this.cancel();
    }

    release() {
        if (this.disposed) return;
        this.holds = Math.max(0, this.holds - 1); this.schedule();
    }

    interact() {
        if (this.disposed) return;
        if (this.visible) this.reveal();
    }

    activate() {
        this.disposed = false;
        this.schedule();
    }

    dispose() {
        this.disposed = true;
        this.holds = 0;
        this.cancel();
    }

    private cancel() {
        if (this.timer !== undefined) this.clock.clearTimeout(this.timer);
        this.timer = undefined;
    }

    private schedule() {
        this.cancel();

        if (this.disposed || !this.visible || this.paused || this.holds > 0) return;

        const timer = this.clock.setTimeout(() => {
            if (this.timer !== timer) return;
            this.timer = undefined;
            if (this.disposed || this.paused || this.holds > 0) return;
            this.visible = false;
            this.changed(false);
        }, CONTROLS_IDLE_MS);
        this.timer = timer;
    }
}

export function formatPlaybackTime(ms: number): string {
    'worklet';

    const seconds = Math.floor(Math.max(0, Number.isFinite(ms) ? ms : 0) / 1000);
    return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

export function lyricTapAction(controlsVisible: boolean, active: boolean, vocal: boolean): 'seek' | 'reveal' {
    'worklet';
    return vocal && (controlsVisible || active) ? 'seek' : 'reveal';
}

export function revealsEarlierLyrics(fingerTravelDp: number): boolean {
    'worklet';
    return fingerTravelDp > 14;
}
