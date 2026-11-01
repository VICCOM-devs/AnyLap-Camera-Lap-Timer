#!/usr/bin/env bash
# Real-video bench. Usage: tools/bench/real/run.sh <clip name> <video file> [--whole-frame] [--out dir]
# Needs ffmpeg, a JDK and kotlinc 2.2.x on PATH (or KOTLINC=/path/to/kotlinc).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; root="$here/../../.."
src="$root/composeApp/src/commonMain/kotlin/app/anylap"
kc="${KOTLINC:-kotlinc}"; build="${BUILD:-/tmp/anylap-realbench}"
mkdir -p "$build"
newest=$(ls -t "$here"/*.kt "$src"/vision/*.kt "$src"/engine/LapSolver.kt | head -1)
if [ ! -f "$build/bench.jar" ] || [ "$newest" -nt "$build/bench.jar" ]; then
  echo "compiling engine + bench…" >&2
  "$kc" "$here/Stubs.kt" $(ls "$src"/vision/*.kt | grep -v '/Camera.kt$') "$src/engine/LapSolver.kt" "$here/RealBench.kt" -d "$build/bench.jar" 2>&1 | grep -E "error" >&2 || true
fi
clip="$1"; shift
std="$(dirname "$(dirname "$(command -v "$kc")")")/lib/kotlin-stdlib.jar"
exec java -Xmx3g -cp "$build/bench.jar:$std" real.RealBenchKt "$here/clips/$clip.json" "$@"
