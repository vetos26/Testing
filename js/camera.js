// ============================================================
// camera.js — камера: панорамирование, зум, преобразования координат
// ============================================================

import { clamp } from './utils.js';
import { WORLD_W, WORLD_H } from './config.js';

export class Camera {
  constructor(canvas) {
    this.canvas = canvas;
    this.x = 0;
    this.y = 0;
    this.zoom = 1;
    this.MIN_ZOOM = 0.55;
    this.MAX_ZOOM = 1.8;
  }

  get vw() { return this.canvas.clientWidth || 1; }
  get vh() { return this.canvas.clientHeight || 1; }

  clampPos() {
    const vw = this.vw / this.zoom, vh = this.vh / this.zoom;
    this.x = clamp(this.x, 0, Math.max(0, WORLD_W - vw));
    this.y = clamp(this.y, 0, Math.max(0, WORLD_H - vh));
  }

  centerOn(x, y) {
    this.x = x - this.vw / (2 * this.zoom);
    this.y = y - this.vh / (2 * this.zoom);
    this.clampPos();
  }

  screenToWorld(sx, sy) {
    return { x: this.x + sx / this.zoom, y: this.y + sy / this.zoom };
  }

  worldToScreen(wx, wy) {
    return { x: (wx - this.x) * this.zoom, y: (wy - this.y) * this.zoom };
  }

  pan(dx, dy) {
    this.x += dx;
    this.y += dy;
    this.clampPos();
  }

  update(dt, panX, panY) {
    if (panX || panY) {
      const speed = 560 / this.zoom;
      this.pan(panX * speed * dt, panY * speed * dt);
    }
  }

  zoomAt(sx, sy, factor) {
    const w0 = this.screenToWorld(sx, sy);
    this.zoom = clamp(this.zoom * factor, this.MIN_ZOOM, this.MAX_ZOOM);
    const w1 = this.screenToWorld(sx, sy);
    this.x += w0.x - w1.x;
    this.y += w0.y - w1.y;
    this.clampPos();
  }

  // виден ли объект в текущем окне просмотра
  onScreen(wx, wy, margin = 40) {
    return wx >= this.x - margin && wx <= this.x + this.vw / this.zoom + margin &&
      wy >= this.y - margin && wy <= this.y + this.vh / this.zoom + margin;
  }
}
