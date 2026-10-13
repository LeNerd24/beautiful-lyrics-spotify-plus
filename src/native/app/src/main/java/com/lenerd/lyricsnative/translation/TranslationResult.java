package com.lenerd.lyricsnative.translation;

public final class TranslationResult {
    public final String sourceText;
    public final String translatedText;
    public final String detectedLanguage;

    public TranslationResult(String sourceText, String translatedText, String detectedLanguage) {
        this.sourceText = sourceText;
        this.translatedText = translatedText;
        this.detectedLanguage = detectedLanguage;
    }
}
