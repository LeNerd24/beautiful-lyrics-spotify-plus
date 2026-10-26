import { ExtensionSetting, SpotifyPlus } from 'spotifyplus';
import { isLyricsOpen, LyricsOpenTrigger, LyricsOverlay, openLyrics } from './Components/lyrics-overlay';
import { canGenerate, GenerationBegin, GenerationLine, GenerationProgress, GenerationResult, generationStore, startGeneration } from './Lyrics/generation-state';

const sparkles = SpotifyPlus.Assets.image('assets/images/sparkles.png');
const musicNote = SpotifyPlus.Assets.image('assets/images/music-note.png');

const animationSetting: Extract<ExtensionSetting, { type: 'select' }> = {
    id: 'lyrics-animation-style',
    type: 'select',
    label: 'Word animation',
    description: 'Applies to word-by-word lyrics.',
    value: SpotifyPlus.Platform.Storage.get('lyrics-animation-style') === 'apple' ? 'apple' : 'beautiful',
    options: [{ label: 'Beautiful Lyrics', value: 'beautiful' }, { label: 'Apple Music', value: 'apple' }]
};

SpotifyPlus.Settings.registerSetting({ title: 'Lyrics', items: [animationSetting] });

SpotifyPlus.on('settings.changed', (payload: unknown) => {
    const setting = payload as ExtensionSetting | undefined;

    if (setting?.id === 'lyrics-animation-style' && setting.type === 'select') {
        animationSetting.value = setting.value === 'apple' ? 'apple' : 'beautiful';
        SpotifyPlus.Platform.Storage.set(setting.id, animationSetting.value);
    }
});

const toggleAnimationStyle = () => {
    animationSetting.value = animationSetting.value === 'apple' ? 'beautiful' : 'apple';
    SpotifyPlus.Platform.Storage.set(animationSetting.id, animationSetting.value);

    SpotifyPlus.Events.emit('lyrics.animationStyleChanged', animationSetting.value);
    SpotifyPlus.toast(`${animationSetting.value === 'apple' ? 'Apple Music' : 'Beautiful Lyrics'} word animation`);
};

new SpotifyPlus.ContextMenu('Beautiful Lyrics', toggleAnimationStyle, () => animationSetting.value !== 'apple', false, 'track').register();
new SpotifyPlus.ContextMenu('Apple Music', toggleAnimationStyle, () => animationSetting.value === 'apple', false, 'track').register();

let lastGenerationStage = '';

SpotifyPlus.Events.on('lyrics.generate.begin', (payload: unknown) => generationStore.begin(payload as GenerationBegin));
SpotifyPlus.Events.on('lyrics.generate.line', (payload: unknown) => generationStore.line(payload as GenerationLine));

SpotifyPlus.Events.on('lyrics.generate.progress', (payload: unknown) => {
    generationStore.progress(payload as GenerationProgress);
    const { trackId, stage, message } = payload as GenerationProgress;
    if (`${trackId}:${stage}` === lastGenerationStage) return;

    lastGenerationStage = `${trackId}:${stage}`;
    SpotifyPlus.toast(message);
});

SpotifyPlus.Events.on('lyrics.generate.result', (payload: unknown) => {
    const result = payload as GenerationResult;
    generationStore.result(result);
    lastGenerationStage = '';
    SpotifyPlus.toast(result.status === 'complete' ? 'Lyrics are ready!' : result.error ?? 'Could not generate lyrics', 'long');
});

new SpotifyPlus.ContextMenu('Generate Lyrics', (uri) => {
    const trackId = /^spotify:track:([A-Za-z0-9]{22})$/.exec(uri)?.[1];
    if (!trackId) return SpotifyPlus.toast('Lyrics can only be generated for Spotify tracks');
    if (!isLyricsOpen() || generationStore.busy() || SpotifyPlus.Player.getCurrentTrack()?.id !== trackId || !canGenerate(trackId)) return;

    startGeneration(trackId);
}, (uri) => {
    const track = SpotifyPlus.Player.getCurrentTrack();
    return isLyricsOpen() && !generationStore.busy() && !!track && uri === track.uri && canGenerate(track.id);
}, false, 'track', sparkles).register();

new SpotifyPlus.ContextMenu('Open Lyrics', (uri) => {
    if (uri === SpotifyPlus.Player.getCurrentTrack()?.uri) openLyrics();
}, (uri) => !isLyricsOpen() && uri === SpotifyPlus.Player.getCurrentTrack()?.uri, false, 'track', musicNote).register();

SpotifyPlus.UI.overlay('nowPlaying.page', LyricsOverlay);
SpotifyPlus.UI.replace('lyrics.page', LyricsOpenTrigger);
