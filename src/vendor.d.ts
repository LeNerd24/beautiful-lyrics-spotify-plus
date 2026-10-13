declare module '@iamtraction/google-translate/src/languages' {
    const languages: { isSupported(code: string): boolean };
    export default languages;
}
