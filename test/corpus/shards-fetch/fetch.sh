#!/usr/bin/env bash
# Reproduce the emon corpus. Usage: ./fetch.sh [section ...]   (no args = all sections)
# Needs: curl, unzip, tar, git, python3 with pyarrow (pip install pyarrow), pdftotext optional.
# Large files are skipped if they already exist. Raw downloads land in corpus/_dl (safe to delete).
set -uo pipefail
SUPPORT="$(cd "$(dirname "$0")" && pwd)"
CORPUS="${EMON_CORPUS:?set EMON_CORPUS to the corpus root}"; cd "$CORPUS"
source "$SUPPORT/tools/common.sh"
X="$PY $SUPPORT/tools/extract_parquet.py"

sec_invoices_katanaml() { # synthetic invoices, MIT
  local d=financial/katanaml-invoices
  for s in test:test-00000-of-00001-56af6bd5ff7eb34d validation:validation-00000-of-00001-b8a5c4a6237baf25 train:train-00000-of-00001-a5c51039eab2980a; do
    n=${s%%:*}; f=${s#*:}
    hf katanaml-org/invoices-donut-data-v1 data/$f.parquet _dl/katanaml_$n.parquet
    [ -d $d/$n ] || { if [ $n = train ]; then $X _dl/katanaml_$n.parquet $d/$n inv_ --max 150; else $X _dl/katanaml_$n.parquet $d/$n inv_; fi; }
  done
}
sec_sroie() { # SROIE 2019 receipts (HF mirror of ICDAR2019 SROIE)
  local d=financial/sroie2019
  hf jsdnrs/ICDAR2019-SROIE data/test-00000-of-00001.parquet _dl/sroie_test.parquet
  hf jsdnrs/ICDAR2019-SROIE data/train-00000-of-00001.parquet _dl/sroie_train.parquet
  hf jsdnrs/ICDAR2019-SROIE README.md $d/README.md
  [ -d $d/test ] || $X _dl/sroie_test.parquet $d/test sroie_
  [ -d $d/train ] || $X _dl/sroie_train.parquet $d/train sroie_ --max 300
}
sec_cord() { # CORD v2 receipts, CC-BY-4.0
  local d=financial/cord-v2
  hf naver-clova-ix/cord-v2 data/test-00000-of-00001-9c204eb3f4e11791.parquet _dl/cord_test.parquet
  hf naver-clova-ix/cord-v2 data/validation-00000-of-00001-cc3c5779fe22e8ca.parquet _dl/cord_val.parquet
  [ -d $d/test ] || $X _dl/cord_test.parquet $d/test cord_
  [ -d $d/validation ] || $X _dl/cord_val.parquet $d/validation cord_
}
sec_funsd() { # FUNSD noisy scanned forms (official zip)
  dl https://guillaumejaume.github.io/FUNSD/dataset.zip _dl/funsd.zip
  [ -d forms/funsd ] || { mkdir -p forms/funsd && unzip -q -o _dl/funsd.zip -d forms/funsd; }
}
sec_doclaynet() { # DocLayNet v1.2 HF parquet: page PNG + original page PDF + COCO boxes + pdf_cells (words). One test shard + one val shard
  local d=layout/doclaynet-v1.2
  hf ds4sd/DocLayNet-v1.2 data/test-00004-of-00006.parquet _dl/dln_test4.parquet
  hf ds4sd/DocLayNet-v1.2 data/validation-00004-of-00007.parquet _dl/dln_val4.parquet
  [ -d $d/test-shard4 ] || $X _dl/dln_test4.parquet $d/test-shard4 dln_
  [ -d $d/val-shard4 ] || $X _dl/dln_val4.parquet $d/val-shard4 dln_
}
sec_fintabnet() { # FinTabNet OTSL (IBM; table crops w/ structure) test shard
  hf ds4sd/FinTabNet_OTSL data/test-00000-of-00002-7d6f82c6161b0377.parquet _dl/fintabnet_test0.parquet
  [ -d tables/fintabnet-otsl ] || $X _dl/fintabnet_test0.parquet tables/fintabnet-otsl/test-shard0 ftn_
}
sec_ctdar() { # ICDAR 2019 cTDaR table detection/recognition
  [ -d tables/icdar2019-ctdar ] && return 0
  git clone --depth 1 -q https://github.com/cndplab-founder/ICDAR2019_cTDaR tables/icdar2019-ctdar && rm -rf tables/icdar2019-ctdar/.git
}
sec_xfund() { # XFUND multilingual forms (MIT)
  for l in zh ja de fr es it pt; do for s in train val; do
    dl https://github.com/doc-analysis/XFUND/releases/download/v1.0/$l.$s.json multilingual/xfund/$l.$s.json
    dl https://github.com/doc-analysis/XFUND/releases/download/v1.0/$l.$s.zip _dl/xfund_$l.$s.zip
    [ -d multilingual/xfund/$l.$s ] || { mkdir -p multilingual/xfund/$l.$s; unzip -q -o _dl/xfund_$l.$s.zip -d multilingual/xfund/$l.$s; }
  done; done
}
sec_cuad() { # CUAD contracts (PDF + expert clause labels), CC-BY-4.0, Zenodo
  dl https://zenodo.org/records/4595826/files/CUAD_v1.zip?download=1 _dl/CUAD_v1.zip
  [ -d layout/cuad ] || { mkdir -p layout/cuad && unzip -q -o _dl/CUAD_v1.zip -d layout/cuad; }
}
sec_pdfa() { # pixparse/pdfa-eng-wds: real PDFs + word boxes (one shard)
  hf pixparse/pdfa-eng-wds pdfa-eng-train-0000.tar _dl/pdfa-eng-train-0000.tar
  [ -d layout/pdfa-eng-wds ] || { mkdir -p layout/pdfa-eng-wds && tar -xf _dl/pdfa-eng-train-0000.tar -C layout/pdfa-eng-wds; }
}
sec_stamps() { # mapo80/stamps "Clean Core Stamp Detection Dataset", CC-BY-4.0, YOLO boxes; val+test splits only (train.zip is 783 MB)
  for sp in val test; do
    hf mapo80/stamps $sp.zip _dl/stamps_$sp.zip
    [ -d seals/mapo80-stamps/images/$sp ] || { mkdir -p seals/mapo80-stamps; unzip -q -o _dl/stamps_$sp.zip -d seals/mapo80-stamps; }
  done
  hf mapo80/stamps dataset.yaml seals/mapo80-stamps/dataset.yaml
  hf mapo80/stamps README.md seals/mapo80-stamps/LICENSE_SOURCE_card.md
}
ALL="stamps invoices_katanaml sroie cord funsd doclaynet fintabnet ctdar xfund cuad pdfa"
source "$SUPPORT/tools/fetch_extra.sh"
for s in ${@:-$ALL $EXTRA}; do echo "== $s"; sec_$s; done
