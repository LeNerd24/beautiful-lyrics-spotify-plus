import { franc } from 'franc';
import { Lyrics, SyllableSyncedLyrics, TextMetadata } from '../Types/lyrics-types';
import { localRomanization } from './romanization';
import { lyricText } from './word-alignment';
import { cloneLyrics } from './platform-utils';
import { createVocalGrouper } from './vocal-grouping';

type NaturalAlignment = 'right' | 'left';
type RomanizedLanguage = 'chinese' | 'japanese' | 'korean' | 'hindi' | 'none';
type BaseInformation = {
    naturalAlignment: NaturalAlignment;
    language: string;
    romanizedLanguage?: RomanizedLanguage;
}

export type TransformedLyrics = (BaseInformation & Lyrics);
type TransformedSyllableLyrics = BaseInformation & SyllableSyncedLyrics;

const rightToLeftLanguages = [

    'pes', 'urd',

    'arb', 'uig',

    'heb', 'ydd',

    'men'
];

export const minimumInterludeDuration: number = 4;
export const endInterludeEarly: number = 0.25;

// Unrestricted, franc routinely scores lyrics as a close relative of their actual language (English as Scots or Low German,
// Hindi as Magahi or Bhojpuri), so only languages songs are realistically written in are considered
const lyricLanguages = [
    'eng', 'spa', 'por', 'fra', 'deu', 'ita', 'nld', 'swe', 'nob', 'dan', 'fin', 'pol', 'ces', 'slk', 'hun', 'ron', 'tur',
    'rus', 'ukr', 'bel', 'bul', 'srp', 'hrv', 'bos', 'slv', 'mkd', 'ell', 'als', 'lit', 'lvs', 'ekk', 'cat', 'glg',
    'cmn', 'jpn', 'kor', 'hin', 'ben', 'pan', 'tam', 'tel', 'kan', 'mal', 'mar', 'guj', 'urd', 'npi', 'sin',
    'pes', 'arb', 'heb', 'tha', 'vie', 'ind', 'zlm', 'tgl', 'swh', 'yor', 'hau', 'ibo', 'zul', 'xho', 'afr', 'amh', 'som',
    'kat', 'hye', 'azj', 'kaz', 'uzn', 'khk', 'khm', 'lao', 'mya',
];
const detectLanguage = (text: string): string => franc(text, { only: lyricLanguages });

const getNaturalAlignment = (language: string): NaturalAlignment => { return rightToLeftLanguages.includes(language) ? 'right' : 'left'; }

const generateRomanization = <L extends TextMetadata>(metadata: L, information: BaseInformation): void => {
    metadata.RomanizedText = localRomanization(metadata.Text, information.language);
    if (information.language === 'kor') information.romanizedLanguage = 'korean';
    if (information.language === 'cmn') information.romanizedLanguage = 'chinese';
    if (information.language === 'jpn') information.romanizedLanguage = 'japanese';
    if (information.language === 'hin') information.romanizedLanguage = 'hindi';
};

const applyLyricOffset = (lyrics: Lyrics): void => {
    const offset = lyrics.LyricOffset;
    delete lyrics.LyricOffset;
    if (lyrics.Type === 'Static' || !offset || !Number.isFinite(offset)) return;

    const shift = (timed: { StartTime: number; EndTime: number }) => {
        timed.StartTime = Math.max(0, timed.StartTime + offset);
        timed.EndTime = Math.max(0, timed.EndTime + offset);
    };

    shift(lyrics);

    if (lyrics.Type === 'Line') {
        for (const vocal of lyrics.Content) shift(vocal);
        return;
    }

    for (const vocalGroup of lyrics.Content) {
        if (vocalGroup.Type !== 'Vocal') {
            shift(vocalGroup);
            continue;
        }

        for (const vocal of [vocalGroup.Lead, ...(vocalGroup.Background ?? [])]) {
            shift(vocal);
            for (const syllable of vocal.Syllables) shift(syllable);
        }
    }
};

export function createLyricsTransformer() {
    const groupVocals = createVocalGrouper();
    let previousInput: SyllableSyncedLyrics | undefined;
    let previousSource: SyllableSyncedLyrics | undefined;
    let previousResult: TransformedSyllableLyrics | undefined;

    return (source: SyllableSyncedLyrics): TransformedSyllableLyrics => {
        if (source === previousInput && previousResult) return previousResult;
        const input = source;
        const content = groupVocals(source.Content);
        if (content !== source.Content) source = { ...source, Content: content };
        if (!source.GenerationId || source.GenerationId !== previousSource?.GenerationId || !previousResult || previousSource.Content.length !== source.Content.length) {
            const result = transformLyrics(source) as TransformedSyllableLyrics;
            previousInput = input;
            previousSource = source;
            previousResult = result;
            return result;
        }

        const previous = previousResult;
        const information: BaseInformation = { language: previous.language, naturalAlignment: previous.naturalAlignment, romanizedLanguage: previous.romanizedLanguage };
        const transformedContent = source.Content.map((line, index) => {
            if (line === previousSource!.Content[index]) return previous.Content[index];
            const copy = cloneLyrics(line);
            if (copy.Type === 'Vocal') {
                const before = previousSource!.Content[index], result = previous.Content[index];
                const transformVocal = (vocal: typeof copy.Lead, original: typeof copy.Lead, oldSource?: typeof copy.Lead, oldResult?: typeof copy.Lead) => {
                    if (original === oldSource && oldResult) return oldResult;
                    for (const syllable of vocal.Syllables) generateRomanization(syllable, information);
                    return vocal;
                };
                if (line.Type === 'Vocal' && before.Type === 'Vocal' && result.Type === 'Vocal') {
                    copy.Lead = transformVocal(copy.Lead, line.Lead, before.Lead, result.Lead);
                    copy.Background = copy.Background?.map((vocal, i) => transformVocal(vocal, line.Background![i], before.Background?.[i], result.Background?.[i]));
                } else {
                    for (const vocal of [copy.Lead, ...(copy.Background ?? [])]) transformVocal(vocal, vocal);
                }
            }
            return copy;
        });
        const unchanged = transformedContent.every((line, index) => line === previous.Content[index]);
        previousInput = input;
        previousSource = source;
        previousResult = { ...source, ...information, Content: unchanged ? previous.Content : transformedContent };
        return previousResult;
    };
}

export const transformLyrics = (providerLyrics: Lyrics, interludeDuration = minimumInterludeDuration): TransformedLyrics => {
    if (!providerLyrics || !['Static', 'Line', 'Syllable'].includes(providerLyrics.Type)) throw new Error('No lyrics available for this song.');
    const lyrics = cloneLyrics(providerLyrics) as TransformedLyrics;
    applyLyricOffset(lyrics);
    if (lyrics.Type === 'Syllable') lyrics.Content = createVocalGrouper()(lyrics.Content);

    if (lyrics.Type === 'Static') {
        let textToProcess = lyrics.Lines[0]?.Text ?? '';
        for (let index = 1; index < lyrics.Lines.length; index += 1) {
            textToProcess += `\n${lyrics.Lines[index].Text}`;
        }

        const language = detectLanguage(textToProcess);

        lyrics.language = language;
        lyrics.naturalAlignment = getNaturalAlignment(language);

        for (const metadata of lyrics.Lines) {
            generateRomanization(metadata, lyrics);
        }
    } else if (lyrics.Type === 'Line') {
        const Lines = [];
        for (const vocalGroup of lyrics.Content) {
            if (vocalGroup.Type === 'Vocal') {
                Lines.push(vocalGroup.Text);
            }
        }

        const textToProcess = Lines.join('\n');
        const language = detectLanguage(textToProcess);

        lyrics.language = language;
        lyrics.naturalAlignment = getNaturalAlignment(language);

        for (const vocalGroup of lyrics.Content) {
            if (vocalGroup.Type === 'Vocal') {
                generateRomanization(vocalGroup, lyrics);
            }
        }
    } else if (lyrics.Type === 'Syllable') {
        const Lines = [];
        for (const vocalGroup of lyrics.Content) {
            if (vocalGroup.Type === 'Vocal') {
                const text = lyricText(vocalGroup.Lead.Syllables);

                Lines.push(text);
            }
        }

        const textToProcess = Lines.join('\n');
        const language = detectLanguage(textToProcess);

        lyrics.language = language;
        lyrics.naturalAlignment = getNaturalAlignment(language);

        for (const vocalGroup of lyrics.Content) {
            if (vocalGroup.Type === 'Vocal') {
                for (const syllable of vocalGroup.Lead.Syllables) {
                    generateRomanization(syllable, lyrics);
                }

                if (vocalGroup.Background !== undefined) {
                    for (const syllable of vocalGroup.Background.flatMap(vocal => vocal.Syllables)) {
                        generateRomanization(syllable, lyrics);
                    }
                }
            }
        }
    }

    if (lyrics.Type === 'Static' || lyrics.Type === 'Syllable' && lyrics.GenerationId) return lyrics;

    const vocalTimes: {
        startTime: number;
        endTime: number;
    }[] = [];

    if (lyrics.Type === 'Line') {
        for (const vocal of lyrics.Content) {
            vocalTimes.push({
                startTime: vocal.StartTime,
                endTime: vocal.EndTime
            });
        }
    } else if (lyrics.Type === 'Syllable') {
        for (const vocal of lyrics.Content) {
            if (vocal.Type === 'Vocal') {
                let startTime = vocal.Lead.StartTime;
                let endTime = vocal.Lead.EndTime;

                if (vocal.Background !== undefined) {
                    for (const backgroundVocal of vocal.Background) {
                        startTime = Math.min(startTime, backgroundVocal.StartTime);
                        endTime = Math.max(endTime, backgroundVocal.EndTime);
                    }
                }

                vocalTimes.push({
                    startTime: startTime,
                    endTime: endTime
                });
            }
        }
    }

    let addedStartInterlude = false;

    const firstVocalGroup = vocalTimes[0];
    if (firstVocalGroup && firstVocalGroup.startTime >= interludeDuration) {
        vocalTimes.unshift({ startTime: -1, endTime: -1 });
        lyrics.Content.unshift({
            Type: 'Interlude',
            StartTime: 0,
            EndTime: (firstVocalGroup.startTime - endInterludeEarly)
        });

        addedStartInterlude = true;
    }

    for (let index = (vocalTimes.length - 1); index > (addedStartInterlude ? 1 : 0); index -= 1) {
        const endingVocalGroup = vocalTimes[index];
        const startingVocalGroup = vocalTimes[index - 1];

        if ((endingVocalGroup.startTime - startingVocalGroup.endTime) >= interludeDuration) {
            vocalTimes.splice(index, 0, { startTime: -1, endTime: -1 });
            lyrics.Content.splice(index, 0, {
                Type: 'Interlude',
                StartTime: startingVocalGroup.endTime,
                EndTime: (endingVocalGroup.startTime - endInterludeEarly)
            });
        }
    }

    return lyrics;
}
