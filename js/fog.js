// ============================================================
// fog.js — туман войны для игрока-человека (ИИ видит всё)
// ============================================================

import { TILE, MAP_W, MAP_H } from './config.js';

export class Fog {
  constructor(map) {
    this.map = map;
    this.explored = new Uint8Array(MAP_W * MAP_H);
    this.visible = new Uint8Array(MAP_W * MAP_H);
    this.version = 0;
    this._t = 0;
    this._interval = 0.12;
    this.update(null, 1); // первичный расчёт от стартовых зданий
  }

  update(game, dt) {
    this._t += dt;
    if (this._t < this._interval) return;
    this._t = 0;
    this.visible.fill(0);
    if (game) {
      for (const e of game.entities) {
        if (e.dead || e.owner !== 0) continue;
        const tx = Math.floor(e.x / TILE), ty = Math.floor(e.y / TILE);
        this._reveal(tx, ty, e.def.sight);
      }
    }
    for (let i = 0; i < this.visible.length; i++) {
      if (this.visible[i]) this.explored[i] = 1;
    }
    this.version++;
  }

  _reveal(cx, cy, r) {
    const r2 = r * r;
    const x0 = Math.max(0, cx - r), x1 = Math.min(MAP_W - 1, cx + r);
    const y0 = Math.max(0, cy - r), y1 = Math.min(MAP_H - 1, cy + r);
    for (let y = y0; y <= y1; y++) {
      for (let x = x0; x <= x1; x++) {
        const dx = x - cx, dy = y - cy;
        if (dx * dx + dy * dy <= r2) this.visible[y * MAP_W + x] = 1;
      }
    }
  }

  vis(tx, ty) {
    if (!this.map.inBounds(tx, ty)) return 0;
    return this.visible[ty * MAP_W + tx];
  }

  exp(tx, ty) {
    if (!this.map.inBounds(tx, ty)) return 0;
    return this.explored[ty * MAP_W + tx];
  }

  visWorld(x, y) {
    return this.vis(Math.floor(x / TILE), Math.floor(y / TILE));
  }
}
