// ============================================================
// config.js — все константы баланса и описания сущностей
// ============================================================

export const TILE = 32;
export const MAP_W = 64;
export const MAP_H = 64;
export const WORLD_W = MAP_W * TILE;
export const WORLD_H = MAP_H * TILE;

export const TERRAIN = {
  GRASS: 0,
  DIRT: 1,
  WATER: 2,
  TREE: 3,
  GOLD: 4,
};

// Сколько ресурса в одной клетке дерева / золотой россыпи
export const TREE_WOOD = 80;
export const MINE_GOLD = 1100;

export const START = {
  gold: 150,
  wood: 60,
  workers: 4,
};

export const SUPPLY = {
  CAP_MAX: 50,
  BASE_PROVIDES: 10,
  HOUSE_PROVIDES: 5,
};

// Добыча: вместимость переноски и время заполнения
export const HARVEST = {
  CAP: 10,
  GATHER_TIME: 2.0,
  RETARGET_RADIUS: 14, // в тайлах, при истощении источника
};

export const BUILD = {
  RATE: 1.0, // прогресс в секунду на одного строителя (делится на buildTime)
};

export const COMBAT = {
  ACQUIRE_SCAN: 0.25,    // период сканирования целей
  AUTO_SWITCH_RANGE: 3.5, // тайла: при атаке здания переключаться на юнитов рядом
  REPATH_TIME: 0.5,
};

export const PROJECTILE_SPEED = 280;

// ------------------------------------------------------------
// Юниты
// kind: 'worker' | 'melee' | 'ranged'
// ------------------------------------------------------------
export const UNIT_DEFS = {
  worker: {
    key: 'worker',
    name: 'Рабочий',
    icon: '⛏',
    kind: 'worker',
    hp: 45,
    armor: 0,
    speed: 88,
    dmg: 4,
    range: 14,
    atkCd: 1.0,
    sight: 6,
    radius: 9,
    cost: { gold: 50 },
    buildTime: 9,
    supply: 1,
    desc: 'Добывает ресурсы и строит здания',
  },
  soldier: {
    key: 'soldier',
    name: 'Мечник',
    icon: '⚔',
    kind: 'melee',
    hp: 115,
    armor: 1,
    speed: 78,
    dmg: 10,
    range: 16,
    atkCd: 1.05,
    sight: 6,
    radius: 10,
    cost: { gold: 60 },
    buildTime: 11,
    supply: 1,
    desc: 'Крепкий боец ближнего боя',
  },
  archer: {
    key: 'archer',
    name: 'Лучник',
    icon: '🏹',
    kind: 'ranged',
    hp: 60,
    armor: 0,
    speed: 82,
    dmg: 7,
    range: 4.4 * TILE,
    atkCd: 1.35,
    sight: 7,
    radius: 9,
    cost: { gold: 55, wood: 25 },
    buildTime: 13,
    supply: 1,
    projectile: true,
    desc: 'Стреляет издалека, уязвим в упор',
  },
};

// ------------------------------------------------------------
// Здания
// size — в тайлах (сторона)
// ------------------------------------------------------------
export const BUILDING_DEFS = {
  base: {
    key: 'base',
    name: 'Штаб',
    icon: '🏛',
    hp: 650,
    size: 3,
    sight: 8,
    dropoff: true,
    supply: SUPPLY.BASE_PROVIDES,
    trains: ['worker'],
    cost: { gold: 280, wood: 120 },
    buildTime: 30,
    desc: 'Производит рабочих, принимает ресурсы',
  },
  barracks: {
    key: 'barracks',
    name: 'Казарма',
    icon: '⛺',
    hp: 450,
    size: 3,
    sight: 6,
    trains: ['soldier', 'archer'],
    cost: { gold: 130, wood: 50 },
    buildTime: 22,
    desc: 'Готовит мечников и лучников',
  },
  house: {
    key: 'house',
    name: 'Дом',
    icon: '🏠',
    hp: 220,
    size: 2,
    sight: 4,
    supply: SUPPLY.HOUSE_PROVIDES,
    cost: { gold: 60, wood: 25 },
    buildTime: 11,
    desc: '+5 к лимиту снабжения',
  },
  turret: {
    key: 'turret',
    name: 'Башня',
    icon: '🗼',
    hp: 330,
    size: 2,
    sight: 8,
    dmg: 9,
    range: 5.2 * TILE,
    atkCd: 1.2,
    projectile: true,
    cost: { gold: 90, wood: 40 },
    buildTime: 15,
    desc: 'Автоматически обстреливает врагов',
  },
};

// Что может строить рабочий (порядок кнопок + хоткеи)
export const BUILD_MENU = [
  { key: 'base', hotkey: 'Q' },
  { key: 'barracks', hotkey: 'W' },
  { key: 'house', hotkey: 'E' },
  { key: 'turret', hotkey: 'R' },
];

// ------------------------------------------------------------
// Игроки
// ------------------------------------------------------------
export const PLAYER_DEFS = [
  { id: 0, name: 'Вы', color: '#4f8ef7', dark: '#2b5cb8', light: '#9cc4ff' },
  { id: 1, name: 'ИИ', color: '#e05555', dark: '#a02c2c', light: '#ff9c9c' },
];

// ------------------------------------------------------------
// Сложность ИИ
// ------------------------------------------------------------
export const DIFFICULTIES = {
  easy: { key: 'easy', name: 'Легко', income: 0.75, wave0: 7, waveStep: 4, waveCap: 14, turrets: 0, workersTarget: 8 },
  normal: { key: 'normal', name: 'Нормально', income: 1.0, wave0: 5, waveStep: 3, waveCap: 18, turrets: 1, workersTarget: 10 },
  hard: { key: 'hard', name: 'Сложно', income: 1.3, wave0: 5, waveStep: 2, waveCap: 22, turrets: 2, workersTarget: 12 },
};

export const QUEUE_MAX = 5;
