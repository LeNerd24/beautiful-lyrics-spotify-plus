import Sanscript from '@indic-transliteration/sanscript';

// Sanscript handles the Devanagari -> Latin mapping, but its schemes are Sanskrit-oriented ("tuma hī ho").
// This adds a casual Hindi scheme plus schwa deletion so lyrics read the way people write them ("tum hi ho").

const devanagari = Sanscript.schemes.devanagari;
// Sanscript reads Brahmic input one code point at a time, so nukta letters must be precomposed to match.
const nuktaForms = new Map(Object.keys(devanagari.extra_consonants).filter(key => [...key].length === 1).map(key => [key.normalize('NFD'), key]));
const nuktaLatin = new Map(Object.entries({
    'क़': 'q', 'ख़': 'kh', 'ग़': 'gh', 'ज़': 'z', 'ड़': 'd', 'ढ़': 'dh', 'फ़': 'f', 'य़': 'y', 'ऱ': 'r', 'ऴ': 'l', 'ऩ': 'n'
}).map(([key, latin]) => [key.normalize('NFD'), latin]));

Sanscript.addRomanScheme('hindi_casual', {
    isRomanScheme: true as unknown as Record<string, string>,
    vowels: { 'अ': 'a', 'आ': 'aa', 'इ': 'i', 'ई': 'ee', 'उ': 'u', 'ऊ': 'oo', 'ऋ': 'ri', 'ॠ': 'ri', 'ऌ': 'li', 'ॡ': 'li', 'ऎ': 'e', 'ए': 'e', 'ऐ': 'ai', 'ऒ': 'o', 'ओ': 'o', 'औ': 'au', 'ऍ': 'e', 'ऑ': 'o' },
    vowel_marks: { 'ा': 'aa', 'ि': 'i', 'ी': 'ee', 'ु': 'u', 'ू': 'oo', 'ृ': 'ri', 'ॄ': 'ri', 'ॢ': 'li', 'ॣ': 'li', 'ॆ': 'e', 'े': 'e', 'ै': 'ai', 'ॊ': 'o', 'ो': 'o', 'ौ': 'au', 'ॅ': 'e', 'ॉ': 'o' },
    yogavaahas: { 'ं': 'n', 'ः': 'h', 'ँ': 'n' },
    virama: { '्': '' },
    consonants: {
        'क': 'k', 'ख': 'kh', 'ग': 'g', 'घ': 'gh', 'ङ': 'n', 'च': 'ch', 'छ': 'chh', 'ज': 'j', 'झ': 'jh', 'ञ': 'n',
        'ट': 't', 'ठ': 'th', 'ड': 'd', 'ढ': 'dh', 'ण': 'n', 'त': 't', 'थ': 'th', 'द': 'd', 'ध': 'dh', 'न': 'n',
        'प': 'p', 'फ': 'ph', 'ब': 'b', 'भ': 'bh', 'म': 'm', 'य': 'y', 'र': 'r', 'ल': 'l', 'व': 'v',
        'श': 'sh', 'ष': 'sh', 'स': 's', 'ह': 'h', 'ळ': 'l'
    },
    extra_consonants: Object.fromEntries(Object.keys(devanagari.extra_consonants).map(key => [key, nuktaLatin.get(key.normalize('NFD')) ?? ''])),
    symbols: { '०': '0', '१': '1', '२': '2', '३': '3', '४': '4', '५': '5', '६': '6', '७': '7', '८': '8', '९': '9', 'ॐ': 'om', 'ऽ': '', '।': '.', '॥': '.' },
    candra: { 'ॅ': 'e' }
});

const consonant = /[क-हक़-य़ॹ-ॿ]/;
const matra = /[ा-ौॢॣ]/;
const yogavaaha = /[ऀ-ः]/;
const virama = '्';

type Unit = { text: string; consonant: boolean; schwa: boolean; nasal: boolean; cluster: boolean };

function units(word: string): Unit[] {
    const result: Unit[] = [];
    const chars = [...word];

    for (let i = 0; i < chars.length; i++) {
        let text = chars[i];

        if (!consonant.test(text)) {
            while (yogavaaha.test(chars[i + 1] ?? '')) text += chars[++i];
            result.push({ text, consonant: false, schwa: false, nasal: false, cluster: false });
            continue;
        }

        if (chars[i + 1] === '़') text += chars[++i];
        text = nuktaForms.get(text.normalize('NFD')) ?? text.replace('़', '');

        const cluster = result.at(-1)?.text.endsWith(virama) ?? false;
        let schwa = true;

        if (chars[i + 1] === virama || matra.test(chars[i + 1] ?? '')) {
            text += chars[++i];
            schwa = false;
        }

        let nasal = false;
        while (yogavaaha.test(chars[i + 1] ?? '')) { text += chars[++i]; nasal = true; }

        result.push({ text, consonant: true, schwa, nasal, cluster });
    }

    return result;
}

const voiced = (unit?: Unit) => !!unit && (!unit.consonant || !unit.text.endsWith(virama));

// Marks inherent vowels that Hindi speakers drop (कमल -> kamal, समझना -> samajhnaa) by adding a virama.
function deleteSchwas(word: string): string {
    const list = units(word);
    const last = list.at(-1);

    if (list.length > 1 && last?.schwa && !last.nasal && !(last.cluster && /^[यरव]/.test(last.text))) {
        last.text += virama;
        last.schwa = false;
    }

    for (let i = list.length - 2; i > 0; i--) {
        const unit = list[i];
        const previous = list[i - 1];
        const next = list[i + 1];

        if (!unit.schwa || unit.nasal || unit.cluster || previous.nasal || !voiced(previous)) continue;
        if (!next.consonant || !voiced(next)) continue;

        unit.text += virama;
        unit.schwa = false;
    }

    // Anusvara before a labial is pronounced m (संभल -> sambhal).
    return list.map(unit => unit.text).join('').replace(/ं(?=[पफबभम])/g, 'म्');
}

// Common words whose usual spelling doesn't follow the rules.
const spellings: Record<string, string> = { 'में': 'mein' };

function romanizeWord(word: string): string {
    if (spellings[word]) return spellings[word];

    const latin = Sanscript.t(deleteSchwas(word.replace(/ज्ञ/g, 'ग्य')), 'devanagari', 'hindi_casual');

    // Final long vowels are written short (मेरा -> mera, तेरी -> teri), except in short words like जा or माँ.
    return latin.replace(/ee$/, 'i').replace(/oo$/, 'u').replace(/(?<=..)aa$/, 'a');
}

export function romanizeHindi(text: string): string {
    return text.normalize('NFC').replace(/[ऀ-ॿ‌‍]+/g, word => romanizeWord(word));
}
