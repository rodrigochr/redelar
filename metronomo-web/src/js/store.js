// Configurações e presets guardados no próprio navegador (localStorage). Tudo protegido por
// try/catch: em janela anônima ou com armazenamento bloqueado o app funciona sem salvar.
import { normalizeSettings } from './music.js';

const KEY_SETTINGS = 'bluestempo.settings.v1';
const KEY_PRESETS = 'bluestempo.presets.v1';

function read(key) {
  try {
    const raw = localStorage.getItem(key);
    return raw ? JSON.parse(raw) : null;
  } catch (e) {
    return null;
  }
}

function write(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch (e) {
    return false;
  }
}

export function loadSettings() {
  return normalizeSettings(read(KEY_SETTINGS));
}

let saveTimer = null;
export function saveSettings(settings) {
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => write(KEY_SETTINGS, settings), 300);
}

export function loadPresets() {
  const list = read(KEY_PRESETS);
  return Array.isArray(list) ? list.filter((p) => p && p.id && p.name && p.settings) : [];
}

export function savePresets(list) {
  return write(KEY_PRESETS, list);
}

/** Opções do aparelho que não mudam ao carregar um preset. */
const DEVICE_OPTIONS = ['keepScreenOn', 'flashOnDownbeat', 'useGrooveTempo'];

export function applyPreset(current, presetSettings) {
  const next = normalizeSettings(presetSettings);
  for (const k of DEVICE_OPTIONS) next[k] = current[k];
  return next;
}
