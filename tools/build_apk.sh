#!/usr/bin/env bash
# ============================================================
# build_apk.sh — воспроизводимая сборка APK без Android SDK.
# Все инструменты берутся с разрешённых хостов:
#   JDK      — PyPI: jdk4py (Temurin JRE 25)
#   kotlinc  — npm:  kotlin-compiler (полный дистрибутив 2.4.21)
#   android.jar — GitHub: Sable/android-platforms (API 34, через blobs API)
#   dx       — GitHub: xiaoyvyv/android-dx (dx 1.16, blobs API)
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

echo "== 4. чистая kotlin-stdlib (без META-INF/versions) =="
if [ ! -f "$WORK/stdlib_clean.jar" ]; then
  cp "$WORK/kcn/package/lib/kotlin-stdlib.jar" "$WORK/stdlib.jar"
  python3 - "$WORK" <<'EOF'
import zipfile, os, sys
w = sys.argv[1]
zin = zipfile.ZipFile(f'{w}/stdlib.jar')
zout = zipfile.ZipFile(f'{w}/stdlib_clean.jar', 'w', zipfile.ZIP_DEFLATED)
for it in zin.infolist():
    n = it.filename
    if n.startswith('META-INF/versions/') or n.endswith('module-info.class') or n.endswith('/'):
        continue
    zout.writestr(n, zin.read(n))
zout.close()
print('stdlib_clean.jar:', os.path.getsize(f'{w}/stdlib_clean.jar'), 'байт')
EOF
fi

echo "== 5. компиляция Kotlin =="
SRC=../app/src/main/java
rm -rf "$WORK/out"; mkdir -p "$WORK/out"
JAVA_HOME="$JAVA_HOME" "$KOTLINC" -cp "$WORK/android.jar" -d "$WORK/out" \
  $(find "$SRC" -name "*.kt") 2>&1 | grep -E "error" && { echo "ОШИБКИ КОМПИЛЯЦИИ"; exit 1; }
echo "классов: $(find "$WORK/out" -name '*.class' | wc -l)"

echo "== 6. dex =="
"$JAVA" -Xmx1g -cp "$WORK/dx.jar" com.xiaoyv.dx.command.Main \
  --dex --min-sdk-version=26 --output "$WORK/classes.dex" \
  "$WORK/out" "$WORK/stdlib_clean.jar"

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
