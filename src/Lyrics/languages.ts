import { iso6393To1 } from 'iso-639-3';

const aliases: Record<string, string> = { cmn: 'zh-CN', zho: 'zh-CN', zh: 'zh-CN', yue: 'zh-TW', pes: 'fa', arb: 'ar', heb: 'he', iw: 'he', in: 'id', fil: 'tl' };

export function languageCode(input: string): string {
    const code = input.trim().replace(/_/g, '-').toLowerCase();
    if (code === 'zh-tw' || code === 'zh-hant') return 'zh-TW';
    if (code === 'zh-cn' || code === 'zh-hans') return 'zh-CN';
    return aliases[code] ?? iso6393To1[code] ?? code.split('-')[0];
}

export function targetLanguage(input: string): string {
    const code = input.trim().replace(/_/g, '-');
    const parts = code.split('-');
    if (!/^[a-z]{2}(?:-[a-z]{2})?$/i.test(code) || !Object.values(iso6393To1).includes(parts[0].toLowerCase())) {
        throw new Error('Enter a supported language code, such as en, fr, ja, ko, or zh-CN.');
    }
    return parts[0].toLowerCase() + (parts.length > 1 ? '-' + parts[1].toUpperCase() : '');
}

export function sameLanguage(source: string, target: string): boolean { return languageCode(source).split('-')[0] === languageCode(target).split('-')[0]; }

export function supportsPronunciation(source: string): boolean {
    return ['ko', 'ja', 'zh', 'ru', 'uk', 'bg', 'sr', 'mk', 'el', 'hi', 'bn', 'ta', 'te', 'gu', 'kn', 'ka', 'hy', 'ar', 'fa', 'he', 'th'].includes(languageCode(source).split('-')[0]);
}
