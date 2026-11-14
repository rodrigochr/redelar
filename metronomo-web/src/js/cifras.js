// Cifras da Setlist: cada música inteira numa tela, com troca de tom, para ler no palco.
// As cifras são as mesmas do metrônomo (data/music.json, gerado pelo app Android).
import { SONG_LIST, grooveById, keyName, mod, spelledName } from './music.js';
import { SCALES, analyzeSong, intervalNames, optionLabel, rootName, scaleNotes, scalePitchClasses } from './scales.js';

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

// ------------------------------------------------------------------ estado (guardado só neste aparelho)

const STORE_KEY = 'bt-cifras-v1';
let state = { index: 0, shifts: {}, stage: false, scales: true };
try {
  const saved = JSON.parse(localStorage.getItem(STORE_KEY) || 'null');
  if (saved && typeof saved === 'object') state = { ...state, ...saved, shifts: { ...(saved.shifts || {}) } };
} catch (e) { /* sem armazenamento: começa do zero */ }
const save = () => { try { localStorage.setItem(STORE_KEY, JSON.stringify(state)); } catch (e) { /* ignora */ } };

const hashOf = (song) => song.id.replace(/_/g, '-');
const fromHash = () => SONG_LIST.findIndex((s) => `#${hashOf(s)}` === location.hash);
// A música atual é guardada pelo id (a ordem da setlist pode mudar).
if (state.id && SONG_LIST.some((s) => s.id === state.id)) state.index = SONG_LIST.findIndex((s) => s.id === state.id);
if (fromHash() >= 0) state.index = fromHash();
state.index = Math.min(Math.max(0, state.index | 0), SONG_LIST.length - 1);

/** Sequência do "anterior/próxima": setlist e extras (uma música que saiu da setlist só pela lista). */
function navOrder() {
  const all = SONG_LIST.map((s, i) => i);
  return SONG_LIST[state.index].group === 'OTHER' ? all : all.filter((i) => SONG_LIST[i].group !== 'OTHER');
}
const stepIndex = (delta) => {
  const nav = navOrder();
  const pos = nav.indexOf(state.index);
  return nav[mod((pos < 0 ? 0 : pos) + delta, nav.length)];
};
const numberOf = (song) => song.label || '–';

const current = () => SONG_LIST[state.index];
const shiftOf = (song) => {
  const v = Number(state.shifts[song.id]) || 0;
  return mod(v + 6, 12) - 6; // de −6 a +5
};
const keyOf = (song) => mod(song.key + shiftOf(song), 12);
const analyses = {};
const analysisOf = (song) => analyses[song.id] || (analyses[song.id] = analyzeSong(song));

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

function chartHtml(song, key, analysis) {
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
    // Parte que pede outra escala (modulação, ou maior ↔ menor): sugestão ao lado do nome.
    const local = state.scales && analysis.sections[s];
    const scaleTag = local
      ? ` <button type="button" class="sec-sc" data-scale="sec:${s}" aria-label="Escalas para ${esc(sec.name)}">${local.options.slice(0, 2).map((o) => esc(optionLabel(song, key, o))).join(' · ')}</button>`
      : '';
    return `<section class="sec"><h3>${esc(sec.name)} ${times}${scaleTag}</h3><div class="bars">${cells.join('')}</div></section>`;
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
  $('num').textContent = numberOf(song);
  $('title').textContent = song.title;
  $('meta').textContent = `${song.artist} · ${song.bpm} BPM · ${g.meter} · ${g.name}`;
  $('key-name').textContent = keyName(key, song.minor);
  $('app').classList.toggle('shifted', shift !== 0);
  $('key-shift').textContent = shift ? `${shift > 0 ? '+' : ''}${shift} · orig. ${song.keyName}` : 'original';
  $('key-label').setAttribute('aria-label', shift ? `Tom ${keyName(key, song.minor)}, ${shift > 0 ? 'mais' : 'menos'} ${Math.abs(shift)} semitom. Toque para voltar a ${song.keyName}` : `Tom original ${song.keyName}`);
  $('road').innerHTML = roadmap(song).map((it, i) =>
    `${i ? '<span class="arrow" aria-hidden="true">›</span>' : ''}<span><b>${esc(it.name)}</b>${it.times > 1 ? ` <span class="times">×${it.times}</span>` : ''}</span>`).join('');
  const analysis = analysisOf(song);
  const impro = $('impro');
  impro.hidden = !state.scales || !analysis.main;
  if (!impro.hidden) {
    impro.innerHTML = `<span class="lbl">Improviso</span>${analysis.main.options.map((o, i) =>
      `<button type="button" class="sc" data-scale="main:${i}">${esc(optionLabel(song, key, o))}</button>`).join('')}`;
  }
  const inner = $('chart-inner');
  inner.innerHTML = chartHtml(song, key, analysis);
  const next = SONG_LIST[stepIndex(1)];
  const nextNum = next.label ? `${next.label}. ` : '';
  $('next-song').innerHTML = `Próxima › <b>${esc(nextNum)}${esc(next.title)}</b> · <span class="${shiftOf(next) ? 'shifted' : ''}">${esc(keyName(keyOf(next), next.minor))}${shiftOf(next) ? ' (tom alterado)' : ''}</span> · ${next.bpm} BPM`;
  fit();
  if (direction) {
    inner.style.setProperty('--dx', `${Math.sign(direction) * 24}px`);
    inner.classList.remove('enter');
    void inner.offsetWidth;
    inner.classList.add('enter');
  }
  state.id = song.id;
  const hash = `#${hashOf(song)}`;
  if (location.hash !== hash) {
    try { history.replaceState(null, '', hash); } catch (e) { location.hash = hash; }
  }
  save();
}

function go(delta) {
  state.index = stepIndex(delta);
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
  const GROUPS = { EXTRA: 'Extras', OTHER: 'Fora da setlist' };
  $('setlist').innerHTML = SONG_LIST.map((s, i) => {
    const shift = shiftOf(s);
    const head = s.group !== 'SETLIST' && (i === 0 || SONG_LIST[i - 1].group !== s.group)
      ? `<li class="grp">${esc(GROUPS[s.group] || s.groupLabel)}</li>` : '';
    return `${head}<li${shift ? ' class="shifted"' : ''}><button type="button" data-index="${i}" aria-current="${i === state.index}">
      <span class="n">${esc(numberOf(s))}</span>
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

// ------------------------------------------------------------------ painel da escala

const STRINGS = [64, 59, 55, 50, 45, 40]; // mi, si, sol, ré, lá, mi (de cima para baixo, como na tablatura)
const FRETS = 12;

/** Braço da guitarra (afinação padrão) com as notas da escala; a tônica em destaque. */
function fretboard(option, key, names) {
  const pcs = scalePitchClasses(key, option);
  const rootPc = pcs[0];
  const nameOf = Object.fromEntries(pcs.map((pc, i) => [pc, names[i]]));
  const fw = 52; const x0 = 44; const y0 = 22; const sh = 28;
  const W = x0 + FRETS * fw + 14; const H = y0 + 5 * sh + 40;
  const parts = [`<rect class="wood" x="${x0}" y="${y0 - 10}" width="${FRETS * fw}" height="${5 * sh + 20}" rx="4"/>`];
  for (const f of [3, 5, 7, 9]) parts.push(`<circle class="mk" cx="${x0 + (f - 0.5) * fw}" cy="${y0 + 2.5 * sh}" r="6"/>`);
  parts.push(`<circle class="mk" cx="${x0 + 11.5 * fw}" cy="${y0 + 1.5 * sh}" r="6"/><circle class="mk" cx="${x0 + 11.5 * fw}" cy="${y0 + 3.5 * sh}" r="6"/>`);
  for (let f = 1; f <= FRETS; f++) parts.push(`<line class="fw" x1="${x0 + f * fw}" y1="${y0 - 10}" x2="${x0 + f * fw}" y2="${y0 + 5 * sh + 10}"/>`);
  parts.push(`<line class="nut" x1="${x0}" y1="${y0 - 10}" x2="${x0}" y2="${y0 + 5 * sh + 10}"/>`);
  STRINGS.forEach((open, i) => parts.push(`<line class="str" x1="${x0 - 30}" y1="${y0 + i * sh}" x2="${x0 + FRETS * fw}" y2="${y0 + i * sh}" stroke-width="${1 + i * 0.35}"/>`));
  for (const f of [3, 5, 7, 9, 12]) parts.push(`<text class="num" x="${x0 + (f - 0.5) * fw}" y="${y0 + 5 * sh + 28}">${f}</text>`);
  STRINGS.forEach((open, i) => {
    for (let f = 0; f <= FRETS; f++) {
      const pc = mod(open + f, 12);
      if (!(pc in nameOf)) continue;
      const cx = f === 0 ? x0 - 18 : x0 + (f - 0.5) * fw;
      const cy = y0 + i * sh;
      const root = pc === rootPc ? ' root' : '';
      parts.push(`<circle class="dot${root}" cx="${cx}" cy="${cy}" r="11.5"/><text class="lbl${root}" x="${cx}" y="${cy}">${esc(nameOf[pc])}</text>`);
    }
  });
  return `<div class="fret"><svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Escala no braço da guitarra, casas 0 a ${FRETS}">${parts.join('')}</svg></div>
    <p class="fret-cap">Guitarra em afinação padrão, casas 0 a ${FRETS} (corda mais aguda em cima). Em laranja, a tônica.</p>`;
}

let scaleRef = null; // { kind: 'main' | 'sec', section, index }

function scaleGroup(song, ref) {
  const a = analysisOf(song);
  if (ref.kind === 'sec') {
    const sec = song.sections[ref.section];
    return { res: a.sections[ref.section], where: `Para a parte ${sec.name}.` };
  }
  const others = Object.keys(a.sections).map((s) => song.sections[s].name);
  return { res: a.main, where: others.length ? `Para a música, exceto: ${others.join(', ')} (veja a sugestão ao lado do nome de cada uma).` : 'Para a música inteira.' };
}

function renderScale() {
  const song = current();
  const key = keyOf(song);
  const { res, where } = scaleGroup(song, scaleRef);
  if (!res) return;
  const opt = res.options[Math.min(scaleRef.index, res.options.length - 1)];
  const root = rootName(song, key, opt);
  const names = scaleNotes(root, opt.type);
  const ints = intervalNames(opt.type);
  $('scale-title').textContent = `${root} ${SCALES[opt.type].name}`;
  const tabs = res.options.map((o, i) =>
    `<button type="button" class="sc" data-scale-tab="${i}" aria-pressed="${i === scaleRef.index}">${esc(optionLabel(song, key, o))}</button>`).join('');
  const notes = names.map((n, i) => `<span class="note${i === 0 ? ' root' : ''}"><b>${esc(n)}</b><small>${ints[i]}</small></span>`).join('');
  let tip = '';
  if (opt.type === 'blues') tip = 'A b5 é nota de passagem: resolva na 4 ou na 5. Sobre cada acorde com sétima, o mixolídio dele também funciona.';
  else if (opt.type === 'majorPent') tip = 'Sem as notas que brigam com os acordes: é a escolha mais segura sobre a música toda.';
  else if (opt.type === 'minorPent') tip = 'O som clássico do rock e do blues; termine as frases na tônica ou na 5.';
  else if (opt.type === 'mixolydian') tip = 'Maior com a 7 menor (b7): o som do rock com o acorde de bVII.';
  else if (opt.type === 'dorian') tip = 'Menor com a 6 maior: dá cor de soul e funk sobre acordes menores.';
  else if (opt.type === 'harmonicMinor') tip = 'Use sobre o acorde maior do quinto grau (V), quando ele aparece; a 7 maior puxa de volta para a tônica.';
  else if (opt.type === 'lydian') tip = 'Maior com a 4 aumentada (#4): som aberto, de trilha.';
  $('scale-body').innerHTML = `
    <p class="where">${esc(where)} Encaixa em ${Math.round(opt.fit * 100)}% das notas dos acordes.</p>
    <div class="scale-tabs" role="group" aria-label="Outras opções">${tabs}</div>
    <div class="notes-row">${notes}</div>
    ${tip ? `<p class="tip">${esc(tip)}</p>` : ''}
    ${fretboard(opt, key, names)}`;
}

function openScale(ref) {
  const [kind, a] = ref.split(':');
  scaleRef = kind === 'sec' ? { kind, section: Number(a), index: 0 } : { kind: 'main', index: Number(a) || 0 };
  renderScale();
  openSheet('scale');
}

let lastFocus = null;
function openSheet(id) {
  lastFocus = document.activeElement;
  $(id).hidden = false;
  const close = $(id).querySelector('.close');
  if (close) close.focus();
}
function closeSheets() {
  for (const id of ['list', 'notes', 'scale']) $(id).hidden = true;
  if (lastFocus && lastFocus.focus) lastFocus.focus();
}
const sheetOpen = () => !$('list').hidden || !$('notes').hidden || !$('scale').hidden;

// ------------------------------------------------------------------ controles

$('prev').addEventListener('click', () => go(-1));
$('next').addEventListener('click', () => go(1));
$('next-song').addEventListener('click', () => go(1));
$('key-down').addEventListener('click', () => transpose(-1));
$('key-up').addEventListener('click', () => transpose(1));
$('key-label').addEventListener('click', resetKey);
$('open-list').addEventListener('click', openList);
$('notes-btn').addEventListener('click', openNotes);
document.addEventListener('click', (e) => {
  const sc = e.target.closest('[data-scale]');
  if (sc) { openScale(sc.dataset.scale); return; }
  const tab = e.target.closest('[data-scale-tab]');
  if (tab && scaleRef) { scaleRef.index = Number(tab.dataset.scaleTab); renderScale(); }
});
function applyScales() { $('scales-btn').setAttribute('aria-pressed', String(!!state.scales)); }
$('scales-btn').addEventListener('click', () => { state.scales = !state.scales; applyScales(); render(); });
applyScales();
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
/** Abre a música de índice [i] da lista (usado pelos testes). */
function show(i) { state.index = i; render(); }
window.Cifras = { go, show, transpose, fit, get index() { return state.index; }, songs: SONG_LIST, analysisOf };
