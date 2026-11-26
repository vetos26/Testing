// ============================================================
// entities.js — классы юнитов, зданий, снарядов
// ============================================================

import { TILE } from './config.js';

let NEXT_ID = 1;
export function nextId() { return NEXT_ID++; }

export class Entity {
  constructor(owner, def, x, y) {
    this.id = nextId();
    this.owner = owner;
    this.def = def;
    this.x = x;
    this.y = y;
    this.hp = def.hp;
    this.maxHp = def.hp;
    this.dead = false;
    this.selected = false;
    this.flashT = 0;      // вспышка при получении урона
    this.lastDmgT = -99;
  }
}

export class Unit extends Entity {
  constructor(owner, def, x, y) {
    super(owner, def, x, y);
    this.kind = 'unit';
    this.face = owner === 0 ? Math.PI : 0; // направление взгляда
    this.order = { type: 'idle' };
    this.resume = null;      // куда вернуться после боя
    this.path = null;        // [{x,y}] мировые точки
    this.pathI = 0;
    this.repathT = 0;
    this.atkT = 0;           // кулдаун атаки
    this.scanT = 0;          // период сканирования целей
    this.stuckT = 0;
    this.lastX = x; this.lastY = y;
    this.carry = null;       // {res:'gold'|'wood', amt}
    this.hPhase = null;      // 'to' | 'gather' | 'return'
    this.gatherT = 0;
    this.buildFxT = 0;
    this.dmgFlashDir = 0;
  }
}

export class Building extends Entity {
  constructor(owner, def, tx, ty, constructed) {
    const cx = tx * TILE + (def.size * TILE) / 2;
    const cy = ty * TILE + (def.size * TILE) / 2;
    super(owner, def, cx, cy);
    this.kind = 'building';
    this.tx = tx;
    this.ty = ty;
    this.size = def.size;
    this.constructed = !!constructed;
    this.progress = constructed ? 1 : 0;
    if (!constructed) {
      this.hp = Math.max(1, Math.round(def.hp * 0.2));
    }
    this.queue = [];   // [{key, t, tMax}]
    this.rally = null; // {x, y, targetId?}
    this.atkT = 0;
    this.scanT = 0;
    this.target = null;
    this.barrelA = 0;
    this.builtT = 0;
  }

  get edgeRadius() {
    return (this.size * TILE) / 2;
  }
}

export class Projectile {
  constructor(owner, x, y, target, dmg, src = null) {
    this.id = nextId();
    this.owner = owner;
    this.x = x;
    this.y = y;
    this.target = target;
    this.src = src;      // кто стрелял (для ответного удара)
    this.tx = target.x;
    this.ty = target.y;
    this.speed = 280;
    this.dmg = dmg;
    this.dead = false;
    this.angle = 0;
  }
}
