import { pinyin } from 'pinyin-pro';
import { transliterate } from 'transliteration';
import { toRomaji } from 'wanakana';
import Aromize from './Aromize';
import { languageCode, supportsPronunciation } from './languages';

export function localRomanization(text: string, language: string): string | undefined {
    const code = languageCode(language).split('-')[0];

    if (!supportsPronunciation(language)) return undefined;
    if (code === 'ko') return Aromize(text, 'RevisedRomanizationTranscription');
    if (code === 'zh') return pinyin(text, { toneType: 'symbol', nonZh: 'consecutive' });
    if (code === 'ja') return /[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\u{20000}-\u{323af}]/u.test(text) ? undefined : toRomaji(text);

    return transliterate(text);
}
