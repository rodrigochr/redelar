// Teste da página de cifras (dist/cifras.html) no Chromium: cada música da setlist precisa caber
// inteira na tela (sem rolagem) em celular em pé, celular deitado e tablet, com letra legível;
// troca de tom, navegação por teclado/pedal e lista. Uso: node test/cifras.mjs [pasta-de-saida]
import { createServer } from 'node:http';
import { mkdirSync, readFileSync } from 'node:fs';
import { extname, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { chromium } = require('playwright');
const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..', 'dist');
const OUT = process.argv[2] || join(dirname(fileURLToPath(import.meta.url)), 'out');
mkdirSync(OUT, { recursive: true });

const TYPES = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.png': 'image/png' };
const server = createServer((req, res) => {
  const file = join(ROOT, decodeURIComponent(new URL(req.url, 'http://x').pathname));
  try {
    const body = readFileSync(file);
    res.writeHead(200, { 'content-type': TYPES[extname(file)] || 'application/octet-stream' });
    res.end(body);
  } catch (e) {
    res.writeHead(404);
    res.end();
  }
}).listen(0);
const base = `http://localhost:${server.address().port}/cifras.html`;

const errors = [];
const check = (cond, msg) => {
  if (!cond) errors.push(msg);
  console.log(`${cond ? '✓' : '✗'} ${msg}`);
};

const browser = await chromium.launch();
const SCREENS = [
  { name: 'celular', width: 390, height: 844, mobile: true, minFont: 13 },
  { name: 'celular-deitado', width: 844, height: 390, mobile: true, minFont: 11 },
  { name: 'tablet', width: 1024, height: 768, mobile: false, minFont: 18 },
];
for (const screen of SCREENS) {
  const page = await browser.newPage({ viewport: { width: screen.width, height: screen.height }, isMobile: screen.mobile, hasTouch: screen.mobile, deviceScaleFactor: 2 });
  page.on('pageerror', (e) => errors.push(`pageerror: ${e.message}`));
  await page.goto(base);
  await page.waitForFunction(() => window.Cifras);
  await page.evaluate(() => document.fonts.ready);
  const n = await page.evaluate(() => window.Cifras.songs.length);
  const results = [];
  for (let i = 0; i < n; i++) {
    const r = await page.evaluate((idx) => {
      const C = window.Cifras;
      C.show(idx);
      const box = document.getElementById('chart');
      const inner = document.getElementById('chart-inner');
      const cut = [...inner.querySelectorAll('.bar b')].filter((b) => b.scrollWidth > b.clientWidth + 1).length;
      return {
        id: C.songs[idx].id,
        fits: inner.scrollHeight <= box.clientHeight + 1 && inner.scrollWidth <= box.clientWidth + 1 && cut === 0,
        page: document.documentElement.scrollWidth <= innerWidth && document.documentElement.scrollHeight <= innerHeight + 1,
        fs: parseFloat(getComputedStyle(inner).getPropertyValue('--fs')),
        fit: inner.dataset.fit,
      };
    }, i);
    results.push(r);
  }
  const bad = results.filter((r) => !r.fits || !r.page);
  check(bad.length === 0, `${screen.name}: as ${n} músicas cabem inteiras na tela${bad.length ? ` (falham: ${bad.map((b) => b.id).join(', ')})` : ''}`);
  const smallest = results.reduce((a, b) => (b.fs < a.fs ? b : a));
  const median = [...results].sort((a, b) => a.fs - b.fs)[Math.floor(n / 2)].fs;
  check(smallest.fs >= screen.minFont, `${screen.name}: acorde de ${median}px na média, menor ${smallest.fs}px (${smallest.id}, ${smallest.fit})`);
  for (const id of ['black', 'nucleo_base', 'like_a_rolling_stone', 'dezesseis']) {
    await page.evaluate((sid) => { const C = window.Cifras; C.show(C.songs.findIndex((s) => s.id === sid)); }, id);
    await page.waitForTimeout(400);
    await page.screenshot({ path: join(OUT, `cifras-${screen.name}-${id}.png`) });
  }
  await page.close();
}

// Tom, teclado/pedal, lista e memória do tom por música.
const page = await browser.newPage({ viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true });
await page.goto(`${base}#flores-astrais`);
await page.waitForFunction(() => window.Cifras);
const chords = () => page.evaluate(() => [...document.querySelectorAll('#chart-inner .bar b')].slice(0, 4).map((b) => b.textContent));
check((await page.textContent('#title')) === 'Flores Astrais' && (await page.textContent('#num')) === '14', 'abre a música pelo endereço (#flores-astrais), número da setlist nova');
check((await chords()).join(' ') === 'D D A/C# A/C#', `cifra no tom original (${(await chords()).join(' ')})`);
await page.tap('#key-up');
await page.tap('#key-up');
check((await chords()).join(' ') === 'E E B/D# B/D#' && (await page.textContent('#key-name')) === 'E', `sobe 2 semitons (${(await chords()).join(' ')})`);
await page.keyboard.press('ArrowRight');
check((await page.textContent('#title')) === 'Loving Cup', 'pedal/seta para a direita: próxima música da setlist');
await page.keyboard.press('PageUp');
check((await page.textContent('#title')) === 'Flores Astrais' && (await page.textContent('#key-name')) === 'E', 'volta e o tom escolhido continua guardado');
await page.reload();
await page.waitForFunction(() => window.Cifras);
check((await page.textContent('#key-name')) === 'E', 'tom guardado depois de recarregar a página');
await page.tap('#key-label');
check((await page.textContent('#key-name')) === 'D', 'toque no tom volta ao original');
// Depois da última da setlist vêm os extras; as que saíram da setlist ficam fora do "próxima".
await page.goto(`${base}#dezesseis`);
await page.waitForFunction(() => window.Cifras);
await page.keyboard.press('ArrowRight');
check((await page.textContent('#title')) === 'Bicho do Paraná' && (await page.textContent('#num')) === 'E1', 'depois da 21ª vem o 1º extra (E1)');
await page.keyboard.press('ArrowRight');
await page.keyboard.press('ArrowRight');
await page.keyboard.press('ArrowRight');
check((await page.textContent('#title')) === 'Núcleo Base', 'depois do último extra volta ao começo da setlist');
await page.tap('#open-list');
check(await page.isVisible('#list'), 'abre a lista da setlist');
await page.screenshot({ path: join(OUT, 'cifras-lista.png') });
const sympathy = await page.evaluate(() => window.Cifras.songs.findIndex((s) => s.id === 'sympathy_for_the_devil'));
check(await page.isVisible('#setlist .grp'), 'lista separa extras e as que saíram da setlist');
await page.tap(`#setlist [data-index="${sympathy}"]`);
check((await page.textContent('#title')) === 'Sympathy for the Devil' && (await page.isHidden('#list')), 'escolhe música na lista');
await page.tap('#stage-btn');
check(await page.evaluate(() => document.documentElement.classList.contains('stage')), 'modo palco');
await page.waitForTimeout(400);
const stageOk = await page.evaluate(() => {
  const r = document.getElementById('chart-inner').getBoundingClientRect();
  return r.left >= 0 && r.right <= innerWidth && getComputedStyle(document.body).backgroundColor === 'rgb(0, 0, 0)';
});
check(stageOk, 'modo palco: fundo preto e cifra dentro da tela');
await page.screenshot({ path: join(OUT, 'cifras-palco.png') });

// Escalas para improvisar: faixa no alto, sugestão nas partes que mudam e painel com braço da guitarra.
await page.goto(`${base}#roadhouse-blues`);
await page.waitForFunction(() => window.Cifras);
const chips = await page.$$eval('#impro .sc', (els) => els.map((e) => e.textContent));
check(chips[0] === 'E blues' && chips.length >= 2, `faixa de improviso (${chips.join(' · ')})`);
await page.tap('#impro .sc');
const notesOf = () => page.$$eval('#scale-body .note b', (els) => els.map((e) => e.textContent).join(' '));
check((await notesOf()) === 'E G A Bb B D' && (await page.isVisible('#scale-body svg')), `painel da escala blues com notas e braço (${await notesOf()})`);
await page.screenshot({ path: join(OUT, 'cifras-escala.png') });
await page.tap('[data-scale-tab="2"]');
check((await page.textContent('#scale-title')).startsWith('E mixolídio'), 'troca de opção no painel (mixolídio)');
await page.tap('[data-close="scale"]');
await page.tap('#key-up');
await page.tap('#key-up');
await page.tap('#impro .sc');
check((await notesOf()) === 'F# A B C C# E', `escala acompanha o tom (+2: ${await notesOf()})`);
await page.tap('[data-close="scale"]');
await page.tap('#key-label');
await page.goto(`${base}#black`);
await page.waitForFunction(() => window.Cifras);
const secChip = page.locator('.sec-sc').first();
check((await secChip.textContent()).includes('E pent. maior'), `parte com outra escala ganha sugestão própria (Black: ${await secChip.textContent()})`);
await secChip.tap();
check((await page.textContent('#scale-title')).startsWith('E pentatônica maior'), 'sugestão da parte abre o painel dela');
await page.tap('[data-close="scale"]');
await page.goto(`${base}#needles-and-pins`);
await page.waitForFunction(() => window.Cifras);
const needles = await page.$$eval('.sec-sc', (els) => els.map((e) => e.textContent).join(' | '));
check(needles.includes('Bb') && !needles.includes('A#'), `modulação com a grafia do tom novo (${needles})`);
await page.tap('#scales-btn');
check(await page.isHidden('#impro') && (await page.locator('.sec-sc').count()) === 0, 'botão Escalas esconde as sugestões');
await page.tap('#scales-btn');

check(errors.filter((e) => e.startsWith('pageerror')).length === 0, 'sem erros no console');
await browser.close();
server.close();
if (errors.length) {
  console.log('\nFalhas:\n' + errors.join('\n'));
  process.exit(1);
}
console.log('\nTudo certo.');
