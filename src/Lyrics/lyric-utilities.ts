import { franc } from 'franc';
import { Lyrics, TextMetadata } from '../Types/lyrics-types';
import { localRomanization } from './romanization';
import { lyricText } from './word-alignment';
import { cloneLyrics } from './platform-utils';

type NaturalAlignment = 'right' | 'left';
type RomanizedLanguage = 'chinese' | 'japanese' | 'korean' | 'none';
type BaseInformation = {
    naturalAlignment: NaturalAlignment;
    language: string;
    romanizedLanguage?: RomanizedLanguage;
}

export type TransformedLyrics = (BaseInformation & Lyrics);

const rightToLeftLanguages = [
    // Persian
    'pes', 'urd',

    // Arabic
    'arb', 'uig',

    // Hebrew
    'heb', 'ydd',

    // Mende
    'men'
];

const minimumInterludeDuration: number = 4;
const endInterludeEarly: number = 0.25;

const getNaturalAlignment = (language: string): NaturalAlignment => {
    return rightToLeftLanguages.includes(language) ? 'right' : 'left';
}

const generateRomanization = <L extends TextMetadata>(metadata: L, information: BaseInformation): void => {
    metadata.RomanizedText = localRomanization(metadata.Text, information.language);
    if (information.language === 'kor') information.romanizedLanguage = 'korean';
    if (information.language === 'cmn') information.romanizedLanguage = 'chinese';
    if (information.language === 'jpn') information.romanizedLanguage = 'japanese';
};
export const transformLyrics = (providerLyrics: Lyrics): TransformedLyrics => {
    if (!providerLyrics || !['Static', 'Line', 'Syllable'].includes(providerLyrics.Type)) throw new Error('No lyrics available for this song.');
    const lyrics = cloneLyrics(providerLyrics) as TransformedLyrics;

    if (lyrics.Type === 'Static') {
        let textToProcess = lyrics.Lines[0]?.Text ?? '';
        for (let index = 1; index < lyrics.Lines.length; index += 1) {
            textToProcess += `\n${lyrics.Lines[index].Text}`;
        }

        const language = franc(textToProcess);

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
        const language = franc(textToProcess);

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
        const language = franc(textToProcess);

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

    if (lyrics.Type === 'Static') return lyrics;

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
    if (firstVocalGroup && firstVocalGroup.startTime >= minimumInterludeDuration) {
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

        if ((endingVocalGroup.startTime - startingVocalGroup.endTime) >= minimumInterludeDuration) {
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
