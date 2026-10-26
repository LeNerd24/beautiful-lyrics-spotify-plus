type TimeMetadata = {
    StartTime: number;
    EndTime: number;
};

export type TextMetadata = {
    Text: string;
    RomanizedText?: string;
    TranslatedText?: string;
    Pronunciation?: SyllableList;
    Translation?: SyllableList;
};

type VocalMetadata = (TimeMetadata & TextMetadata);

export type Interlude = (
    TimeMetadata & { Type: 'Interlude' }
);

export type StaticSyncedLyrics = {
    Type: 'Static',
    SongWriters: string[],
    LyricOffset?: number,
    source?: string,
    Lines: TextMetadata[]
};

export type LineVocal = (
    VocalMetadata & { Type: 'Vocal', OppositeAligned: boolean; }
);

export type LineSyncedLyrics = (
    TimeMetadata & { Type: 'Line', SongWriters?: string[], LyricOffset?: number, source?: string, Content: (LineVocal | Interlude)[] }
);

export type SyllableMetadata = (
    VocalMetadata & { IsPartOfWord: boolean; Emphasized?: boolean; }
);

export type SyllableList = SyllableMetadata[];
export type SyllableVocal = (
    TimeMetadata & { Syllables: SyllableList; Pending?: boolean; Pronunciation?: SyllableList; Translation?: SyllableList; }
);

export type SyllableVocalSet = {
    Type: 'Vocal',
    OppositeAligned: boolean;
    Lead: SyllableVocal;
    Background?: SyllableVocal[];
    Pending?: boolean;
};

export type SyllableSyncedLyrics = (
    TimeMetadata & { Type: 'Syllable', Partial?: boolean; GenerationId?: string; ai?: boolean; SongWriters: string[], LyricOffset?: number, source?: string, Content: (SyllableVocalSet | Interlude)[] }
);

export type Lyrics = (StaticSyncedLyrics | LineSyncedLyrics | SyllableSyncedLyrics);
