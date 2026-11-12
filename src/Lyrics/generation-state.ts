import { SpotifyPlus } from 'spotifyplus';
import { LineSyncedLyrics, SyllableSyncedLyrics, SyllableVocalSet } from '../Types/lyrics-types';
import { endInterludeEarly, TransformedLyrics } from './lyric-utilities';
import { getDisplaySettings } from '../display-settings';
import { supportsPronunciation } from './languages';
import { localRomanization } from './romanization';

export type GenerationProgress = { trackId: string; sessionId: string; stage: string; message: string };
export type GenerationResult = { trackId: string; sessionId: string; status: 'complete' | 'failed'; error?: string };
export type GenerationBegin = { trackId: string; sessionId: string; source: LineSyncedLyrics };
export type GenerationLine = { trackId: string; sessionId: string; index: number; line: SyllableVocalSet; completed: number; total: number };
export type GenerationState = { trackId: string; sessionId: string; status: 'working' | 'complete' | 'failed'; stage?: string; message: string; completed: number; total: number; lyrics?: SyllableSyncedLyrics; received: ReadonlySet<number>; contentIndex?: readonly number[] };

export class GenerationStore {
    private states = new Map<string, GenerationState>();
    private listeners = new Set<() => void>();
    private sequence = 0;

    subscribe = (listener: () => void) => {
        this.listeners.add(listener);
        return () => { this.listeners.delete(listener); };
    };

    get = (trackId?: string) => trackId ? this.states.get(trackId) : undefined;
    getLyrics = (trackId?: string) => this.get(trackId)?.lyrics;
    busy = () => [...this.states.values()].some(state => state.status === 'working');

    request(trackId: string) {
        const sessionId = `${Date.now()}-${++this.sequence}`;
        this.set({ trackId, sessionId, status: 'working', message: 'Finding lyrics', completed: 0, total: 0, received: new Set() });
        return sessionId;
    }

    begin(event: GenerationBegin) {
        const state = this.match(event);
        if (!state || state.status !== 'working' || event.source?.Type !== 'Line' || !Array.isArray(event.source.Content)) return;

        const content: SyllableSyncedLyrics['Content'] = [];
        const contentIndex: number[] = [];
        let previousEnd: number | undefined;
        const { interludeDuration } = getDisplaySettings();

        event.source.Content.forEach((line, index) => {
            if (line.Type === 'Interlude') {
                contentIndex[index] = content.push(line) - 1;
                previousEnd = line.EndTime;
                return;
            }

            const gapStart = previousEnd ?? 0;

            if (line.StartTime - gapStart >= interludeDuration && content[content.length - 1]?.Type !== 'Interlude') {
                content.push({ Type: 'Interlude', StartTime: gapStart, EndTime: line.StartTime - endInterludeEarly });
            }

            contentIndex[index] = content.push({
                Type: 'Vocal', OppositeAligned: line.OppositeAligned, Pending: true,
                Lead: { StartTime: line.StartTime, EndTime: line.EndTime, Syllables: [{ Text: line.Text, StartTime: line.StartTime, EndTime: line.EndTime, IsPartOfWord: false }] }
            }) - 1;

            previousEnd = Math.max(previousEnd ?? 0, line.EndTime);
        });

        const ranges = content.map(line => line.Type === 'Vocal' ? line.Lead : line);
        const total = content.filter(line => line.Type === 'Vocal').length;
        this.set({ ...state, total, contentIndex, lyrics: { Type: 'Syllable', Partial: true, GenerationId: state.sessionId, SongWriters: event.source.SongWriters ?? [], StartTime: Math.min(...ranges.map(line => line.StartTime)), EndTime: Math.max(...ranges.map(line => line.EndTime)), Content: content } });
    }

    line(event: GenerationLine) {
        const state = this.match(event);
        if (!state?.lyrics || state.status !== 'working' || !Number.isInteger(event.index) || event.line?.Type !== 'Vocal' || !Array.isArray(event.line.Lead?.Syllables)) return;

        const target = state.contentIndex?.[event.index] ?? event.index;
        if (target < 0 || target >= state.lyrics.Content.length || state.lyrics.Content[target].Type !== 'Vocal' || state.received.has(event.index)) return;

        const received = new Set(state.received);
        received.add(event.index);
        const content = state.lyrics.Content.slice();
        content[target] = { ...event.line, Pending: false };
        this.set({ ...state, received, completed: received.size, stage: 'generating', message: 'Generating lyrics', lyrics: { ...state.lyrics, Content: content } });
    }

    progress(event: GenerationProgress) {
        const state = this.match(event);
        if (state?.status === 'working' && (state.stage !== event.stage || state.message !== event.message)) this.set({ ...state, stage: event.stage, message: event.message });
    }

    result(event: GenerationResult) {
        const state = this.match(event);
        if (!state || state.status !== 'working') return;
        const lyrics = state.lyrics && { ...state.lyrics, Partial: state.completed < state.total };
        this.set({ ...state, lyrics, status: event.status, message: event.status === 'complete' ? 'Lyrics are ready' : event.error ?? 'Could not finish generating lyrics' });
    }

    private match(event: { trackId: string; sessionId: string }) {
        const state = this.states.get(event.trackId);
        return state?.sessionId === event.sessionId ? state : undefined;
    }

    private set(state: GenerationState) {
        this.states.delete(state.trackId);
        this.states.set(state.trackId, state);
        while (this.states.size > 4) {
            const oldest = [...this.states.values()].find(value => value.status !== 'working');
            if (!oldest) break;
            this.states.delete(oldest.trackId);
        }
        for (const listener of this.listeners) listener();
    }
}

export const generationStore = new GenerationStore();

const sources = new Map<string, TransformedLyrics>();

export function setGenerationSource(trackId: string, lyrics: TransformedLyrics) {
    sources.clear();
    sources.set(trackId, lyrics);
}

export function canGenerate(trackId: string): boolean { return sources.get(trackId)?.Type === 'Line'; }

export function alignmentReadings(lyrics?: TransformedLyrics): Record<string, string> | undefined {
    if (lyrics?.Type !== 'Line' || !supportsPronunciation(lyrics.language)) return undefined;

    const readings: Record<string, string> = {};

    for (const line of lyrics.Content) {
        if (line.Type !== 'Vocal') continue;

        for (const word of line.Text.split(/[\s()]+/)) {
            if (!word || word in readings) continue;

            const reading = localRomanization(word, lyrics.language)?.trim();
            if (reading && reading !== word && /[a-z]/i.test(reading)) readings[word] = reading;
        }
    }

    return Object.keys(readings).length ? readings : undefined;
}

export function startGeneration(trackId: string) {
    const sessionId = generationStore.request(trackId);
    const readings = alignmentReadings(sources.get(trackId));
    SpotifyPlus.Events.emit('lyrics.generate.request', { trackId, sessionId, positionMs: SpotifyPlus.Player.getProgress(), ...(readings && { readings }) });
}
