#!/bin/sh
# P2.7: time kin-generated raster code (Otsu + binarise + 8-connected CCL over
# a 300-dpi A4 page) in Rust, Go, Java and C#, against a hand-written Rust
# comparator. NOT part of `bb gates`.
#
#     bench/run.sh [N] [port|bulk]        N = iterations, best of (default 10)
#     bb bench/report.clj             the table, and the equality check
#
# Needs: bb bench/mkpage.clj (once), and the generated code in gen/
# (`bb emit`). Release builds: rustc -C opt-level=3, `go build`, `javac` + the
# JIT, `dotnet -c Release`. Each arm prints `T <phase> <best ms>` lines and one
# `S <threshold> <stats...>` line; bench/report.clj checks every S line agrees.
set -e
cd "$(dirname "$0")/.."
repo=$(pwd)
N=${1:-10}
mode=${2:-port}
# P6.3's workload (gray, colour histogram, layers, banded Sauvola, deskew, nm-view) is its own script.
if [ "$mode" = norm ]; then exec sh bench/norm.sh "$N"; fi
work="$repo/bench/work"
# PAGE=/abs/other.gray TAG=-clean bench/run.sh ... times another page (made
# with bench/mkpage.clj SRC OUT); results land in results-<mode><TAG>.txt.
page="${PAGE:-$repo/bench/data/page.gray}"
tag="${TAG:-}"
[ -f "$page" ] || bb bench/mkpage.clj
read -r W H < "$page.size"
mkdir -p "$work"
out="$work/results-$mode$tag.txt"
: > "$out"
cap="$repo/kin/scripts/cap"

emit() { echo "== $1 ($mode)" >> "$out"; }

# --- native Rust comparator (hand-written) ---------------------------------
mkdir -p "$work/native"
rustc -C opt-level=3 -o "$work/native/native" bench/native/native.rs 2> "$work/native/build.log"
emit rust-native
"$work/native/native" "$page" "$W" "$H" "$N" >> "$out"

# The same source with the auto-vectoriser off: what the comparator costs when
# it is held to the scalar code Go, Java and C# can emit (their compilers do
# not vectorise these loops; LLVM does).
rustc -C opt-level=3 -C no-vectorize-loops -C no-vectorize-slp -o "$work/native/native-scalar" bench/native/native.rs 2>> "$work/native/build.log"
emit rust-native-scalar
"$work/native/native-scalar" "$page" "$W" "$H" "$N" >> "$out"

# --- Rust, kin-generated ---------------------------------------------------
mkdir -p "$work/rust/emon"
cp gen/rust/emon/raster.rs "$work/rust/emon/"
cp host/rust/src/port.rs "$work/rust/port.rs"
echo "pub mod raster;" > "$work/rust/emon.rs"
cp bench/mains/main.rs "$work/rust/main.rs"
(cd "$work/rust" && rustc -C opt-level=3 -o prog main.rs 2> build.log)
emit rust-kin
"$work/rust/prog" "$page" "$W" "$H" "$N" "$mode" >> "$out"

# --- Go ---------------------------------------------------------------------
mkdir -p "$work/go/raster" "$work/go/port"
cp gen/go/raster/raster.go "$work/go/raster/"
cp host/go/port/*.go "$work/go/port/"
printf 'module emon\n\ngo 1.21\n' > "$work/go/go.mod"
cp bench/mains/main.go "$work/go/main.go"
(cd "$work/go" && go build -o prog . )
emit go-kin
"$work/go/prog" "$page" "$W" "$H" "$N" "$mode" >> "$out"
# Hand-written Go for hist/bin/alloc over the raw slices (labelling is kin's):
# separates the port's cost from Go's scalar loops. Once, in the port run.
if [ "$mode" = port ]; then
  emit go-direct
  "$work/go/prog" "$page" "$W" "$H" "$N" direct >> "$out"
fi

# --- Java ---------------------------------------------------------------------
JDK="${EMON_JDK:-/opt/homebrew/opt/openjdk}"
[ -x "$JDK/bin/javac" ] || JDK=$(/usr/libexec/java_home 2>/dev/null || true)
if [ -x "$JDK/bin/javac" ]; then
  mkdir -p "$work/java/emon"
  cp gen/java/emon/Raster.java "$work/java/emon/"
  cp host/java/emon/*.java "$work/java/emon/"
  cp bench/mains/Main.java "$work/java/Main.java"
  (cd "$work/java" && "$JDK/bin/javac" -encoding UTF-8 -d . emon/*.java Main.java)
  emit java-kin
  (cd "$work/java" && "$JDK/bin/java" -cp . Main "$page" "$W" "$H" "$N" "$mode") >> "$out"
else
  echo "SKIP java"
fi

# --- C# -----------------------------------------------------------------------
if command -v dotnet > /dev/null 2>&1; then
  mkdir -p "$work/cs"
  cp gen/csharp/Emon/Raster.cs "$work/cs/"
  cp host/csharp/Emon/Port.cs "$work/cs/"
  cp bench/mains/Program.cs "$work/cs/Program.cs"
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
  emit csharp-kin
  (cd "$work/cs" && "$cap" 900 dotnet out/cs.dll "$page" "$W" "$H" "$N" "$mode") >> "$out"
else
  echo "SKIP csharp"
fi
echo "wrote $out"
