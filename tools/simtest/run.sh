#!/usr/bin/env bash
# run.sh — компиляция и запуск настольного симулятора движка RTS (JVM, без Android).
#   tools/simtest/run.sh stats [N]                           — баланс: N партий на сложность
#   tools/simtest/run.sh shot [seed] [diff] [seconds] [out]  — снимок мира партии (PNG)
# Инструменты те же, что и в build_apk.sh: python3+pip (jdk4py, pillow) и npm (kotlin-compiler).
set -euo pipefail
cd "$(dirname "$0")"
WORK=/tmp/atools
MODE="${1:-help}"
shift || true

JAVA_HOME=$(python3 -c "import jdk4py; print(jdk4py.JAVA_HOME)")
KC=$WORK/kcn/package

# kotlin-compiler (npm) — тот же, что в основной сборке APK; 1.9 не умеет JVM 25
if [ ! -x "$KC/bin/kotlinc" ]; then
  mkdir -p "$WORK/kcn"
  (cd "$WORK/kcn" && npm pack kotlin-compiler@2.4.21 >/dev/null && tar xzf kotlin-compiler-2.4.21.tgz)
fi

OUT=$WORK/simtest_out
SRC=../../app/src/main/java/com/arena/redradar/engine
mkdir -p "$OUT"

# чистый Kotlin-движок (ModJson.kt исключён — Android-обёртка над AssetManager)
ENGINE=$(ls "$SRC"/*.kt | grep -v ModJson.kt)
if ! JAVA_HOME="$JAVA_HOME" "$KC/bin/kotlinc" \
     -cp "$KC/lib/kotlin-stdlib.jar" -d "$OUT" \
     SimTools.kt $ENGINE; then
  echo "ОШИБКИ КОМПИЛЯЦИИ"; exit 1
fi

"$JAVA_HOME/bin/java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp "$OUT:$KC/lib/kotlin-stdlib.jar" SimToolsKt "$MODE" "$@"

# снимок: P6 PPM -> PNG (pillow)
if [ "$MODE" = "shot" ]; then
  PPM=preview.ppm
  if [ "$#" -ge 1 ]; then PPM="${@: -1}"; fi
  PNG="${PPM%.ppm}.png"
  python3 - "$PPM" "$PNG" <<'EOF'
import sys
from PIL import Image
img = Image.open(sys.argv[1])
img.save(sys.argv[2])
print('PNG:', sys.argv[2], img.size)
EOF
fi
