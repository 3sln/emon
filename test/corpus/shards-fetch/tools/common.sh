# shared helpers for fetch.sh; sourced. CORPUS must be set.
UA="emon-corpus/0.1 (research; contact: stubbs.ray@gmail.com)"
dl() { # dl URL DEST  (resumable, skips if exists)
  local url="$1" dest="$2"; mkdir -p "$(dirname "$dest")"
  [ -s "$dest" ] && return 0
  curl -fsSL --retry 4 --retry-delay 3 -C - -A "$UA" -o "$dest.part" "$url" && mv "$dest.part" "$dest" || echo "FAILED: $url" >&2
}
hf() { # hf REPO PATH DEST
  dl "https://huggingface.co/datasets/$1/resolve/main/$2" "$3"
}
PY="${PY:-python3}"
