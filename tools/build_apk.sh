#!/usr/bin/env bash
# ============================================================
# build_apk.sh — воспроизводимая сборка APK без Android SDK.
# Все инструменты берутся с разрешённых хостов:
#   JDK      — PyPI: jdk4py (Temurin JRE 25)
#   kotlinc  — npm:  kotlin-compiler 2.4.21 + kotlin-stdlib 1.9.25 (stdlib 2.x
#              содержит invokedynamic; app собирается с -no-stdlib и -Xlambdas=class)
#   android.jar — GitHub: Sable/android-platforms (API 34, через blobs API)
#   dx       — GitHub: xiaoyvyv/android-dx (dx 1.16, blobs API, --min-sdk-version=24)
#   stubs    — tools/stubs (kt1lin→kotlin): чистые замены indy-классов stdlib
#   манифест — tools/build_axml.py (свой энкодер AXML, без aapt)
#   apk/подпись — tools/build_apk.py (zip + v1-подпись, python cryptography)
# Требуется: pip install jdk4py cryptography; gh (авторизован) для blobs API.
# ============================================================
set -euo pipefail
cd "$(dirname "$0")"
WORK=/tmp/atools
mkdir -p "$WORK"

JAVA_HOME=$(python3 -c "import jdk4py; print(jdk4py.JAVA_HOME)")
JAVA="$JAVA_HOME/bin/java"

echo "== 1. kotlinc (npm) =="
if [ ! -d "$WORK/kcn/package" ]; then
  mkdir -p "$WORK/kcn" && cd "$WORK/kcn"
  npm pack kotlin-compiler >/dev/null && tar xzf kotlin-compiler-*.tgz
  cd - >/dev/null
fi
KOTLINC="$WORK/kcn/package/bin/kotlinc"

echo "== 2. dx.jar (GitHub blobs) =="
if [ ! -f "$WORK/dx.jar" ]; then
  gh api repos/xiaoyvyv/android-dx/git/blobs/9827a88ef5dfdcb4027fcc68ca84c563a16a0dd1 \
    -H "Accept: application/vnd.github.raw" > "$WORK/dx.jar"
fi

echo "== 3. android.jar API 34 (GitHub blobs) =="
if [ ! -f "$WORK/android.jar" ]; then
  SHA=$(gh api repos/Sable/android-platforms/contents/android-34/android.jar --jq '.sha')
  gh api "repos/Sable/android-platforms/git/blobs/$SHA" \
    -H "Accept: application/vnd.github.raw" > "$WORK/android.jar"
fi

echo "== 4. kotlin-stdlib 1.9.25 (npm) — stdlib 2.x содержит invokedynamic =="
if [ ! -f "$WORK/stdlib19_clean.jar" ]; then
  mkdir -p "$WORK/kc19" && cd "$WORK/kc19"
  npm pack kotlin-compiler@1.9.25 >/dev/null && tar xzf kotlin-compiler-1.9.25.tgz
  cd - >/dev/null
  cp "$WORK/kc19/package/lib/kotlin-stdlib.jar" "$WORK/stdlib19.jar"
  python3 - "$WORK" <<'EOF'
import zipfile, os, sys
w = sys.argv[1]
zin = zipfile.ZipFile(f'{w}/stdlib19.jar')
zout = zipfile.ZipFile(f'{w}/stdlib19_clean.jar', 'w', zipfile.ZIP_DEFLATED)
for it in zin.infolist():
    n = it.filename
    if n.startswith('META-INF/versions/') or n.endswith('module-info.class') or n.endswith('/'):
        continue
    zout.writestr(n, zin.read(n))
zout.close()
print('stdlib19_clean.jar:', os.path.getsize(f'{w}/stdlib19_clean.jar'), 'байт')
EOF
fi

echo "== 5. компиляция Kotlin (-Xlambdas=class: без invokedynamic) =="
SRC=../app/src/main/java
rm -rf "$WORK/out"; mkdir -p "$WORK/out"
JAVA_HOME="$JAVA_HOME" "$KOTLINC" -no-stdlib -jvm-target 1.8 \
  -Xlambdas=class -Xsam-conversions=class \
  -cp "$WORK/android.jar:$WORK/kc19/package/lib/kotlin-stdlib.jar" -d "$WORK/out" \
  $(find "$SRC" -name "*.kt") 2>&1 | grep -E "error" && { echo "ОШИБКИ КОМПИЛЯЦИИ"; exit 1; }
echo "классов игры: $(find "$WORK/out" -name '*.class' | wc -l)"

echo "== 5b. заглушки вместо indy-классов stdlib =="
rm -rf "$WORK/out_stubs"; mkdir -p "$WORK/out_stubs"
JAVA_HOME="$JAVA_HOME" "$KOTLINC" -no-stdlib -jvm-target 1.8 \
  -Xlambdas=class -Xsam-conversions=class \
  -cp "$WORK/android.jar:$WORK/stdlib19_clean.jar" -d "$WORK/out_stubs" \
  $(find ../tools/stubs -name "*.kt") 2>&1 | grep -E "error" && { echo "ОШИБКИ ЗАГЛУШЕК"; exit 1; }
python3 - "$WORK" <<'EOF'
import os, sys
w = sys.argv[1]
n = 0
for root, dirs, files in os.walk(f'{w}/out_stubs'):
    for f in files:
        if not f.endswith('.class'):
            continue
        p = os.path.join(root, f)
        b = open(p, 'rb').read()
        b = b.replace(b'kt1lin/', b'kotlin/')  # пакеты одной длины
        rel = os.path.relpath(p, f'{w}/out_stubs').replace('kt1lin', 'kotlin')
        dst = os.path.join(f'{w}/out', rel)
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        open(dst, 'wb').write(b)
        n += 1
print('заглушек вшито:', n)
EOF

echo "== 6. dex 037 (--min-sdk-version=24) + вычистка indy-классов =="
JAVA="$JAVA_HOME" python3 clean_dex.py "$WORK" "$WORK/out" "$WORK/stdlib19_clean.jar" \
  "$WORK/classes.dex" kotlin/comparisons/ComparisonsKt__ComparisonsKt kotlin/streams/jdk8/StreamsKt

echo "== 7. манифест AXML + APK + v1-подпись =="
python3 build_axml.py "$WORK/manifest.bin"
python3 build_apk.py ../dist/RedRadar.apk

echo "== 8. проверка (androguard) =="
python3 - <<'EOF'
from androguard.core.apk import APK
a = APK('../dist/RedRadar.apk')
assert a.get_package() == 'com.arena.redradar'
assert a.get_main_activity() == 'com.arena.redradar.MainActivity'
assert a.is_signed_v1() and a.is_valid_APK()
print('APK валиден:', a.get_package(), a.get_app_name())
EOF
echo "Готово: dist/RedRadar.apk"
