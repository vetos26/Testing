#!/usr/bin/env python3
"""gen_sprites.py — рисованные (painted) спрайты для «Красного радара».

Генерирует PNG в app/src/main/assets/sprites/:
  тайлы 64x64: grass0-2, dirt0-1, water0-1, ore0-1
  юниты 64x64 (носом вправо, +x): tank_red/blue (+turret), miner, rifle, rocket
  здания: base 96, power 64, barracks 96, factory 96, turret_base/top 64 — red/blue
Рисование в 2x суперсэмплинге, downscaled LANCZOS -> чистые контуры.
Запуск: python3 tools/gen_sprites.py
"""
import math
import os
import random
from PIL import Image, ImageDraw, ImageFilter

OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'sprites')
os.makedirs(OUT, exist_ok=True)
S = 2  # суперсэмплинг

# ---- палитра ----
RED, RED_D, RED_L = (201, 66, 58), (118, 32, 28), (236, 116, 102)
BLUE, BLUE_D, BLUE_L = (74, 120, 216), (34, 58, 124), (126, 170, 240)
METAL, METAL_D, METAL_L = (122, 126, 134), (74, 78, 86), (160, 164, 172)
TREAD, TREAD_D = (46, 44, 42), (30, 28, 26)
SAND, SAND_D = (196, 170, 120), (150, 126, 84)
CONC, CONC_D = (148, 146, 140), (100, 98, 94)
GOLD, GOLD_L = (242, 197, 61), (255, 224, 130)


def W(v):
    return max(1, int(round(v)))


def canvas(px):
    return Image.new('RGBA', (px * S, px * S), (0, 0, 0, 0))


def save(img, name):
    img = img.resize((img.width // S, img.height // S), Image.LANCZOS)
    img.save(os.path.join(OUT, name + '.png'))


def fac(owner):
    return RED if owner == 0 else BLUE


def fac_d(owner):
    return RED_D if owner == 0 else BLUE_D


def fac_l(owner):
    return RED_L if owner == 0 else BLUE_L


def scale_pts(pts, cx, cy, k):
    return [(cx + (x - cx) * k, cy + (y - cy) * k) for x, y in pts]


def poly(d, pts, fill, outline=None, ow=None):
    """Многоугольник с обводкой: тёмный контур снизу, заливка чуть меньше."""
    cx = sum(p[0] for p in pts) / len(pts)
    cy = sum(p[1] for p in pts) / len(pts)
    if outline:
        d.polygon(pts, fill=outline)
        if ow:
            inner = scale_pts(pts, cx, cy, 1.0 - 2.4 * ow / max(1, dist(pts, cx, cy)))
        else:
            inner = scale_pts(pts, cx, cy, 0.88)
        d.polygon(inner, fill=fill)
    else:
        d.polygon(pts, fill=fill)


def dist(pts, cx, cy):
    m = 0.0
    for x, y in pts:
        m = max(m, math.hypot(x - cx, y - cy))
    return max(1.0, m)


def star_pts(cx, cy, R, rot=-90.0):
    pts = []
    for i in range(10):
        r = R if i % 2 == 0 else R * 0.42
        a = math.radians(rot + i * 36.0)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return pts


def mottle(d, rnd, box, colors, n, amin=60, amax=130, rmin=3, rmax=11):
    x0, y0, x1, y1 = box
    for _ in range(n):
        col = rnd.choice(colors)
        rx = rnd.uniform(rmin, rmax)
        ry = rx * rnd.uniform(0.5, 1.0)
        x = rnd.uniform(x0, x1 - rx)
        y = rnd.uniform(y0, y1 - ry)
        d.ellipse([x, y, x + rx * 2, y + ry * 2],
                  fill=col + (rnd.randint(amin, amax),))


# =====================================================================
# ТАЙЛЫ 64x64 (мир: 32px, рисуем 64 -> чисто при зуме)
# =====================================================================

def tile_grass(variant, rnd):
    img = canvas(64)
    d = ImageDraw.Draw(img)
    base = (74, 112, 58)
    d.rectangle([0, 0, 127, 127], fill=base + (255,))
    mottle(d, rnd, (0, 0, 128, 128),
           [(88, 128, 66), (62, 96, 48), (98, 140, 70), (54, 86, 42), (80, 118, 62)], 85, 50, 110, 2, 6)
    # травинки
    for _ in range(34):
        x = rnd.uniform(2, 126)
        y = rnd.uniform(2, 124)
        ln = rnd.uniform(3, 7) * S
        col = (48, 78, 38) if rnd.random() < 0.5 else (112, 152, 82)
        d.line([x, y, x + rnd.uniform(-1.5, 1.5) * S, y - ln], fill=col + (180,), width=S)
    # камушки / цветочки
    for _ in range(2 + variant):
        x = rnd.uniform(10, 118)
        y = rnd.uniform(10, 118)
        if rnd.random() < 0.5:  # камушек
            r = rnd.uniform(3, 5) * S
            d.ellipse([x - r, y - r * 0.7, x + r, y + r * 0.7], fill=(126, 124, 116, 255))
            d.ellipse([x - r * 0.5, y - r * 0.55, x + r * 0.25, y - r * 0.05],
                      fill=(158, 156, 148, 220))
        else:  # цветок
            r = 2.2 * S
            d.ellipse([x - r, y - r, x + r, y + r], fill=(232, 218, 96, 235))
            d.point([x, y], fill=(150, 110, 40, 255))
    save(img, f'grass{variant}')


def tile_dirt(variant, rnd):
    img = canvas(64)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 127, 127], fill=(138, 109, 70, 255))
    mottle(d, rnd, (0, 0, 128, 128),
           [(120, 92, 58), (152, 124, 82), (110, 84, 52), (160, 132, 90)], 40)
    # трещинки
    for _ in range(5):
        x = rnd.uniform(6, 110)
        y = rnd.uniform(6, 110)
        pts = [(x, y)]
        for _ in range(3):
            x += rnd.uniform(-14, 16) * S
            y += rnd.uniform(2, 12) * S
            pts.append((x, y))
        d.line(pts, fill=(96, 72, 44, 190), width=S)
    # камни
    for _ in range(4 + variant * 2):
        x = rnd.uniform(8, 120)
        y = rnd.uniform(8, 120)
        r = rnd.uniform(3, 6) * S
        d.ellipse([x - r, y - r * 0.75, x + r, y + r * 0.75], fill=(112, 104, 94, 255))
        d.ellipse([x - r * 0.55, y - r * 0.6, x + r * 0.2, y - r * 0.1],
                  fill=(146, 140, 130, 230))
    save(img, f'dirt{variant}')


def tile_water(variant, rnd):
    img = canvas(64)
    d = ImageDraw.Draw(img)
    # вертикальный градиент глубины
    top, bot = (32, 84, 138), (22, 62, 106)
    for yy in range(128):
        k = yy / 127.0
        col = tuple(int(top[i] + (bot[i] - top[i]) * k) for i in range(3))
        d.line([0, yy, 128, yy], fill=col + (255,))
    # широкие полосы течения
    for _ in range(3):
        y = rnd.uniform(10, 118)
        d.rounded_rectangle([0, y * S, 128, (y + rnd.uniform(6, 14)) * S],
                            radius=W(2 * S),
                            fill=(46, 98, 152, rnd.randint(60, 110)))
    # блики-волны
    for _ in range(9 + variant * 2):
        x = rnd.uniform(4, 100)
        y = rnd.uniform(6, 122)
        w = rnd.uniform(8, 22) * S
        col = (96, 156, 206) if rnd.random() < 0.7 else (188, 222, 244)
        d.rounded_rectangle([x, y, x + w, y + 2.2 * S], radius=W(1.1 * S),
                            fill=col + (rnd.randint(120, 200),))
    save(img, f'water{variant}')


def tile_ore(variant, rnd):
    img = canvas(64)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, 127, 127], fill=(101, 82, 55, 255))
    mottle(d, rnd, (0, 0, 128, 128),
           [(86, 68, 44), (116, 94, 62), (78, 62, 40)], 30)
    # рудные валы
    for _ in range(3 + variant):
        x = rnd.uniform(14, 114)
        y = rnd.uniform(14, 114)
        r = rnd.uniform(10, 17) * S
        pts = []
        n = rnd.randint(6, 8)
        for i in range(n):
            a = 2 * math.pi * i / n + rnd.uniform(-0.2, 0.2)
            rr_ = r * rnd.uniform(0.75, 1.1)
            pts.append((x + rr_ * math.cos(a), y + rr_ * math.sin(a) * 0.85))
        poly(d, pts, (112, 114, 120), outline=(62, 64, 70), ow=2.4 * S)
        # грань-блик
        hi = scale_pts(pts, x, y, 0.55)
        d.polygon([(px, py - r * 0.18) for px, py in hi], fill=(146, 148, 154, 200))
        # золотые вкрапления
        for _ in range(4 + rnd.randint(0, 3)):
            gx = x + rnd.uniform(-r * 0.6, r * 0.6)
            gy = y + rnd.uniform(-r * 0.55, r * 0.55)
            gr = rnd.uniform(1.6, 2.8) * S
            d.ellipse([gx - gr, gy - gr, gx + gr, gy + gr], fill=GOLD + (255,))
            d.point([gx - gr * 0.3, gy - gr * 0.3], fill=GOLD_L + (255,))
    save(img, f'ore{variant}')


# =====================================================================
# ЮНИТЫ 64x64, носом вправо (+x), центр (32,32)
# =====================================================================

def tread_belt(d, x0, y0, x1, y1, rnd):
    d.rounded_rectangle([x0, y0, x1, y1], radius=W(4 * S), fill=TREAD + (255,),
                        outline=(24, 22, 20, 255), width=S)
    x = x0 + 3 * S
    while x < x1 - 2 * S:
        d.line([x, y0 + S, x, y1 - S], fill=TREAD_D + (255,), width=S)
        x += 5.5 * S


def unit_tank_hull(owner, rnd):
    img = canvas(64)
    d = ImageDraw.Draw(img)
    main, dark, light = fac(owner), fac_d(owner), fac_l(owner)
    # гусеницы
    tread_belt(d, 8 * S, 9 * S, 58 * S, 22 * S, rnd)
    tread_belt(d, 8 * S, 42 * S, 58 * S, 55 * S, rnd)
    # корпус с наклонным носом
    hull = [(11 * S, 20 * S), (40 * S, 20 * S), (55 * S, 24 * S),
            (58 * S, 32 * S), (55 * S, 40 * S), (40 * S, 44 * S), (11 * S, 44 * S)]
    poly(d, hull, main, outline=dark, ow=2.2 * S)
    # кормовая решётка
    d.rounded_rectangle([12 * S, 23 * S, 26 * S, 41 * S], radius=W(2 * S),
                        fill=dark + (255,))
    for yy in range(25, 40, 4):
        d.line([13 * S, yy * S, 25 * S, yy * S], fill=(30, 28, 26, 255), width=S)
    # палуба-панель
    d.rounded_rectangle([27 * S, 22 * S, 44 * S, 42 * S], radius=W(2 * S),
                        fill=light + (255,), outline=dark, width=S)
    # носовой клин-блик
    poly(d, [(45 * S, 24 * S), (54 * S, 27 * S), (56 * S, 32 * S),
             (54 * S, 37 * S), (45 * S, 40 * S)], light, outline=dark, ow=1.6 * S)
    # лючок
    d.ellipse([30 * S, 27 * S, 40 * S, 37 * S], fill=main + (255,),
              outline=dark, width=S)
    save(img, f'tank_{fac_name(owner)}')


def unit_tank_turret(owner, rnd):
    img = canvas(64)
    d = ImageDraw.Draw(img)
    main, dark, light = fac(owner), fac_d(owner), fac_l(owner)
    # ствол (вправо)
    d.rounded_rectangle([30 * S, 28.5 * S, 60 * S, 35.5 * S], radius=W(2 * S),
                        fill=METAL_D + (255,), outline=(30, 28, 26, 255), width=S)
    d.rounded_rectangle([30 * S, 29.6 * S, 58 * S, 31.6 * S], radius=W(1 * S),
                        fill=(96, 100, 108, 255))
    # дульный тормоз
    d.rounded_rectangle([56 * S, 27 * S, 62 * S, 37 * S], radius=W(1.6 * S),
                        fill=METAL + (255,), outline=(30, 28, 26, 255), width=S)
    # башня
    d.ellipse([16 * S, 14 * S, 48 * S, 50 * S], fill=dark + (255,))
    d.ellipse([18 * S, 16 * S, 46 * S, 48 * S], fill=main + (255,))
    # блик слева-сверху
    d.pieslice([18 * S, 16 * S, 46 * S, 48 * S], 150, 240, fill=light + (200,))
    # командирский лючок
    d.ellipse([26 * S, 24 * S, 36 * S, 34 * S], fill=dark + (255,),
              outline=(30, 28, 26, 255), width=S)
    d.ellipse([28 * S, 26 * S, 33 * S, 31 * S], fill=light + (220,))
    save(img, 'tank_turret_' + fac_name(owner))


def unit_miner(owner, rnd):
    img = canvas(64)
    d = ImageDraw.Draw(img)
    main, dark, light = fac(owner), fac_d(owner), fac_l(owner)
    # колёса
    for wx in (14, 24, 34, 44):
        for wy in (18, 46):
            d.ellipse([(wx - 3.4) * S, (wy - 3.4) * S, (wx + 3.4) * S, (wy + 3.4) * S],
                      fill=(28, 26, 24, 255), outline=(14, 13, 12, 255), width=S)
            d.ellipse([(wx - 1.7) * S, (wy - 1.7) * S, (wx + 1.7) * S, (wy + 1.7) * S],
                      fill=METAL_D + (255,))
    # кузов с рудой
    d.rounded_rectangle([8 * S, 20 * S, 34 * S, 44 * S], radius=W(2.5 * S),
                        fill=dark + (255,), outline=(24, 22, 20, 255), width=W(1.8 * S))
    d.rounded_rectangle([10 * S, 22 * S, 32 * S, 42 * S], radius=W(1.6 * S),
                        fill=METAL_D + (255,))
    for _ in range(6):
        gx = rnd.uniform(11, 29)
        gy = rnd.uniform(23, 40)
        gr = rnd.uniform(1.2, 2.2) * S
        d.ellipse([(gx - gr) * 1, (gy - gr) * 1, (gx + gr) * 1, (gy + gr) * 1],
                  fill=GOLD + (255,))
    # кабина
    d.rounded_rectangle([34 * S, 18 * S, 50 * S, 46 * S], radius=W(3 * S),
                        fill=main + (255,), outline=dark, width=W(2 * S))
    d.rounded_rectangle([38 * S, 21 * S, 47 * S, 43 * S], radius=W(2 * S),
                        fill=(58, 74, 92, 255), outline=dark, width=S)
    d.line([42.5 * S, 21 * S, 42.5 * S, 43 * S], fill=dark + (255,), width=S)
    # ковш-долото спереди
    poly(d, [(50 * S, 20 * S), (60 * S, 25 * S), (60 * S, 39 * S), (50 * S, 44 * S)],
         METAL, outline=(30, 28, 26), ow=1.8 * S)
    d.line([52 * S, 26 * S, 58 * S, 30 * S], fill=METAL_L + (220,), width=W(1.6 * S))
    save(img, 'miner_' + fac_name(owner))


def _infantry_base(d, owner):
    main, dark, light = fac(owner), fac_d(owner), fac_l(owner)
    # плечи
    d.ellipse([20 * S, 21 * S, 44 * S, 43 * S], fill=dark + (255,))
    d.ellipse([21.5 * S, 22.5 * S, 42.5 * S, 41.5 * S], fill=main + (255,))
    # руки вперёд
    d.ellipse([36 * S, 17 * S, 48 * S, 26 * S], fill=main + (255,),
              outline=dark, width=S)
    d.ellipse([36 * S, 38 * S, 48 * S, 47 * S], fill=main + (255,),
              outline=dark, width=S)
    return main, dark, light


def unit_rifle(owner, rnd):
    img = canvas(64)
    d = ImageDraw.Draw(img)
    main, dark, light = _infantry_base(d, owner)
    # винтовка вправо
    d.rounded_rectangle([40 * S, 29.5 * S, 58 * S, 34.5 * S], radius=W(1.4 * S),
                        fill=(52, 50, 48, 255), outline=(24, 22, 20, 255), width=S)
    d.rounded_rectangle([36 * S, 31 * S, 44 * S, 36 * S], radius=W(1.2 * S),
                        fill=(84, 62, 40, 255))
    # шлем
    d.ellipse([24 * S, 23 * S, 40 * S, 39 * S], fill=dark + (255,),
              outline=(24, 22, 20, 255), width=W(1.6 * S))
    d.pieslice([25 * S, 24 * S, 39 * S, 38 * S], 150, 250, fill=light + (190,))
    d.ellipse([29 * S, 28 * S, 35 * S, 34 * S], fill=dark + (255,))
    save(img, 'rifle_' + fac_name(owner))


def unit_rocket(owner, rnd):
    img = canvas(64)
    d = ImageDraw.Draw(img)
    main, dark, light = _infantry_base(d, owner)
    # пусковая труба на плече
    d.rounded_rectangle([34 * S, 21 * S, 58 * S, 31 * S], radius=W(2.4 * S),
                        fill=METAL_D + (255,), outline=(24, 22, 20, 255), width=W(1.8 * S))
    d.rounded_rectangle([54 * S, 20 * S, 59 * S, 32 * S], radius=W(1.4 * S),
                        fill=(186, 60, 52, 255), outline=(24, 22, 20, 255), width=S)
    # ранец
    d.rounded_rectangle([16 * S, 24 * S, 23 * S, 40 * S], radius=W(1.6 * S),
                        fill=dark + (255,), outline=(24, 22, 20, 255), width=S)
    # шлем
    d.ellipse([25 * S, 26 * S, 41 * S, 42 * S], fill=dark + (255,),
              outline=(24, 22, 20, 255), width=W(1.6 * S))
    d.pieslice([26 * S, 27 * S, 40 * S, 41 * S], 140, 250, fill=light + (190,))
    save(img, 'rocket_' + fac_name(owner))


def fac_name(owner):
    return 'red' if owner == 0 else 'blue'


# =====================================================================
# ЗДАНИЯ (red/blue), pad = бетонная плита
# =====================================================================

def pad(d, size, rnd):
    d.rounded_rectangle([2 * S, 2 * S, size - 2 * S, size - 2 * S],
                        radius=W(6 * S), fill=CONC + (255,), outline=CONC_D + (255,),
                        width=W(2 * S))
    # болты по углам
    off = 7 * S
    for bx, by in ((off, off), (size - off, off), (off, size - off), (size - off, size - off)):
        d.ellipse([bx - 1.6 * S, by - 1.6 * S, bx + 1.6 * S, by + 1.6 * S],
                  fill=CONC_D + (255,))
    # пятна на бетоне
    mottle(d, rnd, (6, 6, size - 6, size - 6), [(132, 130, 124), (162, 160, 154)], 10, 40, 90, 4, 10)


def sandbags(d, rnd, size, corners=True):
    for cx, cy in ((14, 14), (size - 14, 14), (14, size - 14), (size - 14, size - 14)) if corners else ():
        for k in range(3):
            a = k * 1.05 + rnd.uniform(-0.2, 0.2)
            bx = cx + math.cos(a) * 5.5 * S
            by = cy + math.sin(a) * 5.5 * S
            r = 3.4 * S
            d.ellipse([bx - r, by - r * 0.75, bx + r, by + r * 0.75],
                      fill=SAND + (255,), outline=SAND_D + (255,), width=S)


def building_base(owner, rnd):
    sz = 96
    img = canvas(sz)
    d = ImageDraw.Draw(img)
    main, dark, light = fac(owner), fac_d(owner), fac_l(owner)
    pad(d, sz, rnd)
    sandbags(d, rnd, sz)
    # главный блок
    d.rounded_rectangle([14 * S, 14 * S, 82 * S, 82 * S], radius=W(6 * S),
                        fill=dark + (255,), outline=(30, 28, 26, 255), width=W(2.2 * S))
    d.rounded_rectangle([17 * S, 17 * S, 79 * S, 79 * S], radius=W(5 * S),
                        fill=main + (255,))
    # внутренняя тень снизу
    d.rounded_rectangle([17 * S, 52 * S, 79 * S, 79 * S], radius=W(5 * S),
                        fill=dark + (140,))
    d.rounded_rectangle([17 * S, 17 * S, 79 * S, 50 * S], radius=W(5 * S),
                        fill=main + (255,))
    # панели крыши
    for yy in (24, 34, 44):
        d.line([20 * S, yy * S, 76 * S, yy * S], fill=dark + (150,), width=W(1.4 * S))
    # звезда фракции в центре
    star_col = GOLD if owner == 0 else (240, 244, 250)
    poly(d, star_pts(48 * S, 38 * S, 12 * S), star_col + (255,), outline=(30, 28, 26), ow=1.8 * S)
    # дверь снизу
    d.rounded_rectangle([40 * S, 62 * S, 56 * S, 80 * S], radius=W(2 * S),
                        fill=(52, 50, 48, 255), outline=(24, 22, 20, 255), width=W(1.6 * S))
    d.line([48 * S, 64 * S, 48 * S, 79 * S], fill=(30, 28, 26, 255), width=W(1.4 * S))
    # антенна
    d.line([70 * S, 22 * S, 74 * S, 10 * S], fill=METAL_D + (255,), width=W(2 * S))
    d.ellipse([72 * S, 6 * S, 78 * S, 12 * S], fill=(220, 60, 50, 255),
              outline=(24, 22, 20, 255), width=S)
    save(img, 'base_' + fac_name(owner))


def building_power(owner, rnd):
    sz = 64
    img = canvas(sz)
    d = ImageDraw.Draw(img)
    main, dark = fac(owner), fac_d(owner)
    pad(d, sz, rnd)
    # корпус-реактор
    d.ellipse([10 * S, 10 * S, 54 * S, 54 * S], fill=dark + (255,),
              outline=(30, 28, 26, 255), width=W(2 * S))
    d.ellipse([13 * S, 13 * S, 51 * S, 51 * S], fill=METAL_D + (255,))
    # кольца турбины
    for r in (17, 13):
        d.ellipse([(32 - r) * S, (32 - r) * S, (32 + r) * S, (32 + r) * S],
                  outline=METAL + (255,), width=W(2 * S))
    # светящееся ядро
    d.ellipse([22 * S, 22 * S, 42 * S, 42 * S], fill=(250, 200, 70, 255),
              outline=(160, 110, 30, 255), width=W(1.8 * S))
    d.ellipse([27 * S, 27 * S, 37 * S, 37 * S], fill=GOLD_L + (255,))
    # трубы к углам
    for ang in (45, 135, 225, 315):
        a = math.radians(ang)
        x0 = 32 * S + math.cos(a) * 20 * S
        y0 = 32 * S + math.sin(a) * 20 * S
        x1 = 32 * S + math.cos(a) * 27 * S
        y1 = 32 * S + math.sin(a) * 27 * S
        d.line([x0, y0, x1, y1], fill=main + (255,), width=W(3.4 * S))
    save(img, 'power_' + fac_name(owner))


def building_barracks(owner, rnd):
    sz = 96
    img = canvas(sz)
    d = ImageDraw.Draw(img)
    main, dark, light = fac(owner), fac_d(owner), fac_l(owner)
    pad(d, sz, rnd)
    # крыша-камуфляж
    d.rounded_rectangle([12 * S, 24 * S, 84 * S, 72 * S], radius=W(5 * S),
                        fill=(84, 94, 62, 255), outline=(30, 28, 26, 255), width=W(2.2 * S))
    camo = [(70, 80, 50), (102, 112, 74), (58, 68, 44)]
    mottle(d, rnd, (14, 26, 82, 70), camo, 26, 120, 200, 4, 10)
    # полоса фракции
    d.rounded_rectangle([12 * S, 44 * S, 84 * S, 52 * S], radius=W(2 * S),
                        fill=main + (255,), outline=(30, 28, 26, 255), width=W(1.6 * S))
    # дверь с козырьком
    d.rounded_rectangle([38 * S, 56 * S, 58 * S, 76 * S], radius=W(2 * S),
                        fill=(52, 50, 48, 255), outline=(24, 22, 20, 255), width=W(1.6 * S))
    d.rounded_rectangle([36 * S, 54 * S, 60 * S, 58 * S], radius=W(1.6 * S),
                        fill=light + (255,), outline=(30, 28, 26, 255), width=S)
    save(img, 'barracks_' + fac_name(owner))


def building_factory(owner, rnd):
    sz = 96
    img = canvas(sz)
    d = ImageDraw.Draw(img)
    main, dark, light = fac(owner), fac_d(owner), fac_l(owner)
    pad(d, sz, rnd)
    # корпус
    d.rounded_rectangle([12 * S, 16 * S, 84 * S, 80 * S], radius=W(5 * S),
                        fill=dark + (255,), outline=(30, 28, 26, 255), width=W(2.2 * S))
    d.rounded_rectangle([15 * S, 19 * S, 81 * S, 77 * S], radius=W(4 * S),
                        fill=main + (255,))
    d.rounded_rectangle([15 * S, 19 * S, 81 * S, 48 * S], radius=W(4 * S),
                        fill=light + (255,))
    # крыша-полосы
    for yy in (26, 34, 42):
        d.rounded_rectangle([20 * S, yy * S, 76 * S, (yy + 4) * S], radius=W(1.6 * S),
                            fill=METAL + (255,), outline=(30, 28, 26, 255), width=S)
    # труба
    d.ellipse([18 * S, 24 * S, 34 * S, 40 * S], fill=METAL_D + (255,),
              outline=(24, 22, 20, 255), width=W(1.8 * S))
    d.ellipse([22 * S, 28 * S, 30 * S, 36 * S], fill=(40, 38, 36, 255))
    # ворота с предупреждением
    d.rounded_rectangle([34 * S, 58 * S, 62 * S, 78 * S], radius=W(2 * S),
                        fill=(52, 50, 48, 255), outline=(24, 22, 20, 255), width=W(1.6 * S))
    for i in range(5):
        x = 36 + i * 5
        d.line([x * S, 76 * S, (x + 3) * S, 60 * S], fill=GOLD + (230,), width=W(2 * S))
    save(img, 'factory_' + fac_name(owner))


def building_turret_base(owner, rnd):
    sz = 64
    img = canvas(sz)
    d = ImageDraw.Draw(img)
    pad(d, sz, rnd)
    sandbags(d, rnd, sz, corners=False)
    # бетонное кольцо
    d.ellipse([10 * S, 10 * S, 54 * S, 54 * S], fill=CONC_D + (255,),
              outline=(30, 28, 26, 255), width=W(2 * S))
    d.ellipse([13 * S, 13 * S, 51 * S, 51 * S], fill=CONC + (255,))
    # болты по кругу
    for i in range(8):
        a = 2 * math.pi * i / 8
        bx = 32 * S + math.cos(a) * 15 * S
        by = 32 * S + math.sin(a) * 15 * S
        d.ellipse([bx - 1.3 * S, by - 1.3 * S, bx + 1.3 * S, by + 1.3 * S],
                  fill=CONC_D + (255,))
    save(img, 'turret_base_' + fac_name(owner))


def building_turret_top(owner, rnd):
    sz = 64
    img = canvas(sz)
    d = ImageDraw.Draw(img)
    main, dark, light = fac(owner), fac_d(owner), fac_l(owner)
    # спаренные стволы вправо
    for dy in (-3.2, 3.2):
        d.rounded_rectangle([28 * S, (32 + dy - 1.6) * S, 58 * S, (32 + dy + 1.6) * S],
                            radius=W(1.4 * S), fill=METAL_D + (255,),
                            outline=(24, 22, 20, 255), width=S)
    d.rounded_rectangle([54 * S, 25 * S, 60 * S, 39 * S], radius=W(1.6 * S),
                        fill=METAL + (255,), outline=(24, 22, 20, 255), width=S)
    # бронекупол
    d.ellipse([18 * S, 16 * S, 46 * S, 48 * S], fill=dark + (255,))
    d.ellipse([20 * S, 18 * S, 44 * S, 46 * S], fill=main + (255,))
    d.pieslice([20 * S, 18 * S, 44 * S, 46 * S], 150, 240, fill=light + (200,))
    # ящик боеприпасов сзади
    d.rounded_rectangle([12 * S, 25 * S, 21 * S, 39 * S], radius=W(1.6 * S),
                        fill=dark + (255,), outline=(24, 22, 20, 255), width=S)
    save(img, 'turret_top_' + fac_name(owner))


# =====================================================================

def main():
    for v in range(3):
        tile_grass(v, random.Random(100 + v))
    for v in range(2):
        tile_dirt(v, random.Random(200 + v))
        tile_water(v, random.Random(300 + v))
        tile_ore(v, random.Random(400 + v))
    for o in (0, 1):
        unit_tank_hull(o, random.Random(500 + o))
        unit_tank_turret(o, random.Random(600 + o))
        unit_miner(o, random.Random(700 + o))
        unit_rifle(o, random.Random(800 + o))
        unit_rocket(o, random.Random(900 + o))
        building_base(o, random.Random(1000 + o))
        building_power(o, random.Random(1100 + o))
        building_barracks(o, random.Random(1200 + o))
        building_factory(o, random.Random(1300 + o))
        building_turret_base(o, random.Random(1400 + o))
        building_turret_top(o, random.Random(1500 + o))
    # контрольный лист
    names = sorted(f[:-4] for f in os.listdir(OUT) if f.endswith('.png'))
    cols = 8
    cell = 100
    rows = (len(names) + cols - 1) // cols
    sheet = Image.new('RGBA', (cols * cell, rows * cell), (40, 44, 52, 255))
    dd = ImageDraw.Draw(sheet)
    for i, n in enumerate(names):
        im = Image.open(os.path.join(OUT, n + '.png')).convert('RGBA')
        k = min(90 / im.width, 90 / im.height)
        im = im.resize((max(1, int(im.width * k)), max(1, int(im.height * k))), Image.LANCZOS)
        x = (i % cols) * cell + (cell - im.width) // 2
        y = (i // cols) * cell + (cell - im.height) // 2
        sheet.paste(im, (x, y), im)
        dd.text(((i % cols) * cell + 4, (i // cols) * cell + 2), n, fill=(255, 255, 255, 255))
    sheet.save('/tmp/sprites_preview.png')
    total = sum(os.path.getsize(os.path.join(OUT, f)) for f in os.listdir(OUT))
    print(f'спрайтов: {len(names)}, общий размер: {total} байт')


if __name__ == '__main__':
    main()
