import { SyllableSyncedLyrics, SyllableVocal, SyllableVocalSet } from '../Types/lyrics-types';

type Content = SyllableSyncedLyrics['Content'];

function backingPhrases(text: string): string[] | undefined {
    const phrases: string[] = [];
    let depth = 0, phrase = '';
    for (const character of text) {
        if (character === '(') {
            if (depth++ === 0) phrase = '';
        } else if (character === ')' && depth > 0) {
            if (--depth === 0 && phrase.trim()) phrases.push(phrase.trim());
        } else if (depth > 0) {
            phrase += character;
        } else if (character.trim()) {
            return undefined;
        }
    }
    if (depth > 0 && phrase.trim()) phrases.push(phrase.trim());
    return phrases.length ? phrases : undefined;
}

export function createVocalGrouper() {
    const pending = new WeakMap<SyllableVocalSet, SyllableVocalSet>();
    const attached = new WeakMap<SyllableVocalSet, { backing: SyllableVocal[]; result: SyllableVocalSet }>();

    return (content: Content): Content => {
        const rows: Content = [];
        const additions = new Map<number, SyllableVocal[]>();
        let parent = -1;

        for (const original of content) {
            if (original.Type !== 'Vocal') {
                rows.push(original);
                parent = -1;
                continue;
            }
            let row = original;
            if (row.Pending && row.Lead.Syllables.length === 1) {
                const cached = pending.get(row);
                const phrases = cached ? undefined : backingPhrases(row.Lead.Syllables[0].Text);
                if (cached) row = cached;
                else if (phrases) {
                    row = { ...row, Lead: { StartTime: row.Lead.StartTime, EndTime: row.Lead.StartTime, Syllables: [] }, Background: phrases.map(Text => ({ StartTime: original.Lead.StartTime, EndTime: original.Lead.EndTime, Pending: true, Syllables: [{ Text, StartTime: original.Lead.StartTime, EndTime: original.Lead.EndTime, IsPartOfWord: false }] })) };
                }
                if (!cached) pending.set(original, row);
            }

            if (!row.Lead.Syllables.length && row.Background?.length && parent >= 0) {
                const backing = additions.get(parent) ?? [];
                backing.push(...row.Background);
                additions.set(parent, backing);
            } else {
                rows.push(row);
                if (row.Lead.Syllables.length) parent = rows.length - 1;
            }
        }

        for (const [index, backing] of additions) {
            const row = rows[index] as SyllableVocalSet;
            const cached = attached.get(row);
            if (cached && backing.length === cached.backing.length && backing.every((vocal, i) => vocal === cached.backing[i])) rows[index] = cached.result;
            else {
                const result = { ...row, Background: [...(row.Background ?? []), ...backing] };
                attached.set(row, { backing, result });
                rows[index] = result;
            }
        }
        return rows.length === content.length && rows.every((row, index) => row === content[index]) ? content : rows;
    };
}
