#!/usr/bin/env bash
# Roda o app contra um ELM327 simulado na JVM, nos modos multi-PID, um-PID-por-pedido,
# com falhas no motor, com um clone que trava em pedidos grandes e imitando o HR-V real.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
JAR="$SDK/platforms/android-35/android.jar"
OUT=build/test

rm -rf "$OUT"
mkdir -p "$OUT"
# O SystemClock de teste vem antes do android.jar no classpath.
javac -encoding UTF-8 -Xlint:-options -classpath "$JAR" -d "$OUT" \
    $(find test -name '*.java') \
    src/com/piggyback/hrvmon/{Ch,Pids,Fmt,Elm,Links,Poller,DataStore,PullDetector,Session}.java

for mode in multi single problemas clone honda; do
    echo "=== modo $mode ==="
    java -cp "$OUT:$JAR" com.piggyback.hrvmon.SimTest "$mode"
done
