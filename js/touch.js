// ============================================================
// touch.js — тач-управление: тап (выбор/приказ), перетаскивание
// (панорама камеры), щипок (зум) + пан двумя пальцами,
// тапы по миникарте.
// ============================================================

import { WORLD_W, WORLD_H } from './config.js';
import { clamp } from './utils.js';

export class TouchController {
  constructor({ canvas, input, camera, ui }) {
    this.canvas = canvas;
    this.input = input;
    this.camera = camera;
    this.ui = ui;

    this.mode = 'none'; // none | pending | pan | pinch
    this.startX = 0; this.startY = 0;
    this.prevX = 0; this.prevY = 0;
    this.startT = 0;
    this.moved = 0;
    this.pinchDist = 0;
    this.pinchMidX = 0; this.pinchMidY = 0;
    this.lastTap = { t: 0, x: -99, y: -99 };

    this._onStart = e => this._start(e);
    this._onMove = e => this._move(e);
    this._onEnd = e => this._end(e);
    canvas.addEventListener('touchstart', this._onStart, { passive: false });
    canvas.addEventListener('touchmove', this._onMove, { passive: false });
    canvas.addEventListener('touchend', this._onEnd, { passive: false });
    canvas.addEventListener('touchcancel', this._onEnd, { passive: false });

    if (ui && ui.e && ui.e.mm) {
      this._mmStart = e => { e.preventDefault(); this._mmJump(e.changedTouches[0]); };
      this._mmMove = e => { e.preventDefault(); this._mmJump(e.changedTouches[0]); };
      ui.e.mm.addEventListener('touchstart', this._mmStart, { passive: false });
      ui.e.mm.addEventListener('touchmove', this._mmMove, { passive: false });
    }
  }

  destroy() {
    this.canvas.removeEventListener('touchstart', this._onStart);
    this.canvas.removeEventListener('touchmove', this._onMove);
    this.canvas.removeEventListener('touchend', this._onEnd);
    this.canvas.removeEventListener('touchcancel', this._onEnd);
    if (this.ui && this.ui.e && this.ui.e.mm) {
      this.ui.e.mm.removeEventListener('touchstart', this._mmStart);
      this.ui.e.mm.removeEventListener('touchmove', this._mmMove);
    }
  }

  _pos(t) {
    const r = this.canvas.getBoundingClientRect();
    return { x: t.clientX - r.left, y: t.clientY - r.top };
  }

  _mid(a, b) {
    const r = this.canvas.getBoundingClientRect();
    return {
      x: (a.clientX + b.clientX) / 2 - r.left,
      y: (a.clientY + b.clientY) / 2 - r.top,
    };
  }

  _mmJump(t) {
    const mm = this.ui.e.mm;
    const r = mm.getBoundingClientRect();
    const fx = (t.clientX - r.left) / r.width;
    const fy = (t.clientY - r.top) / r.height;
    if (fx < 0 || fy < 0 || fx > 1 || fy > 1) return;
    this.camera.centerOn(fx * WORLD_W, fy * WORLD_H);
  }

  _start(e) {
    e.preventDefault();
    this.input.lastWasTouch = true;
    // обновляем «мышь» последним касанием (для призрака стройки и подсказок)
    for (const t of e.changedTouches) {
      const p = this._pos(t);
      this.input.mouse.x = p.x;
      this.input.mouse.y = p.y;
      this.input.mouse.inside = true;
    }
    if (e.touches.length === 1) {
      const p = this._pos(e.touches[0]);
      this.mode = 'pending';
      this.startX = p.x; this.startY = p.y;
      this.prevX = p.x; this.prevY = p.y;
      this.startT = performance.now();
      this.moved = 0;
    } else if (e.touches.length >= 2) {
      const a = e.touches[0], b = e.touches[1];
      this.mode = 'pinch';
      this.pinchDist = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
      const m = this._mid(a, b);
      this.pinchMidX = m.x; this.pinchMidY = m.y;
      this.moved = 999; // жест точно не тап
    }
  }

  _move(e) {
    e.preventDefault();
    if (e.touches.length >= 1) {
      const p = this._pos(e.touches[0]);
      this.input.mouse.x = p.x;
      this.input.mouse.y = p.y;
    }
    if (this.mode === 'pinch' && e.touches.length >= 2) {
      const a = e.touches[0], b = e.touches[1];
      const d = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
      const m = this._mid(a, b);
      if (this.pinchDist > 0) {
        const factor = clamp(d / this.pinchDist, 0.5, 2);
        this.camera.zoomAt(m.x, m.y, factor);
      }
      // пан серединой щипка
      this.camera.pan(
        (m.x - this.pinchMidX) / this.camera.zoom,
        (m.y - this.pinchMidY) / this.camera.zoom,
      );
      this.pinchDist = d;
      this.pinchMidX = m.x; this.pinchMidY = m.y;
    } else if ((this.mode === 'pending' || this.mode === 'pan') && e.touches.length === 1) {
      const p = this._pos(e.changedTouches[0]);
      const dx = p.x - this.prevX, dy = p.y - this.prevY;
      this.moved += Math.abs(dx) + Math.abs(dy);
      if (this.mode === 'pending' && this.moved > 14) this.mode = 'pan';
      if (this.mode === 'pan') {
        this.camera.pan(dx / this.camera.zoom, dy / this.camera.zoom);
      }
      this.prevX = p.x; this.prevY = p.y;
    }
  }

  _end(e) {
    const tapOk = this.mode === 'pending' &&
      performance.now() - this.startT < 450 && this.moved <= 14;
    const tapX = this.startX, tapY = this.startY;

    if (this.mode === 'pinch') {
      if (e.touches.length === 1) {
        const p = this._pos(e.touches[0]);
        this.mode = 'pan';
        this.prevX = p.x; this.prevY = p.y;
        this.moved = 999;
      } else if (e.touches.length === 0) {
        this.mode = 'none';
      }
      return;
    }

    if (tapOk) {
      const now = performance.now();
      const dbl = now - this.lastTap.t < 350 &&
        Math.hypot(tapX - this.lastTap.x, tapY - this.lastTap.y) < 32;
      this.lastTap = { t: now, x: tapX, y: tapY };
      this.input.mouse.x = tapX;
      this.input.mouse.y = tapY;
      this.input.tap(tapX, tapY, dbl);
    }

    if (e.touches.length === 1) {
      // остался второй палец — продолжаем как пан
      const p = this._pos(e.touches[0]);
      this.mode = 'pan';
      this.prevX = p.x; this.prevY = p.y;
      this.moved = 999;
    } else {
      this.mode = 'none';
    }
  }
}
