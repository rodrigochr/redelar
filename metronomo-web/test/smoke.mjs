// Teste de fumaça no navegador (Chromium via Playwright): abre o site gerado em dist/ numa tela
// de iPhone, carrega os instrumentos, toca metrônomo e levada, navega pelas abas, renderiza áudio
// real pelo grafo do Web Audio e tira screenshots. Uso: node test/smoke.mjs [pasta-de-saida]
import { createServer } from 'node:http';
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { extname, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const ROOT = process.env.BT_ROOT || join(dirname(fileURLToPath(import.meta.url)), '..', 'dist');
const OUT = process.argv[2] || join(dirname(fileURLToPath(import.meta.url)), 'out');
mkdirSync(OUT, { recursive: true });

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.webmanifest': 'application/manifest+json', '.png': 'image/png', '.bin': 'application/octet-stream', '.txt': 'text/plain' };
const server = createServer((req, res) => {
  const path = decodeURIComponent(new URL(req.url, 'http://x').pathname);
  const file = join(ROOT, path.endsWith('/') ? path + 'index.html' : path);
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'content-type': TYPES[extname(file)] || 'application/octet-stream', 'content-length': body.length });
    res.end(body);
  } catch (e) {
    res.writeHead(404);
    res.end();
  }
}).listen(0);
const base = `http://localhost:${server.address().port}/`;

const errors = [];
const check = (cond, msg) => {
  if (!cond) errors.push(msg);
  console.log(`${cond ? '✓' : '✗'} ${msg}`);
};

const browser = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
const page = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
page.on('console', (m) => { if (m.type() === 'error') errors.push('console: ' + m.text()); });
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message));

await page.goto(base);
page.on('requestfailed', (r) => errors.push('requestfailed: ' + r.url()));
await page.waitForFunction(() => window.BluesTempo && window.BluesTempo.engine.loaded, null, { timeout: 30000 });
check(true, 'instrumentos reais carregados');
await page.screenshot({ path: join(OUT, '1-metronomo.png') });
check(await page.locator('#chord-box').isHidden(), 'caixa de acorde escondida no modo metrônomo');

// Metrônomo
await page.tap('#play');
await page.waitForTimeout(1500);
let state = await page.evaluate(() => ({ playing: BluesTempo.engine.playing, ctx: BluesTempo.engine.ctx.state, pos: BluesTempo.engine.positionNow() }));
check(state.playing && state.ctx === 'running', 'metrônomo tocando (AudioContext em execução)');
check(state.pos && state.pos.mode === 'CLICK', 'posição visual acompanhando o clique');
await page.tap('[data-repeat="bpm"][data-delta="1"]');
check(await page.evaluate(() => BluesTempo.settings.bpm) === 101, 'botão + ajusta o andamento');
await page.tap('#play');
check(!(await page.evaluate(() => BluesTempo.engine.playing)), 'parar');

// Levadas
await page.tap('[data-tab="grooves"]');
await page.tap('[data-action="groove"][data-id="boogie_woogie"]');
check(await page.evaluate(() => BluesTempo.settings.grooveId === 'boogie_woogie' && BluesTempo.settings.bpm === 150), 'seleciona Boogie-Woogie com andamento sugerido');
await page.tap('#play');
await page.waitForTimeout(2500);
state = await page.evaluate(() => ({ pos: BluesTempo.engine.positionNow(), now: document.querySelectorAll('#step-grid .cell.now').length, chord: document.getElementById('chord').textContent }));
check(state.pos && state.pos.mode === 'GROOVE' && state.pos.bar >= 1, `levada tocando (compasso ${state.pos && state.pos.bar + 1})`);
check(state.now > 0, 'grade da levada destaca o passo atual');
await page.screenshot({ path: join(OUT, '2-levadas-tocando.png') });
await page.tap('#play');

for (const [tab, file] of [['practice', '3-treino.png'], ['presets', '4-presets.png']]) {
  await page.tap(`[data-tab="${tab}"]`);
  await page.screenshot({ path: join(OUT, file) });
}
await page.tap('[data-action="preset-new"]');
await page.fill('#preset-name', 'Minha jam');
await page.tap('.preset-form button[type="submit"]');
check(await page.locator('.preset-main strong', { hasText: 'Minha jam' }).count() === 1, 'salva preset');

// Grade completa da levada (página longa) para conferência visual
await page.tap('[data-tab="grooves"]');
await page.evaluate(() => { document.getElementById('tab').scrollTop = 1700; });
await page.screenshot({ path: join(OUT, '5-levadas-baixo.png') });

// Áudio real renderizado pelo grafo do Web Audio (OfflineAudioContext)
for (const id of ['chicago_shuffle', 'swing_medium']) {
  const audio = await page.evaluate(async (grooveId) => {
    const { Engine } = window.BluesTempoInternals;
    const src = BluesTempo.engine;
    const e = new Engine();
    e.setBundle(src.bundle);
    const g = window.BluesTempoInternals.grooveById(grooveId);
    const settings = window.BluesTempoInternals.normalizeSettings({ mode: 'GROOVE', grooveId, bpm: g.bpm, swing: g.swing, bassStyle: g.bass, bassTone: g.tone, progression: g.progression, key: 9 });
    const seconds = 8 * g.beats * 60 / g.bpm;
    const ctx = new OfflineAudioContext(2, Math.ceil(44100 * seconds), 44100);
    const rendered = await e.renderOffline(ctx, settings, seconds);
    const l = rendered.getChannelData(0);
    const r = rendered.getChannelData(1);
    const pcm = new Int16Array(l.length * 2);
    let peak = 0;
    for (let i = 0; i < l.length; i++) {
      peak = Math.max(peak, Math.abs(l[i]), Math.abs(r[i]));
      pcm[2 * i] = Math.max(-32768, Math.min(32767, Math.round(l[i] * 32767)));
      pcm[2 * i + 1] = Math.max(-32768, Math.min(32767, Math.round(r[i] * 32767)));
    }
    let bin = '';
    const bytes = new Uint8Array(pcm.buffer);
    for (let i = 0; i < bytes.length; i += 0x8000) bin += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    return { peak, b64: btoa(bin) };
  }, id);
  const data = Buffer.from(audio.b64, 'base64');
  const header = Buffer.alloc(44);
  header.write('RIFF', 0); header.writeUInt32LE(36 + data.length, 4); header.write('WAVEfmt ', 8);
  header.writeUInt32LE(16, 16); header.writeUInt16LE(1, 20); header.writeUInt16LE(2, 22); header.writeUInt32LE(44100, 24);
  header.writeUInt32LE(44100 * 4, 28); header.writeUInt16LE(4, 32); header.writeUInt16LE(16, 34); header.write('data', 36); header.writeUInt32LE(data.length, 40);
  writeFileSync(join(OUT, `web-${id}.wav`), Buffer.concat([header, data]));
  check(audio.peak > 0.2 && audio.peak <= 1.0, `${id}: áudio renderizado no navegador (pico ${audio.peak.toFixed(2)})`);
}

check(errors.filter((e) => e.startsWith('console') || e.startsWith('pageerror')).length === 0, 'sem erros no console');
await browser.close();
server.close();
if (errors.length) {
  console.log('\nFalhas:\n' + errors.join('\n'));
  process.exit(1);
}
console.log('\nTudo certo.');
