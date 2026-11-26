// ============================================================
// pathfinding.js — A* на сетке тайлов + сглаживание пути
// ============================================================

import { TILE } from './config.js';
import { tileC } from './utils.js';

class MinHeap {
  constructor() { this.a = []; }
  get size() { return this.a.length; }
  push(node) {
    const a = this.a;
    a.push(node);
    let i = a.length - 1;
    while (i > 0) {
      const p = (i - 1) >> 1;
      if (a[p].f <= a[i].f) break;
      [a[p], a[i]] = [a[i], a[p]];
      i = p;
    }
  }
  pop() {
    const a = this.a;
    const top = a[0];
    const last = a.pop();
    if (a.length) {
      a[0] = last;
      let i = 0;
      for (;;) {
        const l = 2 * i + 1, r = l + 1;
        let m = i;
        if (l < a.length && a[l].f < a[m].f) m = l;
        if (r < a.length && a[r].f < a[m].f) m = r;
        if (m === i) break;
        [a[m], a[i]] = [a[i], a[m]];
        i = m;
      }
    }
    return top;
  }
}

// Ближайший проходимый тайл к (tx,ty) — поиск по кольцам
export function nearestPassableTile(map, tx, ty, maxR = 8) {
  tx = Math.round(tx); ty = Math.round(ty);
  if (map.isPassable(tx, ty)) return { tx, ty };
  for (let r = 1; r <= maxR; r++) {
    let best = null, bestD = Infinity;
    for (let dy = -r; dy <= r; dy++) {
      for (let dx = -r; dx <= r; dx++) {
        if (Math.max(Math.abs(dx), Math.abs(dy)) !== r) continue;
        const x = tx + dx, y = ty + dy;
        if (!map.isPassable(x, y)) continue;
        const d = dx * dx + dy * dy;
        if (d < bestD) { bestD = d; best = { tx: x, ty: y }; }
      }
    }
    if (best) return best;
  }
  return null;
}

// Проходима ли прямая между двумя точками (в пикселях) для юнита
export function lineWalkable(map, x0, y0, x1, y1) {
  const d = Math.sqrt((x1 - x0) ** 2 + (y1 - y0) ** 2);
  const steps = Math.max(1, Math.ceil(d / (TILE * 0.4)));
  for (let i = 0; i <= steps; i++) {
    const t = i / steps;
    const x = x0 + (x1 - x0) * t, y = y0 + (y1 - y0) * t;
    if (!map.isPassable(Math.floor(x / TILE), Math.floor(y / TILE))) return false;
  }
  return true;
}

// A*: от тайла к тайлу. Возвращает массив путевых точек в пикселях (без старта) или null.
export function findPath(map, sx, sy, gx, gy, maxNodes = 6000) {
  sx |= 0; sy |= 0; gx |= 0; gy |= 0;
  if (!map.inBounds(sx, sy) || !map.inBounds(gx, gy)) return null;

  // если цель непроходима — ищем ближайшую проходимую
  if (!map.isPassable(gx, gy)) {
    const alt = nearestPassableTile(map, gx, gy, 6);
    if (!alt) return null;
    gx = alt.tx; gy = alt.ty;
  }
  if (!map.isPassable(sx, sy)) {
    const alt = nearestPassableTile(map, sx, sy, 6);
    if (!alt) return null;
    sx = alt.tx; sy = alt.ty;
  }
  if (sx === gx && sy === gy) return [];

  const w = map.w, h = map.h;
  const size = w * h;
  const gScore = new Float32Array(size).fill(Infinity);
  const parent = new Int32Array(size).fill(-1);
  const closed = new Uint8Array(size);
  const open = new MinHeap();

  const H_VAL = 1.02;
  function heur(x, y) {
    const dx = Math.abs(x - gx), dy = Math.abs(y - gy);
    return (Math.max(dx, dy) + 0.42 * Math.min(dx, dy)) * H_VAL;
  }

  const startIdx = sy * w + sx;
  gScore[startIdx] = 0;
  open.push({ i: startIdx, x: sx, y: sy, f: heur(sx, sy) });

  const DIRS = [
    [1, 0, 1], [-1, 0, 1], [0, 1, 1], [0, -1, 1],
    [1, 1, 1.42], [1, -1, 1.42], [-1, 1, 1.42], [-1, -1, 1.42],
  ];

  let expanded = 0;
  let goalIdx = gy * w + gx;
  let found = false;

  while (open.size && expanded < maxNodes) {
    const cur = open.pop();
    const ci = cur.i;
    if (closed[ci]) continue;
    closed[ci] = 1;
    expanded++;
    if (ci === goalIdx) { found = true; break; }

    for (const [dx, dy, cost] of DIRS) {
      const nx = cur.x + dx, ny = cur.y + dy;
      if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
      if (!map.isPassable(nx, ny)) continue;
      // запрет срезания углов по диагонали
      if (dx !== 0 && dy !== 0) {
        if (!map.isPassable(cur.x + dx, cur.y) || !map.isPassable(cur.x, cur.y + dy)) continue;
      }
      const ni = ny * w + nx;
      if (closed[ni]) continue;
      const ng = gScore[ci] + cost;
      if (ng < gScore[ni]) {
        gScore[ni] = ng;
        parent[ni] = ci;
        open.push({ i: ni, x: nx, y: ny, f: ng + heur(nx, ny) });
      }
    }
  }

  if (!found) return null;

  // восстановление пути (центры тайлов)
  const tiles = [];
  let i = goalIdx;
  while (i !== -1 && i !== startIdx) {
    tiles.push({ x: (i % w), y: Math.floor(i / w) });
    i = parent[i];
  }
  tiles.reverse();
  if (!tiles.length) return [];

  // сглаживание: жадный пропуск точек с прямой видимостью
  const out = [];
  let cx = tileC(sx), cy = tileC(sy);
  let k = 0;
  while (k < tiles.length) {
    let j = Math.min(tiles.length - 1, k + 1);
    // ищем самую дальнюю видимую точку
    for (let cand = tiles.length - 1; cand > k; cand--) {
      if (lineWalkable(map, cx, cy, tileC(tiles[cand].x), tileC(tiles[cand].y))) {
        j = cand;
        break;
      }
    }
    const p = tiles[j];
    out.push({ x: tileC(p.x), y: tileC(p.y) });
    cx = out[out.length - 1].x;
    cy = out[out.length - 1].y;
    k = j + 1;
  }
  return out;
}
