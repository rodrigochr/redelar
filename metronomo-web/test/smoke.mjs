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
// Mixagem padrão por levada: muda ao trocar de levada e volta ao padrão depois de mexer.
const mixOf = (id) => page.evaluate((gid) => {
  const g = window.BluesTempoInternals.grooveById(gid);
  const m = BluesTempo.settings.mixer;
  return ['kick', 'snare', 'cymbals', 'percussion', 'bass'].every((k) => Math.abs(m[k] - g.mix[k]) < 0.001);
}, id);
check(await mixOf('boogie_woogie'), 'mixer assume a mixagem padrão do Boogie-Woogie');
await page.evaluate(() => { document.getElementById('mix-BASS').scrollIntoView(); });
await page.locator('#mix-BASS').fill('0.2');
check(await page.evaluate(() => Math.abs(BluesTempo.settings.mixer.bass - 0.2) < 0.001), 'ajusta o volume do baixo');
check(await page.locator('#mixer-reset').isEnabled(), 'botão "Restaurar padrão da levada" habilitado depois de mexer');
await page.tap('#mixer-reset');
check(await mixOf('boogie_woogie'), 'restaura a mixagem padrão');
await page.locator('#mix-BASS').fill('0.2');
await page.evaluate(() => { document.getElementById('tab').scrollTop = 0; });
await page.tap('[data-action="groove"][data-id="chicago_shuffle"]');
check(await mixOf('chicago_shuffle'), 'trocar de levada volta à mixagem padrão dela');
await page.tap('[data-action="groove"][data-id="boogie_woogie"]');
await page.tap('#play');
await page.waitForTimeout(2500);
state = await page.evaluate(() => ({ pos: BluesTempo.engine.positionNow(), now: document.querySelectorAll('#step-grid .cell.now').length, chord: document.getElementById('chord').textContent }));
check(state.pos && state.pos.mode === 'GROOVE' && state.pos.bar >= 1, `levada tocando (compasso ${state.pos && state.pos.bar + 1})`);
check(state.now > 0, 'grade da levada destaca o passo atual');
await page.screenshot({ path: join(OUT, '2-levadas-tocando.png') });
await page.tap('#play');

// Gêneros: a barra Blues/Rock/Jazz precisa estar visível e responder ao toque.
for (const [genre, grooveId] of [['ROCK', 'rock_and_roll'], ['JAZZ', 'swing_medium'], ['BLUES', 'slow_blues']]) {
  const seg = page.locator(`[data-action="genre"][data-id="${genre}"]`);
  const box = await seg.boundingBox();
  check(box && box.height >= 30 && box.x >= 0 && box.x + box.width <= 390, `botão ${genre} visível (${box && Math.round(box.height)} px de altura)`);
  await seg.tap({ timeout: 5000 });
  const first = await page.evaluate(() => BluesTempo.settings.genre);
  check(first === genre, `troca para ${genre}`);
  await page.tap(`[data-action="groove"][data-id="${grooveId}"]`, { timeout: 5000 });
  check(await page.evaluate((id) => BluesTempo.settings.grooveId === id, grooveId), `seleciona ${grooveId}`);
}
await page.evaluate(() => { document.getElementById('tab').scrollTop = 0; });
await page.screenshot({ path: join(OUT, '2b-generos.png') });

// Músicas: carregar uma música ajusta andamento, tom e levada; a cifra acompanha a execução.
await page.tap('[data-tab="songs"]');
const songCount = await page.locator('[data-action="song"]').count();
check(songCount > 0, `setlist com ${songCount} músicas`);
await page.screenshot({ path: join(OUT, '6-musicas.png') });
await page.tap('[data-action="song"][data-id="wish_you_were_here"]');
const loaded = await page.evaluate(() => {
  const s = BluesTempo.settings;
  const song = window.BluesTempoInternals.songById('wish_you_were_here');
  return { ok: s.songId === song.id && s.bpm === song.bpm && s.key === song.key && s.grooveId === song.groove && s.mode === 'GROOVE', bpm: s.bpm };
});
check(loaded.ok, `carrega a música (andamento ${loaded.bpm}, tom e levada)`);
check((await page.locator('#song-chart .prog-bar').count()) > 40, 'mostra a cifra completa');
await page.evaluate(() => BluesTempo.update({ bpm: 200 }));
await page.tap('#play');
await page.waitForTimeout(2500);
state = await page.evaluate(() => ({
  pos: BluesTempo.engine.positionNow(),
  now: document.querySelectorAll('#song-chart .prog-bar.now').length,
  tag: document.getElementById('mode-tag').textContent,
  chord: document.getElementById('chord').textContent,
}));
check(state.pos && state.pos.progressionBar >= 1 && state.now === 1, `cifra destaca o compasso atual (${state.pos && state.pos.progressionBar + 1})`);
check(state.tag.includes('Wish You Were Here'), `painel mostra a música e a parte (${state.tag})`);
check(['Em7', 'G', 'A7sus4'].includes(state.chord), `acorde atual na grafia da música (${state.chord})`);
await page.screenshot({ path: join(OUT, '7-musica-tocando.png') });
await page.tap('#song-chart .part[data-part="2"]');
await page.waitForTimeout(3500);
state = await page.evaluate(() => ({ pos: BluesTempo.engine.positionNow(), part: window.BluesTempoInternals.songById('wish_you_were_here').parts[2] }));
check(state.pos && state.pos.progressionBar >= state.part.start && state.pos.progressionBar < state.part.end, 'loop de uma parte (verso) pula para ela no compasso seguinte');
await page.tap('#play');
await page.tap('[data-action="song-key"][data-v="1"]');
const transposed = await page.evaluate(() => [...document.querySelectorAll('#song-chart .prog-bar b')].slice(0, 6).map((b) => b.textContent));
check(transposed[0] === 'Fm7' && transposed[1] === 'Ab', `transpõe a cifra (+1: ${transposed.join(' ')})`);
await page.tap('[data-action="song-key-reset"]');
await page.tap('[data-action="song-step"][data-v="1"]');
check(await page.evaluate(() => BluesTempo.settings.songId !== 'wish_you_were_here'), 'próxima música');
await page.tap('[data-tab="grooves"]');
check(await page.locator('#prog').evaluate((el) => el.value === ''), 'aba Levadas indica que a progressão vem da música');
await page.selectOption('#prog', 'BLUES_12');
check(await page.evaluate(() => BluesTempo.settings.songId === null), 'escolher uma progressão sai da música');

// Tirar a seleção da música: botão no topo, tocar de novo na música e escolher uma levada.
const songId = () => page.evaluate(() => BluesTempo.settings.songId);
await page.tap('[data-tab="songs"]');
await page.tap('[data-action="song"][data-id="black"]');
check(await songId() === 'black', 'seleciona outra música');
await page.screenshot({ path: join(OUT, '8-musica-selecionada.png') });
await page.tap('.card-head [data-action="song-leave"]');
check(await songId() === null, 'botão "Tirar música" no topo tira a seleção');
await page.tap('[data-action="song"][data-id="black"]');
await page.tap('[data-action="song"][data-id="black"]');
check(await songId() === null, 'tocar de novo na música selecionada tira a seleção');
await page.tap('[data-action="song"][data-id="black"]');
await page.selectOption('#song-groove', 'rock_basic');
check(await page.evaluate(() => BluesTempo.settings.songId === 'black' && BluesTempo.settings.grooveId === 'rock_basic'), 'troca a levada mantendo a música');
await page.tap('[data-tab="grooves"]');
check(await page.locator('.song-banner').isVisible(), 'aba Levadas avisa que há música selecionada');
await page.screenshot({ path: join(OUT, '9-levadas-com-musica.png') });
await page.tap('[data-action="groove"][data-id="rock_basic"]', { timeout: 5000 }).catch(async () => {
  await page.tap('[data-action="genre"][data-id="ROCK"]');
  await page.tap('[data-action="groove"][data-id="rock_basic"]');
});
check(await page.evaluate(() => BluesTempo.settings.songId === null && BluesTempo.settings.progression === 'ROCK_I_IV_V'), 'escolher uma levada tira a música e volta à progressão da levada');
check(!(await page.locator('.song-banner').count()), 'aviso some depois de tirar a música');

// A cifra acompanha o compasso atual enquanto a música toca (e para de acompanhar se desligado).
const currentVisible = () => page.evaluate(() => {
  const cell = document.querySelector('#song-chart .prog-bar.now');
  if (!cell) return null;
  const r = cell.getBoundingClientRect();
  const v = document.getElementById('tab').getBoundingClientRect();
  return r.top >= v.top && r.bottom <= v.bottom;
});
await page.tap('[data-tab="songs"]');
await page.tap('[data-action="song"][data-id="comfortably_numb"]');
await page.evaluate(() => BluesTempo.update({ bpm: 260 }, { rerender: false }));
await page.evaluate(() => { const t = document.getElementById('tab'); t.scrollTop = t.scrollHeight; });
await page.evaluate(() => document.getElementById('play').click());
await page.waitForTimeout(6000);
check(await currentVisible() === true, 'tela rola sozinha até o compasso atual');
await page.screenshot({ path: join(OUT, '11-acompanhando.png') });
await page.evaluate(() => document.getElementById('play').click());
await page.evaluate(() => BluesTempo.update({ followChart: false }));
await page.evaluate(() => { const t = document.getElementById('tab'); t.scrollTop = t.scrollHeight; });
await page.evaluate(() => document.getElementById('play').click());
await page.waitForTimeout(4000);
check(await currentVisible() === false, 'com "Acompanhar" desligado, a tela fica onde a pessoa deixou');
await page.evaluate(() => document.getElementById('play').click());
await page.evaluate(() => BluesTempo.update({ followChart: true }));

// Trocar de música ou de levada tocando recomeça a contagem de compassos e o relógio.
const counter = () => page.evaluate(() => ({ status: document.getElementById('status').textContent, clock: document.getElementById('clock').textContent }));
await page.tap('[data-tab="songs"]');
await page.evaluate(() => { document.getElementById('tab').scrollTop = 0; });
await page.locator('[data-action="song"][data-id="roadhouse_blues"]').scrollIntoViewIfNeeded();
await page.tap('[data-action="song"][data-id="roadhouse_blues"]');
await page.evaluate(() => BluesTempo.update({ bpm: 280 }, { rerender: false }));
await page.evaluate(() => document.getElementById('play').click());
await page.waitForTimeout(4200);
const before = await counter();
await page.evaluate(() => {
  const s = window.BluesTempoInternals.songById('in_between_days');
  BluesTempo.update({ songId: s.id, grooveId: s.groove, key: s.key, bpm: 280, songPart: -1 });
});
await page.waitForTimeout(1900);
const after = await counter();
check(Number(before.status.replace(/\D/g, '')) >= 5 && Number(after.status.replace(/\D/g, '')) <= 2 && ['0:00', '0:01'].includes(after.clock),
  `trocar de música recomeça compassos e relógio (${before.status} ${before.clock} → ${after.status} ${after.clock})`);
await page.waitForTimeout(2500);
const mid = await counter();
await page.evaluate(() => BluesTempo.update({ grooveId: 'rock_basic' }));
await page.waitForTimeout(1900);
const afterGroove = await counter();
check(Number(mid.status.replace(/\D/g, '')) >= 4 && Number(afterGroove.status.replace(/\D/g, '')) <= 2,
  `trocar de levada recomeça os compassos (${mid.status} → ${afterGroove.status})`);
await page.evaluate(() => document.getElementById('play').click());

// Tela estreita (352 px) com o nome de música mais longo tocando: nada pode alargar a página.
await page.setViewportSize({ width: 352, height: 760 });
await page.tap('[data-tab="songs"]');
await page.evaluate(() => { document.getElementById('tab').scrollTop = 0; });
await page.locator('[data-action="song"][data-id="canceriano_sem_lar"]').scrollIntoViewIfNeeded();
await page.tap('[data-action="song"][data-id="canceriano_sem_lar"]');
await page.evaluate(() => document.getElementById('play').click());
await page.waitForTimeout(2000);
const narrow = await page.evaluate(() => ({
  win: innerWidth, doc: document.documentElement.scrollWidth, tr: document.getElementById('transport').scrollWidth,
  play: document.getElementById('play').getBoundingClientRect().right, next: document.getElementById('chord-next').textContent,
}));
check(narrow.win === 352 && narrow.doc <= 352 && narrow.tr <= 352 && narrow.play <= 352,
  `tela de 352 px sem rolagem lateral (página ${narrow.doc} px, painel ${narrow.tr} px)`);
check(narrow.next !== '→ C7', `mostra o próximo acorde diferente (${narrow.next})`);
await page.screenshot({ path: join(OUT, '10-tela-estreita.png') });
await page.evaluate(() => document.getElementById('play').click());
await page.setViewportSize({ width: 390, height: 844 });

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
for (const id of ['chicago_shuffle', 'swing_medium', 'rock_basic', 'bo_diddley', 'jazz_ballad', 'brush_swing']) {
  const audio = await page.evaluate(async (grooveId) => {
    const { Engine } = window.BluesTempoInternals;
    const src = BluesTempo.engine;
    const e = new Engine();
    e.setBundle(src.bundle);
    const g = window.BluesTempoInternals.grooveById(grooveId);
    const settings = window.BluesTempoInternals.normalizeSettings({ mode: 'GROOVE', grooveId, bpm: g.bpm, swing: g.swing, bassStyle: g.bass, bassTone: g.tone, progression: g.progression, key: 9, mixer: { ...g.mix } });
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
