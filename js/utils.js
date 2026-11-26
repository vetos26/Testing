// ============================================================
// utils.js — математика, RNG, шум
// ============================================================

export function clamp(v, a, b) {
  return v < a ? a : v > b ? b : v;
}

export function dist(ax, ay, bx, by) {
  const dx = bx - ax, dy = by - ay;
  return Math.sqrt(dx * dx + dy * dy);
}

export function dist2(ax, ay, bx, by) {
  const dx = bx - ax, dy = by - ay;
  return dx * dx + dy * dy;
}

export function angleTo(ax, ay, bx, by) {
  return Math.atan2(by - ay, bx - ax);
}

// Кратчайшая разница углов
export function angDiff(a, b) {
  let d = (b - a) % (Math.PI * 2);
  if (d > Math.PI) d -= Math.PI * 2;
  if (d < -Math.PI) d += Math.PI * 2;
  return d;
}

export function lerp(a, b, t) {
  return a + (b - a) * t;
}

// Быстрый детерминированный ГПСЧ
export function mulberry32(seed) {
  let a = seed >>> 0;
  return function () {
    a |= 0; a = (a + 0x6D2B79F5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// Гладкий 2D value-noise в [0..1]
export function makeNoise(seed) {
  const rnd = mulberry32(seed);
  const PERM = 256;
  const grid = new Float32Array(PERM * PERM);
  // не заполняем всю сетку 256x256 (65к) — используем хеш с рандомизацией
  const h = new Uint32Array(PERM * 2);
  for (let i = 0; i < h.length; i++) h[i] = (rnd() * 0xffffffff) >>> 0;

  function hash(x, y) {
    let v = (h[x & 255] ^ h[256 + (y & 255)]) >>> 0;
    v = Math.imul(v ^ (v >>> 16), 2246822519) >>> 0;
    return (v % 10000) / 10000;
  }
  function smooth(t) { return t * t * (3 - 2 * t); }

  return function noise(x, y) {
    const xi = Math.floor(x), yi = Math.floor(y);
    const xf = smooth(x - xi), yf = smooth(y - yi);
    const a = hash(xi, yi), b = hash(xi + 1, yi);
    const c = hash(xi, yi + 1), d = hash(xi + 1, yi + 1);
    return lerp(lerp(a, b, xf), lerp(c, d, xf), yf);
  };
}

// Центр тайла в пикселях
export function tileC(t) {
  return t * 32 + 16;
}

// Смещения для построения группы из n юнитов вокруг точки (кольцами)
const _rings = [];
export function ringOffsets(n) {
  if (n <= _rings.length) return _rings.slice(0, n);
  const out = [{ dx: 0, dy: 0 }];
  const step = 26;
  let ring = 1;
  while (out.length < n) {
    const r = ring * step;
    const cnt = ring === 1 ? 6 : 6 * ring; // примерное число точек на кольце
    for (let i = 0; i < cnt && out.length < n; i++) {
      const a = (i / cnt) * Math.PI * 2 + ring * 0.5;
      out.push({ dx: Math.cos(a) * r, dy: Math.sin(a) * r });
    }
    ring++;
  }
  _rings.length = 0;
  _rings.push(...out);
  return out.slice(0, n);
}

// Форматирование времени mm:ss
export function fmtTime(sec) {
  const m = Math.floor(sec / 60), s = Math.floor(sec % 60);
  return `${m}:${s < 10 ? '0' : ''}${s}`;
}
