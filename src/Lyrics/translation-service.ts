import { TransformedLyrics } from './lyric-utilities';
import { SyllableList, SyllableVocal, TextMetadata } from '../Types/lyrics-types';
import { alignTranslation, isEmphasized, lyricText, sourceWords, splitTimedText, SourceWord } from './word-alignment';
import { languageCode, targetLanguage } from './languages';
import { localRomanization } from './romanization';
import { cloneLyrics } from './platform-utils';
import { translateNative, TranslationResult } from './native-translation';

interface LeadRow { metadata: TextMetadata | SyllableVocal; text: string; syllables: SyllableList; words: SourceWord[] }

function leadRows(lyrics: TransformedLyrics): LeadRow[] {
    const locale = languageCode(lyrics.language);

    if (lyrics.Type === 'Static') return lyrics.Lines.map(metadata => ({ metadata, text: metadata.Text, syllables: [], words: [] }));

    return lyrics.Content.filter(item => item.Type === 'Vocal').map(item => {
        const metadata = 'Lead' in item ? item.Lead : item;
        const syllables = 'Syllables' in metadata ? metadata.Syllables : [];

        return {
            metadata,
            text: 'Text' in metadata ? metadata.Text : lyricText(syllables),
            syllables,
            words: syllables.length ? sourceWords(syllables, locale === 'und' ? undefined : locale) : []
        };
    });
}

export async function addTranslations(lyrics: TransformedLyrics, target: string, signal: AbortSignal): Promise<TransformedLyrics> {
    signal.throwIfAborted();

    const result = cloneLyrics(lyrics);
    const rows = leadRows(result).filter(row => row.text.trim());
    const uniqueLines = [...new Set(rows.map(row => row.text.trim()))];

    let normalizedTarget = 'en';

    try {
        normalizedTarget = targetLanguage(target);
    } catch (_) { }

    const results = await translateNative(uniqueLines, normalizedTarget, signal);
    signal.throwIfAborted();

    const translations = new Map<string, TranslationResult>();

    for (const value of results) {
        if (value) translations.set(value.sourceText, value);
    }

    if (uniqueLines.length && !translations.size) {
        throw new Error('No translated lines were returned by the native service.');
    }

    rows.forEach(row => {
        const translated = translations.get(row.text.trim());
        if (!translated) return;
        if ('Text' in row.metadata) row.metadata.TranslatedText = translated.text;

        row.metadata.Translation = alignTranslation(translated.text, row.words, new Map(), normalizedTarget);
    });

    return result;
}

export async function addPronunciation(lyrics: TransformedLyrics, signal: AbortSignal): Promise<TransformedLyrics> {
    const result = cloneLyrics(lyrics);
    const rows = leadRows(result).filter(row => row.text.trim());
    const source = languageCode(result.language);

    signal.throwIfAborted();

    const reading = (text: string) => {
        const value = localRomanization(text, result.language);

        if (!value) throw new Error('Local pronunciation is unavailable for this line.');

        return value;
    };

    rows.forEach(row => {
        signal.throwIfAborted();
        if ('Text' in row.metadata) row.metadata.RomanizedText = reading(row.text);

        if (row.syllables.length) {
            if (source !== 'ja') {
                row.metadata.Pronunciation = row.syllables.map(s => ({
                    ...s,
                    Text: s.RomanizedText ?? localRomanization(s.Text, result.language) ?? s.Text,
                    Emphasized: isEmphasized(s),
                    IsPartOfWord: source.startsWith('zh') ? false : s.IsPartOfWord
                }));
            } else {
                row.metadata.Pronunciation = row.words.flatMap(word => {
                    const tokens = splitTimedText(reading(word.Text), word, 'en');

                    return tokens.map((s, i) => ({ ...s, IsPartOfWord: i === tokens.length - 1 ? false : s.IsPartOfWord }));
                });
            }
        }
    });

    return result;
}
