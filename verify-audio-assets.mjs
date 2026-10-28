import { createHash } from 'node:crypto';
import { existsSync, readFileSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { gunzipSync } from 'node:zlib';

const root = new URL('./', import.meta.url);
const runtime = readFileSync(new URL('src/native/app/src/main/java/com/lenerd/lyricsnative/generation/SpotifyAudioRuntime.java', root), 'utf8');
const gadget = runtime.match(/GADGET_ASSET = "([^"]+)"/)[1];
const assets = [gadget, 'assets/native/spotify-cache-reader.js', 'assets/native/spotify-remote-reader.js'];
const missing = assets.filter(asset => {
    const path = new URL(asset, root);
    return !existsSync(path) || !statSync(path).isFile() || statSync(path).size === 0;
});

if (missing.length) throw new Error(`Required Spotify audio assets are missing or empty:\n${missing.map(asset => fileURLToPath(new URL(asset, root))).join('\n')}\nRestore these assets before building or publishing the extension.`);

const expectedBytes = Number(runtime.match(/GADGET_BYTES = ([\d_]+)/)[1].replaceAll('_', ''));
const expectedHash = runtime.match(/GADGET_SHA256 = "([a-f0-9]+)"/)[1];
const library = gunzipSync(readFileSync(new URL(gadget, root)), { maxOutputLength: expectedBytes });
if (library.length !== expectedBytes || createHash('sha256').update(library).digest('hex') !== expectedHash) throw new Error('The Spotify audio runtime asset does not match its pinned size and SHA-256.');

console.log('Spotify audio assets verified.');
