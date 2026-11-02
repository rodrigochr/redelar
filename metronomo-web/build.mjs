// Gera a versão web do BluesTempo.
//   dist/           site completo para o GitHub Pages (PWA: funciona offline, ícone na tela inicial)
//   dist-artifact/  a mesma página sem o invólucro de documento, para publicar como página do Claude
// Os sons vêm dos samples do app Android (metronomo-android/app/src/main/assets/samples).
import { build } from 'esbuild';
import { createHash } from 'node:crypto';
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

const ROOT = dirname(fileURLToPath(import.meta.url));
const SRC = join(ROOT, 'src');
const SAMPLES = join(ROOT, '..', 'metronomo-android', 'app', 'src', 'main', 'assets', 'samples');

function reset(dir) {
  rmSync(dir, { recursive: true, force: true });
  mkdirSync(dir, { recursive: true });
}

/** Pacote "BTB1": manifesto + PCM 16 bits em delta, tudo em gzip (sem perdas). */
function sampleBundle() {
  const manifest = JSON.parse(readFileSync(join(SAMPLES, 'manifest.json'), 'utf8'));
  const names = [
    ...Object.values(manifest.drums).flatMap((layers) => layers.flatMap((l) => l.files)),
    ...Object.values(manifest.bass).flatMap((regions) => regions.flatMap((r) => r.files)),
  ];
  const sounds = [];
  const chunks = [];
  for (const name of names) {
    const buf = readFileSync(join(SAMPLES, name));
    if (buf.toString('ascii', 0, 4) !== 'BTS1') throw new Error(`Formato inválido: ${name}`);
    const rate = buf.readInt32LE(4);
    const frames = buf.readInt32LE(8);
    const delta = Buffer.alloc(frames * 2);
    let prev = 0;
    for (let i = 0; i < frames; i++) {
      const v = buf.readInt16LE(12 + i * 2);
      delta.writeInt16LE(((v - prev) << 16) >> 16, i * 2);
      prev = v;
    }
    sounds.push({ name, rate, frames });
    chunks.push(delta);
  }
  const json = Buffer.from(JSON.stringify({ drums: manifest.drums, bass: manifest.bass, credits: manifest.credits, sounds }));
  const header = Buffer.alloc(8);
  header.write('BTB1', 0, 'ascii');
  header.writeUInt32LE(json.length, 4);
  return gzipSync(Buffer.concat([header, json, ...chunks]), { level: 9 });
}

const pageHead = (version) => `<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="theme-color" content="#151c2e">
<meta name="apple-mobile-web-app-capable" content="yes">
<meta name="mobile-web-app-capable" content="yes">
<meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
<meta name="apple-mobile-web-app-title" content="BluesTempo">
<link rel="manifest" href="manifest.webmanifest">
<link rel="icon" href="icons/icon-192.png">
<link rel="apple-touch-icon" href="icons/apple-touch-icon.png">
<style>:root{padding-top:env(safe-area-inset-top,0px);padding-bottom:env(safe-area-inset-bottom,0px)}</style>
<script>window.BT_PWA = true; window.BT_VERSION = "${version}";</script>
</head>
<body>
`;

async function main() {
  const dist = join(ROOT, 'dist');
  const artifact = join(ROOT, 'dist-artifact');
  reset(dist);
  reset(artifact);

  const js = await build({
    entryPoints: [join(SRC, 'js', 'main.js')],
    bundle: true,
    minify: true,
    format: 'iife',
    target: ['es2019', 'safari13'],
    write: false,
    loader: { '.json': 'json' },
    legalComments: 'none',
  });
  const appJs = js.outputFiles[0].contents;
  const appCss = readFileSync(join(SRC, 'app.css'));
  const fragment = readFileSync(join(SRC, 'app.html'), 'utf8');
  const samples = sampleBundle();
  const version = createHash('sha1').update(appJs).update(appCss).update(fragment).update(samples).digest('hex').slice(0, 10);

  // 1 s de silêncio (16 bits, 8 kHz): mantém o iPhone na categoria "reprodução" de áudio.
  const silence = Buffer.alloc(44 + 16000);
  silence.write('RIFF', 0); silence.writeUInt32LE(36 + 16000, 4); silence.write('WAVEfmt ', 8);
  silence.writeUInt32LE(16, 16); silence.writeUInt16LE(1, 20); silence.writeUInt16LE(1, 22);
  silence.writeUInt32LE(8000, 24); silence.writeUInt32LE(16000, 28); silence.writeUInt16LE(2, 32); silence.writeUInt16LE(16, 34);
  silence.write('data', 36); silence.writeUInt32LE(16000, 40);

  for (const dir of [dist, artifact]) {
    writeFileSync(join(dir, 'silence.wav'), silence);
    writeFileSync(join(dir, 'app.js'), appJs);
    writeFileSync(join(dir, 'app.css'), appCss);
    writeFileSync(join(dir, 'samples.bin'), samples);
  }
  // Hospedagens que só servem tipos "web" (texto, mídia…) recebem os sons em base64.
  rmSync(join(artifact, 'samples.bin'));
  writeFileSync(join(artifact, 'samples.txt'), samples.toString('base64'));
  writeFileSync(join(artifact, 'index.html'), fragment.replace('<script src="app.js"></script>',
    '<script>window.BT_SAMPLES_URL = "samples.txt";</script>\n<script src="app.js"></script>'));

  // Site/PWA
  writeFileSync(join(dist, 'index.html'), `${pageHead(version)}${fragment.replace('<script src="app.js"></script>', `<script src="app.js?v=${version}"></script>`)}\n</body>\n</html>\n`);
  mkdirSync(join(dist, 'icons'), { recursive: true });
  for (const f of readdirSync(join(SRC, 'icons'))) copyFileSync(join(SRC, 'icons', f), join(dist, 'icons', f));
  writeFileSync(join(dist, 'manifest.webmanifest'), JSON.stringify({
    name: 'BluesTempo — Metrônomo & Levadas',
    short_name: 'BluesTempo',
    lang: 'pt-BR',
    start_url: './',
    scope: './',
    display: 'standalone',
    orientation: 'portrait',
    background_color: '#0d1220',
    theme_color: '#151c2e',
    icons: [
      { src: 'icons/icon-192.png', sizes: '192x192', type: 'image/png' },
      { src: 'icons/icon-512.png', sizes: '512x512', type: 'image/png' },
      { src: 'icons/icon-512.png', sizes: '512x512', type: 'image/png', purpose: 'maskable' },
    ],
  }, null, 2));
  const sw = readFileSync(join(SRC, 'sw.js'), 'utf8').replace('__VERSION__', version);
  writeFileSync(join(dist, 'sw.js'), sw);
  writeFileSync(join(dist, '.nojekyll'), '');

  const kb = (b) => `${(b.length / 1024).toFixed(0)} KB`;
  console.log(`versão ${version}: app.js ${kb(appJs)}, app.css ${kb(appCss)}, samples.bin ${kb(samples)}`);
  if (!existsSync(join(dist, 'icons', 'apple-touch-icon.png'))) console.warn('aviso: ícones ausentes em src/icons');
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
