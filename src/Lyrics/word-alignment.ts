
import { SyllableList, SyllableMetadata } from '../Types/lyrics-types';

export interface SourceWord extends SyllableMetadata { offset: number; endOffset: number }
interface Token { text: string; offset: number; endOffset: number; joined: boolean }

export function lyricText(syllables: SyllableList): string {
    return syllables.map((s, i) => s.Text + (i < syllables.length - 1 && !s.IsPartOfWord ? ' ' : '')).join('');
}

export function tokenize(text: string, language?: string): Token[] {
    let starts: number[] = [];

    if (typeof Intl !== 'undefined' && typeof Intl.Segmenter === 'function') {
        starts = Array.from(new Intl.Segmenter(language, { granularity: 'word' }).segment(text)).filter(s => s.isWordLike).map(s => s.index);
    } else {
        const pattern = language?.startsWith('ja')
            ? /[\u3400-\u9fff\uf900-\ufaff]+[\u3040-\u309f]*|[\u3040-\u30ff]+|[A-Za-z0-9\u00c0-\u02ff]+/g
            : /[\u3040-\u30ff\u3400-\u9fff\uf900-\ufaff]|[A-Za-z0-9\u00c0-\u02ff\u0370-\u052f\u0590-\u06ff\u0900-\u0e7f\u10a0-\u11ff\uac00-\ud7af]+(?:['’][A-Za-z\u00c0-\u02ff]+)*/g;

        starts = Array.from(text.matchAll(pattern)).map(m => m.index!);
    }

    if (!starts.length) return text.trim() ? [{ text, offset: 0, endOffset: text.length, joined: false }] : [];

    starts[0] = 0;

    return starts.map((offset, i) => {
        const endOffset = starts[i + 1] ?? text.length;
        const piece = text.slice(offset, endOffset);

        return { text: piece.trim(), offset, endOffset, joined: i < starts.length - 1 && !/\s$/.test(piece) };
    });
}

export function isEmphasized(s: SyllableMetadata): boolean {
    return s.Emphasized ?? (s.EndTime - s.StartTime >= 1 && s.Text.length <= 12);
}

export function sourceWords(syllables: SyllableList, language?: string): SourceWord[] {
    let offset = 0;

    const spans = syllables.map((s, i) => {
        const span = { s, offset, end: offset + s.Text.length };
        offset = span.end + (i < syllables.length - 1 && !s.IsPartOfWord ? 1 : 0);

        return span;
    });

    return tokenize(lyricText(syllables), language).map(token => {
        const covered = spans.filter(s => s.offset < token.endOffset && s.end > token.offset);
        const first = covered[0]?.s ?? syllables[0];
        const last = covered[covered.length - 1]?.s ?? first;
        const firstSpan = covered[0];
        const lastSpan = covered[covered.length - 1];

        const startFraction = firstSpan ? Math.max(0, Math.min(1, (token.offset - firstSpan.offset) / Math.max(1, first.Text.length))) : 0;
        const endFraction = lastSpan ? Math.max(0, Math.min(1, (token.endOffset - lastSpan.offset) / Math.max(1, last.Text.length))) : 1;

        return {
            Text: token.text,
            StartTime: first.StartTime + (first.EndTime - first.StartTime) * startFraction,
            EndTime: last.StartTime + (last.EndTime - last.StartTime) * endFraction,
            IsPartOfWord: token.joined,
            Emphasized: covered.some(s => isEmphasized(s.s)),
            offset: token.offset,
            endOffset: token.endOffset
        };
    });
}

const normalized = (text: string) => text.toLowerCase().normalize('NFKC').replace(/[^A-Za-z0-9\u00c0-\u02ff\u0370-\u052f\u0590-\u06ff\u0900-\u0e7f\u10a0-\u11ff\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af\uf900-\ufaff]/g, '');

const phraseStarters: Record<string, Set<string>> = {
    fr: new Set(['tout', 'toute', 'tous', 'toutes', 'le', 'la', 'les', 'un', 'une', 'des', 'de', 'du', 'en', 'il', 'ne', 'pas', 'est', 'a', 'au']),
    en: new Set(['all', 'every', 'a', 'the', 'of', 'in', 'on', 'at', 'to', 'as', 'not', 'there', 'thank']),
    es: new Set(['todo', 'toda', 'todos', 'todas', 'el', 'la', 'los', 'las', 'de', 'en', 'no', 'un', 'una']),
    de: new Set(['alle', 'der', 'die', 'das', 'ein', 'eine', 'nicht', 'in', 'zu', 'es']),
    it: new Set(['tutto', 'tutti', 'il', 'la', 'gli', 'le', 'un', 'una', 'di', 'in', 'non']),
    pt: new Set(['todo', 'todos', 'toda', 'todas', 'o', 'a', 'os', 'as', 'de', 'em', 'não'])
};

export function alignmentQueries(words: SourceWord[], source?: string): string[] {
    const queries = new Set(words.map(w => w.Text));
    const starters = phraseStarters[source?.split('-')[0] ?? ''];

    for (let i = 0; i < words.length; i++) {
        if (!starters?.has(normalized(words[i].Text))) continue;

        for (const count of [2, 3]) {
            if (i + count <= words.length) {
                queries.add(words.slice(i, i + count).map(w => w.Text).join(' '));
            }
        }
    }

    return [...queries];
}

export function spokenWeight(text: string): number {
    const latin = text.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
    const vowels = latin.match(/[aeiouy]+/g);

    return Math.max(1, vowels?.length ?? Array.from(normalized(text)).length);
}

export function splitTimedText(text: string, source: SyllableMetadata, language?: string): SyllableList {
    const tokens = tokenize(text, language);
    const weights = tokens.map(t => spokenWeight(t.text));
    const total = weights.reduce((a, b) => a + b, 0);

    let elapsed = 0;

    return tokens.map((t, i) => {
        const start = elapsed / total;
        elapsed += weights[i];

        return {
            Text: t.text,
            IsPartOfWord: t.joined,
            Emphasized: isEmphasized(source),
            StartTime: source.StartTime + (source.EndTime - source.StartTime) * start,
            EndTime: source.StartTime + (source.EndTime - source.StartTime) * elapsed / total
        };
    });
}

export function alignTranslation(text: string, words: SourceWord[], lexicon: Map<string, string[]>, target?: string): SyllableList {
    if (!words.length) return [];

    const tokens = tokenize(text, target);
    if (!tokens.length) return [];

    type Group = { sourceStart: number; sourceEnd: number; targetStart: number; targetEnd: number };
    type Candidate = Group & { score: number };

    const key = (text: string) => {
        const value = normalized(text);

        return target === 'en' && value.length > 3 ? value.replace(/s$/, '') : value;
    };

    const candidates: Candidate[] = [];

    for (let sourceStart = 0; sourceStart < words.length; sourceStart++) {
        for (let count = 1; count <= Math.min(3, words.length - sourceStart); count++) {
            const phrase = words.slice(sourceStart, sourceStart + count).map(w => w.Text).join(' ');

            for (const translated of lexicon.get(phrase) ?? (count === 1 ? [phrase] : [])) {
                const parts = tokenize(translated, target).map(t => key(t.text)).filter(Boolean);
                if (!parts.length) continue;

                if (count > 1 && (parts.length !== 1 || words.slice(sourceStart, sourceStart + count).some(word =>
                    (lexicon.get(word.Text) ?? []).some(value => key(value) === parts[0])))) continue;

                for (let start = 0; start <= tokens.length - parts.length; start++) {
                    if (parts.every((part, i) => part === key(tokens[start + i].text))) {
                        candidates.push({
                            sourceStart,
                            sourceEnd: sourceStart + count - 1,
                            targetStart: start,
                            targetEnd: start + parts.length - 1,
                            score: count * 8 + parts.join('').length + parts.length * 2
                        });
                    }
                }
            }
        }
    }

    candidates.sort((a, b) => b.score - a.score || a.sourceStart - b.sourceStart || a.targetStart - b.targetStart);

    const accepted: Group[] = [];
    const sourceUsed = new Set<number>(), targetUsed = new Set<number>();

    for (const c of candidates) {
        if (words.slice(c.sourceStart, c.sourceEnd + 1).some((_, i) => sourceUsed.has(c.sourceStart + i)) ||
            tokens.slice(c.targetStart, c.targetEnd + 1).some((_, i) => targetUsed.has(c.targetStart + i))) continue;

        accepted.push(c);

        for (let i = c.sourceStart; i <= c.sourceEnd; i++) sourceUsed.add(i);
        for (let i = c.targetStart; i <= c.targetEnd; i++) targetUsed.add(i);
    }

    const sourceOrder = [...accepted].sort((a, b) => a.sourceStart - b.sourceStart);
    const targetOrder = [...accepted].sort((a, b) => a.targetStart - b.targetStart);

    const anchors = sourceOrder.map((s, i) => ({
        sourceStart: s.sourceStart,
        sourceEnd: s.sourceEnd,
        targetStart: targetOrder[i].targetStart,
        targetEnd: targetOrder[i].targetEnd
    }));

    const groups: Group[] = [];
    let sourceCursor = 0, targetCursor = 0;

    for (const anchor of anchors) {
        const sourceGap = sourceCursor < anchor.sourceStart;
        const targetGap = targetCursor < anchor.targetStart;

        if (sourceGap && targetGap) {
            groups.push({
                sourceStart: sourceCursor,
                sourceEnd: anchor.sourceStart - 1,
                targetStart: targetCursor,
                targetEnd: anchor.targetStart - 1
            });
        } else if (sourceGap) {
            if (groups.length) groups[groups.length - 1].sourceEnd = anchor.sourceStart - 1;
            else anchor.sourceStart = sourceCursor;
        } else if (targetGap) {
            anchor.targetStart = targetCursor;
        }

        groups.push(anchor);

        sourceCursor = anchor.sourceEnd + 1;
        targetCursor = anchor.targetEnd + 1;
    }

    if (sourceCursor < words.length && targetCursor < tokens.length) {
        groups.push({
            sourceStart: sourceCursor,
            sourceEnd: words.length - 1,
            targetStart: targetCursor,
            targetEnd: tokens.length - 1
        });
    } else if (groups.length) {
        groups[groups.length - 1].sourceEnd = words.length - 1;
        groups[groups.length - 1].targetEnd = tokens.length - 1;
    }

    if (!groups.length) {
        groups.push({ sourceStart: 0, sourceEnd: words.length - 1, targetStart: 0, targetEnd: tokens.length - 1 });
    }

    const result: SyllableList = [];

    for (const group of groups) {
        const source = words.slice(group.sourceStart, group.sourceEnd + 1);
        const start = source[0].StartTime, end = source[source.length - 1].EndTime;
        const targets = tokens.slice(group.targetStart, group.targetEnd + 1);
        const weights = targets.map(t => spokenWeight(t.text));
        const total = weights.reduce((a, b) => a + b, 0);

        let elapsed = 0;

        targets.forEach((token, i) => {
            const from = start + (end - start) * elapsed / total;
            elapsed += weights[i];

            const to = start + (end - start) * elapsed / total;

            result.push({
                Text: token.text,
                IsPartOfWord: token.joined,
                StartTime: from,
                EndTime: to,
                Emphasized: source.some(w => w.Emphasized && w.StartTime < to && w.EndTime > from)
            });
        });
    }

    return result;
}