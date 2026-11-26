#!/usr/bin/env python3
"""clean_dex.py — дексация с итеративным удалением классов, содержащих
invokedynamic. dx 1.16 не умеет десугарить invokedynamic при
--min-sdk-version < 26, поэтому классы stdlib с indy (например,
kotlin.comparisons.ComparisonsKt__ComparisonsKt и
kotlin.streams.jdk8.StreamsKt в stdlib 1.9.25) приходится исключать из
байткода; их замещают чистые заглушки из tools/stubs (пакет kt1lin —
той же длины, что kotlin/, после компиляции байты kt1lin/ -> kotlin/).

Запуск: JAVA=<java_home> python3 tools/clean_dex.py <work_dir> <out_dir> <base_jar> <output_dex> [prefixes...]
Итоговые файлы: <work_dir>/stdlib_iter.jar (jar без indy-классов), <output_dex>.
"""
import zipfile, os, subprocess, sys, shutil

JAVA = os.environ.get('JAVA')
assert JAVA, 'нужна переменная JAVA (jdk4py JAVA_HOME)'

WORK = sys.argv[1]
OUT = sys.argv[2]
BASE_JAR = sys.argv[3]
OUT_DEX = sys.argv[4]
# префиксы имён классов, всегда исключаемых из jar (заменены заглушками)
removed = set(sys.argv[5:]) if len(sys.argv) > 5 else set()

CUR = f'{WORK}/stdlib_iter.jar'
DX_JAR = f'{WORK}/dx.jar'

def excluded(n):
    return any(n.startswith(p) for p in removed)

def make_jar():
    zin = zipfile.ZipFile(BASE_JAR)
    zout = zipfile.ZipFile(CUR, 'w', zipfile.ZIP_DEFLATED)
    for it in zin.infolist():
        n = it.filename
        if excluded(n):
            continue
        zout.writestr(n, zin.read(n))
    zout.close()

for attempt in range(30):
    make_jar()
    r = subprocess.run(
        [f'{JAVA}/bin/java', '-Xmx1g', '-cp', DX_JAR,
         'com.xiaoyv.dx.command.Main', '--dex', '--min-sdk-version=24',
         '--output', OUT_DEX, OUT, CUR],
        capture_output=True, text=True)
    err = r.stderr + r.stdout
    if r.returncode == 0 and 'Uncaught translation error' not in err:
        print(f'итерация {attempt}: дексация успешна')
        break
    bad = set()
    for line in err.splitlines():
        if 'invalid opcode ba' in line or 'invokedynamic' in line:
            # формат: ERROR in <fqcn>.<method>:<desc>: ... — класс = всё до последней точки
            if 'ERROR in ' in line:
                cls = line.split('ERROR in ')[1].split(':')[0].strip()
                cls = cls.rsplit('.', 1)[0]
                bad.add(cls.replace('.', '/') + '.class')
        elif 'already added' in line and '.class' not in line:
            # дубликат между out и jar — удалить копию из jar
            name = line.split('already added: L')[1].rstrip(';') + '.class'
            bad.add(name.replace('.', '/') if '/' not in name else name)
    if not bad:
        print('ошибки без имён классов:', err[:800])
        sys.exit(1)
    print(f'итерация {attempt}: исключаю {len(bad)}: {sorted(bad)[:4]}')
    removed |= bad
else:
    print('Не сошлось за 30 итераций')
    sys.exit(1)

with open(OUT_DEX, 'rb') as f:
    magic = f.read(8)
print('dex magic:', magic)
assert magic[:5] == b'dex\n0', 'невозможный dex'
assert magic[5:8] < b'038', 'dex >= 038 не ставится на Android 5-7.x'
print('исключено всего:', len(removed))
