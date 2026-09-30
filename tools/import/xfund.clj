(ns import.xfund
  "XFUND -> emon gold (DEVLOG P2.11).   bb import xfund [--limit N]

  Native ground truth: <lang>.<split>.json {lang, version, split, documents: [{id, uid,
  img {fname, width, height}, document: [entity...]}]}: the FUNSD entity format (box in image
  pixels, top-left origin) for 7 languages.  Mapping, claims and coordinate handling are FUNSD's
  (tools/import/funsd.clj); the locale is the file's language.  The annotated image size must equal
  the image file's; a mismatch is skipped and counted."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [import.common :as C]
            [import.funsd :as funsd]
            [pyjson :as pj :refer [om]]))

(defn items [corpus st]
  (let [base (str corpus "/multilingual/xfund")]
    (for [fname (sort (map #(str (fs/file-name %)) (fs/list-dir base)))
          :when (str/ends-with? fname ".json")
          :let [d (pj/parse-file (str base "/" fname))
                lang (get d "lang") split (get d "split")]
          doc (get d "documents")]
      (let [img (str base "/" lang "." split "/" (get-in doc ["img" "fname"]))]
        (if-not (fs/exists? img)
          (do (C/warn! st "image missing") [(get doc "id") nil])
          (let [[page fmt] (C/raster-page img)]
            (if (not= [(get page "w") (get page "h")] [(get-in doc ["img" "width"]) (get-in doc ["img" "height"])])
              (do (C/warn! st "annotated image size differs from the file") [(get doc "id") nil])
              (let [prov (om "producer" (str "XFUND (Xu et al. 2021), human-annotated; " fname)
                             "variants" (om "image" (C/rel img)))
                    gid (str "xfund-" (get doc "id"))]
                [gid (funsd/form-gold gid (get doc "document") page fmt lang prov)]))))))))

(defn import! [opts] (C/run "xfund" items opts))
