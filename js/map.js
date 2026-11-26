// ============================================================
// map.js — генерация карты, тайлы, ресурсы, сетка застройки,
// пре-рендер террейна в offscreen-canvas
// ============================================================

import {
  TILE, MAP_W, MAP_H, TERRAIN, TREE_WOOD, MINE_GOLD,
} from './config.js';
import { mulberry32, makeNoise } from './utils.js';

export class GameMap {
  constructor(seed) {
    this.seed = seed;
    this.w = MAP_W;
    this.h = MAP_H;
    this.terrain = new Uint8Array(MAP_W * MAP_H);
    this.amount = new Int16Array(MAP_W * MAP_H); // запас дерева/золота в тайле
    this.bgrid = new Int32Array(MAP_W * MAP_H);  // id здания в тайле (0 = пусто)
    this.starts = [{ tx: 9, ty: 51 }, { tx: 54, ty: 12 }];
    this._canvas = null;
    this._ctx = null;
    this.version = 0; // для рендера (инвалидация миникарты)
    this._generate();
  }

  idx(tx, ty) { return ty * this.w + tx; }

  inBounds(tx, ty) {
    return tx >= 0 && ty >= 0 && tx < this.w && ty < this.h;
  }

  terrainPassable(tx, ty) {
    if (!this.inBounds(tx, ty)) return false;
    const t = this.terrain[this.idx(tx, ty)];
    return t === TERRAIN.GRASS || t === TERRAIN.DIRT;
  }

  // Проходим ли тайл (с учётом зданий)
  isPassable(tx, ty) {
    if (!this.inBounds(tx, ty)) return false;
    const i = this.idx(tx, ty);
    if (this.bgrid[i] !== 0) return false;
    return this.terrainPassable(tx, ty);
  }

  buildingAt(tx, ty) {
    if (!this.inBounds(tx, ty)) return 0;
    return this.bgrid[this.idx(tx, ty)];
  }

  // Отметить/снять застройку (id или 0)
  setBuilding(tx, ty, size, id) {
    for (let y = ty; y < ty + size; y++) {
      for (let x = tx; x < tx + size; x++) {
        if (this.inBounds(x, y)) this.bgrid[this.idx(x, y)] = id;
      }
    }
    this.version++;
  }

  resourceAt(tx, ty) {
    if (!this.inBounds(tx, ty)) return null;
    const t = this.terrain[this.idx(tx, ty)];
    if (t === TERRAIN.TREE) return 'wood';
    if (t === TERRAIN.GOLD) return 'gold';
    return null;
  }

  // Забрать ресурс из тайла. Возвращает { taken, depleted }
  takeResource(tx, ty, amt) {
    const i = this.idx(tx, ty);
    const taken = Math.min(amt, this.amount[i]);
    this.amount[i] -= taken;
    let depleted = false;
    if (this.amount[i] <= 0) {
      depleted = true;
      this.terrain[i] = TERRAIN.GRASS; // источник исчерпан
      this.amount[i] = 0;
      this.redrawTile(tx, ty);
    }
    return { taken, depleted };
  }

  // Ближайший тайл ресурса данного типа (спираль по кольцам), maxR в тайлах
  nearestResourceTile(px, py, type, maxR = 16) {
    const cx = Math.floor(px / TILE), cy = Math.floor(py / TILE);
    for (let r = 0; r <= maxR; r++) {
      let best = null, bestD = Infinity;
      for (let dy = -r; dy <= r; dy++) {
        for (let dx = -r; dx <= r; dx++) {
          if (Math.max(Math.abs(dx), Math.abs(dy)) !== r) continue;
          const x = cx + dx, y = cy + dy;
          if (!this.inBounds(x, y)) continue;
          if (this.resourceAt(x, y) !== type) continue;
          const d = dx * dx + dy * dy;
          if (d < bestD) { bestD = d; best = { tx: x, ty: y }; }
        }
      }
      if (best) return best;
    }
    return null;
  }

  // Можно ли поставить здание size×size с углом (tx,ty)?
  // opts.ring — требовать свободное кольцо вокруг (без ресурсов/воды/леса)
  canPlace(tx, ty, size, opts = {}) {
    if (tx < 1 || ty < 1 || tx + size > this.w - 1 || ty + size > this.h - 1) return false;
    for (let y = ty; y < ty + size; y++) {
      for (let x = tx; x < tx + size; x++) {
        const i = this.idx(x, y);
        const t = this.terrain[i];
        if (t !== TERRAIN.GRASS && t !== TERRAIN.DIRT) return false;
        if (this.bgrid[i] !== 0) return false;
      }
    }
    if (opts.ring) {
      for (let y = ty - 1; y <= ty + size; y++) {
        for (let x = tx - 1; x <= tx + size; x++) {
          if (!this.inBounds(x, y)) continue;
          const t = this.terrain[this.idx(x, y)];
          if (t === TERRAIN.WATER || t === TERRAIN.TREE || t === TERRAIN.GOLD) return false;
        }
      }
    }
    return true;
  }

  // ---------- пре-рендер террейна ----------
  ensureCanvas(createCanvas) {
    if (this._canvas) return this._canvas;
    const cv = createCanvas(MAP_W * TILE, MAP_H * TILE);
    const ctx = cv.getContext('2d');
    this._canvas = cv;
    this._ctx = ctx;
    for (let y = 0; y < MAP_H; y++)
      for (let x = 0; x < MAP_W; x++)
        this._drawTile(ctx, x, y);
    return cv;
  }

  redrawTile(tx, ty) {
    if (!this._ctx || !this.inBounds(tx, ty)) return;
    this._drawTile(this._ctx, tx, ty);
    this.version++;
  }

  _drawTile(ctx, tx, ty) {
    const t = this.terrain[this.idx(tx, ty)];
    const x = tx * TILE, y = ty * TILE;
    // детерминированная «шумность» на тайл
    const hv = ((tx * 73856093) ^ (ty * 19349663)) >>> 0;
    const rnd = (hv % 1000) / 1000;
    const rnd2 = ((hv >> 10) % 1000) / 1000;

    // база
    let base;
    if (t === TERRAIN.WATER) {
      base = `rgb(${34 + rnd * 10 | 0},${74 + rnd2 * 12 | 0},${116 + rnd * 14 | 0})`;
    } else if (t === TERRAIN.DIRT) {
      base = `rgb(${104 + rnd * 18 | 0},${84 + rnd2 * 14 | 0},${56 + rnd * 12 | 0})`;
    } else {
      const g = 86 + rnd * 18;
      base = `rgb(${52 + rnd * 12 | 0},${g | 0},${40 + rnd2 * 10 | 0})`;
    }
    ctx.fillStyle = base;
    ctx.fillRect(x, y, TILE, TILE);

    if (t === TERRAIN.WATER) {
      // блики-волны
      ctx.fillStyle = 'rgba(150,200,235,0.35)';
      const wy = y + 6 + rnd2 * 20;
      ctx.fillRect(x + 4 + rnd * 8, wy, 9 + rnd * 8, 2);
      ctx.fillRect(x + 14 + rnd2 * 6, wy + 7, 7, 2);
    } else if (t === TERRAIN.DIRT) {
      ctx.fillStyle = 'rgba(60,48,30,0.5)';
      if (rnd < 0.5) ctx.fillRect(x + 6 + rnd2 * 16, y + 8 + rnd * 16, 3, 2);
      if (rnd2 < 0.5) ctx.fillRect(x + 10 + rnd * 12, y + 18, 2, 2);
    } else if (t === TERRAIN.TREE) {
      // трава + ель
      ctx.fillStyle = 'rgba(30,45,28,0.45)';
      ctx.beginPath();
      ctx.ellipse(x + 16, y + 27, 10, 4, 0, 0, Math.PI * 2);
      ctx.fill();
      const j = (rnd - 0.5) * 4;
      ctx.fillStyle = '#1e4222';
      ctx.beginPath();
      ctx.moveTo(x + 16 + j, y + 1);
      ctx.lineTo(x + 5, y + 24);
      ctx.lineTo(x + 27, y + 24);
      ctx.closePath();
      ctx.fill();
      ctx.fillStyle = '#2c5c31';
      ctx.beginPath();
      ctx.moveTo(x + 16 + j, y + 4);
      ctx.lineTo(x + 8, y + 19);
      ctx.lineTo(x + 24, y + 19);
      ctx.closePath();
      ctx.fill();
      ctx.fillStyle = '#5a3d22';
      ctx.fillRect(x + 14, y + 24, 4, 5);
    } else if (t === TERRAIN.GOLD) {
      // валуны с золотыми вкраплениями
      ctx.fillStyle = 'rgba(25,35,22,0.5)';
      ctx.beginPath();
      ctx.ellipse(x + 16, y + 24, 13, 6, 0, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = '#7d7f84';
      ctx.beginPath();
      ctx.moveTo(x + 4, y + 26);
      ctx.lineTo(x + 8, y + 10);
      ctx.lineTo(x + 18, y + 5);
      ctx.lineTo(x + 28, y + 14);
      ctx.lineTo(x + 26, y + 26);
      ctx.closePath();
      ctx.fill();
      ctx.fillStyle = '#9a9ca2';
      ctx.beginPath();
      ctx.moveTo(x + 8, y + 14);
      ctx.lineTo(x + 18, y + 7);
      ctx.lineTo(x + 24, y + 14);
      ctx.lineTo(x + 16, y + 18);
      ctx.closePath();
      ctx.fill();
      ctx.fillStyle = '#f2c53d';
      const n = 4 + Math.floor(rnd * 3);
      for (let i = 0; i < n; i++) {
        const gx = x + 7 + ((hv >> (i * 3)) % 18);
        const gy = y + 10 + ((hv >> (i * 3 + 5)) % 14);
        ctx.fillRect(gx, gy, 2.5, 2.5);
      }
    } else {
      // травинки
      if (rnd < 0.45) {
        ctx.strokeStyle = 'rgba(120,160,90,0.5)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        const bx = x + 6 + rnd2 * 20, by = y + 8 + rnd * 18;
        ctx.moveTo(bx, by + 3);
        ctx.lineTo(bx + 1, by - 2);
        ctx.stroke();
      }
    }
  }

  // ---------- генерация ----------
  _generate() {
    const rnd = mulberry32(this.seed);
    const nWater = makeNoise(this.seed ^ 0xA1B2);
    const nTree = makeNoise(this.seed ^ 0x9C3D);
    const nDirt = makeNoise(this.seed ^ 0x7E11);

    const [pS, eS] = this.starts;
    const baseCenters = [
      { tx: pS.tx + 1, ty: pS.ty + 1 },
      { tx: eS.tx + 1, ty: eS.ty + 1 },
    ];

    const nearBase = (x, y, r) => baseCenters.some(c =>
      Math.max(Math.abs(x - c.tx), Math.abs(y - c.ty)) < r);

    // вода и косметическая грязь
    for (let y = 0; y < MAP_H; y++) {
      for (let x = 0; x < MAP_W; x++) {
        const i = this.idx(x, y);
        const border = x < 2 || y < 2 || x >= MAP_W - 2 || y >= MAP_H - 2;
        if (border) {
          this.terrain[i] = TERRAIN.WATER;
          continue;
        }
        const wv = nWater(x * 0.085, y * 0.085);
        if (wv > 0.64 && !nearBase(x, y, 13)) {
          this.terrain[i] = TERRAIN.WATER;
        } else if (nDirt(x * 0.11, y * 0.11) > 0.72) {
          this.terrain[i] = TERRAIN.DIRT;
        } else {
          this.terrain[i] = TERRAIN.GRASS;
        }
      }
    }

    // лес
    for (let y = 2; y < MAP_H - 2; y++) {
      for (let x = 2; x < MAP_W - 2; x++) {
        const i = this.idx(x, y);
        if (this.terrain[i] !== TERRAIN.GRASS) continue;
        if (nearBase(x, y, 11)) continue;
        if (nTree(x * 0.14 + 40, y * 0.14 + 40) > 0.60) {
          this.terrain[i] = TERRAIN.TREE;
          this.amount[i] = TREE_WOOD + Math.floor(rnd() * 40);
        }
      }
    }

    // расчищаем площадки баз
    for (const c of baseCenters) {
      for (let y = c.ty - 4; y <= c.ty + 4; y++) {
        for (let x = c.tx - 4; x <= c.tx + 4; x++) {
          if (!this.inBounds(x, y)) continue;
          const i = this.idx(x, y);
          if (this.terrain[i] === TERRAIN.WATER || this.terrain[i] === TERRAIN.TREE)
            this.terrain[i] = TERRAIN.GRASS;
        }
      }
    }

    // рудные жилы у баз (дугой к центру карты)
    for (const c of baseCenters) {
      const angToCenter = Math.atan2(MAP_H / 2 - c.ty, MAP_W / 2 - c.tx);
      for (let k = 0; k < 5; k++) {
        const a = angToCenter + (k - 2) * 0.42;
        const d = 6.5 + (k % 2) * 2.2;
        const mx = Math.round(c.tx + Math.cos(a) * d);
        const my = Math.round(c.ty + Math.sin(a) * d);
        this._placeMine(mx, my, rnd);
      }
    }

    // россыпи в середине карты
    const clusters = 6;
    const placed = [];
    for (let c = 0; c < clusters; c++) {
      for (let tries = 0; tries < 40; tries++) {
        const cx = 6 + Math.floor(rnd() * (MAP_W - 12));
        const cy = 6 + Math.floor(rnd() * (MAP_H - 12));
        if (nearBase(cx, cy, 14)) continue;
        if (placed.some(p => Math.hypot(p.x - cx, p.y - cy) < 10)) continue;
        placed.push({ x: cx, y: cy });
        const n = 2 + Math.floor(rnd() * 3);
        for (let k = 0; k < n; k++) {
          this._placeMine(cx + Math.floor(rnd() * 3) - 1, cy + Math.floor(rnd() * 3) - 1, rnd);
        }
        break;
      }
    }

    // гарантированный коридор между базами (2 тайла шириной)
    const [A, B] = baseCenters;
    const steps = Math.max(Math.abs(B.tx - A.tx), Math.abs(B.ty - A.ty)) * 2;
    for (let s = 0; s <= steps; s++) {
      const t = s / steps;
      const x = Math.round(A.tx + (B.tx - A.tx) * t);
      const y = Math.round(A.ty + (B.ty - A.ty) * t);
      for (let dy = -1; dy <= 1; dy++) {
        for (let dx = -1; dx <= 1; dx++) {
          const px = x + dx, py = y + dy;
          if (px < 2 || py < 2 || px >= MAP_W - 2 || py >= MAP_H - 2) continue;
          const i = this.idx(px, py);
          if (this.terrain[i] === TERRAIN.WATER || this.terrain[i] === TERRAIN.TREE)
            this.terrain[i] = TERRAIN.DIRT;
          this.amount[i] = 0;
        }
      }
    }
  }

  _placeMine(mx, my, rnd) {
    if (mx < 3 || my < 3 || mx >= MAP_W - 3 || my >= MAP_H - 3) return;
    const i = this.idx(mx, my);
    const t = this.terrain[i];
    if (t === TERRAIN.GRASS || t === TERRAIN.DIRT || t === TERRAIN.TREE || t === TERRAIN.WATER) {
      this.terrain[i] = TERRAIN.GOLD;
      this.amount[i] = MINE_GOLD + Math.floor(rnd() * 300);
      // расчистить подходы
      for (let dy = -2; dy <= 2; dy++) {
        for (let dx = -2; dx <= 2; dx++) {
          if (Math.abs(dx) + Math.abs(dy) > 2) continue;
          const jx = mx + dx, jy = my + dy;
          if (!this.inBounds(jx, jy)) continue;
          const j = this.idx(jx, jy);
          if (this.terrain[j] === TERRAIN.WATER || this.terrain[j] === TERRAIN.TREE)
            this.terrain[j] = TERRAIN.DIRT;
        }
      }
    }
  }
}
