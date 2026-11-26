// ============================================================
// render.js — отрисовка мира на canvas
// ============================================================

import {
  TILE, MAP_W, MAP_H, WORLD_W, WORLD_H, BUILDING_DEFS, PLAYER_DEFS,
} from './config.js';
import { clamp } from './utils.js';

const PI2 = Math.PI * 2;

export class Renderer {
  constructor({ canvas, game, camera, input }) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.game = game;
    this.camera = camera;
    this.input = input;
    this.fogCanvas = document.createElement('canvas');
    this.fogCanvas.width = MAP_W;
    this.fogCanvas.height = MAP_H;
    this.fogCtx = this.fogCanvas.getContext('2d');
    this._fogV = -1;
  }

  draw() {
    const { ctx, canvas, camera: cam, game: g } = this;
    const dpr = canvas.width / Math.max(1, canvas.clientWidth);
    const vw = canvas.clientWidth, vh = canvas.clientHeight;
    const z = cam.zoom;

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = '#07090c';
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    // мировая трансформация
    ctx.setTransform(dpr * z, 0, 0, dpr * z, -cam.x * dpr * z, -cam.y * dpr * z);
    ctx.imageSmoothingEnabled = true;

    // террейн из пре-рендера
    const terr = g.map.ensureCanvas((w, h) => {
      const c = document.createElement('canvas');
      c.width = w; c.height = h;
      return c;
    });
    const sw = Math.min(vw / z + 2, WORLD_W - cam.x);
    const sh = Math.min(vh / z + 2, WORLD_H - cam.y);
    ctx.drawImage(terr, cam.x, cam.y, sw, sh, cam.x, cam.y, sw, sh);

    // трупы
    for (const c of g.corpses) {
      const a = Math.max(0, 1 - c.t / 1.4);
      ctx.fillStyle = `rgba(40,36,32,${0.55 * a})`;
      ctx.beginPath();
      ctx.ellipse(c.x, c.y, 9, 5, 0, 0, PI2);
      ctx.fill();
    }

    // здания
    for (const e of g.entities) {
      if (e.kind === 'building' && !e.dead) this._building(e);
    }

    // юниты (враги — только в зоне видимости)
    for (const e of g.entities) {
      if (e.kind !== 'unit' || e.dead) continue;
      if (e.owner !== 0 && !g.fog.visWorld(e.x, e.y)) continue;
      this._unit(e);
    }

    // снаряды
    ctx.strokeStyle = '#e8dfc8';
    ctx.lineWidth = 2;
    for (const p of g.projectiles) {
      ctx.beginPath();
      ctx.moveTo(p.x, p.y);
      ctx.lineTo(p.x - Math.cos(p.angle) * 12, p.y - Math.sin(p.angle) * 12);
      ctx.stroke();
    }

    // эффекты
    this._effects();

    // туман войны
    this._buildFog();
    ctx.drawImage(this.fogCanvas, 0, 0, MAP_W, MAP_H, 0, 0, WORLD_W, WORLD_H);

    // ---- UI-слой мира (поверх тумана) ----
    this._worldUI();

    // ---- экранный слой ----
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const d = this.input.drag;
    if (d && (Math.abs(d.x1 - d.x0) > 3 || Math.abs(d.y1 - d.y0) > 3)) {
      const x = Math.min(d.x0, d.x1), y = Math.min(d.y0, d.y1);
      const w = Math.abs(d.x1 - d.x0), h = Math.abs(d.y1 - d.y0);
      ctx.fillStyle = 'rgba(140,220,255,0.1)';
      ctx.fillRect(x, y, w, h);
      ctx.strokeStyle = 'rgba(150,225,255,0.9)';
      ctx.lineWidth = 1;
      ctx.setLineDash([4, 3]);
      ctx.strokeRect(x + 0.5, y + 0.5, w, h);
      ctx.setLineDash([]);
    }
  }

  // ---------- элементы ----------
  _bar(x, y, w, h, pct, color) {
    const ctx = this.ctx;
    ctx.fillStyle = 'rgba(12,14,18,0.85)';
    ctx.fillRect(x - 1, y - 1, w + 2, h + 2);
    ctx.fillStyle = color;
    ctx.fillRect(x, y, w * clamp(pct, 0, 1), h);
  }

  _hpColor(pct) {
    return pct > 0.55 ? '#67d955' : pct > 0.28 ? '#e8c14f' : '#e05555';
  }

  _unit(u) {
    const ctx = this.ctx;
    const r = u.def.radius;
    const col = PLAYER_DEFS[u.owner];

    // тень
    ctx.fillStyle = 'rgba(0,0,0,0.28)';
    ctx.beginPath();
    ctx.ellipse(u.x, u.y + r * 0.65, r * 0.95, r * 0.45, 0, 0, PI2);
    ctx.fill();

    // кольцо выделения (под телом)
    if (u.selected) {
      ctx.strokeStyle = u.owner === 0 ? 'rgba(140,245,155,0.95)' : 'rgba(255,120,120,0.95)';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.ellipse(u.x, u.y + r * 0.55, r + 3.5, (r + 3.5) * 0.55, 0, 0, PI2);
      ctx.stroke();
    }

    // тело
    ctx.fillStyle = col.color;
    ctx.strokeStyle = col.dark;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.arc(u.x, u.y, r, 0, PI2);
    ctx.fill();
    ctx.stroke();

    // «голова»
    ctx.fillStyle = col.light;
    ctx.beginPath();
    ctx.arc(u.x + Math.cos(u.face) * r * 0.45, u.y + Math.sin(u.face) * r * 0.45, r * 0.42, 0, PI2);
    ctx.fill();

    // оружие по типу
    const a = u.face;
    if (u.def.key === 'soldier') {
      ctx.strokeStyle = '#dfe3ea';
      ctx.lineWidth = 2.5;
      ctx.beginPath();
      ctx.moveTo(u.x + Math.cos(a) * r, u.y + Math.sin(a) * r);
      ctx.lineTo(u.x + Math.cos(a) * (r + 8), u.y + Math.sin(a) * (r + 8));
      ctx.stroke();
    } else if (u.def.key === 'archer') {
      ctx.strokeStyle = '#caa24a';
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(u.x + Math.cos(a) * r * 0.7, u.y + Math.sin(a) * r * 0.7, r * 0.85, a - 1.15, a + 1.15);
      ctx.stroke();
    } else if (u.def.key === 'worker') {
      ctx.fillStyle = '#9aa3ad';
      const hx = u.x + Math.cos(a) * (r + 3), hy = u.y + Math.sin(a) * (r + 3);
      ctx.fillRect(hx - 2.5, hy - 2.5, 5, 5);
    }

    // переносимый ресурс
    if (u.carry && u.carry.amt > 0.5) {
      ctx.fillStyle = u.carry.res === 'gold' ? '#f2c53d' : '#a97c50';
      ctx.strokeStyle = 'rgba(255,255,255,0.8)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.arc(u.x, u.y - r - 5, 3.5, 0, PI2);
      ctx.fill();
      ctx.stroke();
    }

    // вспышка урона
    if (u.flashT > 0) {
      ctx.fillStyle = `rgba(255,255,255,${Math.min(0.75, u.flashT * 4)})`;
      ctx.beginPath();
      ctx.arc(u.x, u.y, r, 0, PI2);
      ctx.fill();
    }

    // полоса здоровья
    if (u.hp < u.maxHp) {
      const pct = u.hp / u.maxHp;
      this._bar(u.x - 11, u.y - r - 10, 22, 3.5, pct, this._hpColor(pct));
    }
  }

  _building(b) {
    const ctx = this.ctx;
    const x0 = b.tx * TILE, y0 = b.ty * TILE;
    const s = b.size * TILE;
    const col = PLAYER_DEFS[b.owner];

    if (!b.constructed) {
      ctx.fillStyle = 'rgba(28,32,38,0.62)';
      ctx.fillRect(x0, y0, s, s);
      ctx.strokeStyle = 'rgba(196,164,92,0.9)';
      ctx.lineWidth = 2;
      ctx.strokeRect(x0 + 2, y0 + 2, s - 4, s - 4);
      const hf = (s - 10) * b.progress;
      ctx.fillStyle = col.dark;
      ctx.fillRect(x0 + 5, y0 + s - 5 - hf, s - 10, hf);
      ctx.strokeStyle = 'rgba(220,190,110,0.5)';
      ctx.lineWidth = 1;
      ctx.beginPath();
      ctx.moveTo(x0 + 5, y0 + s / 2);
      ctx.lineTo(x0 + s - 5, y0 + s / 2);
      ctx.stroke();
      this._bar(x0 + 4, y0 - 9, s - 8, 6, b.progress, '#e8c14f');
      return;
    }

    // тень
    ctx.fillStyle = 'rgba(0,0,0,0.25)';
    ctx.beginPath();
    ctx.ellipse(b.x, b.y + s * 0.38, s * 0.5, s * 0.18, 0, 0, PI2);
    ctx.fill();

    if (b.def.key === 'turret') {
      // основание
      ctx.fillStyle = '#787e86';
      ctx.beginPath();
      ctx.arc(b.x, b.y, s * 0.32, 0, PI2);
      ctx.fill();
      ctx.strokeStyle = '#4b5058';
      ctx.lineWidth = 2;
      ctx.stroke();
      ctx.fillStyle = col.dark;
      ctx.beginPath();
      ctx.arc(b.x, b.y, s * 0.2, 0, PI2);
      ctx.fill();
      // ствол
      ctx.save();
      ctx.translate(b.x, b.y);
      ctx.rotate(b.barrelA);
      ctx.fillStyle = '#3d4249';
      ctx.fillRect(0, -3.5, s * 0.52, 7);
      ctx.fillStyle = col.color;
      ctx.fillRect(s * 0.44, -3.5, 5, 7);
      ctx.restore();
    } else {
      // стены
      ctx.fillStyle = b.def.key === 'house' ? '#a58e6c' : '#8d7b63';
      ctx.fillRect(x0 + 3, y0 + s * 0.3, s - 6, s * 0.58);
      ctx.strokeStyle = 'rgba(0,0,0,0.35)';
      ctx.lineWidth = 1.5;
      ctx.strokeRect(x0 + 3, y0 + s * 0.3, s - 6, s * 0.58);
      // крыша цветом игрока
      ctx.fillStyle = col.color;
      ctx.beginPath();
      ctx.moveTo(x0 + 1, y0 + s * 0.34);
      ctx.lineTo(x0 + s / 2, y0 + 2);
      ctx.lineTo(x0 + s - 1, y0 + s * 0.34);
      ctx.closePath();
      ctx.fill();
      ctx.strokeStyle = col.dark;
      ctx.stroke();
      // дверь
      ctx.fillStyle = 'rgba(30,24,18,0.8)';
      const dw = s * 0.18;
      ctx.fillRect(b.x - dw / 2, y0 + s * 0.62, dw, s * 0.26);
      // флаг на штабе
      if (b.def.key === 'base') {
        ctx.strokeStyle = '#2c2620';
        ctx.lineWidth = 2;
        ctx.beginPath();
        ctx.moveTo(x0 + s / 2, y0 + 2);
        ctx.lineTo(x0 + s / 2, y0 - 14);
        ctx.stroke();
        ctx.fillStyle = col.color;
        ctx.beginPath();
        ctx.moveTo(x0 + s / 2, y0 - 14);
        ctx.lineTo(x0 + s / 2 + 12, y0 - 10);
        ctx.lineTo(x0 + s / 2, y0 - 6);
        ctx.closePath();
        ctx.fill();
      }
      // иконка
      ctx.font = `${Math.round(s * 0.3)}px serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.fillText(b.def.icon, b.x, y0 + s * 0.47);
    }

    // вспышка урона
    if (b.flashT > 0) {
      ctx.fillStyle = `rgba(255,255,255,${Math.min(0.6, b.flashT * 3.5)})`;
      ctx.fillRect(x0, y0, s, s);
    }

    // производство — прогресс над зданием
    if (b.queue.length) {
      const q = b.queue[0];
      this._bar(x0 + 4, y0 - 9, s - 8, 6, 1 - q.t / q.tMax, '#6fb4f7');
      ctx.font = '10px system-ui, sans-serif';
      ctx.fillStyle = '#cfd6df';
      ctx.textAlign = 'center';
      ctx.fillText(`+${b.queue.length}`, b.x, y0 - 13);
    }
  }

  _effects() {
    const ctx = this.ctx;
    for (const f of this.game.effects) {
      const k = f.t / 0.45;
      const a = Math.max(0, 1 - k);
      switch (f.kind) {
        case 'hit':
          ctx.strokeStyle = `rgba(255,220,120,${a})`;
          ctx.lineWidth = 2;
          ctx.beginPath();
          ctx.arc(f.x, f.y, 3 + k * 20, 0, PI2);
          ctx.stroke();
          break;
        case 'build':
          ctx.fillStyle = `rgba(240,180,90,${a})`;
          ctx.fillRect(f.x - 2, f.y - 2, 5, 5);
          break;
        case 'die':
          ctx.fillStyle = `rgba(120,120,125,${a * 0.7})`;
          ctx.beginPath();
          ctx.arc(f.x, f.y, 4 + k * 14, 0, PI2);
          ctx.fill();
          break;
        case 'boom':
          ctx.fillStyle = `rgba(255,145,60,${a * 0.55})`;
          ctx.beginPath();
          ctx.arc(f.x, f.y, 6 + k * 46, 0, PI2);
          ctx.fill();
          ctx.strokeStyle = `rgba(255,200,90,${a})`;
          ctx.lineWidth = 3;
          ctx.stroke();
          break;
        case 'spawn':
          ctx.strokeStyle = `rgba(255,255,255,${a * 0.8})`;
          ctx.lineWidth = 2;
          ctx.beginPath();
          ctx.arc(f.x, f.y, 4 + k * 16, 0, PI2);
          ctx.stroke();
          break;
      }
    }
  }

  _buildFog() {
    const f = this.game.fog;
    if (f.version === this._fogV) return;
    const img = this.fogCtx.createImageData(MAP_W, MAP_H);
    const d = img.data;
    for (let i = 0; i < MAP_W * MAP_H; i++) {
      const o = i * 4;
      if (f.visible[i]) {
        d[o + 3] = 0;
      } else if (f.explored[i]) {
        d[o] = 7; d[o + 1] = 9; d[o + 2] = 14; d[o + 3] = 118;
      } else {
        d[o] = 5; d[o + 1] = 6; d[o + 2] = 9; d[o + 3] = 255;
      }
    }
    this.fogCtx.putImageData(img, 0, 0);
    this._fogV = f.version;
  }

  _worldUI() {
    const ctx = this.ctx;
    const g = this.game;
    const cam = this.camera;
    const inp = this.input;

    // точка сбора выбранного здания
    for (const sel of inp.selected) {
      if (sel.dead || sel.kind !== 'building' || !sel.constructed) continue;
      if (sel.def.dmg) {
        ctx.strokeStyle = 'rgba(150,200,255,0.5)';
        ctx.setLineDash([6, 5]);
        ctx.lineWidth = 1.5;
        ctx.beginPath();
        ctx.arc(sel.x, sel.y, sel.def.range, 0, PI2);
        ctx.stroke();
        ctx.setLineDash([]);
      }
      if (sel.rally) {
        ctx.strokeStyle = 'rgba(120,220,255,0.85)';
        ctx.lineWidth = 1.5;
        ctx.setLineDash([5, 4]);
        ctx.beginPath();
        ctx.moveTo(sel.x, sel.y);
        ctx.lineTo(sel.rally.x, sel.rally.y);
        ctx.stroke();
        ctx.setLineDash([]);
        // флажок
        ctx.strokeStyle = '#7dd7ff';
        ctx.beginPath();
        ctx.moveTo(sel.rally.x, sel.rally.y);
        ctx.lineTo(sel.rally.x, sel.rally.y - 12);
        ctx.stroke();
        ctx.fillStyle = '#7dd7ff';
        ctx.beginPath();
        ctx.moveTo(sel.rally.x, sel.rally.y - 12);
        ctx.lineTo(sel.rally.x + 9, sel.rally.y - 8.5);
        ctx.lineTo(sel.rally.x, sel.rally.y - 5);
        ctx.closePath();
        ctx.fill();
      }
    }

    // маркер приказа
    const m = inp.marker;
    if (m) {
      const k = m.t / 0.6;
      const colr = m.kind === 'attack' ? '255,110,110'
        : m.kind === 'gold' ? '242,197,61'
        : m.kind === 'wood' ? '169,124,80'
        : m.kind === 'build' ? '110,180,247'
        : m.kind === 'rally' ? '125,215,255'
        : '140,245,155';
      ctx.strokeStyle = `rgba(${colr},${1 - k})`;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(m.x, m.y, 5 + k * 22, 0, PI2);
      ctx.stroke();
      ctx.fillStyle = `rgba(${colr},${1 - k})`;
      ctx.beginPath();
      ctx.arc(m.x, m.y, 3, 0, PI2);
      ctx.fill();
    }

    // призрак размещения здания
    if (inp.placement) {
      const def = BUILDING_DEFS[inp.placement];
      const wp = cam.screenToWorld(inp.mouse.x, inp.mouse.y);
      const tx = Math.round(wp.x / TILE - def.size / 2);
      const ty = Math.round(wp.y / TILE - def.size / 2);
      const px = tx * TILE, py = ty * TILE, ps = def.size * TILE;
      let ok = g.map.canPlace(tx, ty, def.size, {}) && g.canAfford(0, def.cost);
      if (ok) {
        for (const u of g.entities) {
          if (u.dead || u.kind !== 'unit') continue;
          if (u.x > px - 4 && u.x < px + ps + 4 && u.y > py - 4 && u.y < py + ps + 4) { ok = false; break; }
        }
      }
      ctx.fillStyle = ok ? 'rgba(110,220,130,0.4)' : 'rgba(230,90,90,0.45)';
      ctx.fillRect(px, py, ps, ps);
      ctx.strokeStyle = ok ? 'rgba(150,255,160,0.9)' : 'rgba(255,120,120,0.9)';
      ctx.lineWidth = 2;
      ctx.strokeRect(px, py, ps, ps);
      if (def.range) {
        ctx.strokeStyle = 'rgba(150,200,255,0.5)';
        ctx.setLineDash([6, 5]);
        ctx.beginPath();
        ctx.arc(px + ps / 2, py + ps / 2, def.range, 0, PI2);
        ctx.stroke();
        ctx.setLineDash([]);
      }
      ctx.font = `${Math.round(ps * 0.5)}px serif`;
      ctx.textAlign = 'center';
      ctx.textBaseline = 'middle';
      ctx.globalAlpha = 0.75;
      ctx.fillText(def.icon, px + ps / 2, py + ps / 2);
      ctx.globalAlpha = 1;
    }
  }
}
