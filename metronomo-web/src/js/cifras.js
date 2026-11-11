// Cifras da Setlist: cada música inteira numa tela, com troca de tom, para ler no palco.
// As cifras são as mesmas do metrônomo (data/music.json, gerado pelo app Android).
import { SONG_LIST, grooveById, keyName, mod, spelledName } from './music.js';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

// ------------------------------------------------------------------ estado (guardado só neste aparelho)

const STORE_KEY = 'bt-cifras-v1';
let state = { index: 0, shifts: {}, stage: false };
try {
  const saved = JSON.parse(localStorage.getItem(STORE_KEY) || 'null');
  if (saved && typeof saved === 'object') state = { ...state, ...saved, shifts: { ...(saved.shifts || {}) } };
} catch (e) { /* sem armazenamento: começa do zero */ }
const save = () => { try { localStorage.setItem(STORE_KEY, JSON.stringify(state)); } catch (e) { /* ignora */ } };

const hashOf = (song) => song.id.replace(/_/g, '-');
const fromHash = () => SONG_LIST.findIndex((s) => `#${hashOf(s)}` === location.hash);
if (fromHash() >= 0) state.index = fromHash();
state.index = Math.min(Math.max(0, state.index | 0), SONG_LIST.length - 1);

const current = () => SONG_LIST[state.index];
const shiftOf = (song) => {
  const v = Number(state.shifts[song.id]) || 0;
  return mod(v + 6, 12) - 6; // de −6 a +5
};
const keyOf = (song) => mod(song.key + shiftOf(song), 12);

// ------------------------------------------------------------------ cifra compacta

const chordId = (c) => `${c.degree}|${c.quality}|${c.suffix}|${c.bass ?? ''}|${c.spellShift || 0}`;
const barId = (bar) => `${bar.chords.map(chordId).join(' ')}#${bar.beats ?? ''}`;

/**
 * Agrupa repetições: um compasso repetido vira "×N"; um trecho de 2 a 8 compassos repetido
 * (cobrindo pelo menos 4 compassos) vira ritornelo. Escolhe sempre o que cobre mais compassos.
 */
function compress(bars) {
  const ids = bars.map(barId);
  const same = (a, b, len) => { for (let k = 0; k < len; k++) if (ids[a + k] !== ids[b + k]) return false; return true; };
  const out = [];
  let i = 0;
  while (i < bars.length) {
    let best = { len: 1, times: 1 };
    for (let len = 1; len <= 8 && i + 2 * len <= bars.length; len++) {
      let times = 1;
      while (i + (times + 1) * len <= bars.length && same(i, i + times * len, len)) times++;
      if (times < 2 || (len > 1 && times * len < 4)) continue;
      if (times * len > best.len * best.times) best = { len, times };
    }
    out.push({ bars: bars.slice(i, i + best.len), times: best.times });
    i += best.len * best.times;
  }
  return out;
}

/** Roteiro: as partes na ordem, com repetições seguidas juntas ("Verso ×2"). */
function roadmap(song) {
  const items = [];
  for (const p of song.parts) {
    const last = items[items.length - 1];
    if (last && last.section === p.section) last.times++;
    else items.push({ section: p.section, name: p.name, times: 1 });
  }
  return items;
}

function chartHtml(song, key) {
  const name = (c) => spelledName(c, key, song.minor);
  const g = grooveById(song.groove);
  const compound = g.steps === 3 && g.meter.endsWith('/8');
  const uses = {};
  song.parts.forEach((p) => { uses[p.section] = (uses[p.section] || 0) + 1; });
  const seen = new Map(); // conteúdo → nome da primeira seção igual
  const order = [...new Set(song.parts.map((p) => p.section))];
  return order.map((s) => {
    const sec = song.sections[s];
    const times = uses[s] > 1 ? `<small>${uses[s]}×</small>` : '';
    const content = sec.bars.map(barId).join('|');
    if (seen.has(content)) {
      return `<section class="sec same"><h3>${esc(sec.name)} ${times}<small>= ${esc(seen.get(content))}</small></h3></section>`;
    }
    seen.set(content, sec.name);
    const cells = [];
    for (const group of compress(sec.bars)) {
      group.bars.forEach((bar, i) => {
        const names = bar.chords.map(name);
        // Compasso com o mesmo acorde repetido (ex.: G G G C): mostra só as trocas.
        const shown = names.filter((n, k) => k === 0 || n !== names[k - 1]);
        const nc = bar.chords.every((c) => c.quality === 'NC');
        const cls = ['bar'];
        if (shown.length > 1) cls.push('multi');
        if (nc) cls.push('nc');
        const multiBarRepeat = group.times > 1 && group.bars.length > 1;
        if (multiBarRepeat && i === 0) cls.push('rs');
        if (multiBarRepeat && i === group.bars.length - 1) cls.push('re');
        const last = i === group.bars.length - 1;
        const x = group.times > 1 && last ? `<i class="x">×${group.times}</i>` : '';
        const m = bar.beats != null ? `<small class="m">${compound ? `${bar.beats * 3}/8` : `${bar.beats}/4`}</small>` : '';
        if (x) cls.push('has-x');
        if (m) cls.push('has-m');
        cells.push(`<div class="${cls.join(' ')}"><b>${esc(shown.join(' '))}</b>${x}${m}</div>`);
      });
    }
    return `<section class="sec"><h3>${esc(sec.name)} ${times}</h3><div class="bars">${cells.join('')}</div></section>`;
  }).join('');
}

// ------------------------------------------------------------------ ajuste à tela

/** Maior letra em que a música inteira cabe na tela, testando 1 a 3 colunas e 4, 6 ou 8 compassos por linha. */
function fit() {
  const box = $('chart');
  const inner = $('chart-inner');
  const H = box.clientHeight;
  const W = box.clientWidth;
  if (!H || !W) return;
  const fits = () => {
    if (inner.scrollHeight > H + 1 || inner.scrollWidth > W + 1) return false;
    for (const b of inner.querySelectorAll('.bar b')) if (b.scrollWidth > b.clientWidth + 1) return false;
    return true;
  };
  let best = null;
  for (const cols of [1, 2, 3]) {
    if (cols > 1 && W / cols < 230) break;
    for (const per of [4, 6, 8]) {
      inner.style.columnCount = cols;
      inner.style.setProperty('--per', per);
      let lo = 9;
      let hi = 64;
      inner.style.setProperty('--fs', `${lo}px`);
      if (!fits()) continue;
      while (hi - lo > 0.5) {
        const mid = (lo + hi) / 2;
        inner.style.setProperty('--fs', `${mid}px`);
        if (fits()) lo = mid; else hi = mid;
      }
      // Preferência: letra maior; empatando (±1 px), menos colunas e 4 compassos por linha.
      if (!best || lo > best.fs + 1) best = { cols, per, fs: lo };
    }
  }
  best = best || { cols: 1, per: 8, fs: 9 };
  inner.style.columnCount = best.cols;
  inner.style.setProperty('--per', best.per);
  inner.style.setProperty('--fs', `${Math.floor(best.fs * 2) / 2}px`);
  inner.dataset.fit = `${best.cols}x${best.per}@${best.fs.toFixed(1)}`;
}

// ------------------------------------------------------------------ tela

function render(direction = 0) {
  const song = current();
  const key = keyOf(song);
  const shift = shiftOf(song);
  const g = grooveById(song.groove);
  document.title = `${song.title} · Cifras da Setlist`;
  $('num').textContent = song.number;
  $('title').textContent = song.title;
  $('meta').textContent = `${song.artist} · ${song.bpm} BPM · ${g.meter} · ${g.name}`;
  $('key-name').textContent = keyName(key, song.minor);
  $('key-shift').textContent = shift ? `${shift > 0 ? '+' : ''}${shift} · orig. ${song.keyName}` : 'original';
  $('key-label').setAttribute('aria-label', shift ? `Tom ${keyName(key, song.minor)}, ${shift > 0 ? 'mais' : 'menos'} ${Math.abs(shift)} semitom. Toque para voltar a ${song.keyName}` : `Tom original ${song.keyName}`);
  $('road').innerHTML = roadmap(song).map((it, i) =>
    `${i ? '<span class="arrow" aria-hidden="true">›</span>' : ''}<span><b>${esc(it.name)}</b>${it.times > 1 ? ` <span class="times">×${it.times}</span>` : ''}</span>`).join('');
  const inner = $('chart-inner');
  inner.innerHTML = chartHtml(song, key);
  const next = SONG_LIST[(state.index + 1) % SONG_LIST.length];
  $('next-song').innerHTML = `Próxima › <b>${next.number}. ${esc(next.title)}</b> · ${esc(keyName(keyOf(next), next.minor))} · ${next.bpm} BPM`;
  fit();
  if (direction) {
    inner.style.setProperty('--dx', `${Math.sign(direction) * 24}px`);
    inner.classList.remove('enter');
    void inner.offsetWidth;
    inner.classList.add('enter');
  }
  const hash = `#${hashOf(song)}`;
  if (location.hash !== hash) {
    try { history.replaceState(null, '', hash); } catch (e) { location.hash = hash; }
  }
  save();
}

function go(delta) {
  state.index = mod(state.index + delta, SONG_LIST.length);
  render(delta);
}

function transpose(delta) {
  const song = current();
  const v = shiftOf(song) + delta;
  state.shifts[song.id] = mod(v + 6, 12) - 6;
  if (!state.shifts[song.id]) delete state.shifts[song.id];
  render();
}

function resetKey() {
  delete state.shifts[current().id];
  render();
}

// ------------------------------------------------------------------ lista e notas

function openList() {
  $('setlist').innerHTML = SONG_LIST.map((s, i) => {
    const shift = shiftOf(s);
    return `<li><button type="button" data-index="${i}" aria-current="${i === state.index}">
      <span class="n">${s.number}</span>
      <span class="t"><strong>${esc(s.title)}</strong><small>${esc(s.artist)}</small></span>
      <span class="k">${esc(keyName(keyOf(s), s.minor))}<small>${shift ? `${shift > 0 ? '+' : ''}${shift} · ` : ''}${s.bpm}</small></span>
    </button></li>`;
  }).join('');
  openSheet('list');
  const cur = $('setlist').querySelector('[aria-current="true"]');
  if (cur) cur.scrollIntoView({ block: 'center' });
}

function openNotes() {
  const song = current();
  const g = grooveById(song.groove);
  $('notes-title').textContent = song.title;
  $('notes-body').innerHTML = `
    <p class="muted">${esc(song.artist)} · ${esc(song.reference)}</p>
    <p class="muted">${song.bpm} BPM · ${esc(g.meter)} · ${esc(g.name)} · tom original ${esc(song.keyName)} · confiança ${esc(song.confidenceLabel.toLowerCase())}</p>
    ${song.notes ? `<p>${esc(song.notes)}</p>` : ''}`;
  openSheet('notes');
}

let lastFocus = null;
function openSheet(id) {
  lastFocus = document.activeElement;
  $(id).hidden = false;
  const close = $(id).querySelector('.close');
  if (close) close.focus();
}
function closeSheets() {
  for (const id of ['list', 'notes']) $(id).hidden = true;
  if (lastFocus && lastFocus.focus) lastFocus.focus();
}
const sheetOpen = () => !$('list').hidden || !$('notes').hidden;

// ------------------------------------------------------------------ controles

$('prev').addEventListener('click', () => go(-1));
$('next').addEventListener('click', () => go(1));
$('next-song').addEventListener('click', () => go(1));
$('key-down').addEventListener('click', () => transpose(-1));
$('key-up').addEventListener('click', () => transpose(1));
$('key-label').addEventListener('click', resetKey);
$('open-list').addEventListener('click', openList);
$('notes-btn').addEventListener('click', openNotes);
$('setlist').addEventListener('click', (e) => {
  const b = e.target.closest('[data-index]');
  if (!b) return;
  const i = Number(b.dataset.index);
  const dir = i > state.index ? 1 : i < state.index ? -1 : 0;
  state.index = i;
  closeSheets();
  render(dir);
});
document.querySelectorAll('[data-close]').forEach((b) => b.addEventListener('click', closeSheets));
document.querySelectorAll('.sheet').forEach((s) => s.addEventListener('click', (e) => { if (e.target === s) closeSheets(); }));

function applyStage() {
  document.documentElement.classList.toggle('stage', !!state.stage);
  $('stage-btn').setAttribute('aria-pressed', String(!!state.stage));
}
$('stage-btn').addEventListener('click', () => { state.stage = !state.stage; applyStage(); save(); });
applyStage();

const fullBtn = $('full-btn');
if (!document.documentElement.requestFullscreen) fullBtn.hidden = true;
fullBtn.addEventListener('click', async () => {
  try {
    if (document.fullscreenElement) await document.exitFullscreen();
    else await document.documentElement.requestFullscreen();
  } catch (e) {
    fullBtn.hidden = true; // este aparelho não deixa
  }
});

// Teclado e pedal de virar página: pedais mandam setas (para os lados ou para cima/baixo) ou
// PageUp/PageDown. Todas trocam de música; o tom só muda com + e −, para não mudar sem querer no show.
const NEXT_KEYS = ['ArrowRight', 'ArrowDown', 'PageDown'];
const PREV_KEYS = ['ArrowLeft', 'ArrowUp', 'PageUp'];
document.addEventListener('keydown', (e) => {
  if (e.altKey || e.ctrlKey || e.metaKey) return;
  if (e.key === 'Escape' && sheetOpen()) { closeSheets(); return; }
  if (sheetOpen()) return;
  const onButton = !!e.target.closest('button');
  if (NEXT_KEYS.includes(e.key) || (e.key === ' ' && !onButton)) { e.preventDefault(); go(1); }
  else if (PREV_KEYS.includes(e.key)) { e.preventDefault(); go(-1); }
  else if (e.key === '+' || e.key === '=') { e.preventDefault(); transpose(1); }
  else if (e.key === '-' || e.key === '_') { e.preventDefault(); transpose(-1); }
});

// Deslizar o dedo para os lados troca de música.
let touch = null;
$('chart').addEventListener('pointerdown', (e) => { touch = { x: e.clientX, y: e.clientY, t: performance.now() }; });
$('chart').addEventListener('pointerup', (e) => {
  if (!touch) return;
  const dx = e.clientX - touch.x;
  const dy = e.clientY - touch.y;
  if (Math.abs(dx) > 60 && Math.abs(dy) < 50 && performance.now() - touch.t < 800) go(dx < 0 ? 1 : -1);
  touch = null;
});
$('chart').addEventListener('pointercancel', () => { touch = null; });

// Tela sempre acesa enquanto a página estiver aberta.
let wakeLock = null;
async function keepAwake() {
  try {
    if ('wakeLock' in navigator && document.visibilityState === 'visible' && !wakeLock) {
      wakeLock = await navigator.wakeLock.request('screen');
      wakeLock.addEventListener('release', () => { wakeLock = null; });
    }
  } catch (e) { wakeLock = null; }
}
document.addEventListener('visibilitychange', keepAwake);
document.addEventListener('pointerdown', keepAwake, { once: true });
keepAwake();

window.addEventListener('hashchange', () => {
  const i = fromHash();
  if (i >= 0 && i !== state.index) { state.index = i; render(); }
});
let resizeTimer = null;
window.addEventListener('resize', () => { clearTimeout(resizeTimer); resizeTimer = setTimeout(fit, 80); });
if (document.fonts && document.fonts.ready) document.fonts.ready.then(fit).catch(() => {});

render();

// Exposto para testes automatizados.
window.Cifras = { go, transpose, fit, get index() { return state.index; }, songs: SONG_LIST };
