#!/usr/bin/env python3
"""check_structure.py — структурные проверки dex, без которых ART падает
VerifyError/ClassNotFound: для каждого класса внутри dex:
  1) суперкласс существует (в dex или внешний) и НЕ final;
  2) интерфейсы существуют и являются интерфейсами;
  3) нет java.lang.invoke (invokedynamic остался).
Запуск: python3 check_structure.py <apk>
Выход: код 1 при проблемах.
"""
import zipfile, struct, sys

z = zipfile.ZipFile(sys.argv[1])
dex = z.read('classes.dex')

def u32(o): return struct.unpack_from('<I', dex, o)[0]
def u16(o): return struct.unpack_from('<H', dex, o)[0]
sids_o, tids_o = u32(0x3c), u32(0x44)
cdefs_n, cdefs_o = u32(0x60), u32(0x64)

def uleb(o):
    n = 0; shift = 0
    while True:
        b = dex[o]; o += 1
        n |= (b & 0x7f) << shift
        shift += 7
        if not b & 0x80: break
    return n, o

def getstr(i):
    o = u32(sids_o + 4*i)
    n, o2 = uleb(o)
    return dex[o2:o2+n].decode('utf-8', 'replace')

def gettype(i): return getstr(u32(tids_o + 4*i))

ACC_FINAL = 0x10
ACC_INTERFACE = 0x200

cls = {}   # name -> (access_flags, super, [ifaces])
for c in range(cdefs_n):
    o = cdefs_o + 32*c
    cn = gettype(u32(o))
    acc = u32(o+4)
    s = u32(o+8)
    sup = gettype(s) if s != 0xFFFFFFFF else None
    ioff = u32(o+12)
    ifaces = []
    if ioff:
        n = u32(ioff)
        ifaces = [gettype(u16(ioff+4+2*j)) for j in range(n)]
    cls[cn] = (acc, sup, ifaces)

def is_external(name):
    return name.startswith('Landroid/') or name.startswith('Ljava/') or name.startswith('Ljavax/')

problems = []
for cn, (acc, sup, ifaces) in cls.items():
    if sup is not None:
        if is_external(sup):
            pass  # проверены против API 24 отдельно
        elif sup not in cls:
            problems.append(f'{cn}: суперкласс {sup} отсутствует в dex')
        elif cls[sup][0] & ACC_FINAL:
            problems.append(f'{cn}: суперкласс {sup} объявлен final (VerifyError)')
    for it in ifaces:
        if is_external(it):
            continue
        if it not in cls:
            problems.append(f'{cn}: интерфейс {it} отсутствует в dex')
        elif not cls[it][0] & ACC_INTERFACE:
            problems.append(f'{cn}: {it} не является интерфейсом')

if dex.count(b'Ljava/lang/invoke/'):
    problems.append('в dex остались ссылки java.lang.invoke (invokedynamic)')

if problems:
    print('СТРУКТУРНЫЕ ПРОБЛЕМЫ:', len(problems))
    for p in problems[:40]:
        print('  ', p)
    sys.exit(1)
print(f'структура OK: {len(cls)} классов, суперклассы и интерфейсы разрешаемы, финальности нет')
