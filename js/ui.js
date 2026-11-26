// ============================================================
// ui.js — HUD: ресурсы, панель команд, выбор, миникарта, оверлеи
// ============================================================

import {
  UNIT_DEFS, BUILDING_DEFS, BUILD_MENU, DIFFICULTIES, QUEUE_MAX,
  MAP_W, MAP_H, TILE, WORLD_W, WORLD_H,
} from './config.js';
import { fmtTime } from './utils.js';

export class UI {
  constructor({ game, input, camera, renderer, onRestart, onDifficulty }) {
    this.game = game;
    this.input = input;
    this.camera = camera;
    this.renderer = renderer;
    this.onRestart = onRestart;
    this.onDifficulty = onDifficulty;
    this.paused = false;
    this.isTouch = ('ontouchstart' in window) || (navigator.maxTouchPoints || 0) > 0;
    if (this.isTouch) document.body.classList.add('touch');
    this.toasts = [];
    this.pings = [];
    this._cmdT = 0;
    this._selT = 0;
    this._mmT = 0;
    this._selSig = null;
    this._draggingMap = false;

    this.el = id => document.getElementById(id);
    this.e = {
      topGold: this.el('resGold'),
      topWood: this.el('resWood'),
      topSupply: this.el('resSupply'),
      time: this.el('gameTime'),
      btnPause: this.el('btnPause'),
      btnHelp: this.el('btnHelp'),
      btnRestart: this.el('btnRestart'),
      toasts: this.el('toasts'),
      selPanel: this.el('selPanel'),
      cmdCard: this.el('cmdCard'),
      mm: this.el('minimap'),
      overlay: this.el('overlay'),
    };
    this.mmCtx = this.e.mm.getContext('2d');
    this._bind();
    this._cmdSig = '';
  }

  destroy() {
    const mm = this.e.mm;
    mm.removeEventListener('mousedown', this._mmDown);
    mm.removeEventListener('mousemove', this._mmMoveM);
    window.removeEventListener('mouseup', this._mmUp);
  }

  _bind() {
    this.e.btnPause.onclick = () => this.togglePause();
    this.e.btnHelp.onclick = () => this.showHelp();
    this.e.btnRestart.onclick = () => this.onRestart && this.onRestart();

    // кнопки быстрого выбора (видны только на тач-устройствах, но привязываем всегда)
    const bArmy = document.getElementById('btnArmy');
    const bWork = document.getElementById('btnWorkers');
    const bBase = document.getElementById('btnBase');
    if (bArmy) bArmy.onclick = () => this.input.selectAllOf('military');
    if (bWork) bWork.onclick = () => this.input.selectAllOf('worker');
    if (bBase) bBase.onclick = () => this.input.selectBase();

    // миникарта
    const mm = this.e.mm;
    mm.addEventListener('contextmenu', e => e.preventDefault());
    this._mmDown = e => {
      e.preventDefault();
      this._draggingMap = true;
      this._mmJump(e);
    };
    this._mmMoveM = e => { if (this._draggingMap) this._mmJump(e); };
    this._mmUp = () => { this._draggingMap = false; };
    mm.addEventListener('mousedown', this._mmDown);
    mm.addEventListener('mousemove', this._mmMoveM);
    window.addEventListener('mouseup', this._mmUp);
  }

  _mmJump(e) {
    const r = this.e.mm.getBoundingClientRect();
    const x = (e.clientX - r.left) / r.width * WORLD_W;
    const y = (e.clientY - r.top) / r.height * WORLD_H;
    this.camera.centerOn(x, y);
  }

  // ---------- события игры ----------
  toast(msg, kind = 'info') {
    this.toasts.push({ msg, kind, t: 0 });
    if (this.toasts.length > 4) this.toasts.shift();
    this._renderToasts();
  }

  ping(x, y) { this.pings.push({ x, y, t: 0 }); }

  onSelectionChanged() { this._selSig = ''; }

  togglePause() {
    this.paused = !this.paused;
    this.e.btnPause.textContent = this.paused ? '▶' : '⏸';
    this.e.btnPause.classList.toggle('active', this.paused);
  }

  onEscape() {
    const ov = this.e.overlay;
    if (ov.style.display === 'flex' && this._overlayKind === 'help') this.hideOverlay();
  }

  // ---------- оверлеи ----------
  _showOverlay(html, kind) {
    this._overlayKind = kind;
    this.e.overlay.innerHTML = html;
    this.e.overlay.style.display = 'flex';
  }

  hideOverlay() {
    this.e.overlay.style.display = 'none';
    this._overlayKind = null;
    this.paused = false;
    this.e.btnPause.textContent = '⏸';
  }

  showStart() {
    const diffs = Object.values(DIFFICULTIES)
      .map(d => `<label class="diff"><input type="radio" name="diff" value="${d.key}" ${d.key === 'normal' ? 'checked' : ''}><span>${d.name}</span></label>`)
      .join('');
    const controls = this.isTouch ? `
          <div>Касание — выбор юнита/здания · тап по земле — приказ</div>
          <div>Перетаскивание — камера · щипок — зум · двойной тап — все юниты типа</div>
          <div>Кнопки справа: ⚔ армия · ⛏ рабочие · 🏛 к базе</div>
          <div>Кнопки в панели команд: постройки и обучение войск</div>
          <div>Тап по миникарте — перейти к месту</div>
        ` : `
          <div>ЛКМ / рамка — выбор юнитов · двойной ЛКМ — все юниты типа</div>
          <div>ПКМ — приказ: идти / добывать / атаковать / строить</div>
          <div><kbd>A</kbd> + ЛКМ — атака-движение · <kbd>S</kbd> — стоп · <kbd>H</kbd> — к штабу</div>
          <div><kbd>Ctrl</kbd>+<kbd>1..5</kbd> — создать группу · <kbd>1..5</kbd> — вызвать</div>
          <div>Стрелки / край экрана — камера · колесо — зум · <kbd>Пробел</kbd> — пауза</div>
          <div>ПКМ при выбранном здании — точка сбора</div>
        `;
    const rotate = this.isTouch ? '<p class="hint rotateHint">↻ Поверните устройство горизонтально — так удобнее</p>' : '';
    this._showOverlay(`
      <div class="panel start">
        <h1>РАЗЛОМ</h1>
        <p class="sub">браузерная стратегия в реальном времени</p>
        <div class="diffrow">${diffs}</div>
        <div class="controls">
          <b>Управление</b>
          ${controls}
        </div>
        <button id="btnStart" class="primary">▶ Начать игру</button>
        <p class="hint">Цель: уничтожить штаб противника. Постройте казарму и соберите армию.</p>
        ${rotate}
      </div>
    `, 'start');
    this.e.overlay.style.display = 'flex';
    this.el('btnStart').onclick = () => {
      const diff = this.e.overlay.querySelector('input[name=diff]:checked').value;
      this.hideOverlay();
      (this.onDifficulty || (() => {}))(diff);
    };
  }

  showEnd(result, time) {
    const win = result === 'victory';
    this._showOverlay(`
      <div class="panel end ${win ? 'win' : 'lose'}">
        <h1>${win ? '🏆 Победа!' : '💀 Поражение'}</h1>
        <p class="sub">${win ? 'Штаб противника разрушен' : 'Ваша база уничтожена'}</p>
        <p class="time">Время партии: ${fmtTime(time)}</p>
        <button id="btnAgain" class="primary">↻ Играть снова</button>
      </div>
    `, 'end');
    this.el('btnAgain').onclick = () => {
      this.hideOverlay();
      this.onRestart && this.onRestart();
    };
  }

  showHelp() {
    this.paused = true;
    this.e.btnPause.textContent = '▶';
    const controls = this.isTouch ? `
          <div>Касание — выбор · тап по земле/врагу — приказ (идти, атаковать, добывать, достроить)</div>
          <div>Перетаскивание — камера · щипок — зум · двойной тап — все юниты типа</div>
          <div>Кнопки справа: ⚔ армия · ⛏ рабочие · 🏛 к базе</div>
          <div>Выбранное здание: тап по земле — точка сбора</div>
          <div>Тап по миникарте — перейти к месту</div>
        ` : `
          <div>ЛКМ / рамка — выбор · двойной ЛКМ — все юниты типа</div>
          <div>ПКМ — приказ (идти / добывать / атаковать / достроить)</div>
          <div><kbd>A</kbd> + ЛКМ — атака-движение · <kbd>S</kbd> — стоп · <kbd>H</kbd> — к штабу</div>
          <div><kbd>Ctrl</kbd>+<kbd>1..5</kbd> — группы · <kbd>1..5</kbd> — вызвать группу</div>
          <div>Стрелки / край экрана — камера · колесо — зум · <kbd>Пробел</kbd> — пауза</div>
          <div>Выбранный рабочий: <kbd>Q</kbd> штаб · <kbd>W</kbd> казарма · <kbd>E</kbd> дом · <kbd>R</kbd> башня</div>
          <div>Казарма: <kbd>Q</kbd> мечник · <kbd>W</kbd> лучник</div>
          <div>ПКМ при выбранном здании — точка сбора новых войск</div>
        `;
    this._showOverlay(`
      <div class="panel help">
        <h2>Управление</h2>
        <div class="controls">${controls}</div>
        <button id="btnClose" class="primary">Продолжить</button>
      </div>
    `, 'help');
    this.el('btnClose').onclick = () => this.hideOverlay();
  }

  _renderToasts() {
    this.e.toasts.innerHTML = this.toasts
      .map(t => `<div class="toast ${t.kind}">${t.msg}</div>`).join('');
  }

  // ---------- команды ----------
  _commands() {
    const cmds = [];
    const inp = this.input;
    const g = this.game;

    if (inp.placement) {
      for (const b of BUILD_MENU) {
        const def = BUILDING_DEFS[b.key];
        cmds.push({
          code: 'Key' + b.hotkey, hk: b.hotkey, icon: def.icon, label: def.name,
          cost: this._costStr(def.cost), enabled: true,
          highlight: inp.placement === b.key,
          onClick: () => inp.beginPlacement(b.key),
        });
      }
      cmds.push({
        code: 'KeyX', hk: 'X', icon: '✖', label: 'Отмена',
        onClick: () => inp._cancelPlacement(),
      });
      return cmds;
    }

    const sel = inp.selected.filter(s => !s.dead);
    if (sel.length === 1 && sel[0].kind === 'building') {
      const b = sel[0];
      if (!b.constructed) {
        cmds.push({
          code: 'KeyQ', hk: 'Q', icon: '✖', label: 'Отмена стройки',
          hint: 'полный возврат ресурсов',
          onClick: () => { g.cancelSite(b); inp.setSelection([]); },
        });
        return cmds;
      }
      const hks = ['KeyQ', 'KeyW', 'KeyE'];
      (b.def.trains || []).forEach((key, i) => {
        const def = UNIT_DEFS[key];
        const st = g.stats(0);
        const cnt = b.queue.filter(q => q.key === key).length;
        const first = b.queue[0];
        cmds.push({
          code: hks[i], hk: hks[i].slice(3), icon: def.icon, label: def.name,
          cost: this._costStr(def.cost), title: def.desc,
          enabled: g.canAfford(0, def.cost) && st.used + def.supply <= st.cap && b.queue.length < QUEUE_MAX,
          badge: cnt || null,
          progress: first && first.key === key ? 1 - first.t / first.tMax : null,
          onClick: () => g.trainUnit(b, key),
        });
      });
      if (b.queue.length) {
        cmds.push({
          code: 'KeyX', hk: 'X', icon: '↩', label: 'Отменить',
          hint: 'вернуть ресурсы за последний',
          onClick: () => g.cancelTrain(b),
        });
      }
      return cmds;
    }

    const units = sel.filter(s => s.kind === 'unit');
    if (units.length) {
      const hasWorker = units.some(u => u.def.kind === 'worker');
      if (hasWorker) {
        for (const b of BUILD_MENU) {
          const def = BUILDING_DEFS[b.key];
          cmds.push({
            code: 'Key' + b.hotkey, hk: b.hotkey, icon: def.icon, label: def.name,
            cost: this._costStr(def.cost), title: def.desc,
            enabled: g.canAfford(0, def.cost),
            onClick: () => inp.beginPlacement(b.key),
          });
        }
      }
      cmds.push({
        code: 'KeyA', hk: 'A', icon: '⚔', label: 'Атака',
        hint: 'атака-движение: клик по цели',
        highlight: inp.attackMoveArmed,
        onClick: () => { inp.attackMoveArmed = true; this.toast('Укажите цель атаки', 'info'); },
      });
      cmds.push({
        code: 'KeyS', hk: 'S', icon: '✋', label: 'Стоп',
        onClick: () => g.stopUnits(units),
      });
    }
    return cmds;
  }

  _costStr(cost) {
    const parts = [];
    if (cost.gold) parts.push(`${cost.gold}💰`);
    if (cost.wood) parts.push(`${cost.wood}🪵`);
    return parts.join(' ');
  }

  _renderCommands() {
    const cmds = this._commands();
    this.e.cmdCard.innerHTML = cmds.map((c, i) => `
      <button class="cmd ${c.highlight ? 'hl' : ''} ${c.enabled === false ? 'off' : ''}" data-i="${i}" title="${c.hint || c.title || ''}">
        ${c.progress != null ? `<div class="prog" style="height:${Math.round(c.progress * 100)}%"></div>` : ''}
        <span class="ico">${c.icon}</span>
        <span class="lbl">${c.label}</span>
        ${c.cost ? `<span class="cost">${c.cost}</span>` : ''}
        ${c.badge ? `<span class="badge">${c.badge}</span>` : ''}
        <span class="hk">${c.hk}</span>
      </button>`).join('');
    this.e.cmdCard.querySelectorAll('.cmd').forEach(btn => {
      const c = cmds[+btn.dataset.i];
      btn.onclick = e => { e.preventDefault(); if (c.enabled !== false) c.onClick(); };
    });
  }

  // ---------- панель выбора ----------
  _renderSelection() {
    const sel = this.input.selected.filter(s => !s.dead);
    const p = this.e.selPanel;
    if (!sel.length) {
      p.innerHTML = `<div class="selHint">ЛКМ — выбор · ПКМ — приказ · колесо — зум<br>Постройте казарму и соберите армию!</div>`;
      return;
    }
    if (sel.length === 1) {
      const s = sel[0];
      const pct = Math.max(0, s.hp / s.maxHp);
      let extra = '';
      if (s.kind === 'unit') {
        extra = `<div class="row2"><span>⚔ ${s.def.dmg}</span><span>🛡 ${s.def.armor}</span>${s.carry ? `<span>${s.carry.res === 'gold' ? '💰' : '🪵'} ${Math.floor(s.carry.amt)}</span>` : ''}</div>`;
      } else {
        if (!s.constructed) extra = `<div class="row2"><span>Стройка: ${Math.round(s.progress * 100)}%</span></div>`;
        else if (s.queue.length) extra = `<div class="row2"><span>В очереди: ${s.queue.map(q => UNIT_DEFS[q.key].icon).join(' ')}</span></div>`;
      }
      p.innerHTML = `
        <div class="portrait">${s.def.icon}</div>
        <div class="selInfo">
          <div class="selName">${s.def.name}</div>
          <div class="hpbar"><div style="width:${Math.round(pct * 100)}%" class="${pct > 0.55 ? 'g' : pct > 0.28 ? 'y' : 'r'}"></div></div>
          <div class="hpnum">${Math.ceil(s.hp)} / ${s.maxHp}</div>
          ${extra}
        </div>`;
      return;
    }
    const items = sel.slice(0, 24).map(s => {
      const pct = Math.max(0, s.hp / s.maxHp);
      return `<div class="mini"><div class="miniIco">${s.def.icon}</div><div class="miniHp"><div style="width:${Math.round(pct * 100)}%" class="${pct > 0.55 ? 'g' : pct > 0.28 ? 'y' : 'r'}"></div></div></div>`;
    }).join('');
    p.innerHTML = `<div class="selCount">Выбрано: ${sel.length}</div><div class="miniGrid">${items}</div>`;
  }

  // ---------- миникарта ----------
  _drawMinimap() {
    const mm = this.mmCtx;
    const S = this.e.mm.width; // 168
    const k = S / WORLD_W;
    const g = this.game;
    mm.fillStyle = '#0b0d10';
    mm.fillRect(0, 0, S, S);

    const terr = g.map.ensureCanvas((w, h) => {
      const c = document.createElement('canvas');
      c.width = w; c.height = h;
      return c;
    });
    mm.drawImage(terr, 0, 0, WORLD_W, WORLD_H, 0, 0, S, S);

    // туман
    mm.drawImage(this.renderer.fogCanvas, 0, 0, MAP_W, MAP_H, 0, 0, S, S);

    // здания (видимые/исследованные)
    for (const e of g.entities) {
      if (e.dead || e.kind !== 'building') continue;
      if (e.owner !== 0 && !g.fog.exp(Math.floor(e.x / TILE), Math.floor(e.y / TILE))) continue;
      mm.fillStyle = e.owner === 0 ? '#4f8ef7' : '#e05555';
      const sz = e.size * TILE * k;
      mm.fillRect(e.x * k - sz / 2, e.y * k - sz / 2, sz, sz);
    }
    // юниты
    for (const e of g.entities) {
      if (e.dead || e.kind !== 'unit') continue;
      if (e.owner !== 0 && !g.fog.visWorld(e.x, e.y)) continue;
      mm.fillStyle = e.owner === 0 ? '#9cc4ff' : '#ff9c9c';
      mm.fillRect(e.x * k - 1.5, e.y * k - 1.5, 3, 3);
    }

    // рамка камеры
    const cam = this.camera;
    mm.strokeStyle = 'rgba(255,255,255,0.9)';
    mm.lineWidth = 1.5;
    mm.strokeRect(cam.x * k, cam.y * k, (cam.vw / cam.zoom) * k, (cam.vh / cam.zoom) * k);

    // пинги
    for (const p of this.pings) {
      const a = Math.max(0, 1 - p.t / 2.5);
      mm.strokeStyle = `rgba(255,90,90,${a})`;
      mm.lineWidth = 2;
      mm.beginPath();
      mm.arc(p.x * k, p.y * k, 4 + p.t * 12, 0, Math.PI * 2);
      mm.stroke();
    }
  }

  // ---------- кадр ----------
  update(dt) {
    const g = this.game;
    const st = g.stats(0);

    this.e.topGold.textContent = st.gold;
    this.e.topWood.textContent = st.wood;
    this.e.topSupply.textContent = `${st.used}/${st.cap}`;
    this.e.topSupply.classList.toggle('full', st.used >= st.cap);
    this.e.time.textContent = fmtTime(g.time);

    // тосты
    let dirty = false;
    for (const t of this.toasts) t.t += dt;
    const before = this.toasts.length;
    this.toasts = this.toasts.filter(t => t.t < 2.8);
    if (this.toasts.length !== before) this._renderToasts();
    else if (this.toasts.length) {
      // затемнять уходящие
      this.e.toasts.querySelectorAll('.toast').forEach((el, i) => {
        const t = this.toasts[i];
        if (t && t.t > 2.2) el.style.opacity = String(Math.max(0, 1 - (t.t - 2.2) / 0.6));
      });
    }

    for (const p of this.pings) p.t += dt;
    this.pings = this.pings.filter(p => p.t < 2.5);

    // панель выбора
    this._selT += dt;
    if (this._selT > 0.15) {
      this._selT = 0;
      const sig = this.input.selected.filter(s => !s.dead).map(s => s.id).join(',');
      if (sig !== this._selSig) {
        this._selSig = sig;
        this._renderSelection();
      } else if (sel1NeedsRefresh(this.input.selected)) {
        this._renderSelection();
      }
    }

    // команды
    this._cmdT += dt;
    if (this._cmdT > 0.2) {
      this._cmdT = 0;
      this._renderCommands();
    }

    // миникарта
    this._mmT += dt;
    if (this._mmT > 0.12) {
      this._mmT = 0;
      this._drawMinimap();
    }
  }

  handleHotkey(code) {
    const cmds = this._commands();
    for (const c of cmds) {
      if (c.code === code && c.enabled !== false) {
        c.onClick();
        return true;
      }
    }
    return false;
  }
}

// обновлять панель одной сущности, если меняется её hp/queue
function sel1NeedsRefresh(sel) {
  if (sel.length !== 1) return false;
  const s = sel[0];
  if (s.dead) return false;
  if (s.kind === 'building' && (s.queue.length || (!s.constructed && s.progress < 1))) return true;
  return false;
}
