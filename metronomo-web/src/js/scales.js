// Escalas e modos para improvisar sobre as músicas da setlist.
// A sugestão vem das notas dos acordes de cada parte (fundamental, terça, quinta e sétima, pesadas
// pela duração): para cada centro tonal candidato, escolhe o modo que mais "encaixa" nessas notas,
// a pentatônica correspondente e, nos blues, a escala blues. Tudo em graus do tom da música,
// então acompanha a transposição.
import { M, grooveById, mod, spelledName } from './music.js';

/** steps = letra (grau da escala de 7 notas) de cada nota, para grafar certo (Bb e não A#). */
export const SCALES = {
  ionian: { name: 'maior (jônio)', short: 'maior', semis: [0, 2, 4, 5, 7, 9, 11], steps: [0, 1, 2, 3, 4, 5, 6], rank: 0, major: true },
  aeolian: { name: 'menor natural (eólio)', short: 'eólio', semis: [0, 2, 3, 5, 7, 8, 10], steps: [0, 1, 2, 3, 4, 5, 6], rank: 0, major: false },
  mixolydian: { name: 'mixolídio', short: 'mixolídio', semis: [0, 2, 4, 5, 7, 9, 10], steps: [0, 1, 2, 3, 4, 5, 6], rank: 1, major: true },
  dorian: { name: 'dórico', short: 'dórico', semis: [0, 2, 3, 5, 7, 9, 10], steps: [0, 1, 2, 3, 4, 5, 6], rank: 1, major: false },
  harmonicMinor: { name: 'menor harmônica', short: 'menor harm.', semis: [0, 2, 3, 5, 7, 8, 11], steps: [0, 1, 2, 3, 4, 5, 6], rank: 2, major: false },
  lydian: { name: 'lídio', short: 'lídio', semis: [0, 2, 4, 6, 7, 9, 11], steps: [0, 1, 2, 3, 4, 5, 6], rank: 3, major: true },
  phrygian: { name: 'frígio', short: 'frígio', semis: [0, 1, 3, 5, 7, 8, 10], steps: [0, 1, 2, 3, 4, 5, 6], rank: 3, major: false },
  majorPent: { name: 'pentatônica maior', short: 'pent. maior', semis: [0, 2, 4, 7, 9], steps: [0, 1, 2, 4, 5], pent: true },
  minorPent: { name: 'pentatônica menor', short: 'pent. menor', semis: [0, 3, 5, 7, 10], steps: [0, 2, 3, 4, 6], pent: true },
  blues: { name: 'blues (pentatônica menor + b5)', short: 'blues', semis: [0, 3, 5, 6, 7, 10], steps: [0, 2, 3, 4, 4, 6], pent: true },
};
const MODES = ['ionian', 'aeolian', 'mixolydian', 'dorian', 'harmonicMinor', 'lydian', 'phrygian'];
const INTERVAL = ['1', 'b2', '2', 'b3', '3', '4', 'b5', '5', 'b6', '6', 'b7', '7'];
const SEVENTH = new Set(['DOM7', 'MIN7', 'MAJ7', 'HALF_DIM', 'DIM7']);

/** Notas de um acorde, em graus do tom da música, com o peso de cada uma. */
function chordTones(chord) {
  if (chord.quality === 'NC') return [];
  const q = M.qualities[chord.quality];
  const r = chord.degree;
  const tones = [[r, 1], [r + q.third, 0.8], [r + q.fifth, 0.6]];
  if (SEVENTH.has(chord.quality)) tones.push([r + q.seventh, 0.7]);
  if (chord.bass != null) tones.push([chord.bass, 0.6]);
  return tones.map(([d, w]) => [mod(d, 12), w]);
}

/** Peso de cada grau (0–11) nas notas dos acordes de uma lista de compassos. */
function profile(bars) {
  const w = new Array(12).fill(0);
  const roots = new Array(12).fill(0);
  const dominants = new Set();
  for (const bar of bars) {
    const share = 1 / bar.chords.length;
    for (const c of bar.chords) {
      if (c.quality === 'NC') continue;
      roots[mod(c.degree, 12)] += share;
      if (c.quality === 'DOM7') dominants.add(mod(c.degree, 12));
      for (const [d, weight] of chordTones(c)) w[d] += weight * share;
    }
  }
  return { w, roots, dominants, total: w.reduce((a, b) => a + b, 0) };
}

const fitOf = (p, root, type) => {
  if (!p.total) return 0;
  const set = new Set(SCALES[type].semis.map((s) => mod(root + s, 12)));
  return p.w.reduce((acc, weight, d) => acc + (set.has(d) ? weight : 0), 0) / p.total;
};

/**
 * Melhores escalas para um trecho. [home] é o centro tonal "de casa" (o tom da música ou o tom
 * local de uma modulação). Devolve opções em ordem: { root, type, fit }.
 */
function suggest(bars, home, homeMinor, bluesFeel = false) {
  const p = profile(bars);
  if (!p.total) return null;
  const first = bars.find((b) => b.chords.some((c) => c.quality !== 'NC'));
  const firstRoot = first ? mod(first.chords.find((c) => c.quality !== 'NC').degree, 12) : home;
  const topRoot = p.roots.indexOf(Math.max(...p.roots));
  const centers = [...new Set([home, firstRoot, topRoot])];
  let best = null;
  for (const root of centers) {
    for (const type of MODES) {
      const fit = fitOf(p, root, type);
      // Desempate: modos mais comuns, o centro de casa e, depois, o acorde que abre o trecho.
      const score = fit - 0.02 * SCALES[type].rank - (root === home ? 0 : 0.03) - (root === firstRoot ? 0 : 0.01)
        + (SCALES[type].major === !homeMinor && root === home ? 0.005 : 0);
      if (!best || score > best.score) best = { root, type, fit, score };
    }
  }
  const major = SCALES[best.type].major;
  // Blues: sétima dominante no I, ou dominantes em dois dos graus I, IV e V.
  const bluesRoots = [0, 5, 7].map((d) => mod(best.root + d, 12)).filter((d) => p.dominants.has(d));
  const blues = p.dominants.has(best.root) || bluesRoots.length >= 2;
  const r = best.root;
  const options = [];
  if (blues) {
    options.push({ root: r, type: 'blues' });
    if (major) options.push({ root: r, type: 'majorPent' });
    options.push({ root: r, type: major ? 'mixolydian' : best.type });
  } else if (best.type === 'mixolydian') {
    // Rock em mixolídio (I–bVII–IV): as duas pentatônicas funcionam; a que mais encaixa vem antes.
    const pents = ['minorPent', 'majorPent'].sort((a, b) => fitOf(p, r, b) - fitOf(p, r, a));
    pents.forEach((type) => options.push({ root: r, type }));
    options.push({ root: r, type: 'mixolydian' });
  } else {
    options.push({ root: r, type: major ? 'majorPent' : 'minorPent' });
    options.push({ root: r, type: best.type });
  }
  // Menor com acorde maior no V (ex.: Am … E7): menor harmônica sobre ele.
  if (!major && best.type !== 'harmonicMinor'
    && bars.some((b) => b.chords.some((c) => mod(c.degree - r, 12) === 7 && ['DOM7', 'MAJ'].includes(c.quality)))) {
    options.push({ root: r, type: 'harmonicMinor' });
  }
  // Levada de blues: a escala blues sempre cabe como cor.
  if (bluesFeel && !options.some((o) => o.type === 'blues')) options.push({ root: r, type: 'blues' });
  return { options: options.map((o) => ({ ...o, fit: fitOf(p, o.root, o.type) })), blues, mode: best };
}

const sameFamily = (a, b) => a && b && a.root === b.root && SCALES[a.type].major === SCALES[b.type].major;

/**
 * Sugestões da música: as principais (música inteira) e as de cada parte que pede outra coisa
 * (modulação ou mudança de maior para menor).
 */
export function analyzeSong(song) {
  const home = 0;
  const all = song.parts.flatMap((p) => p.bars);
  const bluesFeel = grooveById(song.groove).genre === 'BLUES';
  const main = suggest(all, home, song.minor, bluesFeel);
  const sections = {};
  const seen = new Set(song.parts.map((p) => p.section));
  for (const s of seen) {
    const sec = song.sections[s];
    const spelled = sec.bars.flatMap((b) => b.chords).find((c) => c.spellShift || c.spellMinor != null);
    // Trechos curtos (turnarounds, breques) seguem a sugestão da música; nos blues, a escala blues
    // cobre também o refrão. Modulações (tom local) sempre ganham sugestão própria.
    if (!spelled && (sec.bars.length < 4 || (main && main.blues))) continue;
    const localHome = spelled ? mod(spelled.spellShift || 0, 12) : home;
    const localMinor = spelled && spelled.spellMinor != null ? spelled.spellMinor : song.minor;
    const res = suggest(sec.bars, localHome, localMinor, bluesFeel);
    if (!res || !main) continue;
    const differs = !sameFamily(res.mode, main.mode);
    if (differs) {
      // Grafia do tom local (Bb e não A# depois de uma modulação).
      if (spelled) res.options.forEach((o) => { o.spellShift = spelled.spellShift || 0; o.spellMinor = localMinor; });
      sections[s] = res;
    }
  }
  return { main, sections };
}

// ------------------------------------------------------------------ nomes e notas

const LETTERS = 'CDEFGAB';
const NATURALS = [0, 2, 4, 5, 7, 9, 11];

/** Nome da tônica da escala, com a mesma grafia dos acordes da música no tom atual. */
export const rootName = (song, key, option) => spelledName(
  { degree: option.root, quality: 'MAJ', suffix: '', spellShift: option.spellShift || 0, spellMinor: option.spellMinor },
  key, song.minor);

/** Notas da escala com letras certas (cada grau numa letra), a partir do nome da tônica. */
export function scaleNotes(rootLabel, type) {
  const sc = SCALES[type];
  const letter0 = LETTERS.indexOf(rootLabel[0]);
  const acc0 = rootLabel.slice(1) === '#' ? 1 : rootLabel.slice(1) === 'b' ? -1 : 0;
  const rootPc = mod(NATURALS[letter0] + acc0, 12);
  return sc.semis.map((s, i) => {
    const letter = (letter0 + sc.steps[i]) % 7;
    const pc = mod(rootPc + s, 12);
    const acc = mod(pc - NATURALS[letter] + 6, 12) - 6;
    const sign = acc === 1 ? '#' : acc === -1 ? 'b' : acc === 2 ? '##' : acc === -2 ? 'bb' : '';
    return Math.abs(acc) <= 2 ? LETTERS[letter] + sign : '?';
  });
}

export const intervalNames = (type) => SCALES[type].semis.map((s) => INTERVAL[s]);

/** Graus (pitch classes) da escala no tom atual, para desenhar no braço do instrumento. */
export const scalePitchClasses = (key, option) => SCALES[option.type].semis.map((s) => mod(key + option.root + s, 12));

/** Rótulo curto (ex.: "E blues", "C# pent. menor"). */
export function optionLabel(song, key, option, long = false) {
  const root = rootName(song, key, option);
  return `${root} ${long ? SCALES[option.type].name : SCALES[option.type].short}`;
}
