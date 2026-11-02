// Interface da versão web do BluesTempo (mesmas abas do app Android).
import { Engine } from './engine.js';
import {
  ACCENT_CYCLE, KEYS, M, MAX_BPM, MIN_BPM, activeBeatsPerBar, bassStyleById, channelKey, chordAt, chordName,
  defaultAccents, effectiveAccents, grooveById, grooveMix, normalizeSettings, progressionById, subdivisionById, tempoMarking,
} from './music.js';
import { loadSampleBundle } from './sounds.js';
import { applyPreset, loadPresets, loadSettings, savePresets, saveSettings } from './store.js';

const $ = (sel, root = document) => root.querySelector(sel);
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
const pct = (v) => `${Math.round(v * 100)}%`;

let settings = loadSettings();
let presets = loadPresets();
let tab = settings.mode === 'GROOVE' ? 'grooves' : 'metronome';
let samplesState = 'loading'; // loading | ready | error
let samplesProgress = 0;
let presetForm = null; // { mode: 'save' } | { mode: 'delete', id }
let wakeLock = null;
const taps = [];

const engine = new Engine({
  onStop: (reason) => {
    releaseWakeLock();
    stopIOSAudio();
    renderTransport();
    if (reason === 'timer') toast('Tempo de estudo concluído.');
  },
});

// ------------------------------------------------------------------ estado

function update(changes, { rerender = true } = {}) {
  settings = normalizeSettings({ ...settings, ...changes });
  saveSettings(settings);
  engine.update(settings);
  if (rerender) render();
  else renderTransport();
}

function setMixer(key, value) {
  update({ mixer: { ...settings.mixer, [key]: value } }, { rerender: false });
  refreshMixerReset();
}

function getPath(path) {
  return path.split('.').reduce((o, k) => (o ? o[k] : undefined), settings);
}

function setPath(path, value) {
  if (path.startsWith('mixer.')) setMixer(path.slice(6), value);
  else update({ [path]: value }, { rerender: false });
}

// ------------------------------------------------------------------ transporte

async function togglePlay() {
  if (engine.playing) {
    engine.stop();
    return;
  }
  if (settings.mode === 'GROOVE' && samplesState !== 'ready') {
    toast(samplesState === 'error' ? 'Não foi possível carregar os instrumentos. Verifique a conexão e recarregue.' : 'Carregando os instrumentos reais…');
    return;
  }
  prepareIOSAudio();
  try {
    await engine.start(settings);
  } catch (e) {
    console.error(e);
    toast('O navegador bloqueou o áudio. Toque em ▶ novamente.');
    return;
  }
  requestWakeLock();
  renderTransport();
  // Confere se o relógio de áudio realmente começou a andar.
  const t0 = engine.ctx.currentTime;
  setTimeout(() => {
    if (engine.playing && (!engine.audioRunning || engine.ctx.currentTime <= t0)) {
      toast(IS_IOS
        ? 'O iPhone não liberou o som. Aumente o volume, toque em ■ e depois em ▶. Se continuar mudo, abra a página no Safari.'
        : 'O navegador não liberou o som. Toque em ■ e depois em ▶ novamente.');
    }
  }, 900);
}

const IS_IOS = /iPad|iPhone|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);

engine.onStateChange = (state) => {
  if (!engine.playing) return;
  if (state === 'interrupted' || state === 'suspended') {
    engine.stop('interrupted');
    toast('O som foi interrompido (ligação, alarme ou outro app). Toque em ▶ para continuar.');
  }
};

function nudge(delta) {
  update({ bpm: clamp(settings.bpm + delta, MIN_BPM, MAX_BPM) }, { rerender: false });
}

function tap() {
  const now = performance.now();
  if (taps.length && now - taps[taps.length - 1] > 2000) taps.length = 0;
  if (taps.length && now - taps[taps.length - 1] < 150) return; // toque duplo acidental
  taps.push(now);
  while (taps.length > 6) taps.shift();
  if (taps.length >= 2) {
    const intervals = taps.slice(1).map((t, i) => t - taps[i]);
    const avg = intervals.reduce((a, b) => a + b, 0) / intervals.length;
    update({ bpm: clamp(Math.round(60000 / avg), MIN_BPM, MAX_BPM) }, { rerender: false });
  }
}

/**
 * iPhone: um <audio> mudo tocando em loop coloca a página na categoria "reprodução" do iOS,
 * então o som sai mesmo com a chave de silencioso ligada. O arquivo vem junto com a página
 * (silence.wav); se não carregar, usa uma versão embutida.
 */
let silentAudio = null;
function prepareIOSAudio() {
  try {
    if (navigator.audioSession) navigator.audioSession.type = 'playback';
  } catch (e) { /* sem suporte */ }
  try {
    if (!silentAudio) {
      silentAudio = document.createElement('audio');
      silentAudio.setAttribute('playsinline', '');
      silentAudio.setAttribute('x-webkit-airplay', 'deny');
      silentAudio.preload = 'auto';
      silentAudio.loop = true;
      silentAudio.src = 'silence.wav';
      silentAudio.addEventListener('error', () => {
        if (!silentAudio.src.startsWith('data:')) {
          silentAudio.src = silentWav();
          silentAudio.play().catch(() => {});
        }
      }, { once: true });
    }
    silentAudio.play().catch(() => {});
  } catch (e) { /* sem suporte */ }
}

function stopIOSAudio() {
  try { if (silentAudio) silentAudio.pause(); } catch (e) { /* sem suporte */ }
}

function silentWav() {
  const rate = 8000;
  const frames = 800;
  const buf = new ArrayBuffer(44 + frames);
  const v = new DataView(buf);
  const str = (o, s) => [...s].forEach((c, i) => v.setUint8(o + i, c.charCodeAt(0)));
  str(0, 'RIFF'); v.setUint32(4, 36 + frames, true); str(8, 'WAVEfmt ');
  v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
  v.setUint32(24, rate, true); v.setUint32(28, rate, true); v.setUint16(32, 1, true); v.setUint16(34, 8, true);
  str(36, 'data'); v.setUint32(40, frames, true);
  for (let i = 0; i < frames; i++) v.setUint8(44 + i, 128);
  let bin = '';
  new Uint8Array(buf).forEach((b) => { bin += String.fromCharCode(b); });
  return 'data:audio/wav;base64,' + btoa(bin);
}

async function requestWakeLock() {
  if (!settings.keepScreenOn || !('wakeLock' in navigator)) return;
  try {
    wakeLock = await navigator.wakeLock.request('screen');
  } catch (e) {
    wakeLock = null;
  }
}

function releaseWakeLock() {
  if (wakeLock) wakeLock.release().catch(() => {});
  wakeLock = null;
}

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible' && engine.playing && !wakeLock) requestWakeLock();
});

// ------------------------------------------------------------------ componentes

const chip = (label, active, action, extra = '') =>
  `<button type="button" class="chip${active ? ' on' : ''}" data-action="${action}" ${extra} aria-pressed="${active}">${esc(label)}</button>`;

const section = (title, body, subtitle = '') =>
  `<section class="card"><h2>${esc(title)}</h2>${subtitle ? `<p class="hint">${esc(subtitle)}</p>` : ''}${body}</section>`;

function slider(id, label, path, { min = 0, max = 1, step = 0.01, format = pct } = {}) {
  const value = getPath(path);
  return `<div class="slider"><label for="${id}"><span>${esc(label)}</span><output data-for="${path}">${esc(format(value))}</output></label>
    <input id="${id}" type="range" min="${min}" max="${max}" step="${step}" value="${value}" data-slider="${path}" data-format="${format === pct ? 'pct' : 'swing'}"></div>`;
}

function toggle(id, label, path, hint = '') {
  const on = !!getPath(path);
  return `<div class="toggle"><div><label for="${id}">${esc(label)}</label>${hint ? `<p class="hint">${esc(hint)}</p>` : ''}</div>
    <input id="${id}" type="checkbox" role="switch" ${on ? 'checked' : ''} data-toggle="${path}"></div>`;
}

function stepper(label, path, min, max) {
  const value = getPath(path);
  return `<div class="stepper"><span>${esc(label)}</span>
    <button type="button" class="round sm" data-repeat="step" data-path="${path}" data-delta="-1" data-min="${min}" data-max="${max}" aria-label="Diminuir ${esc(label)}">−</button>
    <output data-for="${path}">${value}</output>
    <button type="button" class="round sm" data-repeat="step" data-path="${path}" data-delta="1" data-min="${min}" data-max="${max}" aria-label="Aumentar ${esc(label)}">+</button></div>`;
}

function select(id, label, path, options) {
  const value = getPath(path);
  return `<div class="field"><label for="${id}">${esc(label)}</label><select id="${id}" data-select="${path}">${options
    .map(([v, l]) => `<option value="${esc(v)}"${String(v) === String(value) ? ' selected' : ''}>${esc(l)}</option>`).join('')}</select></div>`;
}

export function swingText(v) {
  const name = v < 0.53 ? 'reto' : v < 0.62 ? 'leve' : v < 0.7 ? 'shuffle' : 'pontuado';
  return `${Math.round(v * 100)}% · ${name}`;
}

// ------------------------------------------------------------------ abas

function metronomeTab() {
  const meters = M.meters.map((m) => chip(`${m.beats}/${m.unit}`, m.beats === settings.beatsPerBar && m.unit === settings.beatUnit,
    'meter', `data-beats="${m.beats}" data-unit="${m.unit}"`)).join('');
  const units = [2, 4, 8, 16].map((u) => chip(`/${u}`, u === settings.beatUnit, 'meter', `data-beats="${settings.beatsPerBar}" data-unit="${u}"`)).join('');
  const accents = effectiveAccents(settings).map((a, i) =>
    `<button type="button" class="accent a-${a.toLowerCase()}" data-action="accent" data-i="${i}" aria-label="Tempo ${i + 1}: ${M.accents.find((x) => x.id === a).label}">
      <span class="bar"></span><span class="num">${i + 1}</span></button>`).join('');
  const sub = subdivisionById(settings.subdivision);
  return [
    section('Fórmula de compasso', `<div class="chips">${meters}</div>${stepper('Tempos por compasso', 'beatsPerBar', 1, 16)}
      <p class="label">Figura de tempo</p><div class="chips">${units}</div>`),
    section('Acentos', `<div class="accents">${accents}</div>
      <div class="row end"><button type="button" class="text" data-action="accents-none">Sem acento</button>
      <button type="button" class="text" data-action="accents-reset">Padrão</button></div>`,
    'Toque em cada tempo para alternar: forte → normal → fraco → mudo.'),
    section('Subdivisão', `<div class="chips">${M.subdivisions.map((s) => chip(s.label, s.id === settings.subdivision, 'sub', `data-id="${s.id}"`)).join('')}</div>
      ${sub.swingPair != null ? slider('click-swing', 'Swing', 'clickSwing', { min: 0.5, max: 0.75, format: swingText }) : ''}
      ${settings.subdivision !== 'NONE' ? slider('sub-vol', 'Volume da subdivisão', 'subdivisionVolume') : ''}`),
    section('Som do clique', `<div class="chips">${M.clickSounds.map((c) => chip(c.label, c.id === settings.clickSound, 'click-sound', `data-id="${c.id}"`)).join('')}</div>
      ${slider('click-vol', 'Volume do clique', 'clickVolume')}${slider('master-vol', 'Volume geral', 'masterVolume')}`),
  ].join('');
}

function stepGrid(g) {
  const steps = g.beats * g.steps;
  const rows = g.tracks.map((t) => {
    const cells = Array.from({ length: steps }, (_, s) => {
      const v = t.v[s];
      return `<i class="cell${v > 0 ? ' hit' : ''}${s % g.steps === 0 ? ' beat' : ''}" data-step="${s}" style="--v:${v}"></i>`;
    }).join('');
    return `<div class="grid-row"><span>${esc(M.instruments[t.instrument].label)}</span><div class="cells" style="--steps:${steps}">${cells}</div></div>`;
  }).join('');
  return `<div class="grid" id="step-grid" data-bars="${g.bars}">${rows}</div>
    ${g.bars > 1 ? `<p class="hint" id="pattern-bar">Padrão de ${g.bars} compassos — mostrando o 1º</p>` : ''}`;
}

function progressionGrid() {
  const prog = progressionById(settings.progression);
  return `<div class="prog" id="prog-grid">${prog.bars.map((bar, i) =>
    `<div class="prog-bar" data-bar="${i}"><small>${i + 1}</small><b class="${bar.length > 1 ? 'two' : ''}">${bar.map((c) => esc(chordName(c, settings.key))).join(' ')}</b></div>`).join('')}</div>`;
}

function isDefaultMix(g) {
  const def = grooveMix(g, settings.mixer);
  return Object.keys(def).every((k) => Math.abs(def[k] - settings.mixer[k]) < 0.001);
}

function refreshMixerReset() {
  const btn = document.getElementById('mixer-reset');
  if (!btn) return;
  const g = grooveById(settings.grooveId);
  const isDefault = isDefaultMix(g);
  btn.disabled = isDefault;
  btn.textContent = isDefault ? `Mixagem padrão de ${g.name}` : 'Restaurar padrão da levada';
}

function groovesTab() {
  const g = grooveById(settings.grooveId);
  const genres = M.genres.map((x) =>
    `<button type="button" class="seg${x.id === settings.genre ? ' on' : ''}" data-action="genre" data-id="${x.id}">${esc(x.label)} <small>${M.grooves.filter((gr) => gr.genre === x.id).length}</small></button>`).join('');
  const list = M.grooves.filter((x) => x.genre === settings.genre).map((x) => {
    const on = x.id === g.id;
    return `<button type="button" class="groove${on ? ' on' : ''}" data-action="groove" data-id="${x.id}" aria-pressed="${on}">
      <span class="groove-head"><strong>${esc(x.name)}</strong><span class="gbpm">${x.bpm} BPM</span></span>
      <span class="desc">${esc(x.description)}</span>
      <span class="tags"><span class="tag">${esc(x.meter)}</span><span class="tag">${esc(bassStyleById(x.bass).label)}</span></span></button>`;
  }).join('');
  const bassOptions = M.bassStyles.map((b) => [b.id, b.id === g.bass ? `${b.label} (sugerido)` : b.label]);
  return [
    `<div class="segmented" role="group" aria-label="Gênero">${genres}</div><div class="grooves">${list}</div>`,
    section(g.name, stepGrid(g), `${g.meter} · ${g.description}`),
    section('Sensação', `${g.swingUnit != null ? slider('swing', 'Swing', 'swing', { min: 0.5, max: 0.75, format: swingText })
      : '<p class="hint">Levada em tercinas: o shuffle já está escrito na grade.</p>'}
      ${toggle('humanize', 'Humanizar', 'humanize', 'Pequenas variações de dinâmica, como um baterista real.')}
      ${toggle('fills', 'Viradas e prato de ataque', 'fills', 'Virada no último compasso da progressão e prato na volta.')}`),
    section('Baixo e harmonia', `${toggle('bass-on', 'Linha de baixo', 'bassEnabled')}
      <p class="label">Tom</p><div class="chips keys">${KEYS.map((k, i) => chip(k, i === settings.key, 'key', `data-i="${i}"`)).join('')}</div>
      ${select('prog', 'Progressão', 'progression', M.progressions.map((p) => [p.id, p.label]))}
      <p class="hint">${esc(progressionById(settings.progression).description)}</p>
      ${progressionGrid()}
      ${select('bass-style', 'Estilo do baixo', 'bassStyle', bassOptions)}
      <p class="label">Timbre</p><div class="chips">${M.bassTones.map((t) => chip(t.label, t.id === settings.bassTone, 'tone', `data-id="${t.id}"`)).join('')}</div>
      <p class="label">Região</p><div class="chips">${[[-1, 'Mais grave'], [0, 'Normal'], [1, 'Aguda (alto-falante do celular)']]
        .map(([v, l]) => chip(l, v === settings.bassOctave, 'octave', `data-v="${v}"`)).join('')}</div>`),
    section('Mixer', `${M.channels.map((c) => slider(`mix-${c.id}`, c.label, `mixer.${channelKey(c.id)}`)).join('')}
      ${slider('master-vol', 'Volume geral', 'masterVolume')}
      <div class="row end"><button type="button" class="text" id="mixer-reset" data-action="mixer-reset"${isDefaultMix(g) ? ' disabled' : ''}>
        ${isDefaultMix(g) ? `Mixagem padrão de ${esc(g.name)}` : 'Restaurar padrão da levada'}</button></div>`,
    'Cada levada tem sua mixagem padrão. Ao trocar de levada, os volumes voltam ao padrão dela.'),
  ].join('');
}

function practiceTab() {
  const direction = settings.trainerTargetBpm >= settings.bpm ? 'sobe' : 'desce';
  return [
    section('Contagem inicial', `<div class="chips">${[0, 1, 2, 4].map((n) => chip(n ? `${n} comp.` : 'Sem contagem', n === settings.countInBars, 'count-in', `data-v="${n}"`)).join('')}</div>`,
      'Compassos de clique antes de começar (a levada entra depois).'),
    section('Treinador de andamento', `${toggle('trainer', 'Ativar', 'trainerEnabled')}
      ${stepper('Andamento alvo', 'trainerTargetBpm', MIN_BPM, MAX_BPM)}${stepper('Mudar (BPM)', 'trainerStepBpm', 1, 20)}${stepper('A cada (compassos)', 'trainerEveryBars', 1, 32)}
      <p class="hint">Começa em ${settings.bpm} BPM e ${direction} ${settings.trainerStepBpm} BPM a cada ${settings.trainerEveryBars} compasso(s) até ${settings.trainerTargetBpm} BPM.</p>`,
    'Acelera (ou desacelera) automaticamente enquanto você toca.'),
    section('Compassos em silêncio', `${toggle('gap', 'Ativar', 'gapEnabled')}${stepper('Tocar (compassos)', 'gapPlayBars', 1, 16)}${stepper('Silenciar (compassos)', 'gapMuteBars', 1, 16)}`,
      'O som some por alguns compassos e volta: você deve continuar no tempo.'),
    section('Timer de estudo', `<div class="chips">${[0, 5, 10, 15, 20, 30, 45, 60].map((n) => chip(n ? `${n} min` : 'Desligado', n === settings.timerMinutes, 'timer', `data-v="${n}"`)).join('')}</div>`,
      'Para automaticamente depois do tempo escolhido.'),
    section('Opções', `${toggle('screen', 'Manter a tela ligada', 'keepScreenOn', 'Enquanto estiver tocando.')}
      ${toggle('flash', 'Piscar no tempo 1', 'flashOnDownbeat', 'Referência visual para palco e ensaio.')}
      ${toggle('groove-tempo', 'Usar andamento sugerido', 'useGrooveTempo', 'Ao escolher uma levada, ajusta o BPM para o andamento típico do estilo.')}`),
    section('Sobre', `<p class="hint">Versão web do BluesTempo, com o mesmo motor e as mesmas levadas do app Android. No iPhone, o som para quando a tela bloqueia ou você troca de app.</p>
      <p class="hint">Instrumentos reais em domínio público (CC0): Virtuosity Drums (Versilian Studios &amp; Karoryfer Samples), Big Rusty Drums, Black And Blue Basses e Meatbass (Karoryfer Samples) e VCSL (Versilian Community Sample Library).</p>`),
  ].join('');
}

function presetSummary(p) {
  const s = normalizeSettings(p.settings);
  if (s.mode === 'GROOVE') {
    return `${grooveById(s.grooveId).name} · ${s.bpm} BPM${s.bassEnabled ? ` · Tom ${KEYS[s.key]}` : ''} · ${progressionById(s.progression).label}`;
  }
  return `Metrônomo ${s.beatsPerBar}/${s.beatUnit} · ${s.bpm} BPM · ${subdivisionById(s.subdivision).label}`;
}

function presetsTab() {
  const suggestion = settings.mode === 'GROOVE' ? `${grooveById(settings.grooveId).name} ${settings.bpm}` : `Clique ${settings.bpm}`;
  const form = presetForm && presetForm.mode === 'save'
    ? `<form class="preset-form" data-form="save"><label for="preset-name">Nome do preset</label>
        <input id="preset-name" name="name" maxlength="60" value="${esc(suggestion)}" autocomplete="off">
        <div class="row end"><button type="button" class="text" data-action="preset-cancel">Cancelar</button><button type="submit" class="primary">Salvar</button></div></form>`
    : '<button type="button" class="primary wide" data-action="preset-new">Salvar configuração atual</button>';
  const list = presets.length
    ? presets.map((p) => {
      const confirming = presetForm && presetForm.mode === 'delete' && presetForm.id === p.id;
      return `<div class="preset"><button type="button" class="preset-main" data-action="preset-load" data-id="${esc(p.id)}">
        <strong>${esc(p.name)}</strong><span>${esc(presetSummary(p))}</span></button>
        ${confirming
          ? `<div class="row"><button type="button" class="text danger" data-action="preset-delete" data-id="${esc(p.id)}">Excluir</button><button type="button" class="text" data-action="preset-cancel">Manter</button></div>`
          : `<div class="row"><button type="button" class="text" data-action="preset-overwrite" data-id="${esc(p.id)}">Atualizar</button><button type="button" class="text" data-action="preset-ask-delete" data-id="${esc(p.id)}">Excluir</button></div>`}
      </div>`;
    }).join('')
    : '<p class="empty">Nenhum preset salvo ainda. Monte sua levada (ex.: Shuffle de Chicago em Lá, 116 BPM) e salve com o nome da música.</p>';
  return section('Presets e setlist', form, 'Guarde andamento, levada, tom, progressão, mixer e treinos para cada música. Ficam salvos neste navegador.') + list;
}

// ------------------------------------------------------------------ renderização

const TABS = { metronome: metronomeTab, grooves: groovesTab, practice: practiceTab, presets: presetsTab };

function render() {
  const main = $('#tab');
  const scroll = main.scrollTop;
  main.innerHTML = TABS[tab]();
  main.scrollTop = scroll;
  document.querySelectorAll('.tabs button').forEach((b) => b.setAttribute('aria-current', b.dataset.tab === tab ? 'page' : 'false'));
  lastPositionKey = '';
  renderTransport();
}

function renderTransport() {
  const g = grooveById(settings.grooveId);
  const groove = settings.mode === 'GROOVE';
  $('#mode-tag').textContent = groove ? `${g.name} · ${g.meter}` : `Metrônomo · ${settings.beatsPerBar}/${settings.beatUnit}`;
  $('#bpm-slider').value = settings.bpm;
  $('#play').classList.toggle('playing', engine.playing);
  $('#play').setAttribute('aria-label', engine.playing ? 'Parar' : 'Tocar');
  $('#play-icon').setAttribute('d', engine.playing ? 'M7 7h10v10H7z' : 'M8 5v14l11-7z');
  $('#chord-box').hidden = !groove;
  lastPositionKey = '';
  updatePosition(true);
}

let lastPositionKey = '';
function updatePosition(force) {
  const pos = engine.positionNow();
  const key = pos ? `${pos.bar}|${pos.beat}|${pos.step}|${pos.bpm}|${pos.muted}|${pos.countIn}` : `stop|${settings.bpm}|${settings.mode}|${settings.beatsPerBar}`;
  if (!force && key === lastPositionKey) return;
  lastPositionKey = key;
  const groove = settings.mode === 'GROOVE';
  const bpm = pos ? pos.bpm : settings.bpm;
  $('#bpm').textContent = bpm;
  const g = grooveById(settings.grooveId);
  const unit = groove ? g.beatNote : '♩';
  $('#bpm-label').textContent = `BPM (${unit}) · ${tempoMarking(bpm)}${settings.trainerEnabled ? `  →  ${settings.trainerTargetBpm}` : ''}`;

  const beats = pos ? pos.beatsPerBar : activeBeatsPerBar(settings);
  const accents = groove ? null : effectiveAccents(settings);
  const dots = $('#beats');
  if (dots.childElementCount !== beats || dots.dataset.accents !== String(accents)) {
    dots.dataset.accents = String(accents);
    dots.innerHTML = Array.from({ length: beats }, (_, i) => `<i class="dot ${accents ? 'a-' + accents[i].toLowerCase() : i === 0 ? 'a-accent' : ''}"></i>`).join('');
  }
  [...dots.children].forEach((d, i) => {
    d.classList.toggle('now', !!pos && pos.beat === i);
    d.classList.toggle('alt', !!pos && (pos.countIn || pos.muted));
  });

  const status = $('#status');
  const loading = !pos && groove && samplesState === 'loading';
  status.textContent = pos ? (pos.countIn ? 'Contagem' : pos.muted ? 'Silêncio' : `Comp. ${pos.bar + 1}`)
    : loading ? `Carregando sons… ${Math.round(samplesProgress * 100)}%` : '';
  status.className = pos && pos.muted ? 'status muted' : pos && pos.countIn ? 'status count' : loading ? 'status loading' : 'status';

  if (groove) {
    const prog = progressionById(settings.progression);
    const bar = pos && pos.progressionBar >= 0 ? pos.progressionBar : 0;
    const beat = pos ? pos.beat : 0;
    $('#chord').textContent = chordName(chordAt(prog, bar, beat, g.beats), settings.key);
    $('#chord-next').textContent = `→ ${chordName(chordAt(prog, bar + 1, 0, g.beats), settings.key)}`;
    const grid = document.getElementById('step-grid');
    if (grid) {
      const step = pos && pos.mode === 'GROOVE' && !pos.countIn ? pos.step : -1;
      grid.querySelectorAll('.cell.now').forEach((c) => c.classList.remove('now'));
      if (step >= 0) grid.querySelectorAll(`.cell[data-step="${step}"]`).forEach((c) => c.classList.add('now'));
      const patternBar = pos ? pos.patternBar : 0;
      if (grid.dataset.shown !== String(patternBar)) {
        grid.dataset.shown = String(patternBar);
        const steps = g.beats * g.steps;
        grid.querySelectorAll('.grid-row').forEach((row, r) => {
          row.querySelectorAll('.cell').forEach((cell, s) => {
            const v = g.tracks[r].v[patternBar * steps + s];
            cell.classList.toggle('hit', v > 0);
            cell.style.setProperty('--v', v);
          });
        });
        const label = document.getElementById('pattern-bar');
        if (label) label.textContent = `Padrão de ${g.bars} compassos — mostrando o ${patternBar + 1}º`;
      }
    }
    const progGrid = document.getElementById('prog-grid');
    if (progGrid) {
      const current = pos && pos.progressionBar >= 0 ? pos.progressionBar : -1;
      progGrid.querySelectorAll('.prog-bar').forEach((el) => el.classList.toggle('now', Number(el.dataset.bar) === current));
    }
  }
  const accentBars = document.querySelectorAll('.accent');
  accentBars.forEach((el, i) => el.classList.toggle('now', !!pos && pos.mode === 'CLICK' && !pos.countIn && pos.beat === i));

  if (pos && settings.flashOnDownbeat && pos.beat === 0 && !pos.countIn) {
    const t = $('#transport');
    t.classList.remove('flash');
    void t.offsetWidth; // reinicia a animação
    t.classList.add('flash');
  }
}

function clock() {
  const el = $('#clock');
  if (!engine.playing) {
    el.textContent = '0:00';
    el.classList.remove('on');
    return;
  }
  const secs = Math.max(0, Math.floor(engine.ctx.currentTime - engine.startedAt));
  el.textContent = `${Math.floor(secs / 60)}:${String(secs % 60).padStart(2, '0')}`;
  el.classList.add('on');
}

function loop() {
  if (engine.playing) updatePosition(false);
  else if (lastPositionKey.indexOf('stop') !== 0) updatePosition(true);
  requestAnimationFrame(loop);
}

let toastTimer = null;
function toast(message) {
  const el = $('#toast');
  el.textContent = message;
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, 3500);
}

// ------------------------------------------------------------------ ações

const ACTIONS = {
  tab: (d) => {
    tab = d.tab;
    presetForm = null;
    if (tab === 'metronome') update({ mode: 'CLICK' });
    else if (tab === 'grooves') update({ mode: 'GROOVE' });
    else render();
    $('#tab').scrollTop = 0;
  },
  play: () => togglePlay(),
  tap: () => tap(),
  meter: (d) => {
    const beats = Number(d.beats);
    const unit = Number(d.unit);
    update({ beatsPerBar: beats, beatUnit: unit, accents: defaultAccents(beats, unit) });
  },
  accent: (d) => {
    const accents = [...effectiveAccents(settings)];
    const i = Number(d.i);
    accents[i] = ACCENT_CYCLE[(ACCENT_CYCLE.indexOf(accents[i]) + 1) % ACCENT_CYCLE.length];
    update({ accents });
  },
  'mixer-reset': () => update({ mixer: grooveMix(grooveById(settings.grooveId), settings.mixer) }),
  'accents-reset': () => update({ accents: defaultAccents(settings.beatsPerBar, settings.beatUnit) }),
  'accents-none': () => update({ accents: Array(settings.beatsPerBar).fill('NORMAL') }),
  sub: (d) => update({ subdivision: d.id }),
  'click-sound': (d) => update({ clickSound: d.id }),
  genre: (d) => update({ genre: d.id }),
  groove: (d) => {
    const g = grooveById(d.id);
    const changes = {
      grooveId: g.id, genre: g.genre, mode: 'GROOVE', swing: g.swing, bassStyle: g.bass, bassTone: g.tone, progression: g.progression,
      mixer: grooveMix(g, settings.mixer), // cada levada volta à sua mixagem padrão
    };
    if (settings.useGrooveTempo) changes.bpm = g.bpm;
    update(changes);
  },
  key: (d) => update({ key: Number(d.i) }),
  tone: (d) => update({ bassTone: d.id }),
  octave: (d) => update({ bassOctave: Number(d.v) }),
  'count-in': (d) => update({ countInBars: Number(d.v) }),
  timer: (d) => update({ timerMinutes: Number(d.v) }),
  'preset-new': () => { presetForm = { mode: 'save' }; render(); const input = $('#preset-name'); if (input) { input.focus(); input.select(); } },
  'preset-cancel': () => { presetForm = null; render(); },
  'preset-load': (d) => {
    const p = presets.find((x) => x.id === d.id);
    if (!p) return;
    settings = applyPreset(settings, p.settings);
    saveSettings(settings);
    engine.update(settings);
    render();
    toast(`Preset “${p.name}” carregado.`);
  },
  'preset-overwrite': (d) => {
    presets = presets.map((p) => (p.id === d.id ? { ...p, settings: { ...settings } } : p));
    persistPresets('Preset atualizado.');
  },
  'preset-ask-delete': (d) => { presetForm = { mode: 'delete', id: d.id }; render(); },
  'preset-delete': (d) => {
    presets = presets.filter((p) => p.id !== d.id);
    presetForm = null;
    persistPresets('Preset excluído.');
  },
};

function persistPresets(message) {
  const ok = savePresets(presets);
  render();
  toast(ok ? message : 'Este navegador não permite salvar (janela anônima?). O preset vale só até fechar a página.');
}

const app = document.getElementById('app');

app.addEventListener('click', (e) => {
  const el = e.target.closest('[data-action], [data-repeat]');
  if (!el) return;
  if (el.dataset.repeat) {
    if (e.detail === 0) runRepeat(el); // teclado (Enter/Espaço)
    return;
  }
  const fn = ACTIONS[el.dataset.action];
  if (fn) fn(el.dataset);
});

app.addEventListener('submit', (e) => {
  const form = e.target.closest('[data-form="save"]');
  if (!form) return;
  e.preventDefault();
  const name = (new FormData(form).get('name') || '').toString().trim() || 'Preset';
  presets = [...presets, { id: String(Date.now()) + Math.random().toString(36).slice(2, 6), name: name.slice(0, 60), settings: { ...settings } }];
  presetForm = null;
  persistPresets('Preset salvo.');
});

app.addEventListener('input', (e) => {
  const el = e.target;
  if (el.dataset.slider) {
    const value = Number(el.value);
    setPath(el.dataset.slider, value);
    const out = app.querySelectorAll(`output[data-for="${el.dataset.slider}"]`);
    out.forEach((o) => { o.textContent = el.dataset.format === 'swing' ? swingText(value) : pct(value); });
  } else if (el.id === 'bpm-slider') {
    update({ bpm: Number(el.value) }, { rerender: false });
  }
});

app.addEventListener('change', (e) => {
  const el = e.target;
  if (el.dataset.toggle) update({ [el.dataset.toggle]: el.checked }, { rerender: true });
  else if (el.dataset.select) update({ [el.dataset.select]: el.value });
  else if (el.dataset.slider && tab === 'practice') render();
});

// Botões −/+ repetem enquanto pressionados.
function runRepeat(el) {
  if (el.dataset.repeat === 'bpm') nudge(Number(el.dataset.delta));
  else {
    const path = el.dataset.path;
    const value = clamp(getPath(path) + Number(el.dataset.delta), Number(el.dataset.min), Number(el.dataset.max));
    const changes = { [path]: value };
    if (path === 'beatsPerBar') changes.accents = defaultAccents(value, settings.beatUnit);
    update(changes, { rerender: path === 'beatsPerBar' || tab === 'practice' });
    app.querySelectorAll(`output[data-for="${path}"]`).forEach((o) => { o.textContent = value; });
  }
}

let repeatTimer = null;
app.addEventListener('pointerdown', (e) => {
  const el = e.target.closest('[data-repeat]');
  if (!el) return;
  e.preventDefault();
  runRepeat(el);
  let interval = 90;
  const again = () => {
    runRepeat(el);
    interval = Math.max(35, interval - 6);
    repeatTimer = setTimeout(again, interval);
  };
  repeatTimer = setTimeout(again, 420);
});
['pointerup', 'pointercancel', 'pointerleave'].forEach((type) => app.addEventListener(type, () => clearTimeout(repeatTimer)));

// ------------------------------------------------------------------ início

render();
requestAnimationFrame(loop);
setInterval(clock, 250);

const bundleUrl = window.BT_SAMPLES_URL || 'samples.bin';
loadSampleBundle(bundleUrl, (p) => {
  samplesProgress = p;
  if (!engine.playing) updatePosition(true);
}).then((bundle) => {
  engine.setBundle(bundle);
  samplesState = 'ready';
  renderTransport();
}).catch((err) => {
  console.error(err);
  samplesState = 'error';
  renderTransport();
  if (settings.mode === 'GROOVE') toast('Não foi possível carregar os instrumentos. Verifique a conexão e recarregue a página.');
});

if (window.BT_PWA && 'serviceWorker' in navigator) {
  navigator.serviceWorker.register('sw.js').catch(() => {});
}

// Exposto para testes automatizados.
window.BluesTempo = { engine, get settings() { return settings; }, update };
window.BluesTempoInternals = { Engine, grooveById, normalizeSettings };
