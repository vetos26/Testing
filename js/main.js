// ============================================================
// main.js — загрузка, игровой цикл, оркестрация модулей
// ============================================================

import { Game } from './game.js';
import { Camera } from './camera.js';
import { Input } from './input.js';
import { TouchController } from './touch.js';
import { Renderer } from './render.js';
import { UI } from './ui.js';

const canvas = document.getElementById('game');
let session = null;
let difficulty = 'normal';

function resize() {
  const dpr = window.devicePixelRatio || 1;
  canvas.width = Math.round(canvas.clientWidth * dpr);
  canvas.height = Math.round(canvas.clientHeight * dpr);
}
window.addEventListener('resize', resize);
window.addEventListener('orientationchange', resize);

function newGame(diff) {
  if (diff) difficulty = diff;
  if (session) {
    session.input.destroy();
    session.touch.destroy();
    session.ui.destroy();
  }
  const seed = (Date.now() ^ (Math.random() * 0xffffffff)) >>> 0;
  const game = new Game(seed, difficulty);
  const camera = new Camera(canvas);
  const input = new Input({ canvas, game, camera, ui: null });
  const renderer = new Renderer({ canvas, game, camera, input });
  const ui = new UI({
    game, input, camera, renderer,
    onRestart: () => newGame(),
    onDifficulty: () => {},
  });
  input.ui = ui;
  const touch = new TouchController({ canvas, input, camera, ui });

  game.onEvent = (type, d) => {
    if (type === 'toast') ui.toast(d.msg, d.kind);
    else if (type === 'attackWarn') {
      ui.toast('Мы под атакой!', 'bad');
      ui.ping(d.x, d.y);
    } else if (type === 'end') ui.showEnd(d.result, game.time);
  };

  const base = game.playerBase(0);
  if (base) camera.centerOn(base.x, base.y);
  resize();

  session = { game, camera, input, touch, renderer, ui };
  if (typeof window !== 'undefined') window.__rts = session; // отладка/тесты
  return session;
}

// ---------- игровой цикл: фиксированный шаг симуляции ----------
const DT = 1 / 60;
let last = performance.now();
let acc = 0;

function frame(now) {
  requestAnimationFrame(frame);
  const dt = Math.max(0, Math.min(0.1, (now - last) / 1000));
  last = now;
  const s = session;
  if (!s) return;

  s.input.update(dt);

  if (!s.ui.paused && !s.game.over) {
    acc += dt;
    let it = 0;
    while (acc >= DT && it < 5) {
      s.game.tick(DT);
      acc -= DT;
      it++;
    }
    if (acc > DT * 5) acc = 0; // защита от накопления
  }

  s.renderer.draw();
  s.ui.update(dt);
}

// авто-пауза при уходе со вкладки
document.addEventListener('visibilitychange', () => {
  if (document.hidden && session && !session.game.over && !session.ui.paused) {
    session.ui.togglePause();
  }
});

// ---------- запуск ----------
resize();
session = newGame('normal');
session.ui.paused = true;
session.ui.showStart();
requestAnimationFrame(frame);
