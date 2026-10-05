#!/bin/sh
# P6.4: build the tracer's benchmark drivers (Rust and Go: `emon.norm` then `emon.trace` on a raw page) and, unless told to stop, time them.
# NOT part of `bb gates` (`bb trace` uses the builds).
#
#     bench/trace.sh [N]            N = iterations, best of (default 3)
#     bench/trace.sh N build        build only
#
# Needs the generated code in gen/ (`bb emit`). Output: bench/work/trace/{rust,go}/prog; `prog PAGE W H CH N DPI100 [dump]`.
set -e
cd "$(dirname "$0")/.."
repo=$(pwd)
N=${1:-3}
work="$repo/bench/work/trace"
mkdir -p "$work/rust/emon"
for m in norm trace evidence model json trig raster; do cp "gen/rust/emon/$m.rs" "$work/rust/emon/"; done
cp host/rust/src/port.rs "$work/rust/port.rs"
printf 'pub mod norm;\npub mod trace;\npub mod evidence;\npub mod model;\npub mod json;\npub mod trig;\npub mod raster;\n' > "$work/rust/emon.rs"
cp bench/mains/trace.rs "$work/rust/main.rs"
(cd "$work/rust" && rustc -C opt-level=3 -o prog main.rs 2> build.log) || { cat "$work/rust/build.log"; exit 1; }

mkdir -p "$work/go/port"
for m in norm trace evidence model json trig; do mkdir -p "$work/go/$m"; cp "gen/go/$m/$m.go" "$work/go/$m/"; done
cp host/go/port/*.go "$work/go/port/"
printf 'module emon\n\ngo 1.21\n' > "$work/go/go.mod"
cp bench/mains/trace.go "$work/go/main.go"
rm -f "$work/go/port/"*_test.go
(cd "$work/go" && go build -o prog .)
[ "${2:-run}" = build ] && exit 0
read -r RW RH < bench/data/wm.rgb.size
echo "== rust"; "$work/rust/prog" bench/data/wm.rgb "$RW" "$RH" 3 "$N" 30000
echo "== go";   "$work/go/prog" bench/data/wm.rgb "$RW" "$RH" 3 "$N" 30000
