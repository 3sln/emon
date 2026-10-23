# sourced by fetch.sh
EXTRA="agami_bank bsb icdar2013 irs gov arxiv udhr rvlsmall chartqa libtiff derived"
sec_agami_bank() { # synthetic Indian business bank statements: digital + scanned PDFs + JSON GT, Apache-2.0
  $PY - <<'PYEOF'
from huggingface_hub import snapshot_download
snapshot_download('AgamiAI/Indian-Bank-Statements', repo_type='dataset', local_dir='financial/agami-indian-bank-statements')
PYEOF
  rm -rf financial/agami-indian-bank-statements/.cache
}
sec_bsb() { # synthetic multi-country bank statement PDFs benchmark (MIT); GT for advanced tiers is server-held
  [ -d financial/bank-statement-benchmark ] || { git clone --depth 1 -q https://github.com/Chandrakanth10/bank-statement-parsing-benchmark financial/bank-statement-benchmark && rm -rf financial/bank-statement-benchmark/.git; }
}
sec_icdar2013() { # ICDAR 2013 table competition (EU+US gov PDFs, region + structure XML), via tabula-java test resources (MIT-licensed repo; docs are public-domain gov PDFs)
  [ -d tables/icdar2013 ] && return 0
  rm -rf _dl/tabula && git clone -q --depth 1 --filter=blob:none --sparse https://github.com/tabulapdf/tabula-java _dl/tabula \
   && (cd _dl/tabula && git sparse-checkout set src/test/resources/technology/tabula/icdar2013-dataset) \
   && mkdir -p tables/icdar2013 && cp -R _dl/tabula/src/test/resources/technology/tabula/icdar2013-dataset/* tables/icdar2013/ && rm -rf _dl/tabula
}
sec_irs() { # IRS forms: fillable born-digital PDFs (US gov, public domain)
  for f in fw9 f1040 f1040sc f1040s1 i1040gi f941 f8949 p15 f1099nec fw4 f1120 f990 f1065; do dl https://www.irs.gov/pub/irs-pdf/$f.pdf forms/irs/$f.pdf; done
}
sec_gov() { # gov statistical / financial reports & company annual report (tables, multipage tables, headers/footers)
  G=tables/gov-reports
  dl https://www.bls.gov/news.release/pdf/empsit.pdf $G/bls-empsit.pdf
  dl https://www.bls.gov/news.release/pdf/cpi.pdf $G/bls-cpi.pdf
  dl https://www.census.gov/content/dam/Census/library/publications/2023/demo/p60-279.pdf $G/census-p60-279.pdf
  dl https://www.bea.gov/sites/default/files/2024-09/gdp2q24-3rd.pdf $G/bea-gdp2q24.pdf
  dl https://www.uscourts.gov/sites/default/files/data_tables/jb_c1_0930.2023.pdf $G/uscourts-jb-c1-2023.pdf
  dl https://www.eia.gov/outlooks/steo/pdf/steo_full.pdf $G/eia-steo-full.pdf
  dl https://www.gao.gov/assets/gao-24-106540.pdf layout/gov-reports/gao-24-106540.pdf
  dl https://www.federalreserve.gov/monetarypolicy/files/fomcminutes20240918.pdf layout/gov-reports/fomc-minutes-20240918.pdf
  dl https://www.berkshirehathaway.com/2023ar/2023ar.pdf financial/annual-reports/berkshire-2023-annual-report-10k.pdf
  dl https://www.berkshirehathaway.com/letters/2023ltr.pdf layout/gov-reports/berkshire-2023-letter.pdf
}
sec_arxiv() { # arXiv papers (multi-column, math, tables, figures); each paper carries its own license (arXiv non-exclusive distrib or CC) -- test use only
  for id in 1706.03762 2206.01062 1512.03385 1810.04805 2005.14165 2103.00020 2010.11929 1409.1556; do dl https://arxiv.org/pdf/$id layout/arxiv/$id.pdf; sleep 3; done
}
sec_udhr() { # UDHR in non-Latin scripts (OHCHR): Hebrew, Hindi, Russian, Ukrainian, Chinese, Japanese, Egyptian Arabic, Dari, Urdu, Tamil, Greek, Amharic, Khmer, Kannada
  for c in hbr hnd rus ukr chn jpn prs urd tam grk amh khm kkn; do dl https://www.ohchr.org/sites/default/files/UDHR/Documents/UDHR_Translations/$c.pdf multilingual/udhr/$c.pdf; done
  # designed UN booklets (Arabic RTL, French); Arabic Wikipedia page as PDF (CC BY-SA 4.0)
  for l in ar fr; do dl https://www.un.org/$l/udhrbook/pdf/udhr_booklet_${l}_web.pdf multilingual/udhr/un-booklet-$l.pdf; done
  dl 'https://ar.wikipedia.org/api/rest_v1/page/pdf/%D8%A7%D9%84%D8%A5%D8%B9%D9%84%D8%A7%D9%86_%D8%A7%D9%84%D8%B9%D8%A7%D9%84%D9%85%D9%8A_%D9%84%D8%AD%D9%82%D9%88%D9%82_%D8%A7%D9%84%D8%A5%D9%86%D8%B3%D8%A7%D9%86' multilingual/udhr/ar-wikipedia-udhr.pdf
  dl 'https://he.wikipedia.org/api/rest_v1/page/pdf/%D7%99%D7%A8%D7%95%D7%A9%D7%9C%D7%99%D7%9D' multilingual/udhr/he-wikipedia-jerusalem.pdf
  dl 'https://hi.wikipedia.org/api/rest_v1/page/pdf/%E0%A4%AD%E0%A4%BE%E0%A4%B0%E0%A4%A4' multilingual/udhr/hi-wikipedia-india.pdf
  dl 'https://ru.wikipedia.org/api/rest_v1/page/pdf/%D0%9C%D0%BE%D1%81%D0%BA%D0%B2%D0%B0' multilingual/udhr/ru-wikipedia-moscow.pdf
  dl 'https://ja.wikipedia.org/api/rest_v1/page/pdf/%E6%9D%B1%E4%BA%AC' multilingual/udhr/ja-wikipedia-tokyo.pdf
}
sec_rvlsmall() { # 200/class? small RVL-CDIP subset as TIFF (labels = folder name)
  $PY - <<'PYEOF'
from huggingface_hub import snapshot_download
snapshot_download('hadasBublil/rvl_cdip-small-200', repo_type='dataset', local_dir='scanned/rvl-cdip-small')
PYEOF
  rm -rf scanned/rvl-cdip-small/.cache
}
sec_chartqa() { # ChartQA test split (charts + QA), GPL-3.0 dataset
  hf HuggingFaceM4/ChartQA data/test-00000-of-00001-e2cd0b7a0f9eb20d.parquet _dl/chartqa_test.parquet
  [ -d graphics/chartqa-test ] || $X _dl/chartqa_test.parquet graphics/chartqa-test cqa_ --max 400
}
sec_libtiff() { # libtiff sample TIFFs (fax G3/G4, tiles, strips, palette, multipage)
  dl https://download.osgeo.org/libtiff/pics-3.8.0.tar.gz _dl/libtiff-pics.tar.gz
  [ -d formats/libtiff-pics ] || { mkdir -p formats/libtiff-pics && tar -xzf _dl/libtiff-pics.tar.gz -C formats/libtiff-pics --strip-components=1; }
}
sec_derived() { $PY "$SUPPORT/tools/make_derived.py"; }
