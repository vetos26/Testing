#!/usr/bin/env python3
"""mod_check.py — валидатор контента движка RTS Engine.

Проверяет assets/mod/*.json на ошибки, которые сломают игру:
  - ссылки: trains -> units.json, buildMenu -> buildings.json,
    buildingAbbr -> buildings, спрайты фракций -> assets/sprites;
  - границы: старты баз на карте, размеры карты;
  - дубликаты ключей, обязательные поля, положительные значения.

Запуск: python3 tools/mod_check.py [dir_mod] [dir_sprites]
Код возврата 1 при ошибках (сборка прерывается).
"""
import json
import os
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOD_DIR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(REPO, 'app', 'src', 'main', 'assets', 'mod')
SPR_DIR = sys.argv[2] if len(sys.argv) > 2 else os.path.join(REPO, 'app', 'src', 'main', 'assets', 'sprites')

errors = []
warnings = []


def err(msg):
    errors.append(msg)


def warn(msg):
    warnings.append(msg)


def load(name, required=False):
    p = os.path.join(MOD_DIR, name)
    if not os.path.exists(p):
        if required:
            err(f'{name}: файл отсутствует (движок подставит встроенный контент!)')
        else:
            warn(f'{name}: отсутствует — будет использован встроенный fallback')
        return None
    try:
        with open(p, encoding='utf-8') as f:
            return json.load(f)
    except Exception as e:
        err(f'{name}: не читается JSON — {e}')
        return None


def dup_keys(items, name):
    seen = set()
    for it in items:
        k = it.get('key')
        if k in seen:
            err(f'{name}: дубликат ключа "{k}"')
        seen.add(k)
    return seen


# ---------- units.json ----------
units = load('units.json', required=True) or []
if not isinstance(units, list) or not units:
    err('units.json: должен быть непустым списком')
    units = []
unit_keys = dup_keys(units, 'units.json')
for u in units:
    k = u.get('key', '?')
    if not u.get('key'):
        err(f'units.json: юнит без key: {u}')
    if u.get('cost', 0) <= 0:
        err(f'units.json[{k}]: cost должен быть > 0')
    if u.get('hp', 0) <= 0:
        err(f'units.json[{k}]: hp должен быть > 0')
    if u.get('buildTime', 0) <= 0:
        err(f'units.json[{k}]: buildTime должен быть > 0')
    if u.get('radius', 0) <= 0:
        err(f'units.json[{k}]: radius должен быть > 0')

# ---------- buildings.json ----------
buildings = load('buildings.json', required=True) or []
if not isinstance(buildings, list) or not buildings:
    err('buildings.json: должен быть непустым списком')
    buildings = []
bkeys = dup_keys(buildings, 'buildings.json')
has_base = False
for b in buildings:
    k = b.get('key', '?')
    if not b.get('key'):
        err(f'buildings.json: здание без key: {b}')
    if b.get('cost', 0) <= 0:
        err(f'buildings.json[{k}]: cost должен быть > 0')
    sz = b.get('size', 2)
    if not (1 <= sz <= 5):
        err(f'buildings.json[{k}]: size {sz} вне диапазона 1..5')
    if b.get('hp', 0) <= 0:
        err(f'buildings.json[{k}]: hp должен быть > 0')
    for t in b.get('trains', []):
        if t not in unit_keys:
            err(f'buildings.json[{k}]: trains ссылается на несуществующего юнита "{t}"')
    if b.get('dropoff'):
        has_base = True
if not has_base:
    warn('buildings.json: ни у одного здания нет dropoff=true — шахтёры не смогут сдавать руду!')
if not any(b.get('powerGive', 0) > 0 for b in buildings):
    warn('buildings.json: нет источника энергии (powerGive > 0) — здания с powerUse не будут работать')

# ---------- difficulty.json ----------
diffs = load('difficulty.json', required=True) or []
if not isinstance(diffs, list) or not diffs:
    err('difficulty.json: должен быть непустым списком')
    diffs = []
dup_keys(diffs, 'difficulty.json')
has_normal = False
for d in diffs:
    if d.get('income', 0) <= 0:
        err(f'difficulty.json[{d.get("key", "?")}]: income должен быть > 0')
    if d.get('key') == 'normal':
        has_normal = True
if not has_normal:
    warn('difficulty.json: нет сложности с key="normal" — она используется по умолчанию')

# ---------- mod.json ----------
mod = load('mod.json', required=True) or {}
map_w = mod.get('mapW', 56)
map_h = mod.get('mapH', 56)
if not (16 <= map_w <= 160 and 16 <= map_h <= 160):
    err(f'mod.json: карта {map_w}x{map_h} вне разумного диапазона 16..160')
if mod.get('startCredits', 0) <= 0:
    err('mod.json: startCredits должен быть > 0')
if mod.get('unitCap', 0) <= 0:
    err('mod.json: unitCap должен быть > 0')

for key in mod.get('buildMenu', []):
    if key not in bkeys:
        err(f'mod.json: buildMenu ссылается на несуществующее здание "{key}"')

factions = mod.get('factions', [])
if len(factions) < 2:
    warn('mod.json: меньше 2 фракций — противник будет того же цвета')
f_sprites = []
for i, f in enumerate(factions):
    s = f.get('sprite', f'f{i}')
    f_sprites.append(s)
    for c in ('color', 'colorDark', 'colorLight'):
        v = f.get(c)
        if not (isinstance(v, list) and len(v) == 3 and all(isinstance(x, int) and 0 <= x <= 255 for x in v)):
            err(f'mod.json: фракция {i} ({f.get("name", "?")}): {c} должен быть [R,G,B] 0..255')

mg = mod.get('map', {})
starts = mg.get('starts', [])
if len(starts) < 2:
    err('mod.json: map.starts должно содержать минимум 2 стартовые точки')
pad = mg.get('baseClearR', 4) + 5
for i, (x, y) in enumerate(starts):
    if not (pad <= x < map_w - pad and pad <= y < map_h - pad):
        err(f'mod.json: старт {i} ({x},{y}) слишком близко к краю карты {map_w}x{map_h} (нужен отступ {pad})')
if len(starts) >= 2:
    (x1, y1), (x2, y2) = starts[0], starts[1]
    if abs(x1 - x2) + abs(y1 - y2) < 20:
        warn('mod.json: старты баз слишком близко (манхэттен < 20 тайлов)')

labels = mod.get('labels', {})
for key in labels.get('buildingAbbr', {}):
    if key not in bkeys:
        warn(f'mod.json: labels.buildingAbbr содержит ключ "{key}", которого нет в buildings.json')

# ---------- спрайты ----------
if os.path.isdir(SPR_DIR):
    sprites = set(f[:-4] for f in os.listdir(SPR_DIR) if f.endswith('.png'))
    for u in units:
        base = u.get('sprite') or u.get('key')
        for fs in f_sprites:
            for suffix in (fs, 'turret_' + fs if base == 'tank' else fs):
                name = f'{base}_{suffix}'
                if name not in sprites:
                    warn(f'спрайтов нет: {name}.png — юнит "{u.get("key")}" будет нарисован фигурами')
    for b in buildings:
        base = b.get('sprite') or b.get('key')
        if base == 'turret':
            for fs in f_sprites:
                for part in ('turret_base', 'turret_top'):
                    if f'{part}_{fs}' not in sprites:
                        warn(f'спрайтов нет: {part}_{fs}.png — турель будет нарисована фигурами')
        else:
            for fs in f_sprites:
                if f'{base}_{fs}' not in sprites:
                    warn(f'спрайтов нет: {base}_{fs}.png — здание "{b.get("key")}" будет нарисовано фигурами')
else:
    warn(f'каталог спрайтов не найден: {SPR_DIR}')

# ---------- итог ----------
for w in warnings:
    print('ПРЕДУПРЕЖДЕНИЕ:', w)
if errors:
    print(f'ОШИБКИ МОДА: {len(errors)}')
    for e in errors:
        print('  ', e)
    sys.exit(1)
print(f'Мод валиден: юнитов {len(unit_keys)}, зданий {len(bkeys)}, сложностей {len(diffs)},'
      f' фракций {len(factions)}; предупреждений: {len(warnings)}')
