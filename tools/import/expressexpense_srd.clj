(ns import.expressexpense-srd
  "ExpressExpense SRD: MIT images, NO text/entity annotations. Gold claims page dimensions only."
  (:require [babashka.fs :as fs] [clojure.string :as str]
            [import.common :as C] [pyjson :refer [om]]))
(defn items [corpus st]
  (for [f (sort (map str (fs/glob (str corpus "/financial/expressexpense-srd") "*.jpg")))
        :when (not (str/starts-with? (str (fs/file-name f)) "._"))]
    (let [[w h fmt orientation] (C/image-info f)
          id (str "expressexpense-srd-" (fs/strip-ext (fs/file-name f)))]
      (if (not= orientation 1)
        (do (C/warn! st "EXIF-oriented image retained but page gold skipped") [id nil])
        (let [page (om "n" 1 "w" w "h" h "rot" 0 "kind" "image" "px" [w h])
              ann (C/annotated {:structure [] :attributes (om) :text false :pages ["n" "px" "kind"]})]
          [id (C/gold id ann [page] [] fmt :provenance
                (om "producer" "ExpressExpense SRD (MIT); image-only, no OCR ground truth"
                    "variants" (om "image" (C/rel f))))])))))

(defn import! [opts] (C/run "expressexpense-srd" items opts))
