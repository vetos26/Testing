// ============================================================
// browser.mjs — smoke-тест UI-слоя в jsdom (заглушка canvas)
// Ловит ошибки интеграции: отсутствующие id, обращения к DOM,
// падения рендера/инпута/панели команд за первые кадры игры.
// Запуск: node test/browser.mjs
// ============================================================

import { JSDOM } from 'jsdom';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const html = readFileSync(join(root, 'index.html'), 'utf8');

const dom = new JSDOM(html, { url: 'http://localhost/', pretendToBeVisual: true });
const { window } = dom;

// фиктивные размеры вьюпорта
for (const prop of ['clientWidth', 'clientHeight']) {
  Object.defineProperty(window.HTMLElement.prototype, prop, {
    get() { return prop === 'clientWidth' ? 1024 : 700; },
    configurable: true,
  });
}
Object.defineProperty(window.HTMLElement.prototype, 'getBoundingClientRect', {
  get() {
    return () => ({ left: 0, top: 0, width: 1024, height: 700, right: 1024, bottom: 700 });
  },
  configurable: true,
});

// заглушка 2D-контекста
function makeCtx(canvas) {
  const target = { canvas };
  return new Proxy(target, {
    get(t, p) {
      if (p in t) return t[p];
      if (p === 'createImageData') {
        return (w, h) => ({ width: w, height: h, data: new Uint8ClampedArray((w || 1) * (h || 1) * 4) });
      }
      if (p === 'measureText') return () => ({ width: 10 });
      return (...args) => undefined;
    },
    set(t, p, v) { t[p] = v; return true; },
  });
}
window.HTMLCanvasElement.prototype.getContext = function () {
  if (!this.__ctx) this.__ctx = makeCtx(this);
  return this.__ctx;
};

// глобали для модуля (performance оставляем нативную — подмена создаёт рекурсию)
globalThis.window = window;
globalThis.document = window.document;
globalThis.requestAnimationFrame = (cb) => { rafQ.push(cb); return rafQ.length; };
globalThis.cancelAnimationFrame = () => {};

const rafQ = [];
const errors = [];
window.addEventListener('error', e => errors.push(e.message));

// импорт приложения (выполняет загрузку)
await import(join(root, 'js/main.js'));

let simNow = performance.now();
function pump(frames, msStep = 33) {
  for (let i = 0; i < frames; i++) {
    simNow += msStep;
    const q = rafQ.splice(0, rafQ.length);
    for (const cb of q) {
      try {
        cb(simNow);
      } catch (e) {
        errors.push('кадр: ' + (e && e.stack ? e.stack.split('\n').slice(0, 4).join(' | ') : e));
        return; // дальше кадры не гоняем
      }
    }
  }
}

const results = [];
function check(name, fn) {
  try {
    fn();
    results.push(`  ✓ ${name}`);
  } catch (e) {
    results.push(`  ✗ ${name}: ${e.message}`);
    errors.push(`${name}: ${e.stack}`);
  }
}

// --- стартовый экран ---
check('стартовый оверлей показан', () => {
  const ov = document.getElementById('overlay');
  if (ov.style.display !== 'flex') throw new Error('overlay скрыт');
  if (!document.getElementById('btnStart')) throw new Error('нет кнопки старта');
});

// --- начало игры ---
check('кнопка «Начать игру» работает', () => {
  document.getElementById('btnStart').click();
  const ov = document.getElementById('overlay');
  if (ov.style.display === 'flex' && ov.style.display !== 'none') {
    // оверлей мог смениться — ошибка, если это снова старт
    if (ov.querySelector('.start')) throw new Error('оверлей старта не закрылся');
  }
});

// --- первые кадры: рендер, HUD, симуляция ---
pump(180); // ~6 секунд игровых

check('HUD обновляется', () => {
  const g = document.getElementById('resGold');
  if (g.textContent === '0') throw new Error('золото не изменилось (рабочие не добыли)');
  const s = document.getElementById('resSupply');
  if (!/\d+\/\d+/.test(s.textContent)) throw new Error('лимит не отображается');
});

// экранные координаты штаба игрока
function baseScreen() {
  const S = window.__rts;
  const b = S.game.playerBase(0);
  const p = S.camera.worldToScreen(b.x, b.y);
  return { x: Math.round(p.x), y: Math.round(p.y) };
}

function clickBase() {
  const canvas = document.getElementById('game');
  const { x, y } = baseScreen();
  canvas.dispatchEvent(new window.MouseEvent('mousedown', { button: 0, clientX: x, clientY: y, bubbles: true }));
  window.dispatchEvent(new window.MouseEvent('mouseup', { button: 0, clientX: x, clientY: y, bubbles: true }));
}

check('выбор штаба кликом и панель команд', () => {
  clickBase();
  pump(10); // ждём перерисовку команд
  const cc = document.getElementById('cmdCard');
  if (!cc.querySelectorAll('.cmd').length) {
    const S = window.__rts;
    throw new Error(`команды не отрисованы (selected=${S ? S.input.selected.length : '?'} sig=${S ? JSON.stringify(S.ui._selSig) : '?'})`);
  }
});

check('обучение рабочего кнопкой', () => {
  const cc = document.getElementById('cmdCard');
  const btn = cc.querySelector('.cmd[data-i="0"]');
  if (!btn) throw new Error('нет кнопок команд');
  btn.click();
  pump(3);
});

check('миникарта не падает', () => {
  const mm = document.getElementById('minimap');
  if (!mm.__ctx) throw new Error('контекст миникарты не создан');
});

// --- взаимодействие ---
check('клик ЛКМ по canvas не падает', () => {
  const canvas = document.getElementById('game');
  canvas.dispatchEvent(new window.MouseEvent('mousedown', { button: 0, clientX: 500, clientY: 300, bubbles: true }));
  window.dispatchEvent(new window.MouseEvent('mouseup', { button: 0, clientX: 500, clientY: 300, bubbles: true }));
});

check('рамка выделения не падает', () => {
  const canvas = document.getElementById('game');
  canvas.dispatchEvent(new window.MouseEvent('mousedown', { button: 0, clientX: 200, clientY: 200, bubbles: true }));
  window.dispatchEvent(new window.MouseEvent('mousemove', { clientX: 600, clientY: 450, bubbles: true }));
  window.dispatchEvent(new window.MouseEvent('mouseup', { button: 0, clientX: 600, clientY: 450, bubbles: true }));
});

check('ПКМ — приказ не падает', () => {
  const canvas = document.getElementById('game');
  canvas.dispatchEvent(new window.MouseEvent('mousedown', { button: 2, clientX: 480, clientY: 320, bubbles: true }));
});

check('хоткей постройки дома (E) — режим размещения', () => {
  // выделяем рабочего рамкой вокруг базы игрока
  const canvas = document.getElementById('game');
  // камера была центрирована на базе; база в центре экрана ~ (512,350)
  canvas.dispatchEvent(new window.MouseEvent('mousedown', { button: 0, clientX: 512, clientY: 350, bubbles: true }));
  window.dispatchEvent(new window.MouseEvent('mouseup', { button: 0, clientX: 512, clientY: 350, bubbles: true }));
  window.dispatchEvent(new window.KeyboardEvent('keydown', { code: 'KeyE', bubbles: true }));
  window.dispatchEvent(new window.KeyboardEvent('keydown', { code: 'Escape', bubbles: true }));
});

check('Esc и пауза не падают', () => {
  window.dispatchEvent(new window.KeyboardEvent('keydown', { code: 'Space', bubbles: true }));
  window.dispatchEvent(new window.KeyboardEvent('keydown', { code: 'Space', bubbles: true }));
});

check('колесо мыши — зум', () => {
  const canvas = document.getElementById('game');
  canvas.dispatchEvent(new window.WheelEvent('wheel', { deltaY: -120, clientX: 500, clientY: 300, bubbles: true, cancelable: true }));
});

pump(180);

check('кнопка помощи работает', () => {
  document.getElementById('btnHelp').click();
  if (!document.getElementById('btnClose')) throw new Error('нет кнопки закрытия справки');
  document.getElementById('btnClose').click();
});

check('кнопка перезапуска работает', () => {
  document.getElementById('btnRestart').click();
});

pump(120);

check('после рестарта HUD живой', () => {
  clickBase();
  pump(6);
  if (!document.getElementById('cmdCard').querySelectorAll('.cmd').length)
    throw new Error('команды не отрисованы после рестарта');
});

// --- отчёт ---
console.log('SMOKE:');
console.log(results.join('\n'));
if (errors.length) {
  console.error('\nFAIL: ошибки в smoke-тесте:');
  errors.slice(0, 10).forEach(e => console.error(' -', String(e).split('\n')[0]));
  process.exit(1);
}
console.log('\nOK: smoke-тест UI прошёл');
