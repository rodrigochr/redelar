// Motor de áudio da versão web: porta do Sequencer do app Android para o Web Audio.
// Os eventos são gerados tempo a tempo (mudança de andamento vale no tempo seguinte) e
// agendados no relógio do AudioContext, que tem precisão de amostra.
import {
  C, INSTRUMENTS, M, activeBeatsPerBar, bassStyleById, channelKey, chordAt, chordRoot, defaultAccents,
  degreeSemitones, effectiveAccents, grooveById, mod, progressionById, sameChord, subdivisionById, swingWarp,
} from './music.js';
import { synthesizeClicks } from './sounds.js';

const LOOKAHEAD = 0.12;
const TICK_MS = 25;
const MAX_MARKERS = 512;

class SampledDrum {
  constructor(layers) {
    this.layers = layers; // [{ level, buffers: [AudioBuffer] }], em ordem crescente
    this.rr = 0;
  }

  pick(target) {
    let layer = this.layers[this.layers.length - 1];
    for (const l of this.layers) {
      if (l.level >= target) {
        layer = l;
        break;
      }
    }
    this.rr++;
    return {
      buffer: layer.buffers[this.rr % layer.buffers.length],
      gain: Math.min(1.25, Math.max(0.05, target / layer.level)),
    };
  }
}

function makeRandom(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s + 0x6d2b79f5) >>> 0;
    let t = Math.imul(s ^ (s >>> 15), 1 | s);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

/** Curva do limitador final (a mesma do app Android), aplicada por um WaveShaper. */
function limiterCurve() {
  const n = 4096;
  const curve = new Float32Array(n);
  const knee = C.limitKnee;
  const range = 1 - knee;
  for (let i = 0; i < n; i++) {
    const x = ((i / (n - 1)) * 2 - 1) * 2; // entrada pré-atenuada por 0.5
    const a = Math.abs(x);
    let y = a;
    if (a > knee) {
      const over = a - knee;
      y = knee + range * (over / (over + range));
    }
    curve[i] = x < 0 ? -y : y;
  }
  return curve;
}

function tickerWorker() {
  try {
    const code = `let id=null;onmessage=(e)=>{if(e.data==='start'){clearInterval(id);id=setInterval(()=>postMessage('tick'),${TICK_MS});}else{clearInterval(id);id=null;}}`;
    return new Worker(URL.createObjectURL(new Blob([code], { type: 'text/javascript' })));
  } catch (e) {
    return null;
  }
}

export class Engine {
  constructor({ onStop } = {}) {
    this.onStop = onStop || (() => {});
    this.ctx = null;
    this.bundle = null;
    this.playing = false;
    this.settings = null;
    this.markers = [];
    this.random = makeRandom(7);
    /** Lista opcional de eventos agendados (para testes de paridade com o app Android). */
    this.trace = null;
  }

  /** Recebe o pacote de sons já decodificado (pode chegar depois do primeiro play). */
  setBundle(bundle) {
    this.bundle = bundle;
    if (this.ctx) this.buildBuffers();
  }

  /** Pacote de sons baixado e decodificado (os buffers de áudio são criados no primeiro Play). */
  get loaded() {
    return !!this.bundle;
  }

  createContext(offline) {
    if (offline) {
      this.ctx = offline;
    } else if (!this.ctx) {
      const AC = window.AudioContext || window.webkitAudioContext;
      this.ctx = new AC({ latencyHint: 'playback' });
    }
    const ctx = this.ctx;
    if (!offline) ctx.onstatechange = () => this.onStateChange && this.onStateChange(ctx.state);
    this.mix = ctx.createGain();
    this.master = ctx.createGain();
    this.master.gain.value = 0;
    const pre = ctx.createGain();
    pre.gain.value = 0.5;
    const shaper = ctx.createWaveShaper();
    shaper.curve = limiterCurve();
    this.mix.connect(this.master);
    this.master.connect(pre);
    pre.connect(shaper);
    shaper.connect(ctx.destination);
    this.bassBus = ctx.createGain();
    this.bassBus.connect(this.mix);
    // Um barramento estéreo fixo por peça, com a mesma lei de pan do app Android
    // (esquerda = min(1, 1 - pan), direita = min(1, 1 + pan)).
    this.buses = {};
    for (const [inst, meta] of Object.entries(INSTRUMENTS)) {
      const input = ctx.createGain();
      input.channelCount = 1;
      input.channelCountMode = 'explicit';
      input.channelInterpretation = 'speakers';
      const left = ctx.createGain();
      const right = ctx.createGain();
      left.gain.value = Math.min(1, 1 - meta.pan);
      right.gain.value = Math.min(1, 1 + meta.pan);
      const merger = ctx.createChannelMerger(2);
      input.connect(left);
      input.connect(right);
      left.connect(merger, 0, 0);
      right.connect(merger, 0, 1);
      merger.connect(this.mix);
      this.buses[inst] = input;
    }
    this.clicks = Object.fromEntries(Object.entries(synthesizeClicks(ctx.sampleRate)).map(([k, levels]) => [
      k, levels.map((data) => this.toBuffer(data, ctx.sampleRate)),
    ]));
    if (this.bundle) this.buildBuffers();
  }

  toBuffer(data, rate) {
    const buffer = this.ctx.createBuffer(1, data.length, rate);
    buffer.getChannelData(0).set(data);
    return buffer;
  }

  buildBuffers() {
    const { manifest, sounds } = this.bundle;
    const buf = (name) => this.toBuffer(sounds[name].data, sounds[name].rate);
    this.drums = {};
    for (const [inst, layers] of Object.entries(manifest.drums)) {
      this.drums[inst] = new SampledDrum(layers.map((l) => ({ level: Math.max(0.01, l.level), buffers: l.files.map(buf) })));
    }
    this.basses = {};
    for (const [tone, regions] of Object.entries(manifest.bass)) {
      this.basses[tone] = regions.map((r) => ({ key: r.key, cents: r.cents || 0, buffers: r.files.map(buf) }));
    }
  }

  // ---------------------------------------------------------------- transporte

  async start(settings) {
    if (this.playing || this.starting) return;
    this.starting = true;
    try {
      if (!this.ctx) this.createContext();
      const ctx = this.ctx;
      // iOS: um buffer mudo tocado dentro do próprio toque "destrava" o áudio da página.
      try {
        const unlock = ctx.createBufferSource();
        unlock.buffer = ctx.createBuffer(1, 1, ctx.sampleRate);
        unlock.connect(ctx.destination);
        unlock.start(0);
      } catch (e) { /* sem suporte */ }
      // resume() é chamado dentro do toque; não espera indefinidamente pela confirmação.
      const resumed = ctx.state === 'running' ? Promise.resolve() : Promise.resolve(ctx.resume()).catch(() => {});
      await Promise.race([resumed, new Promise((r) => setTimeout(r, 400))]);
      this.reset(settings, ctx.currentTime + C.leadIn + 0.05);
      this.playing = true;
      this.startedAt = ctx.currentTime;
      // Dois despertadores: o worker (continua em segundo plano) e um intervalo comum,
      // caso o navegador bloqueie workers. Agendar duas vezes é inofensivo.
      if (this.worker === undefined) this.worker = tickerWorker();
      if (this.worker) {
        this.worker.onmessage = () => this.tick();
        this.worker.onerror = () => { this.worker = null; };
        this.worker.postMessage('start');
      }
      clearInterval(this.interval);
      this.interval = setInterval(() => this.tick(), TICK_MS);
      this.tick();
    } finally {
      this.starting = false;
    }
  }

  /** O relógio de áudio está andando? (false quando o navegador ainda não liberou o som) */
  get audioRunning() {
    return !!this.ctx && this.ctx.state === 'running';
  }

  stop(reason) {
    if (!this.playing) return;
    this.playing = false;
    if (this.worker) this.worker.postMessage('stop');
    clearInterval(this.interval);
    this.interval = null;
    const now = this.ctx.currentTime;
    this.master.gain.cancelScheduledValues(now);
    this.master.gain.setTargetAtTime(0, now, 0.015);
    const voices = this.activeVoices;
    this.activeVoices = [];
    setTimeout(() => voices.forEach((v) => { try { v.src.stop(); } catch (e) { /* já terminou */ } }), 150);
    this.markers = [];
    this.onStop(reason);
  }

  update(settings) {
    this.settings = settings;
    if (this.playing) this.applyLiveGains(this.ctx.currentTime);
  }

  /** Renderiza [seconds] segundos sem tempo real (usado nos testes e para conferir com o app Android). */
  renderOffline(offlineCtx, settings, seconds) {
    this.createContext(offlineCtx);
    this.reset(settings, C.leadIn);
    this.playing = true;
    while (this.nextBeatTime < seconds) this.scheduleBeat();
    this.playing = false;
    return offlineCtx.startRendering();
  }

  reset(settings, t0) {
    this.settings = settings;
    this.barSettings = settings;
    this.barGroove = grooveById(settings.grooveId);
    this.barBeats = activeBeatsPerBar(settings);
    this.nextBeatTime = t0;
    this.beatInBar = 0;
    this.countInLeft = settings.countInBars;
    this.barsPlayed = 0;
    this.musicalBar = 0;
    this.isCountIn = false;
    this.isMuted = false;
    this.hasFill = false;
    this.hasCrash = false;
    this.progBar = 0;
    this.currentBpm = settings.bpm;
    this.lastUserBpm = settings.bpm;
    this.prevBassNote = -1;
    this.segmentRootNote = -1;
    this.random = makeRandom(7);
    this.markers = [];
    this.activeVoices = [];
    this.chokeVoices = {};
    this.bassVoices = [];
    this.applyLiveGains(t0, true);
  }

  applyLiveGains(time, immediate) {
    const s = this.settings;
    const real = !!this.drums;
    const level = this.barSettings.mode === 'CLICK' ? C.clickHeadroom : C.grooveHeadroom * (real ? this.barGroove.level : 1);
    const target = s.masterVolume * level;
    const bass = s.mixer.bass * C.sampledBassLevel;
    if (immediate) {
      this.master.gain.cancelScheduledValues(0);
      this.master.gain.setValueAtTime(target, Math.max(0, time - 0.01));
      this.bassBus.gain.setValueAtTime(bass, Math.max(0, time - 0.01));
    } else {
      this.master.gain.setTargetAtTime(target, time, 0.02);
      this.bassBus.gain.setTargetAtTime(bass, time, 0.02);
    }
  }

  tick() {
    if (!this.playing) return;
    const now = this.ctx.currentTime;
    while (this.nextBeatTime < now + LOOKAHEAD) this.scheduleBeat();
    const minutes = this.settings.timerMinutes;
    if (minutes > 0 && now - this.startedAt >= minutes * 60) this.stop('timer');
    if (this.activeVoices.length > 256) this.activeVoices = this.activeVoices.filter((v) => v.end > now);
  }

  /** Posição que está soando agora (considera a latência de saída quando o navegador informa). */
  positionNow() {
    if (!this.playing || !this.markers.length) return null;
    const t = this.ctx.currentTime - (this.ctx.outputLatency || this.ctx.baseLatency || 0);
    for (let i = this.markers.length - 1; i >= 0; i--) if (this.markers[i].time <= t) return this.markers[i].pos;
    return null;
  }

  get effectiveBpm() {
    return Math.round(this.currentBpm);
  }

  // ---------------------------------------------------------------- agendamento (igual ao Sequencer.kt)

  scheduleBeat() {
    const start = this.nextBeatTime;
    const latest = this.settings;
    if (this.beatInBar === 0) this.startBar(latest, start);
    this.updateBpm(latest);
    const beatLen = 60 / this.currentBpm;
    if (this.isCountIn) this.scheduleCountIn(start, latest);
    else if (this.barSettings.mode === 'CLICK') this.scheduleClick(start, beatLen, latest);
    else this.scheduleGroove(start, beatLen, latest);
    this.nextBeatTime = start + beatLen;
    this.beatInBar++;
    if (this.beatInBar >= this.barBeats) {
      this.beatInBar = 0;
      if (!this.isCountIn) this.barsPlayed++;
    }
  }

  startBar(s, time) {
    const modeChanged = this.barSettings.mode !== s.mode || this.barSettings.grooveId !== s.grooveId;
    this.barSettings = s;
    this.barGroove = grooveById(s.grooveId);
    this.barBeats = activeBeatsPerBar(s);
    if (modeChanged) this.applyLiveGains(time);
    if (this.countInLeft > 0) {
      this.countInLeft--;
      this.isCountIn = true;
      this.isMuted = false;
      this.hasFill = false;
      this.hasCrash = false;
      return;
    }
    this.isCountIn = false;
    this.musicalBar = this.barsPlayed;
    if (s.trainerEnabled && this.musicalBar > 0 && this.musicalBar % s.trainerEveryBars === 0) {
      const target = s.trainerTargetBpm;
      const step = s.trainerStepBpm;
      this.currentBpm = this.currentBpm < target ? Math.min(target, this.currentBpm + step) : Math.max(target, this.currentBpm - step);
    }
    const cycle = s.gapPlayBars + s.gapMuteBars;
    this.isMuted = s.gapEnabled && this.musicalBar % cycle >= s.gapPlayBars;
    const prog = progressionById(s.progression);
    this.progBar = this.musicalBar % prog.bars.length;
    const groove = s.mode === 'GROOVE' && s.fills;
    this.hasFill = groove && prog.bars.length > 1 && this.progBar === prog.bars.length - 1;
    this.hasCrash = groove && this.progBar === 0 && this.musicalBar > 0;
  }

  updateBpm(latest) {
    if (latest.bpm !== this.lastUserBpm) {
      this.lastUserBpm = latest.bpm;
      this.currentBpm = latest.bpm;
    } else if (!latest.trainerEnabled) {
      this.currentBpm = latest.bpm;
    }
  }

  marker(time, step, patternBar = 0) {
    this.markers.push({
      time,
      pos: {
        mode: this.barSettings.mode,
        bar: this.isCountIn ? -1 : this.musicalBar,
        beat: this.beatInBar,
        beatsPerBar: this.barBeats,
        step,
        patternBar,
        progressionBar: !this.isCountIn && this.barSettings.mode === 'GROOVE' ? this.progBar : -1,
        countIn: this.isCountIn,
        countInBarsLeft: this.isCountIn ? this.countInLeft : 0,
        muted: this.isMuted,
        bpm: Math.round(this.currentBpm),
      },
    });
    if (this.markers.length > MAX_MARKERS) this.markers.splice(0, this.markers.length - MAX_MARKERS);
  }

  scheduleCountIn(start, s) {
    this.marker(start, -1);
    const sound = this.barSettings.mode === 'GROOVE' ? 'STICKS' : s.clickSound;
    const volume = this.barSettings.mode === 'CLICK' ? Math.max(s.clickVolume, 0.5) : 0.85;
    const first = this.beatInBar === 0;
    this.click(start, sound, first ? 0 : 1, volume * (first ? 1 : 0.85));
  }

  scheduleClick(start, beatLen, s) {
    this.marker(start, -1);
    if (this.isMuted) return;
    let accents = effectiveAccents(s);
    if (accents.length !== this.barBeats) accents = defaultAccents(this.barBeats, this.barSettings.beatUnit);
    const accent = accents[this.beatInBar] || 'NORMAL';
    if (accent === 'MUTE') return;
    const [level, gain] = accent === 'ACCENT' ? [0, 1] : accent === 'NORMAL' ? [1, 0.85] : [1, 0.42];
    this.click(start, s.clickSound, level, gain * s.clickVolume);
    const sub = subdivisionById(s.subdivision);
    const subGain = s.subdivisionVolume * s.clickVolume * (accent === 'SOFT' ? 0.7 : 1);
    for (let k = 1; k < sub.positions.length; k++) {
      const p = sub.swingPair != null ? swingWarp(sub.positions[k], s.clickSwing, sub.swingPair) : sub.positions[k];
      this.click(start + p * beatLen, s.clickSound, 2, subGain);
    }
  }

  scheduleGroove(start, beatLen, s) {
    const g = this.barGroove;
    const spb = g.steps;
    const patternBar = this.musicalBar % g.bars;
    const swing = g.swingUnit != null ? s.swing : 0.5;
    const pair = g.swingUnit != null ? g.swingUnit : 1;
    const fillBeats = g.beats >= 4 ? 2 : 1;
    const fillActive = this.hasFill && this.beatInBar >= g.beats - fillBeats;
    const stepsPerBar = g.beats * spb;

    for (let st = 0; st < spb; st++) {
      const at = start + swingWarp(st / spb, swing, pair) * beatLen;
      const stepInBar = this.beatInBar * spb + st;
      this.marker(at, stepInBar, patternBar);
      if (this.isMuted) continue;
      const idx = patternBar * stepsPerBar + stepInBar;
      const crashHere = this.hasCrash && this.beatInBar === 0 && st === 0;
      let kickHere = false;
      for (const track of g.tracks) {
        const v = track.v[idx];
        if (v <= 0) continue;
        const inst = INSTRUMENTS[track.instrument];
        if (fillActive && inst.fill) continue;
        if (crashHere && inst.channel === 'CYMBALS' && track.instrument !== 'HAT_PEDAL') continue;
        if (track.instrument === 'KICK') kickHere = true;
        this.drum(at, track.instrument, v, s);
      }
      if (fillActive) {
        const fillStep = (this.beatInBar - (g.beats - fillBeats)) * spb + st;
        this.fill(at, g.fillKit, fillStep, fillBeats * spb, fillBeats, s);
      }
      if (crashHere) {
        this.drum(at, 'CRASH', 0.85, s);
        if (!kickHere) this.drum(at, 'KICK', 0.85, s);
      }
    }
    if (this.isMuted) return;
    if (s.bassEnabled) this.scheduleBass(start, beatLen, s, g);
    if (s.mixer.guideClick > 0) {
      const first = this.beatInBar === 0;
      this.click(start, s.clickSound, first ? 0 : 1, s.mixer.guideClick * (first ? 1 : 0.8));
    }
  }

  fill(at, kit, fillStep, total, fillBeats, s) {
    const progress = total > 1 ? fillStep / (total - 1) : 1;
    const velocity = 0.5 + 0.5 * progress;
    let inst;
    if (kit === 'BRUSHES') inst = 'BRUSH_TAP';
    else if (fillBeats > 1 && fillStep < Math.floor(total / 2)) inst = 'SNARE';
    else if (fillStep < Math.floor((total * 3) / 4) && fillBeats > 1) inst = 'TOM_HIGH';
    else if (fillBeats === 1 && fillStep < Math.floor(total / 2)) inst = 'TOM_HIGH';
    else inst = 'TOM_LOW';
    this.drum(at, inst, velocity, s);
  }

  // ---------------------------------------------------------------- vozes

  play(buffer, time, gain, bus, chokeGroup) {
    if (!buffer || gain <= 0) return;
    const ctx = this.ctx;
    const src = ctx.createBufferSource();
    src.buffer = buffer;
    const g = ctx.createGain();
    g.gain.value = gain;
    src.connect(g);
    g.connect(bus || this.mix);
    if (chokeGroup) {
      const list = this.chokeVoices[chokeGroup] || (this.chokeVoices[chokeGroup] = []);
      for (const v of list) {
        if (v.end > time && !v.choked) {
          v.choked = true;
          v.g.gain.setTargetAtTime(0, time, 0.0015);
          try { v.src.stop(time + 0.03); } catch (e) { /* já parado */ }
        }
      }
      this.chokeVoices[chokeGroup] = list.filter((v) => v.end > time && !v.choked);
    }
    src.start(time);
    const voice = { src, g, end: time + buffer.duration, choked: false };
    if (chokeGroup) this.chokeVoices[chokeGroup].push(voice);
    this.activeVoices.push(voice);
  }

  drum(time, inst, velocity, s) {
    const drum = this.drums && this.drums[inst];
    if (!drum) return;
    let v = velocity;
    if (s.humanize) v *= 1 + (this.random() - 0.5) * 0.14;
    const target = Math.pow(Math.min(1, Math.max(0, v)), 1.5);
    const meta = INSTRUMENTS[inst];
    const hit = drum.pick(target);
    const gain = hit.gain * meta.trim * s.mixer[channelKey(meta.channel)];
    if (this.trace && gain > 0) this.trace.push([time, 'drum', inst]);
    this.play(hit.buffer, time, gain, this.buses[inst], meta.choke);
  }

  click(time, sound, level, gain) {
    if (gain <= 0) return;
    if (this.trace) this.trace.push([time, 'click', sound + level]);
    this.play(this.clicks[sound][level], time, gain, null, 0);
  }

  // ---------------------------------------------------------------- baixo

  rootMidi(chord, s) {
    return C.bassLowRoot + mod(chordRoot(chord, s.key) - 4, 12) + 12 * s.bassOctave;
  }

  lowLimit(s) { return C.minBassNote + 12 * s.bassOctave; }

  highLimit(s) { return C.maxBassNote + 12 * s.bassOctave; }

  nearOctave(root, s) {
    if (this.prevBassNote < 0) return root;
    const up = root + 12;
    return up <= this.highLimit(s) && Math.abs(up - this.prevBassNote) < Math.abs(root - this.prevBassNote) ? up : root;
  }

  scheduleBass(start, beatLen, s, g) {
    const prog = progressionById(this.barSettings.progression);
    const beats = g.beats;
    const style = bassStyleById(s.bassStyle);
    if (style.algorithm === 'WALKING') {
      const note = this.walkingNote(prog, beats, s);
      this.bass(start, note, beatLen * 0.92, this.beatInBar === 0 ? 0.95 : 0.82, s);
    } else if (style.algorithm === 'TWO_FEEL') {
      const half = beats % 2 === 0 ? beats / 2 : beats;
      if (this.beatInBar % half === 0) this.bass(start, this.twoFeelNote(prog, beats, half, s), beatLen * half * 0.92, 0.9, s);
    } else {
      const bspb = style.stepsPerBeat;
      const variation = style.variations[prog.barsSinceChange[this.progBar] % style.variations.length];
      let bassSwing = 0.5;
      let bassPair = 1;
      if (g.steps === 3 && bspb === 2) bassSwing = 2 / 3;
      else if (g.steps === 3) bassSwing = 0.5;
      else if (g.swingUnit != null) { bassSwing = s.swing; bassPair = g.swingUnit; }
      const chord = chordAt(prog, this.progBar, this.beatInBar, beats);
      const root = this.rootMidi(chord, s);
      const stepLen = beatLen / bspb;
      for (let k = 0; k < bspb; k++) {
        const spec = variation.notes[mod(this.beatInBar * bspb + k, variation.length)];
        if (!spec) continue;
        const at = start + swingWarp(k / bspb, bassSwing, bassPair) * beatLen;
        const midi = root + degreeSemitones(spec.degree, chord.quality);
        this.bass(at, midi, stepLen * spec.len * style.gate, k === 0 ? 0.95 : 0.8, s);
      }
    }
  }

  walkingNote(prog, beats, s) {
    const chord = chordAt(prog, this.progBar, this.beatInBar, beats);
    let segStart = this.beatInBar;
    while (segStart > 0 && sameChord(chordAt(prog, this.progBar, segStart - 1, beats), chord)) segStart--;
    let segEnd = this.beatInBar;
    while (segEnd + 1 < beats && sameChord(chordAt(prog, this.progBar, segEnd + 1, beats), chord)) segEnd++;
    const segLen = segEnd - segStart + 1;
    const pos = this.beatInBar - segStart;
    const variant = mod(this.musicalBar * 5 + this.progBar * 3 + segStart, 7);
    if (pos === 0) {
      this.segmentRootNote = this.nearOctave(this.rootMidi(chord, s), s);
      return this.segmentRootNote;
    }
    if (pos === segLen - 1) {
      const next = this.beatInBar + 1 < beats ? chordAt(prog, this.progBar, this.beatInBar + 1, beats) : chordAt(prog, this.progBar + 1, 0, beats);
      return this.approach(this.nearOctave(this.rootMidi(next, s), s), variant, s);
    }
    const template = M.walkTemplates[variant % M.walkTemplates.length];
    const degree = template[(pos - 1) % template.length];
    let note = this.segmentRootNote + degreeSemitones(degree, chord.quality);
    if (note > this.highLimit(s)) note -= 12;
    return note;
  }

  approach(target, variant, s) {
    let note;
    if (variant % 3 === 2) note = target + 7 <= this.highLimit(s) ? target + 7 : target - 5;
    else if (this.prevBassNote > target) note = target + 1;
    else note = target - 1;
    if (note < this.lowLimit(s)) return note + 12;
    if (note > this.highLimit(s)) return note - 12;
    return note;
  }

  twoFeelNote(prog, beats, half, s) {
    const chord = chordAt(prog, this.progBar, this.beatInBar, beats);
    const isStart = this.beatInBar === 0 || !sameChord(chordAt(prog, this.progBar, this.beatInBar - 1, beats), chord);
    if (isStart) {
      this.segmentRootNote = this.nearOctave(this.rootMidi(chord, s), s);
      return this.segmentRootNote;
    }
    const nextBeat = this.beatInBar + half;
    const next = nextBeat < beats ? chordAt(prog, this.progBar, nextBeat, beats) : chordAt(prog, this.progBar + 1, 0, beats);
    if (!sameChord(next, chord)) {
      const target = this.nearOctave(this.rootMidi(next, s), s);
      return this.prevBassNote > target ? target + 1 : target - 1;
    }
    const fifth = this.segmentRootNote + M.qualities[chord.quality].fifth;
    return fifth > this.highLimit(s) ? fifth - 12 : fifth;
  }

  bass(time, midiRaw, length, velocity, s) {
    const midi = Math.min(C.maxBassNote + 12, Math.max(C.minBassNote, midiRaw));
    this.prevBassNote = midi;
    if (this.trace) this.trace.push([time, 'bass', midi]);
    const regions = this.basses && this.basses[s.bassTone];
    if (!regions) return;
    let region = regions[0];
    for (const r of regions) if (Math.abs(r.key - midi) < Math.abs(region.key - midi)) region = r;
    // Abafa a nota anterior, como um baixista faria.
    for (const v of this.bassVoices) if (v.end > time) v.g.gain.setTargetAtTime(0, time, 0.006);
    this.bassVoices = this.bassVoices.filter((v) => v.end > time);
    const loud = velocity >= 0.9 && region.buffers.length > 1;
    const buffer = region.buffers[loud ? region.buffers.length - 1 : 0];
    const ctx = this.ctx;
    const src = ctx.createBufferSource();
    src.buffer = buffer;
    src.playbackRate.value = Math.pow(2, (midi - region.key - region.cents / 100) / 12);
    const g = ctx.createGain();
    const gain = Math.min(1.15, Math.max(0.5, velocity / (loud ? 1 : 0.85)));
    g.gain.setValueAtTime(gain, time);
    g.gain.setTargetAtTime(0, time + length, 0.03);
    src.connect(g);
    g.connect(this.bassBus);
    src.start(time);
    const voice = { src, g, end: time + buffer.duration / src.playbackRate.value };
    this.bassVoices.push(voice);
    this.activeVoices.push(voice);
  }
}
