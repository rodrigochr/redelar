// Teoria musical e dados (levadas, progressões, baixo) — os dados vêm do app Android
// (WebDataExportTest gera data/music.json), então as duas versões tocam igual.
import data from '../data/music.json';

export const M = data;
export const C = data.constants;

const index = (list) => Object.fromEntries(list.map((x) => [x.id, x]));
export const GROOVES = index(data.grooves);
export const PROGRESSIONS = index(data.progressions);
export const BASS_STYLES = index(data.bassStyles);
export const SUBDIVISIONS = index(data.subdivisions);
export const INSTRUMENTS = data.instruments;

export const grooveById = (id) => GROOVES[id] || data.grooves[0];
export const progressionById = (id) => PROGRESSIONS[id] || data.progressions[0];
export const bassStyleById = (id) => BASS_STYLES[id] || data.bassStyles[0];
export const subdivisionById = (id) => SUBDIVISIONS[id] || data.subdivisions[0];

export const KEYS = ['C', 'Db', 'D', 'Eb', 'E', 'F', 'F#', 'G', 'Ab', 'A', 'Bb', 'B'];
const SHARPS = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];
const FLATS = ['C', 'Db', 'D', 'Eb', 'E', 'F', 'Gb', 'G', 'Ab', 'A', 'Bb', 'B'];
const FLAT_KEYS = new Set([0, 1, 3, 5, 8, 10]);

export const mod = (a, n) => ((a % n) + n) % n;

export function chordRoot(chord, key) {
  return mod(key + chord.degree, 12);
}

/** Nota mais grave do acorde (o baixo invertido, se houver). */
export function chordBass(chord, key) {
  return mod(key + (chord.bass != null ? chord.bass : chord.degree), 12);
}

export function chordName(chord, key) {
  const names = FLAT_KEYS.has(mod(key, 12)) ? FLATS : SHARPS;
  return names[chordRoot(chord, key)] + data.qualities[chord.quality].suffix;
}

// Grafia das notas como graus do tom (igual a NoteNames.spell no app Android).
const MINOR_TONICS = ['C', 'C#', 'D', 'Eb', 'E', 'F', 'F#', 'G', 'G#', 'A', 'Bb', 'B'];
const LETTERS = 'CDEFGAB';
const NATURALS = [0, 2, 4, 5, 7, 9, 11];
const STEP_OF_DEGREE = [0, 1, 1, 2, 2, 3, 3, 4, 5, 5, 6, 6];
const FLAT_SIGNATURES = new Set([1, 3, 5, 8, 10]);
const AWKWARD = new Set(['E#', 'B#', 'Cb', 'Fb']);

export function keyName(key, minor) {
  return minor ? `${MINOR_TONICS[mod(key, 12)]}m` : KEYS[mod(key, 12)];
}

export function spell(pitchClass, key, minor) {
  const pc = mod(pitchClass, 12);
  const k = mod(key, 12);
  const tonic = minor ? MINOR_TONICS[k] : KEYS[k];
  const letter = (LETTERS.indexOf(tonic[0]) + STEP_OF_DEGREE[mod(pc - k, 12)]) % 7;
  const accidental = mod(pc - NATURALS[letter] + 6, 12) - 6;
  if (accidental >= -1 && accidental <= 1) {
    const name = LETTERS[letter] + (accidental === 1 ? '#' : accidental === -1 ? 'b' : '');
    if (!AWKWARD.has(name)) return name;
  }
  return (FLAT_SIGNATURES.has(mod(k + (minor ? 3 : 0), 12)) ? FLATS : SHARPS)[pc];
}

/** Nome do acorde de uma música, com a grafia do tom (ex.: F#m em Ré, Bb em Fá, D/F#). */
export function spelledName(chord, key, minor) {
  if (chord.quality === 'NC') return chord.suffix;
  // Trechos que modulam são grafados no tom local.
  const local = key + (chord.spellShift || 0);
  const localMinor = chord.spellMinor != null ? chord.spellMinor : minor;
  const root = spell(chordRoot(chord, key), local, localMinor) + chord.suffix;
  return chord.bass != null ? `${root}/${spell(key + chord.bass, local, localMinor)}` : root;
}

/** Acorde no tempo [beat] de um compasso: os acordes dividem o compasso em partes iguais (arredondando para cima). */
export function chordInBar(chords, beat, beats) {
  const k = chords.length;
  if (k === 1) return chords[0];
  let j = k - 1;
  while (j > 0 && beat < Math.floor((j * beats + k - 1) / k)) j--;
  return chords[j];
}

export function chordAt(prog, bar, beat, beatsPerBar) {
  return chordInBar(prog.bars[mod(bar, prog.bars.length)], beat, beatsPerBar);
}

export const sameChord = (a, b) => a.degree === b.degree && a.quality === b.quality
  && (a.suffix || '') === (b.suffix || '') && (a.bass ?? null) === (b.bass ?? null)
  && (a.spellShift || 0) === (b.spellShift || 0) && (a.spellMinor ?? null) === (b.spellMinor ?? null);

/** Para cada compasso, há quantos compassos o mesmo acorde vinha soando (alterna padrões de baixo). */
export function computeBarsSinceChange(bars) {
  return bars.map((bar, index) => {
    let count = 0;
    for (let p = index - 1; p >= 0; p--) {
      if (bars[p].length === 1 && sameChord(bars[p][0], bar[0])) count++;
      else break;
    }
    return count;
  });
}

// ------------------------------------------------------------------ progressões e músicas
// Mesma interface de Progression (Harmony.kt): bars, barsSinceChange, beatsAt, fillAt, crashAt,
// loops, entryBar, displayBar e chordName.

const typeProgressions = {};
function typeProgression(id) {
  const p = progressionById(id);
  if (!typeProgressions[p.id]) {
    typeProgressions[p.id] = {
      id: p.id,
      bars: p.bars,
      barsSinceChange: p.barsSinceChange,
      loops: true,
      beatsAt: () => null,
      fillAt(bar) { return this.bars.length > 1 && bar === this.bars.length - 1; },
      crashAt: (bar) => bar === 0,
      entryBar: () => 0,
      displayBar(bar) { return mod(bar, this.bars.length); },
      chordName: (chord, key) => chordName(chord, key),
    };
  }
  return typeProgressions[p.id];
}

class SongProgression {
  constructor(song, from, until, loops) {
    this.song = song;
    this.from = from;
    this.until = until;
    this.loops = loops;
    this.songBars = song.bars.slice(from, until);
    this.bars = this.songBars.map((b) => b.chords);
    this.barsSinceChange = computeBarsSinceChange(this.bars);
  }

  beatsAt(bar) { return this.songBars[mod(bar, this.bars.length)].beats; }

  startsPhrase(bar) { return bar === 0 || this.song.phraseStart[this.from + bar]; }

  fillAt(bar) { return this.bars.length > 1 && (bar === this.bars.length - 1 || this.startsPhrase(bar + 1)); }

  crashAt(bar) { return this.startsPhrase(bar); }

  displayBar(bar) { return this.from + mod(bar, this.bars.length); }

  entryBar(previous, previousBar) {
    if (!(previous instanceof SongProgression) || previous.song !== this.song) return 0;
    const next = previous.displayBar(previousBar) + 1;
    if (next >= this.from && next < this.until) return next - this.from;
    if (next >= this.until && !this.loops) return this.bars.length;
    return 0;
  }

  chordName(chord, key) { return spelledName(chord, key, this.song.minor); }
}

function buildSong(raw) {
  const song = { ...raw };
  // Compassos chegam como índices na tabela de acordes da música (+ tempos dos compassos irregulares).
  const sections = raw.sections.map((sec) => ({
    name: sec.name,
    bars: sec.bars.map((ids, i) => ({ chords: ids.map((k) => raw.chords[k]), beats: sec.beats && sec.beats[i] != null ? sec.beats[i] : null })),
  }));
  song.sections = sections;
  let start = 0;
  song.parts = raw.form.map((s, index) => {
    const part = { index, section: s, name: sections[s].name, bars: sections[s].bars, start, length: sections[s].bars.length };
    part.end = start + part.length;
    start = part.end;
    return part;
  });
  song.bars = song.parts.flatMap((p) => p.bars);
  song.length = song.bars.length;
  song.keyName = keyName(raw.key, raw.minor);
  song.phraseStart = song.bars.map(() => false);
  song.parts.forEach((p, i) => { if (i === 0 || song.parts[i - 1].section !== p.section) song.phraseStart[p.start] = true; });
  song.partAt = (bar) => { const b = mod(bar, song.length); return song.parts.filter((p) => p.start <= b).pop(); };
  const whole = { true: new SongProgression(song, 0, song.length, true), false: new SongProgression(song, 0, song.length, false) };
  const parts = song.parts.map((p) => new SongProgression(song, p.start, p.end, true));
  song.progression = (part, repeat) => (part >= 0 && part < parts.length ? parts[part] : whole[!!repeat]);
  return song;
}

export const SONG_LIST = (data.songs || []).map(buildSong);
const SONGS = index(SONG_LIST);
export const songById = (id) => (id != null && SONGS[id]) || null;

/** Progressão tocada no modo levada: a da música escolhida ou a progressão padrão. */
export function activeProgression(s) {
  const song = s.mode === 'GROOVE' ? songById(s.songId) : null;
  return song ? song.progression(s.songPart, s.songRepeat) : typeProgression(s.progression);
}

const MAJOR_SCALE = [0, 2, 4, 5, 7, 9, 11, 12];

/** Semitons de um grau (ver Degree no app Android). */
export function degreeSemitones(d, qualityId) {
  const q = data.qualities[qualityId];
  let base;
  if (d.chord) {
    base = [0, 0, 2, q.third, 5, q.fifth, q.sixth, q.seventh, 12][d.n];
  } else {
    base = MAJOR_SCALE[d.n - 1] + d.acc;
  }
  return base + 12 * d.oct;
}

/** Deformação de swing dentro de cada par (0.5 = reto, 0.667 = tercina, 0.75 = pontuado). */
export function swingWarp(position, swing, pairLength) {
  if (swing <= 0.5001) return position;
  const k = Math.floor(position / pairLength);
  const f = (position - k * pairLength) / pairLength;
  const w = f < 0.5 ? (f / 0.5) * swing : swing + ((f - 0.5) / 0.5) * (1 - swing);
  return (k + w) * pairLength;
}

export function defaultAccents(beats, unit) {
  let groups = null;
  if (unit === 8 && beats === 5) groups = [3, 2];
  else if (unit === 8 && beats === 7) groups = [2, 2, 3];
  else if (unit === 8 && beats % 3 === 0 && beats > 3) groups = Array(beats / 3).fill(3);
  if (!groups) return Array.from({ length: beats }, (_, i) => (i === 0 ? 'ACCENT' : 'NORMAL'));
  const result = Array(beats).fill('SOFT');
  let i = 0;
  groups.forEach((size, g) => {
    result[i] = g === 0 ? 'ACCENT' : 'NORMAL';
    i += size;
  });
  return result;
}

export function tempoMarking(bpm) {
  const marks = [[40, 'Grave'], [55, 'Largo'], [66, 'Larghetto'], [76, 'Adagio'], [92, 'Andante'],
    [108, 'Moderato'], [120, 'Allegretto'], [156, 'Allegro'], [176, 'Vivace'], [200, 'Presto']];
  for (const [limit, name] of marks) if (bpm < limit) return name;
  return 'Prestissimo';
}

export const ACCENT_CYCLE = ['ACCENT', 'NORMAL', 'SOFT', 'MUTE'];
export const MIN_BPM = 20;
export const MAX_BPM = 300;

export const DEFAULT_SETTINGS = Object.freeze({
  mode: 'CLICK',
  bpm: 100,
  beatsPerBar: 4,
  beatUnit: 4,
  accents: defaultAccents(4, 4),
  subdivision: 'NONE',
  clickSwing: 0.5,
  clickSound: 'CLASSIC',
  clickVolume: 1,
  subdivisionVolume: 0.55,
  grooveId: 'chicago_shuffle',
  genre: 'BLUES',
  swing: 0.67,
  bassEnabled: true,
  bassStyle: 'SHUFFLE_156',
  bassTone: 'ELECTRIC',
  bassOctave: 0,
  key: 9,
  progression: 'BLUES_12',
  songId: null,
  songPart: -1,
  songRepeat: true,
  fills: true,
  humanize: true,
  mixer: { ...data.grooves[0].mix, guideClick: 0 }, // mixagem padrão da levada inicial
  masterVolume: 0.9,
  countInBars: 0,
  trainerEnabled: false,
  trainerTargetBpm: 140,
  trainerStepBpm: 5,
  trainerEveryBars: 4,
  gapEnabled: false,
  gapPlayBars: 2,
  gapMuteBars: 2,
  timerMinutes: 0,
  keepScreenOn: true,
  flashOnDownbeat: false,
  useGrooveTempo: true,
});

const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));
const CHANNEL_KEYS = { KICK: 'kick', SNARE: 'snare', CYMBALS: 'cymbals', PERCUSSION: 'percussion', BASS: 'bass', GUIDE_CLICK: 'guideClick' };
export const channelKey = (channel) => CHANNEL_KEYS[channel];

/** Garante valores válidos (dados salvos antigos ou corrompidos). */
export function normalizeSettings(raw) {
  const s = { ...DEFAULT_SETTINGS, ...(raw || {}) };
  s.mixer = { ...DEFAULT_SETTINGS.mixer, ...(raw && raw.mixer) };
  s.bpm = clamp(Math.round(Number(s.bpm) || 100), MIN_BPM, MAX_BPM);
  s.beatsPerBar = clamp(Math.round(Number(s.beatsPerBar) || 4), 1, 16);
  if (![2, 4, 8, 16].includes(s.beatUnit)) s.beatUnit = 4;
  if (!Array.isArray(s.accents) || s.accents.length !== s.beatsPerBar || !s.accents.every((a) => ACCENT_CYCLE.includes(a))) {
    s.accents = defaultAccents(s.beatsPerBar, s.beatUnit);
  }
  if (!SUBDIVISIONS[s.subdivision]) s.subdivision = 'NONE';
  if (!data.clickSounds.some((c) => c.id === s.clickSound)) s.clickSound = 'CLASSIC';
  if (!GROOVES[s.grooveId]) s.grooveId = data.grooves[0].id;
  if (!data.genres.some((g) => g.id === s.genre)) s.genre = 'BLUES';
  if (!BASS_STYLES[s.bassStyle]) s.bassStyle = grooveById(s.grooveId).bass;
  if (!data.bassTones.some((t) => t.id === s.bassTone)) s.bassTone = 'ELECTRIC';
  if (!PROGRESSIONS[s.progression]) s.progression = 'BLUES_12';
  const song = songById(s.songId);
  s.songId = song ? song.id : null;
  s.songPart = song && Number.isInteger(s.songPart) && s.songPart >= 0 && s.songPart < song.parts.length ? s.songPart : -1;
  s.songRepeat = s.songRepeat !== false;
  if (s.mode !== 'GROOVE') s.mode = 'CLICK';
  s.swing = clamp(Number(s.swing) || 0.5, 0.5, 0.75);
  s.clickSwing = clamp(Number(s.clickSwing) || 0.5, 0.5, 0.75);
  s.bassOctave = clamp(Math.round(Number(s.bassOctave) || 0), -1, 1);
  s.key = mod(Math.round(Number(s.key) || 0), 12);
  s.countInBars = clamp(Math.round(Number(s.countInBars) || 0), 0, 4);
  s.trainerTargetBpm = clamp(Math.round(Number(s.trainerTargetBpm) || 140), MIN_BPM, MAX_BPM);
  s.trainerStepBpm = clamp(Math.round(Number(s.trainerStepBpm) || 5), 1, 20);
  s.trainerEveryBars = clamp(Math.round(Number(s.trainerEveryBars) || 4), 1, 32);
  s.gapPlayBars = clamp(Math.round(Number(s.gapPlayBars) || 2), 1, 16);
  s.gapMuteBars = clamp(Math.round(Number(s.gapMuteBars) || 2), 1, 16);
  s.timerMinutes = clamp(Math.round(Number(s.timerMinutes) || 0), 0, 120);
  for (const k of ['clickVolume', 'subdivisionVolume', 'masterVolume']) s[k] = clamp(Number(s[k]) || 0, 0, 1);
  for (const k of Object.keys(s.mixer)) s.mixer[k] = clamp(Number(s.mixer[k]) || 0, 0, 1);
  return s;
}

/** Mixagem padrão de uma levada, mantendo o clique guia que o usuário escolheu. */
export const grooveMix = (groove, current) => ({ ...groove.mix, guideClick: current ? current.guideClick : 0 });

export const activeBeatsPerBar = (s) => (s.mode === 'GROOVE' ? grooveById(s.grooveId).beats : s.beatsPerBar);
export const effectiveAccents = (s) =>
  s.accents.length === s.beatsPerBar ? s.accents : defaultAccents(s.beatsPerBar, s.beatUnit);
