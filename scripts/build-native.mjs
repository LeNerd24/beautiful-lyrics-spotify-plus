import { copyFileSync, existsSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root = fileURLToPath(new URL('../', import.meta.url));
const sdk = new URL('../../SpotifyPlus/spotifyplus-sdk/build/outputs/aar/spotifyplus-sdk-release.aar', import.meta.url);
mkdirSync(new URL('../src/native/lib/', import.meta.url), { recursive: true });
copyFileSync(sdk, new URL('../src/native/lib/spotifyplus-sdk.aar', import.meta.url));

mkdirSync(new URL('../src/native/app/src/main/assets/images/', import.meta.url), { recursive: true });
for (const name of ['microphone', 'sparkle', 'globe', 'subtitle']) {
    const svg = readFileSync(new URL(`../assets/images/${name}.svg`, import.meta.url), 'utf8');
    const box = svg.match(/viewBox="([^"]+)"/)[1].split(/\s+/).map(Number);
    const paths = [...svg.matchAll(/<path\s+d="([^"]+)"/g)].map(match => match[1]);
    writeFileSync(new URL(`../src/native/app/src/main/assets/images/${name}.json`, import.meta.url), JSON.stringify({ box, paths }));
}
mkdirSync(new URL('../src/native/app/src/main/assets/licenses/', import.meta.url), { recursive: true });
const nativeApk = new URL('../src/native/app/build/outputs/apk/debug/app-debug.apk', import.meta.url);
rmSync(nativeApk, { force: true });
execFileSync('cmd.exe', ['/d', '/s', '/c', 'src\\native\\gradlew.bat -p src/native :app:assembleDebug --console=plain'], { cwd: root, stdio: 'inherit', windowsHide: true });
if (statSync(nativeApk).size > 32 * 1024 * 1024) {
    throw new Error('Native APK exceeds the 32 MB hot-reload limit. Reuse host dependencies before publishing the APK.');
}
copyFileSync(nativeApk, new URL('../lyrics.apk', import.meta.url));
