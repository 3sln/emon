#!/bin/sh
# P6.3: time kin-generated normalization (`emon.norm`: gray, colour histogram, ink layers, banded Sauvola, deskew and the
# whole `nm-view`) in Rust, Go, Java and C#, on a 300-dpi gray scan and a 300-dpi RGB render. NOT part of `bb gates`.
#
#     bench/norm.sh [N]            N = iterations, best of (default 5);  also  bench/run.sh N norm
#     bb bench/norm_report.clj     the table, and the agreement check
#
# Needs: bb bench/mkpage.clj (gray, once), bb bench/mknorm.clj (RGB, once), and the generated code in gen/ (`bb emit`).
# Results land in bench/work/results-norm.txt: for each page and arm `T <phase> <best ms>` lines and the summary lines
# (`S`, `L`, `H`) that every arm must agree on.
set -e
cd "$(dirname "$0")/.."
repo=$(pwd)
N=${1:-5}
work="$repo/bench/work/norm"
out="$repo/bench/work/results-norm.txt"
mkdir -p "$work"
[ -f bench/data/page.gray ] || bb bench/mkpage.clj
[ -f bench/data/wm.rgb ] || bb bench/mknorm.clj
cap="$repo/kin/scripts/cap"
: > "$out"

# --- builds -------------------------------------------------------------------
mkdir -p "$work/rust/emon"
for m in norm evidence model json trig raster; do cp "gen/rust/emon/$m.rs" "$work/rust/emon/"; done
cp host/rust/src/port.rs "$work/rust/port.rs"
printf 'pub mod norm;\npub mod evidence;\npub mod model;\npub mod json;\npub mod trig;\npub mod raster;\n' > "$work/rust/emon.rs"
cp bench/mains/norm.rs "$work/rust/main.rs"
(cd "$work/rust" && rustc -C opt-level=3 -o prog main.rs 2> build.log) || { cat "$work/rust/build.log"; exit 1; }

mkdir -p "$work/go/port"
for m in norm evidence model json trig; do mkdir -p "$work/go/$m"; cp "gen/go/$m/$m.go" "$work/go/$m/"; done
cp host/go/port/*.go "$work/go/port/"
printf 'module emon\n\ngo 1.21\n' > "$work/go/go.mod"
cp bench/mains/norm.go "$work/go/main.go"
rm -f "$work/go/port/"*_test.go
(cd "$work/go" && go build -o prog .)

JDK="${EMON_JDK:-/opt/homebrew/opt/openjdk}"
[ -x "$JDK/bin/javac" ] || JDK=$(/usr/libexec/java_home 2>/dev/null || true)
# ONLY=rg builds Rust and Go alone (what `bb ink` needs); `bench/norm.sh N build` stops after the builds.
if [ "${ONLY:-all}" = rg ]; then JDK=none; fi
if [ -x "$JDK/bin/javac" ]; then
  mkdir -p "$work/java/emon"
  for m in Norm Evidence Model Json Trig; do cp "gen/java/emon/$m.java" "$work/java/emon/"; done
  cp host/java/emon/Port.java "$work/java/emon/"
  cp bench/mains/Norm.java "$work/java/Norm.java"
  (cd "$work/java" && "$JDK/bin/javac" -encoding UTF-8 -d . emon/*.java Norm.java)
fi

if [ "${ONLY:-all}" = all ] && command -v dotnet > /dev/null 2>&1; then
  mkdir -p "$work/cs"
  for m in Norm Evidence Model Json Trig; do cp "gen/csharp/Emon/$m.cs" "$work/cs/"; done
  cp host/csharp/Emon/Port.cs "$work/cs/"
  cp bench/mains/NormProgram.cs "$work/cs/Program.cs"
  cat > "$work/cs/cs.csproj" <<'PROJ'
<Project Sdk="Microsoft.NET.Sdk">
  <PropertyGroup>
    <OutputType>Exe</OutputType>
    <TargetFramework>net10.0</TargetFramework>
    <Nullable>disable</Nullable>
    <Optimize>true</Optimize>
    <TieredPGO>true</TieredPGO>
  </PropertyGroup>
</Project>
PROJ
  (cd "$work/cs" && dotnet build -c Release -v q -o out > build.log 2>&1)
fi

[ "${2:-run}" = build ] && exit 0

# --- runs ---------------------------------------------------------------------
run_page() {
  tag=$1; page=$2; W=$3; H=$4; CH=$5
  echo "== page $tag $W x $H x $CH" >> "$out"
  echo "== rust $tag" >> "$out";  "$work/rust/prog" "$page" "$W" "$H" "$CH" "$N" 30000 >> "$out"
  echo "== go $tag" >> "$out";    "$work/go/prog" "$page" "$W" "$H" "$CH" "$N" 30000 >> "$out"
  if [ -x "$JDK/bin/javac" ]; then
    echo "== java $tag" >> "$out"; (cd "$work/java" && "$JDK/bin/java" -cp . Norm "$page" "$W" "$H" "$CH" "$N" 30000) >> "$out"
  fi
  if [ -d "$work/cs/out" ]; then
    echo "== csharp $tag" >> "$out"; (cd "$work/cs" && "$cap" 900 dotnet out/cs.dll "$page" "$W" "$H" "$CH" "$N" 30000) >> "$out"
  fi
}
read -r GW GH < bench/data/page.gray.size
run_page inv_00007.noise "$repo/bench/data/page.gray" "$GW" "$GH" 1
read -r RW RH < bench/data/wm.rgb.size
run_page watermark-draft "$repo/bench/data/wm.rgb" "$RW" "$RH" 3
echo "wrote $out"
