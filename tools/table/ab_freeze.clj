(ns table.ab-freeze
  "Deterministic tables-ab/v1 population freeze. --check never rewrites the manifest."
  (:require [babashka.fs :as fs] [cheshire.core :as json] [clojure.string :as str]
            [table.ab-checks :as C]))
(def root (str (fs/canonicalize ".")))
(def frozen "/Volumes/SSK SSD/emon-scratch/trd/frozen")
(def salt "tables-ab/v1/")
(def exclude #{"ftn_00000" "ftn_02204" "ftn_03429" "ftn_03897" "ftn_04781"
               "cTDaR_t00781" "cTDaR_t10095" "cTDaR_t10202"
               "Digital_Type1/00005" "Digital_Type1/00035" "Digital_Type1/00075"
               "Scanned_Type1/00010" "Scanned_Type1/00076" "Scanned_Type1/00077" "Scanned_Type1/00091" "Scanned_Type2/00044"})
(defn digest [in]
  (let [md (java.security.MessageDigest/getInstance "SHA-256") buf (byte-array 1048576)]
    (with-open [s in] (loop [] (let [n (.read s buf)] (when (pos? n) (.update md buf 0 n) (recur)))))
    (apply str (map #(format "%02x" (bit-and % 255)) (.digest md)))))
(defn sha [p] (digest (java.io.FileInputStream. (str p))))
(defn rank [id] (digest (java.io.ByteArrayInputStream. (.getBytes (str salt id) "UTF-8"))))
(defn paths [dir glob] (sort-by str (remove #(str/starts-with? (str (fs/file-name %)) "._") (fs/glob dir glob))))
(defn rel [p] (str (fs/relativize root (fs/absolutize p))))
(defn has-kind [j k] (some #(= k (get % "kind")) (C/walk (get-in j ["model" "body"]))))
(defn synth []
  (vec (for [g (paths "test/corpus/synth/gold" "*.gold.json")
             :let [id (str/replace (str (fs/file-name g)) #"\.gold\.json$" "") pdf (str "test/corpus/synth/pdf/" id ".pdf")]
             :when (and (fs/exists? pdf) (has-kind (C/read-json g) "table"))]
         {"id" id "input" pdf "gold" (str g) "sha256" (sha pdf)})))
(defn goldens []
  (vec (for [p (paths "test/table" "*.json") :let [id (fs/strip-ext (str (fs/file-name p)))]]
         {"id" id "golden" (str p) "input" (if (= id "ttab-low-trust") "test/table/ttab-low-trust.pdf" (str "test/corpus/synth/pdf/" id ".pdf"))})))
(defn gold-set [folder input-of]
  (vec (for [g (paths (str "corpus/_gold/" folder) "*.gold.json") :let [j (C/read-json g) input (input-of j)]
             :when (and input (fs/exists? (str "corpus/" input)))
             :let [prod (get-in j ["provenance" "producer"] "")]]
         {"id" (get j "id") "key" (fs/strip-ext (str (fs/file-name input))) "input" (str "corpus/" input) "gold" (str g)
          "track" (cond (str/includes? prod "Track A") "A" (str/includes? prod "Track B2") "B2" (str/includes? prod "Track B1") "B1" :else nil)
          "structure" (boolean (has-kind j "row"))})))
(defn pick [cands n taken]
  (let [chosen (vec (take n (remove #(or (exclude (get % "key")) (@taken (get % "id"))) (sort-by #(rank (get % "id")) cands))))]
    (swap! taken into (map #(get % "id") chosen)) chosen))
(defn agami [kind]
  (vec (for [p (paths (str "corpus/financial/agami-indian-bank-statements/train/India_Bank_Statement_" kind) "*.pdf")
             :let [id (str kind "/" (fs/strip-ext (str (fs/file-name p)))) gold (str (fs/strip-ext p) ".json")]
             :when (fs/exists? gold)] {"id" id "key" id "input" (str p) "gold" gold})))
(defn with-sha [items] (mapv #(-> % (assoc "sha256" (sha (get % "input"))) (dissoc "key")) items))
(defn build []
  (let [taken (atom #{}) icdar (gold-set "icdar2013" #(get-in % ["provenance" "pdf"]))
        ftn (gold-set "fintabnet_otsl" #(get-in % ["provenance" "variants" "image"]))
        ctd (gold-set "ctdar" #(get-in % ["provenance" "variants" "image"]))
        a (filter #(= "A" (get % "track")) ctd) s (filter #(and (get % "structure") (not= "A" (get % "track"))) ctd)
        kinds ["Digital_Type1" "Digital_Type2" "Scanned_Type1" "Scanned_Type2"] ag (zipmap kinds (map agami kinds))
        fintabnet (pick ftn 300 taken) ctdar (into (pick a 60 taken) (pick s 60 taken))
        anchors (vec (for [k kinds c (ag k) :when (#{"Digital_Type1/00075" "Scanned_Type1/00077"} (get c "key"))] c))
        _ (swap! taken into (map #(get % "id") anchors))
        ledgers (into anchors (mapcat #(pick (ag %) 4 taken) kinds))
        held (array-map "fintabnet" (pick ftn 150 taken) "ctdar" (into (pick a 30 taken) (pick s 30 taken))
                        "ledgers" (vec (mapcat #(pick (ag %) 4 taken) kinds))
                        "bsb" [{"id" "bsb-003" "input" "corpus/financial/bank-statement-benchmark/datasets/basic/bsb-003/bsb-003-statement.pdf"
                                "gold" "corpus/financial/bank-statement-benchmark/datasets/basic/bsb-003/statement-info.json"}])
        pubtables (vec (sort (map str (filter #(str/includes? (str/lower-case (str %)) "pubtables") (fs/glob "corpus" "*/*")))))
        controls (remove #(or (str/blank? %) (str/starts-with? % "#")) (str/split-lines (slurp "test/controls/sweep3.txt")))
        live ["--pdfium" "--tesseract"]]
    {"format" 1 "salt" salt "frozen" "2026-10-29 at main a39535b1" "frozen_dir" frozen
     "flags" {"live" live "named" (conj live "--assets=figures") "controls" (into live ["--ocr=auto" "--assets=figures"]) "synth" []}
     "exclude" (vec (sort exclude))
     "populations" {"synth" {"mode" "direct" "gold" "emon" "items" (synth) "goldens" (goldens)}
                    "icdar2013" {"mode" "record" "gold" "emon" "items" (with-sha icdar)}
                    "fintabnet" {"mode" "record" "gold" "emon" "items" (with-sha fintabnet)}
                    "ctdar" {"mode" "record" "gold" "emon" "items" (with-sha ctdar)}
                    "pubtables" {"absent" (empty? pubtables) "items" pubtables}
                    "ledgers" {"mode" "record" "gold" "agami" "items" (with-sha ledgers)}
                    "named" {"mode" "record" "gold" "named" "flags" "named" "items" (C/read-json (str frozen "/selection.json"))
                             "expectations" "test/tables-ab/named.json" "triples" (str frozen "/protected-triples.json")}
                    "heldout" {"mode" "record" "gold" "mixed" "items" (vec (mapcat (fn [[k v]] (map #(assoc % "part" k) (with-sha v))) held))}
                    "controls" {"mode" "live" "flags" "controls" "items" (mapv (fn [n p] {"id" (format "%02d" n) "input" (str/trim p)}) (range) controls)}}}))
;; Match Python's indent=1, sorted-key serialization, including empty collections.
(defn frozen-json [v depth]
  (let [pad #(apply str (repeat % " ")) child #(frozen-json % (inc depth))]
    (cond (map? v) (if (empty? v) "{}" (str "{\n" (str/join ",\n" (for [[k x] (sort-by key v)] (str (pad (inc depth)) (json/generate-string k) ": " (child x)))) "\n" (pad depth) "}"))
          (sequential? v) (if (empty? v) "[]" (str "[\n" (str/join ",\n" (map #(str (pad (inc depth)) (child %)) v)) "\n" (pad depth) "]"))
          :else (json/generate-string v))))
(defn -main [& args]
  (let [data (build) text (str (frozen-json data 0) "\n") out "test/tables-ab/populations.json"]
    (if (some #{"--check"} args)
      (if (= text (slurp out)) (println "  ok populations frozen")
          (do (println "  FAIL populations.json differs from the recomputed selection") (System/exit 1)))
      (do (spit out text) (println (into {} (for [[k v] (get data "populations")] [k (count (get v "items"))])))))))
(apply -main *command-line-args*)
