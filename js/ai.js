// ============================================================
// ai.js — контроллер ИИ-противника: экономика, стройка, волны атак
// ============================================================

import { BUILDING_DEFS, DIFFICULTIES, TILE } from './config.js';
import { dist2 } from './utils.js';

export class AIController {
  constructor(game, pid, diffKey = 'normal') {
    this.game = game;
    this.pid = pid;
    this.enemy = pid === 0 ? 1 : 0;
    this.diffCfg = DIFFICULTIES[diffKey] || DIFFICULTIES.normal;
    this.thinkT = 0;
    this.tacticsT = 0;
    this.waveSize = this.diffCfg.wave0;
    this.wave = 0;
    this.mode = 'build';       // build | defend
    this.defendUntil = 0;
    this.gather = null;
    this.log = [];             // для отладки/тестов: {t, type}
  }

  _log(type) {
    this.log.push({ t: this.game.time, type });
    if (this.log.length > 400) this.log.shift();
  }

  tick(dt) {
    this.thinkT += dt;
    if (this.thinkT >= 0.6) { this.thinkT = 0; this._think(); }
    this.tacticsT += dt;
    if (this.tacticsT >= 0.9) { this.tacticsT = 0; this._tactics(); }
  }

  // ---------- экономика и стройка ----------
  _think() {
    const g = this.game, pid = this.pid;
    const base = g.playerBase(pid);
    const myBuildings = g.buildingsOf(pid);
    const workers = g.unitsOf(pid).filter(u => u.def.kind === 'worker');
    const army = g.unitsOf(pid).filter(u => u.def.kind !== 'worker');
    const st = g.stats(pid);
    if (!base && !myBuildings.length) return;

    // 1. производство рабочих
    if (base && base.constructed) {
      const queued = base.queue.filter(q => q.key === 'worker').length;
      if (workers.length + queued < this.diffCfg.workersTarget) {
        g.trainUnit(base, 'worker', true);
      }
    }

    // 2. бездельных рабочих — на добычу
    const barracksBuilt = myBuildings.some(b => b.def.key === 'barracks' && b.constructed);
    const wantWood = barracksBuilt ? 2 : 3;
    let woodCount = 0, goldCount = 0;
    for (const w of workers) {
      if (w.order.type === 'harvest') {
        if (w.order.res === 'wood') woodCount++; else goldCount++;
        continue;
      }
      if (w.order.type === 'build') continue;
      let type = woodCount < wantWood ? 'wood' : 'gold';
      let res = g.map.nearestResourceTile(w.x, w.y, type, 22);
      if (!res && type === 'wood') res = g.map.nearestResourceTile(w.x, w.y, 'gold', 22);
      if (res) {
        g.issueHarvest([w], res.tx, res.ty);
        if (g.map.resourceAt(res.tx, res.ty) === 'wood') woodCount++; else goldCount++;
      }
    }

    // 3. дома под лимит
    const housePending = myBuildings.some(b => b.def.key === 'house' && !b.constructed);
    if (!housePending && st.cap < 50 && st.used >= st.cap - 2) {
      this._place('house', base);
    }

    // 4. казармы
    const barracksCount = myBuildings.filter(b => b.def.key === 'barracks').length;
    if (barracksCount === 0 && !housePending) {
      this._place('barracks', base) && this._log('barracks');
    } else if (barracksCount === 1 && army.length >= 8 && st.gold > 200) {
      this._place('barracks', base) && this._log('barracks2');
    }

    // 5. башни
    const turretCount = myBuildings.filter(b => b.def.key === 'turret').length;
    if (turretCount < this.diffCfg.turrets && st.wood >= 40 && base) {
      this._placeTurret(base) && this._log('turret');
    }

    // 6. убедиться, что стройки не брошены
    for (const site of myBuildings) {
      if (site.constructed) continue;
      const hasBuilder = workers.some(w => w.order.type === 'build' && w.order.siteId === site.id);
      if (!hasBuilder) {
        const free = workers
          .filter(w => w.order.type !== 'build')
          .sort((a, b) => dist2(a.x, a.y, site.x, site.y) - dist2(b.x, b.y, site.x, site.y))[0];
        if (free) g.issueBuild([free], site);
      }
    }

    // 7. армия
    for (const b of myBuildings) {
      if (!b.constructed || !b.def.trains || b.def.key === 'base') continue;
      if (b.queue.length >= 2) continue;
      const key = (st.wood > 80 && Math.random() < 0.45) ? 'archer' : 'soldier';
      g.trainUnit(b, key, true);
    }
  }

  _place(key, nearBuilding) {
    const g = this.game, def = BUILDING_DEFS[key];
    if (!g.canAfford(this.pid, def.cost)) return false;
    const cx = nearBuilding ? Math.floor(nearBuilding.x / TILE) : 32;
    const cy = nearBuilding ? Math.floor(nearBuilding.y / TILE) : 32;
    const spot = this._findSpot(def.size, cx, cy);
    if (!spot) return false;
    const r = g.placeBuildingSite(this.pid, key, spot.tx, spot.ty);
    if (!r.site) return false;
    // назначить ближайшего рабочего
    const workers = g.unitsOf(this.pid).filter(u => u.def.kind === 'worker');
    const w = workers
      .filter(x => x.order.type !== 'build')
      .sort((a, b) => dist2(a.x, a.y, r.site.x, r.site.y) - dist2(b.x, b.y, r.site.x, r.site.y))[0];
    if (w) g.issueBuild([w], r.site);
    return true;
  }

  _placeTurret(base) {
    const g = this.game;
    const eb = g.playerBase(this.enemy);
    if (!eb) return this._place('turret', base);
    const dx = eb.x - base.x, dy = eb.y - base.y;
    const d = Math.sqrt(dx * dx + dy * dy) || 1;
    const tx = Math.floor((base.x + (dx / d) * 6 * TILE) / TILE);
    const ty = Math.floor((base.y + (dy / d) * 6 * TILE) / TILE);
    const def = BUILDING_DEFS.turret;
    if (!g.canAfford(this.pid, def.cost)) return false;
    for (let r = 0; r <= 4; r++) {
      for (let ddy = -r; ddy <= r; ddy++) {
        for (let ddx = -r; ddx <= r; ddx++) {
          if (Math.max(Math.abs(ddx), Math.abs(ddy)) !== r) continue;
          const x = tx + ddx, y = ty + ddy;
          if (g.map.canPlace(x, y, def.size, { ring: true })) {
            const res = g.placeBuildingSite(this.pid, 'turret', x, y);
            if (res.site) {
              const workers = g.unitsOf(this.pid).filter(u => u.def.kind === 'worker');
              const w = workers
                .filter(xx => xx.order.type !== 'build')
                .sort((a, b) => dist2(a.x, a.y, res.site.x, res.site.y) - dist2(b.x, b.y, res.site.x, res.site.y))[0];
              if (w) g.issueBuild([w], res.site);
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  _findSpot(size, cx, cy) {
    const g = this.game;
    for (let r = 2; r <= 11; r++) {
      for (let dy = -r; dy <= r; dy++) {
        for (let dx = -r; dx <= r; dx++) {
          if (Math.max(Math.abs(dx), Math.abs(dy)) !== r) continue;
          const x = cx + dx, y = cy + dy;
          if (g.map.canPlace(x, y, size, { ring: true })) return { tx: x, ty: y };
        }
      }
    }
    return null;
  }

  // ---------- тактика: оборона и волны ----------
  _tactics() {
    const g = this.game, pid = this.pid;
    const army = g.unitsOf(pid).filter(u => u.def.kind !== 'worker');
    const base = g.playerBase(pid);
    if (!base) {
      // штаба нет — все в атаку на то, что осталось
      const target = this._nearestEnemyBuilding(base);
      if (target && army.length) g.issueAttack(army, target);
      return;
    }

    // оборона: атакованные здания или потери рядом с базой
    const threats = g.recentDamage.filter(r =>
      r.owner === pid && g.time - r.t < 5 &&
      (r.b || (base && dist2(r.x, r.y, base.x, base.y) < (12 * TILE) ** 2)));
    if (threats.length) {
      this.mode = 'defend';
      this.defendUntil = g.time + 9;
    }
    if (this.mode === 'defend') {
      const t = threats.length ? threats[threats.length - 1] : null;
      if (t) {
        const idle = army.filter(u => u.order.type === 'idle' || u.order.type === 'stop' ||
          (u.order.type === 'attackmove' && dist2(u.x, u.y, t.x, t.y) > (8 * TILE) ** 2));
        if (idle.length) g.issueMove(idle, t.x, t.y, true);
      }
      if (g.time > this.defendUntil) this.mode = 'build';
      return;
    }

    // точка сбора между базами (ближе к себе)
    const eb = g.playerBase(this.enemy);
    if (!eb) return;
    if (!this.gather) {
      this.gather = { x: base.x + (eb.x - base.x) * 0.3, y: base.y + (eb.y - base.y) * 0.3 };
    }
    const rally = this.gather;

    // собрать бездельников у точки сбора
    for (const u of army) {
      if (u.order.type === 'idle' || u.order.type === 'stop') {
        if (dist2(u.x, u.y, rally.x, rally.y) > (4 * TILE) ** 2) g.issueMove([u], rally.x, rally.y);
      }
    }

    // готовые к волне
    const ready = army.filter(u =>
      (u.order.type === 'idle' || u.order.type === 'stop' || u.order.type === 'move') &&
      dist2(u.x, u.y, rally.x, rally.y) < (6 * TILE) ** 2);

    // у врага не осталось армии — финальный раш
    const enemyArmy = g.unitsOf(this.enemy).filter(u => u.def.kind !== 'worker').length;
    const sinceWave = g.time - (this.lastWaveT || 0);
    const bigWave = ready.length >= this.waveSize;
    const pressureWave = ready.length >= 4 && sinceWave > 110;
    const finalPush = ready.length >= 6 && enemyArmy === 0;

    if (bigWave || pressureWave || finalPush) {
      const target = this._nearestEnemyBuilding(base);
      if (target) {
        g.issueAttack(ready, target);
        this.wave++;
        if (bigWave) {
          this.waveSize = Math.min(this.waveSize + this.diffCfg.waveStep, this.diffCfg.waveCap);
        }
        this.lastWaveT = g.time;
        this._log('wave');
        // новая точка сбора ближе к врагу
        this.gather = { x: base.x + (eb.x - base.x) * 0.3, y: base.y + (eb.y - base.y) * 0.3 };
      }
    }
  }

  _nearestEnemyBuilding(from) {
    const g = this.game;
    const list = g.buildingsOf(this.enemy);
    if (!list.length) return null;
    const fx = from ? from.x : 32 * TILE, fy = from ? from.y : 32 * TILE;
    return list.sort((a, b) => dist2(a.x, a.y, fx, fy) - dist2(b.x, b.y, fx, fy))[0];
  }
}
