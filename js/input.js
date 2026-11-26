// ============================================================
// input.js — мышь и клавиатура: выбор, приказы, размещение зданий
// ============================================================

import { TILE, BUILDING_DEFS } from './config.js';
import { dist2 } from './utils.js';

export class Input {
  constructor({ canvas, game, camera, ui }) {
    this.canvas = canvas;
    this.game = game;
    this.camera = camera;
    this.ui = ui;
    this.selected = [];
    this.placement = null;      // ключ здания в режиме размещения
    this.attackMoveArmed = false;
    this.drag = null;           // {x0,y0,x1,y1} — экранные координаты
    this.mouse = { x: 0, y: 0, inside: false };
    this.groups = {};           // цифра → массив сущностей
    this.lastClick = { t: 0, x: 0, y: 0, def: null };
    this.marker = null;         // {x,y,t,kind} — маркер приказа в мире
    this._destroyed = false;
    this._bind();
  }

  destroy() {
    this._destroyed = true;
    window.removeEventListener('keydown', this._onKey);
    window.removeEventListener('keyup', this._onKeyUp);
    window.removeEventListener('mousemove', this._onMove);
    window.removeEventListener('mouseup', this._onUp);
  }

  _bind() {
    const c = this.canvas;
    c.addEventListener('contextmenu', e => e.preventDefault());

    c.addEventListener('mousedown', e => {
      e.preventDefault();
      const m = this._mousePos(e);
      this.mouse.x = m.x; this.mouse.y = m.y; this.mouse.inside = true;
      if (e.button === 0) this._leftDown(e, m);
      else if (e.button === 2) this._rightDown(e, m);
    });

    this._onMove = e => {
      const m = this._mousePos(e);
      this.mouse.x = m.x; this.mouse.y = m.y;
      this.mouse.inside = m.x >= 0 && m.y >= 0 && m.x <= this.canvas.clientWidth && m.y <= this.canvas.clientHeight;
      if (this.drag) { this.drag.x1 = m.x; this.drag.y1 = m.y; }
    };
    window.addEventListener('mousemove', this._onMove);

    this._onUp = e => {
      if (e.button === 0) this._leftUp(e);
      else if (e.button === 2) { /* приказ уже в mousedown */ }
    };
    window.addEventListener('mouseup', this._onUp);

    c.addEventListener('wheel', e => {
      e.preventDefault();
      const m = this._mousePos(e);
      const factor = Math.exp(-e.deltaY * 0.0012);
      this.camera.zoomAt(m.x, m.y, factor);
    }, { passive: false });

    this._onKey = e => this._keyDown(e);
    window.addEventListener('keydown', this._onKey);
    this._onKeyUp = e => this._keyUp(e);
    window.addEventListener('keyup', this._onKeyUp);
  }

  _mousePos(e) {
    const r = this.canvas.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  }

  _world(m) { return this.camera.screenToWorld(m.x, m.y); }

  // ---------- выделение ----------
  setSelection(list) {
    for (const s of this.selected) s.selected = false;
    this.selected = list.filter(s => !s.dead);
    for (const s of this.selected) s.selected = true;
    if (this.ui) this.ui.onSelectionChanged();
  }

  _selectAt(w, add) {
    const g = this.game;
    const ent = g.entityAt(w.x, w.y, { hideHidden: true });
    if (!ent) {
      if (!add) this.setSelection([]);
      return;
    }
    if (add && ent.owner === 0) {
      const cur = this.selected.slice();
      const i = cur.indexOf(ent);
      if (i >= 0) cur.splice(i, 1); else cur.push(ent);
      this.setSelection(cur);
      return;
    }
    this.setSelection([ent]);
  }

  _selectBox(x0, y0, x1, y1) {
    const a = this.camera.screenToWorld(Math.min(x0, x1), Math.min(y0, y1));
    const b = this.camera.screenToWorld(Math.max(x0, x1), Math.max(y0, y1));
    const units = this.game.unitsInRect(a.x, a.y, b.x, b.y);
    this.setSelection(units);
  }

  _selectSameTypeOnScreen(ent) {
    const g = this.game;
    const all = g.entities.filter(e =>
      !e.dead && e.kind === 'unit' && e.owner === 0 && e.def === ent.def &&
      this.camera.onScreen(e.x, e.y));
    this.setSelection(all.length ? all : [ent]);
  }

  // ---------- клики ----------
  _leftDown(e, m) {
    if (this.placement) {
      this._tryPlace(e.shiftKey);
      return;
    }
    this.drag = { x0: m.x, y0: m.y, x1: m.x, y1: m.y };
  }

  _leftUp(e) {
    if (!this.drag) return;
    const d = this.drag;
    this.drag = null;
    const w = Math.abs(d.x1 - d.x0), h = Math.abs(d.y1 - d.y0);
    if (w > 8 || h > 8) {
      this._selectBox(d.x0, d.y0, d.x1, d.y1);
      return;
    }
    // одиночный клик (+двойной — выделить всех такого типа)
    const wp = this.camera.screenToWorld(d.x1, d.y1);
    const now = performance.now();
    const ent = this.game.entityAt(wp.x, wp.y, { hideHidden: true });

    if (ent && ent.kind === 'unit' && ent.owner === 0 &&
      this.lastClick.def === ent.def && now - this.lastClick.t < 350 &&
      dist2(wp.x, wp.y, this.lastClick.x, this.lastClick.y) < 20 * 20) {
      this._selectSameTypeOnScreen(ent);
    } else {
      this._selectAt(wp, e.shiftKey);
    }
    this.lastClick = { t: now, x: wp.x, y: wp.y, def: ent ? ent.def : null };
  }

  _rightDown(e, m) {
    if (this.placement) { this._cancelPlacement(); return; }
    if (this.attackMoveArmed) { this.attackMoveArmed = false; this._orderAttackMove(m); return; }
    this._smartOrder(m);
  }

  _smartOrder(m) {
    const g = this.game;
    const wp = this._world(m);
    const sel = this.selected.filter(s => !s.dead);
    if (!sel.length) return;

    // здание с производством → точка сбора
    if (sel.length === 1 && sel[0].kind === 'building' && sel[0].constructed && sel[0].def.trains) {
      const t = g.entityAt(wp.x, wp.y, { hideHidden: true });
      g.setRally(sel[0], wp.x, wp.y, t && t.owner !== 0 ? t.id : null);
      this._marker(wp.x, wp.y, 'rally');
      return;
    }

    const units = sel.filter(s => s.kind === 'unit');
    if (!units.length) return;
    const t = g.entityAt(wp.x, wp.y, { hideHidden: true });

    if (t && t.owner !== 0 && !t.dead) {
      g.issueAttack(units, t);
      this._marker(wp.x, wp.y, 'attack');
      return;
    }
    // свой недостроенный объект → помочь достроить
    if (t && t.owner === 0 && t.kind === 'building' && !t.constructed) {
      g.issueBuild(units, t);
      this._marker(wp.x, wp.y, 'build');
      return;
    }
    // ресурс?
    const tx = Math.floor(wp.x / TILE), ty = Math.floor(wp.y / TILE);
    const res = g.map.resourceAt(tx, ty);
    if (res) {
      const workers = units.filter(u => u.def.kind === 'worker');
      const rest = units.filter(u => u.def.kind !== 'worker');
      if (workers.length) g.issueHarvest(workers, tx, ty);
      if (rest.length) g.issueMove(rest, wp.x, wp.y, false);
      this._marker(wp.x, wp.y, res === 'gold' ? 'gold' : 'wood');
      return;
    }
    g.issueMove(units, wp.x, wp.y, false);
    this._marker(wp.x, wp.y, 'move');
  }

  _orderAttackMove(m) {
    const wp = this._world(m);
    const units = this.selected.filter(s => !s.dead && s.kind === 'unit');
    if (!units.length) return;
    this.game.issueMove(units, wp.x, wp.y, true);
    this._marker(wp.x, wp.y, 'attack');
  }

  _marker(x, y, kind) {
    this.marker = { x, y, kind, t: 0 };
  }

  // ---------- размещение зданий ----------
  beginPlacement(key) {
    this.placement = key;
    this.attackMoveArmed = false;
    if (this.ui) this.ui.onSelectionChanged();
  }

  _cancelPlacement() {
    this.placement = null;
    if (this.ui) this.ui.onSelectionChanged();
  }

  _tryPlace(keepMode) {
    const key = this.placement;
    if (!key) return;
    const def = BUILDING_DEFS[key];
    const wp = this._world(this.mouse);
    const tx = Math.round(wp.x / TILE - def.size / 2);
    const ty = Math.round(wp.y / TILE - def.size / 2);
    const r = this.game.placeBuildingSite(0, key, tx, ty);
    if (r.err === 'cost') this.ui && this.ui.toast('Не хватает ресурсов', 'warn');
    else if (r.err === 'place' || r.err === 'units') this.ui && this.ui.toast('Здесь строить нельзя', 'warn');
    if (r.site) {
      const workers = this.selected.filter(s => !s.dead && s.kind === 'unit' && s.def.kind === 'worker');
      if (workers.length) this.game.issueBuild(workers, r.site);
      if (!keepMode) this.placement = null;
      if (this.ui) this.ui.onSelectionChanged();
    }
  }

  // ---------- клавиатура ----------
  _keyDown(e) {
    if (this._destroyed) return;
    // стрелки — панорама
    if (e.code.startsWith('Arrow')) {
      this.camera.keys = this.camera.keys || new Set();
      this.camera.keys.add(e.code);
      e.preventDefault();
      return;
    }
    if (e.ctrlKey && /^Digit[1-5]$/.test(e.code)) {
      const n = e.code.slice(5);
      this.groups[n] = this.selected.filter(s => !s.dead && s.owner === 0);
      this.ui && this.ui.toast(`Группа ${n} назначена`, 'info');
      e.preventDefault();
      return;
    }
    if (/^Digit[1-5]$/.test(e.code) && !e.ctrlKey) {
      const n = e.code.slice(5);
      const grp = (this.groups[n] || []).filter(s => !s.dead);
      if (grp.length) this.setSelection(grp);
      return;
    }

    // командные хоткеи (раскладко-независимо по e.code)
    if (this.ui && this.ui.handleHotkey(e.code, e)) {
      e.preventDefault();
      return;
    }

    switch (e.code) {
      case 'Escape':
        if (this.placement) this._cancelPlacement();
        else if (this.attackMoveArmed) this.attackMoveArmed = false;
        else if (this.selected.length) this.setSelection([]);
        else if (this.ui) this.ui.onEscape();
        e.preventDefault();
        break;
      case 'KeyH': {
        const b = this.game.playerBase(0);
        if (b) this.camera.centerOn(b.x, b.y);
        break;
      }
      case 'Space':
        if (this.ui) this.ui.togglePause();
        e.preventDefault();
        break;
    }
  }

  _keyUp(e) {
    if (e.code.startsWith('Arrow') && this.camera.keys) {
      this.camera.keys.delete(e.code);
    }
  }

  // вызывается каждый кадр
  update(dt) {
    const cam = this.camera;
    // панорама стрелками
    let px = 0, py = 0;
    const k = cam.keys;
    if (k) {
      if (k.has('ArrowLeft')) px -= 1;
      if (k.has('ArrowRight')) px += 1;
      if (k.has('ArrowUp')) py -= 1;
      if (k.has('ArrowDown')) py += 1;
    }
    // скролл у края экрана
    const M = 24;
    if (this.mouse.inside) {
      if (this.mouse.x < M) px -= 1;
      if (this.mouse.x > this.canvas.clientWidth - M) px += 1;
      if (this.mouse.y < M) py -= 1;
      if (this.mouse.y > this.canvas.clientHeight - M) py += 1;
    }
    cam.update(dt, px, py);

    // чистка мёртвых из выделения
    const alive = this.selected.filter(s => !s.dead);
    if (alive.length !== this.selected.length) {
      this.setSelection(alive);
    }
    if (this.marker) {
      this.marker.t += dt;
      if (this.marker.t > 0.6) this.marker = null;
    }
  }
}
