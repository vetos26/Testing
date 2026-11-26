// ============================================================
// sim.mjs — headless-прогоны игры в Node
//   node test/sim.mjs [seed] [seconds] [difficulty] [vs]
//   vs=1 — второй ИИ играет за игрока 0 (ИИ против ИИ)
// ============================================================

import { Game } from '../js/game.js';
import { AIController } from '../js/ai.js';
import { findPath } from '../js/pathfinding.js';

const seed = process.argv[2] ? Number(process.argv[2]) : 12345;
const seconds = process.argv[3] ? Number(process.argv[3]) : 900;
const diff = process.argv[4] || 'normal';
const vs = process.argv[5] === '1';

console.log(`=== Симуляция: seed=${seed}, ${seconds}s, сложность=${diff}, ИИ-против-ИИ=${vs ? 'да' : 'нет'} ===`);

const game = new Game(seed, diff);
if (vs) game.aiP0 = new AIController(game, 0, 'normal');

const events = [];
game.onEvent = (type, data) => {
  if (type === 'toast') events.push({ t: game.time, type: 'toast', msg: data.msg });
  if (type === 'attackWarn') events.push({ t: game.time, type: 'attackWarn' });
  if (type === 'end') events.push({ t: game.time, type: 'end', result: data.result });
};

// проверка пути между базами
const [ps, es] = game.map.starts;
const path = findPath(game.map, ps.tx + 3, ps.ty, es.tx - 1, es.ty);
console.log(`Путь между базами: ${path ? path.length + ' точек' : 'НЕ НАЙДЕН!'}`);
if (!path) {
  console.error('FAIL: путь между базами не найден');
  process.exit(1);
}

const DT = 1 / 30;
let ticks = 0;
const maxTicks = Math.floor(seconds / DT);
let nextLog = 60;
let maxUnits0 = 0, maxArmy0 = 0;

for (; ticks < maxTicks; ticks++) {
  game.tick(DT);
  if (vs && !game.over) game.aiP0.tick(DT);
  if (game.over) break;

  if (vs) {
    maxUnits0 = Math.max(maxUnits0, game.unitsOf(0).length);
    maxArmy0 = Math.max(maxArmy0, game.unitsOf(0).filter(u => u.def.kind !== 'worker').length);
  }

  if (game.time >= nextLog) {
    nextLog += 60;
    const st0 = game.stats(0);
    const st1 = game.stats(1);
    console.log(`t=${Math.floor(game.time)}s | P: 💰${st0.gold} 🪵${st0.wood} 🧍${st0.used}/${st0.cap} юн:${game.unitsOf(0).length} зд:${game.buildingsOf(0).length} | ИИ: 💰${st1.gold} 🪵${st1.wood} 🧍${st1.used}/${st1.cap} юн:${game.unitsOf(1).length} зд:${game.buildingsOf(1).length}`);
  }
}

// ---------- отчёт ----------
console.log(`\nПрошло: ${Math.floor(game.time)}s (${ticks} тиков)`);
const dump = (ai) => {
  const m = {};
  for (const l of ai.log) m[l.type] = (m[l.type] || 0) + 1;
  return JSON.stringify(m);
};
console.log('ИИ-1 действия:', dump(game.ai));
if (vs) console.log('ИИ-0 действия:', dump(game.aiP0));
console.log('События:', events.length);
for (const e of events.slice(0, 30)) {
  console.log(`  t=${Math.floor(e.t)} ${e.type}${e.msg ? ' — ' + e.msg : ''}${e.result ? ' — ' + e.result : ''}`);
}
console.log(`Финал P:  💰${game.stats(0).gold} 🪵${game.stats(0).wood} юн:${game.unitsOf(0).length} зд:${game.buildingsOf(0).length}`);
console.log(`Финал ИИ: 💰${game.stats(1).gold} 🪵${game.stats(1).wood} юн:${game.unitsOf(1).length} зд:${game.buildingsOf(1).length}`);
console.log(`Результат: ${game.over || 'ничья по таймауту'}`);

// ---------- проверки ----------
const problems = [];
if (seconds >= 600 && !game.over) {
  const waves = game.ai.log.filter(l => l.type === 'wave').length;
  const barracks = game.ai.log.filter(l => l.type.startsWith('barracks')).length;
  if (waves === 0) problems.push('ИИ не послал ни одной волны');
  if (barracks === 0) problems.push('ИИ не построил казарму');
}
for (const e of game.entities) {
  if (!Number.isFinite(e.x) || !Number.isFinite(e.y) || Number.isNaN(e.hp)) {
    problems.push(`NaN у сущности ${e.id} (${e.def.key})`);
    break;
  }
}
const goldGrew = vs && maxUnits0 > 6 && maxArmy0 > 2;
if (vs && !goldGrew) problems.push(`экономика ИИ-0 не работала (макс юнитов ${maxUnits0}, макс армии ${maxArmy0})`);
if (vs && seconds >= 1200 && !game.over) {
  console.log('  (равная партия по таймауту — допустимо, но проверяем логи ИИ)');
}

if (problems.length) {
  console.error('\nFAIL:');
  problems.forEach(p => console.error(' -', p));
  process.exit(1);
}
console.log('\nOK: симуляция прошла без ошибок');
