// ============================================================
// game.js — состояние игры и симуляция (без DOM, тестируется в Node)
// ============================================================

import {
  TILE, START, SUPPLY, HARVEST, BUILD, COMBAT,
  UNIT_DEFS, BUILDING_DEFS, PLAYER_DEFS, QUEUE_MAX, PROJECTILE_SPEED,
} from './config.js';
import { dist, dist2, angleTo, clamp, tileC, ringOffsets } from './utils.js';
import { GameMap } from './map.js';
import { findPath } from './pathfinding.js';
import { Fog } from './fog.js';
import { Unit, Building, Projectile } from './entities.js';
import { AIController } from './ai.js';

const UNIT_CAP = 60;

export class Game {
  constructor(seed, difficulty = 'normal') {
    this.seed = seed;
    this.difficulty = difficulty;
    this.map = new GameMap(seed);
    this.players = PLAYER_DEFS.map(p => ({ id: p.id, name: p.name, gold: START.gold, wood: START.wood }));
    this.entities = [];
    this.projectiles = [];
    this.effects = [];
    this.corpses = [];
    this.recentDamage = [];
    this.time = 0;
    this.over = null; // null | 'victory' | 'defeat'
    this.onEvent = null; // (type, data) => void — ставит UI
    this._byId = new Map();
    this._winT = 0;
    this._warnT = -99;

    this.fog = new Fog(this.map);
    this.ai = new AIController(this, 1, difficulty);

    this._spawnStartingForces();
  }

  // ---------- вспомогательные ----------
  entityById(id) { return this._byId.get(id) || null; }

  _register(e) { this.entities.push(e); this._byId.set(e.id, e); return e; }

  unitsOf(pid) { return this.entities.filter(e => !e.dead && e.kind === 'unit' && e.owner === pid); }
  buildingsOf(pid) { return this.entities.filter(e => !e.dead && e.kind === 'building' && e.owner === pid); }

  stats(pid) {
    const p = this.players[pid];
    let used = 0, cap = 0;
    for (const e of this.entities) {
      if (e.dead) continue;
      if (e.kind === 'unit' && e.owner === pid) used += e.def.supply;
      if (e.kind === 'building' && e.owner === pid) {
        if (e.constructed) cap += e.def.supply || 0;
        for (const q of e.queue) used += UNIT_DEFS[q.key].supply;
      }
    }
    used = Math.min(used, SUPPLY.CAP_MAX);
    cap = Math.min(cap, SUPPLY.CAP_MAX);
    return { gold: Math.floor(p.gold), wood: Math.floor(p.wood), used, cap };
  }

  canAfford(pid, cost) {
    const p = this.players[pid];
    return p.gold >= (cost.gold || 0) && p.wood >= (cost.wood || 0);
  }

  pay(pid, cost) {
    const p = this.players[pid];
    p.gold -= cost.gold || 0;
    p.wood -= cost.wood || 0;
  }

  refund(pid, cost) {
    const p = this.players[pid];
    p.gold += cost.gold || 0;
    p.wood += cost.wood || 0;
  }

  event(type, data) { if (this.onEvent) this.onEvent(type, data || {}); }

  // ---------- стартовые силы ----------
  _spawnStartingForces() {
    for (const s of this.map.starts) {
      const pid = this.map.starts.indexOf(s);
      const def = BUILDING_DEFS.base;
      const b = this._register(new Building(pid, def, s.tx, s.ty, true));
      let placed = 0;
      for (let r = 1; r <= 4 && placed < START.workers; r++) {
        for (let dy = -r; dy <= r && placed < START.workers; dy++) {
          for (let dx = -r; dx <= r && placed < START.workers; dx++) {
            if (Math.max(Math.abs(dx), Math.abs(dy)) !== r) continue;
            const tx = b.tx + 1 + dx, ty = b.ty + 1 + dy;
            if (!this.map.isPassable(tx, ty)) continue;
            const u = this._register(new Unit(pid, UNIT_DEFS.worker, tileC(tx), tileC(ty)));
            // автостарт добычи: 3 на золото, 1 на дерево
            const res = placed === 0 ? 'wood' : 'gold';
            const spot = this.map.nearestResourceTile(u.x, u.y, res, 16);
            if (spot) this.issueHarvest([u], spot.tx, spot.ty);
            placed++;
          }
        }
      }
    }
    this.fog.update(this, 1);
  }

  playerBase(pid) {
    return this.buildingsOf(pid).find(b => b.def.key === 'base') || this.buildingsOf(pid)[0] || null;
  }

  // ---------- выбор целей ----------
  // Автозахват: юниты в приоритете, при их отсутствии — здания врага
  acquireTarget(u, rangeTiles = null) {
    const range = (rangeTiles ?? u.def.sight) * TILE;
    let bestU = null, bdU = Infinity;
    let bestB = null, bdB = Infinity;
    for (const e of this.entities) {
      if (e.dead || e.owner === u.owner) continue;
      const d = dist2(u.x, u.y, e.x, e.y);
      if (e.kind === 'unit') {
        if (d < range * range && d < bdU) { bdU = d; bestU = e; }
      } else if (e.kind === 'building') {
        if (d < range * range && d < bdB) { bdB = d; bestB = e; }
      }
    }
    return bestU || bestB;
  }

  findEnemyUnitNear(building, enemyOwner, rangeTiles) {
    const r = rangeTiles * TILE;
    let best = null, bd = Infinity;
    for (const e of this.entities) {
      if (e.dead || e.kind !== 'unit' || e.owner !== enemyOwner) continue;
      const d = dist2(building.x, building.y, e.x, e.y);
      if (d < r * r && d < bd) { bd = d; best = e; }
    }
    return best;
  }

  entityAt(x, y, opts = {}) {
    let best = null, bd = Infinity;
    for (const e of this.entities) {
      if (e.dead) continue;
      if (e.kind !== 'unit') continue;
      if (opts.playerOnly && e.owner !== 0) continue;
      if (e.owner !== 0 && opts.hideHidden && !this.fog.visWorld(e.x, e.y)) continue;
      const d = dist2(x, y, e.x, e.y);
      if (d < (e.def.radius + 5) ** 2 && d < bd) { bd = d; best = e; }
    }
    if (best) return best;
    for (const e of this.entities) {
      if (e.dead || e.kind !== 'building') continue;
      if (opts.playerOnly && e.owner !== 0) continue;
      if (e.owner !== 0 && opts.hideHidden && !this.fog.visWorld(e.x, e.y)) continue;
      const er = e.edgeRadius;
      if (Math.abs(x - e.x) <= er && Math.abs(y - e.y) <= er) return e;
    }
    return null;
  }

  unitsInRect(x0, y0, x1, y1) {
    const ax = Math.min(x0, x1), bx = Math.max(x0, x1);
    const ay = Math.min(y0, y1), by = Math.max(y0, y1);
    return this.entities.filter(e =>
      !e.dead && e.kind === 'unit' && e.owner === 0 &&
      e.x >= ax && e.x <= bx && e.y >= ay && e.y <= by);
  }

  // ---------- приказы ----------
  issueMove(units, x, y, attackMove = false) {
    const sorted = [...units].sort((a, b) => dist2(a.x, a.y, x, y) - dist2(b.x, b.y, x, y));
    const offs = ringOffsets(sorted.length);
    sorted.forEach((u, i) => {
      const tx = clamp(Math.floor((x + offs[i].dx) / TILE), 0, this.map.w - 1);
      const ty = clamp(Math.floor((y + offs[i].dy) / TILE), 0, this.map.h - 1);
      u.resume = null;
      u.order = { type: attackMove ? 'attackmove' : 'move', x: tileC(tx), y: tileC(ty) };
      u.path = null;
      u.repathT = 0;
    });
  }

  issueAttack(units, target) {
    for (const u of units) {
      u.resume = null;
      u.order = { type: 'attack', target, buildingTarget: target.kind === 'building' ? target : null };
      u.path = null;
      u.repathT = 0;
    }
  }

  issueHarvest(units, tx, ty) {
    for (const u of units) {
      if (u.def.kind !== 'worker') continue;
      const res = this.map.resourceAt(tx, ty);
      if (!res) continue;
      u.resume = null;
      u.order = { type: 'harvest', res, tx, ty };
      u.hPhase = 'to';
      u.path = null;
    }
  }

  issueBuild(units, site) {
    for (const u of units) {
      if (u.def.kind !== 'worker') continue;
      if (u.order.type === 'harvest') u.resume = { ...u.order };
      u.order = { type: 'build', siteId: site.id };
      u.path = null;
    }
  }

  stopUnits(units) {
    for (const u of units) {
      u.order = { type: 'stop' };
      u.resume = null;
      u.path = null;
    }
  }

  setRally(b, x, y, targetId = null) {
    b.rally = { x, y, targetId };
  }

  // ---------- строительство ----------
  placeBuildingSite(pid, key, tx, ty) {
    const def = BUILDING_DEFS[key];
    if (!def) return { err: 'def' };
    if (!this.canAfford(pid, def.cost)) return { err: 'cost' };
    if (!this.map.canPlace(tx, ty, def.size, {})) return { err: 'place' };
    // юниты не должны стоять в зоне
    const x0 = tx * TILE, y0 = ty * TILE, s = def.size * TILE;
    for (const u of this.entities) {
      if (u.dead || u.kind !== 'unit') continue;
      if (u.x > x0 - 4 && u.x < x0 + s + 4 && u.y > y0 - 4 && u.y < y0 + s + 4)
        return { err: 'units' };
    }
    this.pay(pid, def.cost);
    const site = this._register(new Building(pid, def, tx, ty, false));
    this.map.setBuilding(tx, ty, def.size, site.id);
    this.event('placed', { pid, key });
    return { site };
  }

  cancelSite(site) {
    if (site.dead || site.constructed) return;
    this.refund(site.owner, site.def.cost);
    this.map.setBuilding(site.tx, site.ty, site.size, 0);
    site.dead = true;
  }

  finishSite(site) {
    site.progress = 1;
    site.constructed = true;
    site.hp = site.maxHp;
    this.map.version++;
    if (site.owner === 0) this.event('toast', { msg: `Построено: ${site.def.name}`, kind: 'ok' });
  }

  _standTileAround(px0, py0, size, u) {
    // ближайший проходимый тайл к периметру прямоугольника (px0,py0, size тайлов)
    let best = null, bd = Infinity;
    for (let y = py0 - 1; y <= py0 + size; y++) {
      for (let x = px0 - 1; x <= px0 + size; x++) {
        const onEdge =
          (x === px0 - 1 || x === px0 + size) || (y === py0 - 1 || y === py0 + size);
        if (!onEdge) continue;
        if (!this.map.isPassable(x, y)) continue;
        const d = dist2(u.x, u.y, tileC(x), tileC(y));
        if (d < bd) { bd = d; best = { tx: x, ty: y }; }
      }
    }
    return best;
  }

  // ---------- производство ----------
  trainUnit(b, key, silent = false) {
    const def = UNIT_DEFS[key];
    if (!def || b.dead || !b.constructed) return false;
    if (b.queue.length >= QUEUE_MAX) return false;
    const st = this.stats(b.owner);
    if (st.used + def.supply > st.cap) {
      if (b.owner === 0 && !silent) this.event('toast', { msg: 'Нужны дома — лимит снабжения!', kind: 'warn' });
      return false;
    }
    if (this.unitsOf(b.owner).length + b.queue.length >= UNIT_CAP) return false;
    if (!this.canAfford(b.owner, def.cost)) {
      if (b.owner === 0 && !silent) this.event('toast', { msg: 'Не хватает ресурсов', kind: 'warn' });
      return false;
    }
    this.pay(b.owner, def.cost);
    b.queue.push({ key, t: def.buildTime, tMax: def.buildTime });
    this.event('train', { pid: b.owner });
    return true;
  }

  cancelTrain(b) {
    const q = b.queue.pop();
    if (q) this.refund(b.owner, UNIT_DEFS[q.key].cost);
  }

  _freeSpotAroundBuilding(b) {
    for (let r = 1; r <= 4; r++) {
      const cand = [];
      for (let dy = -r; dy < b.size + r; dy++) {
        for (let dx = -r; dx < b.size + r; dx++) {
          const onEdge = dx === -r || dx === b.size + r - 1 || dy === -r || dy === b.size + r - 1;
          if (!onEdge) continue;
          const tx = b.tx + dx, ty = b.ty + dy;
          if (!this.map.isPassable(tx, ty)) continue;
          cand.push({ tx, ty });
        }
      }
      if (cand.length) {
        cand.sort((a, c) => dist2(tileC(a.tx), tileC(a.ty), b.x, b.y) - dist2(tileC(c.tx), tileC(c.ty), b.x, b.y));
        for (const c of cand) {
          let occupied = false;
          for (const u of this.entities) {
            if (u.dead || u.kind !== 'unit') continue;
            if (dist2(u.x, u.y, tileC(c.tx), tileC(c.ty)) < 20 * 20) { occupied = true; break; }
          }
          if (!occupied) return { x: tileC(c.tx), y: tileC(c.ty) };
        }
        return { x: tileC(cand[0].tx), y: tileC(cand[0].ty) };
      }
    }
    return { x: b.x, y: b.y + b.edgeRadius + TILE };
  }

  _spawnFromBuilding(b, key) {
    const spot = this._freeSpotAroundBuilding(b);
    const u = this._register(new Unit(b.owner, UNIT_DEFS[key], spot.x, spot.y));
    this.addEffect(spot.x, spot.y, 'spawn');

    if (b.rally) {
      const rt = b.rally.targetId ? this.entityById(b.rally.targetId) : null;
      if (rt && !rt.dead && rt.owner !== b.owner) {
        this.issueAttack([u], rt);
        return;
      }
      const res = this.map.resourceAt(Math.floor(b.rally.x / TILE), Math.floor(b.rally.y / TILE));
      if (res && u.def.kind === 'worker') {
        this.issueHarvest([u], Math.floor(b.rally.x / TILE), Math.floor(b.rally.y / TILE));
        return;
      }
      this.issueMove([u], b.rally.x, b.rally.y);
      return;
    }
    if (key === 'worker' && b.def.dropoff) {
      const mine = this.map.nearestResourceTile(b.x, b.y, 'gold', 18);
      if (mine) { this.issueHarvest([u], mine.tx, mine.ty); return; }
    }
    const dir = b.owner === 0 ? -1 : 1;
    this.issueMove([u], b.x, b.y + dir * (b.edgeRadius + 30));
  }

  nearestDropoff(pid, x, y) {
    let best = null, bd = Infinity;
    for (const e of this.entities) {
      if (e.dead || e.kind !== 'building' || e.owner !== pid || !e.constructed || !e.def.dropoff) continue;
      const d = dist2(x, y, e.x, e.y);
      if (d < bd) { bd = d; best = e; }
    }
    return best;
  }

  // ---------- урон и смерть ----------
  damage(ent, amt, attacker) {
    if (ent.dead) return;
    const armor = ent.kind === 'unit' ? ent.def.armor : 0;
    const real = Math.max(1, Math.round(amt - armor));
    ent.hp -= real;
    ent.flashT = 0.18;
    ent.lastDmgT = this.time;
    this.recentDamage.push({
      x: ent.x, y: ent.y, owner: ent.owner, t: this.time,
      b: ent.kind === 'building',
    });
    if (ent.owner === 0 && this.time - this._warnT > 8) {
      this._warnT = this.time;
      this.event('attackWarn', { x: ent.x, y: ent.y });
    }
    this._retaliate(ent, attacker);
    if (ent.hp <= 0) this.killEntity(ent);
  }

  // ответный удар: военный юнит атакует обидчика, если не занят боем
  _retaliate(ent, attacker) {
    if (!attacker || attacker.dead || attacker.owner === undefined || attacker.owner === ent.owner) return;
    if (ent.kind !== 'unit' || ent.def.kind === 'worker') return;
    const o = ent.order;
    const rettable = o.type === 'idle' || o.type === 'stop' || o.type === 'harvest' || o.type === 'attackmove';
    if (!rettable) return;
    if (o.type === 'attackmove' || o.type === 'harvest') ent.resume = { ...o };
    else ent.resume = { type: 'idle' };
    ent.order = { type: 'attack', target: attacker, buildingTarget: attacker.kind === 'building' ? attacker : null };
    ent.path = null;
    ent.repathT = 0;
  }

  killEntity(ent) {
    if (ent.dead) return;
    ent.dead = true;
    ent.selected = false;
    if (ent.kind === 'unit') {
      this.corpses.push({ x: ent.x, y: ent.y, owner: ent.owner, t: 0 });
      this.addEffect(ent.x, ent.y, 'die');
    } else {
      this.map.setBuilding(ent.tx, ent.ty, ent.size, 0);
      this.addEffect(ent.x, ent.y, 'boom');
      this.event('toast', {
        msg: ent.owner === 0 ? `Разрушено: ${ent.def.name}` : `Уничтожено здание врага: ${ent.def.name}`,
        kind: ent.owner === 0 ? 'bad' : 'ok',
      });
    }
  }

  addEffect(x, y, kind) {
    this.effects.push({ x, y, kind, t: 0 });
  }

  // ---------- главный такт ----------
  tick(dt) {
    if (this.over) return;
    this.time += dt;

    for (const e of this.entities) {
      if (e.dead) continue;
      if (e.flashT > 0) e.flashT -= dt;
      if (e.kind === 'unit') this._updateUnit(e, dt);
      else this._updateBuilding(e, dt);
    }

    this._separateUnits();

    // снаряды
    for (const p of this.projectiles) {
      if (p.dead) continue;
      if (p.target && !p.target.dead) { p.tx = p.target.x; p.ty = p.target.y; }
      const d = dist(p.x, p.y, p.tx, p.ty);
      const step = p.speed * dt;
      p.angle = angleTo(p.x, p.y, p.tx, p.ty);
      if (d <= step + 6) {
        p.x = p.tx; p.y = p.ty;
        if (p.target && !p.target.dead) {
          this.damage(p.target, p.dmg, p.src || { owner: p.owner });
          this.addEffect(p.tx, p.ty, 'hit');
        }
        p.dead = true;
      } else {
        p.x += Math.cos(p.angle) * step;
        p.y += Math.sin(p.angle) * step;
      }
    }

    // эффекты
    for (const f of this.effects) f.t += dt;
    this.effects = this.effects.filter(f => f.t < 0.45);
    for (const c of this.corpses) c.t += dt;
    this.corpses = this.corpses.filter(c => c.t < 1.4);
    this.recentDamage = this.recentDamage.filter(r => this.time - r.t < 12);

    // мёртвые из списков
    if (this.entities.some(e => e.dead)) this.entities = this.entities.filter(e => !e.dead);
    this.projectiles = this.projectiles.filter(p => !p.dead);

    this.fog.update(this, dt);
    this.ai.tick(dt);

    // проверка победы
    this._winT += dt;
    if (this._winT > 0.6) {
      this._winT = 0;
      const pB = this.entities.some(e => e.kind === 'building' && e.owner === 0);
      const eB = this.entities.some(e => e.kind === 'building' && e.owner === 1);
      if (!pB) { this.over = 'defeat'; this.event('end', { result: 'defeat' }); }
      else if (!eB) { this.over = 'victory'; this.event('end', { result: 'victory' }); }
    }
  }

  // ---------- юниты ----------
  _updateUnit(u, dt) {
    u.atkT = Math.max(0, u.atkT - dt);
    const o = u.order;
    switch (o.type) {
      case 'idle': this._uIdle(u, dt); break;
      case 'stop': break;
      case 'move': this._uMove(u, dt, false); break;
      case 'attackmove': this._uAttackMove(u, dt); break;
      case 'attack': this._uAttack(u, dt); break;
      case 'harvest': this._uHarvest(u, dt); break;
      case 'build': this._uBuild(u, dt); break;
      default: u.order = { type: 'idle' };
    }
    // застревание
    if (u.path && u.pathI < u.path.length) {
      const moved = dist2(u.x, u.y, u.lastX, u.lastY);
      if (moved < 0.6) {
        u.stuckT += dt;
        if (u.stuckT > 0.8) {
          u.stuckT = 0;
          u.path = null; // пересчитать путь с текущего места
        }
      } else {
        u.stuckT = 0;
        u.lastX = u.x; u.lastY = u.y;
      }
    }
  }

  _uIdle(u, dt) {
    if (u.def.kind === 'worker') return;
    u.scanT -= dt;
    if (u.scanT <= 0) {
      u.scanT = COMBAT.ACQUIRE_SCAN;
      const t = this.acquireTarget(u);
      if (t) {
        u.resume = { type: 'idle' };
        u.order = { type: 'attack', target: t, buildingTarget: null };
        u.path = null;
      }
    }
  }

  _uMove(u, dt, attackMove) {
    const o = u.order;
    if (!u.path) {
      const p = findPath(this.map, Math.floor(u.x / TILE), Math.floor(u.y / TILE),
        Math.floor(o.x / TILE), Math.floor(o.y / TILE));
      if (!p) { u.order = { type: 'idle' }; return; }
      u.path = p; u.pathI = 0;
      if (p.length === 0) { u.order = { type: 'idle' }; return; }
    }
    if (this._followPath(u, dt)) u.order = { type: 'idle' };
  }

  _uAttackMove(u, dt) {
    const o = u.order;
    u.scanT -= dt;
    if (u.scanT <= 0) {
      u.scanT = COMBAT.ACQUIRE_SCAN;
      const t = this.acquireTarget(u);
      if (t) {
        u.resume = { type: 'attackmove', x: o.x, y: o.y };
        u.order = { type: 'attack', target: t, buildingTarget: null };
        u.path = null;
        return;
      }
    }
    this._uMove(u, dt, true);
  }

  // Точная дистанция до края сущности (для зданий — до прямоугольника)
  _edgeDist(e, x, y) {
    if (e.kind === 'unit') {
      const d = dist(x, y, e.x, e.y);
      return Math.max(0, d - e.def.radius);
    }
    const dx = Math.max(Math.abs(x - e.x) - e.edgeRadius, 0);
    const dy = Math.max(Math.abs(y - e.y) - e.edgeRadius, 0);
    return Math.sqrt(dx * dx + dy * dy);
  }

  // прямой шаг к точке (для доводки вблизи цели, с запретом непроходимых тайлов)
  _nudgeToward(u, tx, ty, dt) {
    const dx = tx - u.x, dy = ty - u.y;
    const d = Math.sqrt(dx * dx + dy * dy) || 1;
    const step = u.def.speed * dt;
    this._tryMove(u, (dx / d) * step, (dy / d) * step);
    u.face = Math.atan2(dy, dx);
  }

  _uAttack(u, dt) {
    const o = u.order;
    let t = o.target;
    if (!t || t.dead) {
      // здание-цель могло быть заменено юнитом — вернуться к зданию
      if (o.buildingTarget && !o.buildingTarget.dead) {
        t = o.target = o.buildingTarget;
      } else {
        u.order = u.resume || { type: 'idle' };
        u.resume = null;
        u.path = null;
        return;
      }
    }
    // при штурме здания переключаемся на юнитов рядом
    if (t.kind === 'building') {
      u.scanT -= dt;
      if (u.scanT <= 0) {
        u.scanT = 0.35;
        const near = this.findEnemyUnitNear(t, u.owner === 0 ? 1 : 0, COMBAT.AUTO_SWITCH_RANGE);
        if (near) o.target = near;
      }
      t = o.target;
      if (!t || t.dead) return;
    }

    const dEdge = this._edgeDist(t, u.x, u.y);

    if (dEdge > u.def.range) {
      const sameTile = Math.floor(u.x / TILE) === Math.floor(t.x / TILE) &&
        Math.floor(u.y / TILE) === Math.floor(t.y / TILE);
      if (sameTile) {
        // уже в тайле цели — подтягиваемся напрямую
        u.path = null;
        this._nudgeToward(u, t.x, t.y, dt);
        return;
      }
      u.repathT -= dt;
      if (!u.path || u.repathT <= 0 || u.pathI >= u.path.length) {
        const p = findPath(this.map, Math.floor(u.x / TILE), Math.floor(u.y / TILE),
          Math.floor(t.x / TILE), Math.floor(t.y / TILE));
        u.repathT = COMBAT.REPATH_TIME;
        if (!p) { u.order = u.resume || { type: 'idle' }; u.resume = null; u.path = null; return; }
        if (p.length === 0) {
          u.path = null;
          this._nudgeToward(u, t.x, t.y, dt);
          return;
        }
        u.path = p; u.pathI = 0;
      }
      if (u.path) this._followPath(u, dt);
    } else {
      u.path = null;
      u.face = angleTo(u.x, u.y, t.x, t.y);
      if (u.atkT <= 0) {
        u.atkT = u.def.atkCd;
        if (u.def.projectile) {
          const pr = new Projectile(u.owner, u.x + Math.cos(u.face) * 10, u.y + Math.sin(u.face) * 10, t, u.def.dmg, u);
          pr.speed = PROJECTILE_SPEED;
          this.projectiles.push(pr);
        } else {
          this.damage(t, u.def.dmg, u);
          this.addEffect(t.x, t.y, 'hit');
        }
      }
    }
  }

  _uHarvest(u, dt) {
    const o = u.order;
    // источник ещё жив?
    if (this.map.resourceAt(o.tx, o.ty) !== o.res) {
      const alt = this.map.nearestResourceTile(u.x, u.y, o.res, HARVEST.RETARGET_RADIUS);
      if (alt) {
        o.tx = alt.tx; o.ty = alt.ty;
        u.path = null;
        if (u.hPhase === 'gather') u.hPhase = 'to';
      } else if (u.carry && u.carry.amt > 0 && u.hPhase !== 'return') {
        u.hPhase = 'return';
        u.path = null;
      } else if (u.hPhase !== 'return') {
        u.order = u.resume || { type: 'idle' };
        u.resume = null;
        u.path = null;
        return;
      }
    }

    const resX = tileC(o.tx), resY = tileC(o.ty);

    if (u.hPhase === 'to') {
      if (!u.path) {
        const stand = this._standTileAround(o.tx, o.ty, 1, u);
        if (!stand) { u.order = { type: 'idle' }; return; }
        const p = findPath(this.map, Math.floor(u.x / TILE), Math.floor(u.y / TILE), stand.tx, stand.ty);
        if (!p) { u.order = { type: 'idle' }; return; }
        u.path = p; u.pathI = 0;
        if (p.length === 0) { u.hPhase = 'gather'; u.gatherT = 0; u.path = null; return; }
      }
      if (this._followPath(u, dt)) {
        u.path = null;
        if (dist(u.x, u.y, resX, resY) <= TILE * 1.6) {
          u.hPhase = 'gather';
          u.gatherT = 0;
        }
        // иначе попытаемся снова найти точку стояния в следующий тик
      }
    } else if (u.hPhase === 'gather') {
      if (dist(u.x, u.y, resX, resY) > TILE * 2) { u.hPhase = 'to'; u.path = null; return; }
      u.gatherT += dt;
      const amt = Math.min(HARVEST.CAP, (u.gatherT / HARVEST.GATHER_TIME) * HARVEST.CAP);
      u.carry = { res: o.res, amt };
      u.face = angleTo(u.x, u.y, resX, resY);
      if (amt >= HARVEST.CAP) {
        const r = this.map.takeResource(o.tx, o.ty, HARVEST.CAP);
        u.carry.amt = r.taken;
        u.hPhase = 'return';
        u.path = null;
      }
    } else if (u.hPhase === 'return') {
      const drop = this.nearestDropoff(u.owner, u.x, u.y);
      if (!drop) {
        u.order = { type: 'idle' };
        u.path = null;
        if (u.owner === 0 && !this._dropWarned) {
          this._dropWarned = true;
          this.event('toast', { msg: 'Нет штаба для сдачи ресурсов!', kind: 'bad' });
        }
        return;
      }
      if (!u.path) {
        const p = findPath(this.map, Math.floor(u.x / TILE), Math.floor(u.y / TILE),
          Math.floor(drop.x / TILE), Math.floor(drop.y / TILE));
        if (!p) { u.order = { type: 'idle' }; return; }
        u.path = p; u.pathI = 0;
        if (p.length === 0) { this._deposit(u, drop); return; }
      }
      if (this._followPath(u, dt) || dist(u.x, u.y, drop.x, drop.y) <= drop.edgeRadius + TILE * 0.6) {
        this._deposit(u, drop);
      }
    }
  }

  _deposit(u, drop) {
    if (u.carry && u.carry.amt > 0) {
      const mult = (u.owner === 1 && this.ai.diffCfg) ? this.ai.diffCfg.income : 1;
      const amt = Math.round(u.carry.amt * mult);
      if (u.carry.res === 'gold') this.players[u.owner].gold += amt;
      else this.players[u.owner].wood += amt;
    }
    u.carry = null;
    u.hPhase = 'to';
    u.path = null;
  }

  _uBuild(u, dt) {
    const o = u.order;
    const site = this.entityById(o.siteId);
    if (!site || site.dead || site.constructed) {
      u.order = u.resume || { type: 'idle' };
      u.resume = null;
      u.path = null;
      return;
    }
    const dEdge = this._edgeDist(site, u.x, u.y);
    if (dEdge > TILE * 0.9) {
      if (!u.path) {
        const stand = this._standTileAround(site.tx, site.ty, site.size, u);
        if (!stand) { u.order = { type: 'idle' }; return; }
        const p = findPath(this.map, Math.floor(u.x / TILE), Math.floor(u.y / TILE), stand.tx, stand.ty);
        if (!p) { u.order = { type: 'idle' }; return; }
        u.path = p; u.pathI = 0;
        if (p.length === 0) { u.path = null; this._nudgeToward(u, site.x, site.y, dt); return; }
      }
      if (this._followPath(u, dt)) u.path = null;
    } else {
      u.path = null;
      u.face = angleTo(u.x, u.y, site.x, site.y);
      site.progress = Math.min(1, site.progress + (dt * BUILD.RATE) / site.def.buildTime);
      const targetHp = site.maxHp * (0.2 + 0.8 * site.progress);
      if (site.hp < targetHp) site.hp = targetHp;
      u.buildFxT -= dt;
      if (u.buildFxT <= 0) {
        u.buildFxT = 0.5;
        this.addEffect(u.x + Math.cos(u.face) * 12, u.y + Math.sin(u.face) * 12, 'build');
      }
      if (site.progress >= 1) this.finishSite(site);
    }
  }

  _followPath(u, dt) {
    if (!u.path || u.pathI >= u.path.length) return true;
    const wp = u.path[u.pathI];
    const dx = wp.x - u.x, dy = wp.y - u.y;
    const d = Math.sqrt(dx * dx + dy * dy);
    const step = u.def.speed * dt;
    if (d <= step + 0.01) {
      this._tryMove(u, dx, dy);
      u.pathI++;
      u.face = angleTo(u.x, u.y, wp.x, wp.y);
      return u.pathI >= u.path.length;
    }
    this._tryMove(u, (dx / d) * step, (dy / d) * step);
    u.face = angleTo(u.x, u.y, wp.x, wp.y);
    return false;
  }

  // движение с запретом входа в непроходимые тайлы (скольжение по осям)
  _tryMove(u, dx, dy) {
    const nx = u.x + dx, ny = u.y + dy;
    if (this.map.isPassable(Math.floor(nx / TILE), Math.floor(u.y / TILE))) u.x = nx;
    if (this.map.isPassable(Math.floor(u.x / TILE), Math.floor(ny / TILE))) u.y = ny;
  }

  // расталкивание юнитов
  _separateUnits() {
    const units = this.entities.filter(e => e.kind === 'unit' && !e.dead);
    const n = units.length;
    for (let i = 0; i < n; i++) {
      const a = units[i];
      for (let j = i + 1; j < n; j++) {
        const b = units[j];
        const dx = b.x - a.x, dy = b.y - a.y;
        const minD = a.def.radius + b.def.radius;
        const d2v = dx * dx + dy * dy;
        if (d2v >= minD * minD || d2v < 0.0001) continue;
        const d = Math.sqrt(d2v);
        const push = (minD - d) * 0.4;
        const ux = dx / d, uy = dy / d;
        this._tryMove(a, -ux * push, -uy * push);
        this._tryMove(b, ux * push, uy * push);
      }
    }
  }

  // ---------- здания ----------
  _updateBuilding(b, dt) {
    if (!b.constructed) return;
    b.builtT += dt;

    // производство
    if (b.queue.length) {
      const q = b.queue[0];
      q.t -= dt;
      if (q.t <= 0) {
        b.queue.shift();
        this._spawnFromBuilding(b, q.key);
      }
    }

    // башня
    if (b.def.dmg) {
      b.scanT -= dt;
      if (b.scanT <= 0) {
        b.scanT = 0.3;
        if (!b.target || b.target.dead || dist(b.x, b.y, b.target.x, b.target.y) > b.def.range) {
          let best = null, bd = Infinity;
          for (const e of this.entities) {
            if (e.dead || e.kind !== 'unit' || e.owner === b.owner) continue;
            const d = dist2(b.x, b.y, e.x, e.y);
            if (d < b.def.range * b.def.range && d < bd) { bd = d; best = e; }
          }
          b.target = best;
        }
      }
      if (b.target && !b.target.dead) {
        b.barrelA = angleTo(b.x, b.y, b.target.x, b.target.y);
        b.atkT = Math.max(0, b.atkT - dt);
        if (b.atkT <= 0 && dist(b.x, b.y, b.target.x, b.target.y) <= b.def.range) {
          b.atkT = b.def.atkCd;
          const pr = new Projectile(b.owner, b.x + Math.cos(b.barrelA) * 14, b.y + Math.sin(b.barrelA) * 14, b.target, b.def.dmg, b);
          pr.speed = PROJECTILE_SPEED;
          this.projectiles.push(pr);
        }
      }
    }
  }
}
