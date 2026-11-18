#!/usr/bin/env bb
;; The model pack catalogue (K1, D898; pkg1 section 3, D885, D896). release/packs/packs.edn is the AUTHORED source: id, rev, engine, kind, languages and scripts, files with
;; SHA-256 and byte count, licence, upstream. This tool checks it and GENERATES what is derived from it:
;;
;;   kin/packdata.kin            what kin resolves packs from and every host verifies bytes against: the pack ids (`pkid`), the licences (`plic`), and the rows, languages,
;;                               scripts, files (SHA-256 as eight big-endian words) and file names. NOT hand-edited.
;;   release/packs/packs.json    the same catalogue for hooks and tools (committed).
;;
;;   bb packs --check                  the catalogue is sound (names, kinds, hashes, licences, languages) and agrees with the two pin files it absorbed
;;                                     (tools/tessdata/models.edn, tools/ppocr/assets.edn: they stay as the inputs of the requested identities, D883; a disagreement fails)
;;   bb packs --emit                   write kin/packdata.kin and release/packs/packs.json
;;   bb packs --emit --check           the DRIFT CHECK: both files are what the catalogue generates (also runs `--check`'s checks). Part of `bb ci core`
;;   bb packs --list                   the catalogue, one line a pack
;;   bb packs --self-test              each refusal of the checks fires on a planted failure
;;
;; The engine, kind and platform names the catalogue uses are those of kin/engines.kin (its `eng` and `pkind` enums), read from that file, so a name is defined once.
(require '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def catalogue-file "release/packs/packs.edn")
(def kin-file "kin/packdata.kin")
(def json-file "release/packs/packs.json")
(def engines-file "kin/engines.kin")
(def tess-pins "tools/tessdata/models.edn")
(def pp-pins "tools/ppocr/assets.edn")

;; The licences a pack may carry. K4 adds the same allowlist to `bb release-audit`.
(def licence-allowlist #{"Apache-2.0" "MIT"})

(defn die [& m] (println "  FAIL" (apply str m)) (System/exit 1))

;; ------------------------------------------------------------------------------------------------------ the names, from kin/engines.kin
(defn enum-rows
  "[[symbol code spelling]] of `(defenum ... name [...])` in kin/engines.kin."
  [text nm]
  (let [start (str/index-of text (str " " nm "\n  [["))
        _ (when-not start (die "kin/engines.kin has no enum " nm))
        end (str/index-of text "]])" start)
        body (subs text start (+ end 2))]
    (vec (for [[_ s c n] (re-seq #"\[(\S+) (\d+) \"([^\"]+)\"\]" body)] [s (Long/parseLong c) n]))))

(defn names-of [text nm] (into {} (map (fn [[_ c n]] [n c]) (enum-rows text nm))))

(defn kin-names
  "{:engines {name code} :kinds {name code}} from the text of kin/engines.kin."
  [text]
  {:engines (names-of text "eng") :kinds (names-of text "pkind")})

;; ------------------------------------------------------------------------------------------------------ checking
(def id-re #"[a-z0-9]+(?:-[a-z0-9.]+)*")
(def sha-re #"[0-9a-f]{64}")

(defn pack-problems
  "A vector of sentences: what is wrong with the catalogue map `cat`, given the kin names `kn`. Pure."
  [cat kn]
  (let [packs (:packs cat)
        engine-name (fn [k] (if (= k :any) "any" (name k)))
        engine-ok? (fn [k] (or (= k :any) (contains? (:engines kn) (engine-name k))))]
    (vec
     (concat
      (when-not (= 1 (:schema cat)) ["the catalogue's :schema is not 1"])
      (when-not (and (vector? packs) (seq packs)) ["the catalogue has no :packs vector"])
      (for [[id n] (frequencies (map :id packs)) :when (> n 1)] (str "pack id " (pr-str id) " is listed " n " times"))
      (mapcat
       (fn [{:keys [id rev engine kind langs scripts licence files upstream] :as p}]
         (let [who (str "pack " (pr-str id))]
           (concat
            (when-not (and (string? id) (re-matches id-re id)) [(str who ": the id is not <engine>-<variant>-<lang|role> in lowercase")])
            (when-not (and (integer? rev) (pos? rev)) [(str who ": :rev is not a positive integer")])
            (when-not (engine-ok? engine) [(str who ": unknown engine " (pr-str engine) " (kin/engines.kin's `eng` has: " (str/join ", " (sort (keys (:engines kn)))) ", and :any)")])
            (when-not (contains? (:kinds kn) (some-> kind name)) [(str who ": unknown kind " (pr-str kind) " (kin/engines.kin's `pkind` has: " (str/join ", " (sort (keys (:kinds kn)))) ")")])
            (when-not (contains? licence-allowlist licence) [(str who ": licence " (pr-str licence) " is not in the allowlist " (pr-str (sort licence-allowlist)))])
            (when-not (and (map? upstream) (string? (:repo upstream)) (string? (:revision upstream))) [(str who ": :upstream needs :repo and :revision")])
            (if (= kind :recognizer)
              (concat
               (when-not (and (vector? langs) (seq langs) (every? #(and (string? %) (re-matches #"[a-z]{3}" %)) langs)) [(str who ": a recognizer needs :langs, three lowercase letters each")])
               (when-not (and (vector? scripts) (seq scripts) (every? #(and (string? %) (re-matches #"[A-Z][a-z]{3}" %)) scripts)) [(str who ": a recognizer needs :scripts (ISO 15924, e.g. \"Latn\")")]))
              (when (or (seq langs) (seq scripts)) [(str who ": only a recognizer has :langs or :scripts")]))
            (if-not (and (vector? files) (seq files))
              [(str who ": no :files")]
              (concat
               (for [[nm n] (frequencies (map :name files)) :when (> n 1)] (str who ": file " (pr-str nm) " is listed " n " times"))
               (mapcat (fn [{:keys [name sha256 bytes]}]
                         (concat
                          (when-not (and (string? name) (re-matches #"[A-Za-z0-9._-]+" name)) [(str who ": a file name " (pr-str name) " is not a plain name")])
                          (when-not (and (string? sha256) (re-matches sha-re sha256)) [(str who ": file " (pr-str name) " has no 64-digit lowercase :sha256")])
                          (when-not (and (integer? bytes) (pos? bytes)) [(str who ": file " (pr-str name) " has no positive :bytes")])))
                       files))))))
       packs)))))

(defn legacy-problems
  "The two pin files this catalogue absorbed, held to it. `tess` is tools/tessdata/models.edn, `pp` is tools/ppocr/assets.edn. Pure."
  [cat tess pp]
  (let [packs (:packs cat)
        by-file (into {} (for [p packs, f (:files p)] [[(:engine p) (:name f)] [p f]]))
        runtimes (into {} (for [r (:runtimes cat), f (:files r)] [(:name f) [r f]]))
        tess-packs (filter #(= :tesseract (:engine %)) packs)
        pp-packs (filter #(= :ppocr (:engine %)) packs)]
    (vec
     (remove nil?
     (concat
      (for [[model {:keys [sha256 bytes]}] (:models tess)
            :let [nm (str model ".traineddata")
                  [p f] (by-file [:tesseract nm])]]
        (cond (nil? p) (str "tools/tessdata/models.edn pins " nm ", which the catalogue has no pack for")
              (not= [sha256 bytes] [(:sha256 f) (:bytes f)]) (str nm ": models.edn says sha256 " sha256 " bytes " bytes ", the catalogue pack " (pr-str (:id p)) " says " (:sha256 f) " " (:bytes f))))
      (for [p tess-packs, f (:files p)
            :let [model (str/replace (:name f) #"\.traineddata$" "")]
            :when (not (contains? (:models tess) model))]
        (str "pack " (pr-str (:id p)) " has " (:name f) ", which tools/tessdata/models.edn does not pin"))
      (for [p tess-packs :when (not= (:revision tess) (get-in p [:upstream :revision]))]
        (str "pack " (pr-str (:id p)) ": upstream revision " (get-in p [:upstream :revision]) " is not models.edn's " (:revision tess)))
      (for [a (:assets pp)
            :let [[p f] (or (by-file [:ppocr (:file a)]) (runtimes (:file a)))]]
        (cond (nil? p) (str "tools/ppocr/assets.edn pins " (:file a) ", which the catalogue has neither as a pack nor as a runtime")
              (not= [(:sha256 a) (:bytes a) (:licence a)] [(:sha256 f) (:bytes f) (:licence p)])
              (str (:file a) ": assets.edn and the catalogue's " (pr-str (:id p)) " disagree on sha256, bytes or licence")))
      (for [p pp-packs, f (:files p) :when (not-any? #(= (:file %) (:name f)) (:assets pp))]
        (str "pack " (pr-str (:id p)) " has " (:name f) ", which tools/ppocr/assets.edn does not pin"))
      (for [[nm [r f]] runtimes :when (not-any? #(= (:file %) nm) (:assets pp))]
        (str "runtime " (pr-str (:id r)) " has " nm ", which tools/ppocr/assets.edn does not pin")))))))

;; ------------------------------------------------------------------------------------------------------ generating
(defn pack-lang [s] (+ (bit-shift-left (int (nth s 0)) 24) (bit-shift-left (int (nth s 1)) 16) (bit-shift-left (int (nth s 2)) 8)))
(defn pack-script [s] (+ (bit-shift-left (int (nth s 0)) 24) (bit-shift-left (int (nth s 1)) 16) (bit-shift-left (int (nth s 2)) 8) (int (nth s 3))))

(defn i32 [^long n] (let [m (bit-and n 0xffffffff)] (if (>= m 0x80000000) (- m 0x100000000) m)))

(defn sha-words [hex] (mapv #(i32 (Long/parseLong (subs hex (* 8 %) (* 8 (inc %))) 16)) (range 8)))

(defn sym [prefix s] (str prefix (-> s str/lower-case (str/replace #"[^a-z0-9]+" "-") (str/replace #"^-|-$" ""))))

(defn rows-text [stride rows]
  (str/join "\n         " (map #(str/join " " %) rows)))

(defn names-data-text [names]
  (str/join "\n         " (map #(str/join " " %) (partition-all 24 names))))

(defn kin-text
  "kin/packdata.kin for the catalogue. Pure; `kn` is the kin names."
  [cat kn]
  (let [packs (:packs cat)
        lics (vec (distinct (map :licence packs)))
        lic-code (into {} (map-indexed (fn [i l] [l (inc i)]) lics))
        id-code (into {} (map-indexed (fn [i p] [(:id p) (inc i)]) packs))
        code-of-engine (fn [k] (if (= k :any) 0 (get (:engines kn) (name k))))
        files (vec (for [p packs, f (:files p)] [(:id p) f]))
        ;; names: a leading 0 so that offset 0 is no name, then each file name and a 0
        [names offsets] (reduce (fn [[ns offs] [_ f]] [(into ns (concat (map int (:name f)) [0])) (conj offs (count ns))]) [[0] []] files)
        first-file (reduce (fn [m [i [id _]]] (if (contains? m id) m (assoc m id i))) {} (map-indexed vector files))
        pack-rows (vec (for [p packs]
                         [(id-code (:id p)) (:rev p) (code-of-engine (:engine p)) (get (:kinds kn) (name (:kind p))) (lic-code (:licence p))
                          (first-file (:id p)) (count (:files p)) (reduce + (map :bytes (:files p)))]))
        lang-rows (vec (for [p packs, l (:langs p)] [(id-code (:id p)) (pack-lang l)]))
        script-rows (vec (for [p packs, s (:scripts p)] [(id-code (:id p)) (pack-script s)]))
        file-rows (vec (map-indexed (fn [i [id f]] (into [(id-code id) (:bytes f) (nth offsets i)] (sha-words (:sha256 f)))) files))
        enum (fn [rows] (str/join "\n   " (map (fn [[s c n]] (str "[" s " " c " " (pr-str n) "]")) rows)))]
    (str ";; GENERATED by tools/packs.clj (`bb packs --emit`) from release/packs/packs.edn -- do not edit.\n"
         ";;\n"
         ";; The model pack catalogue as kin holds it (K1, D898): what `emon.engines` resolves packs from and what every host verifies a pack's bytes against, so no host keeps a\n"
         ";; table of its own that could drift. " (count packs) " packs, " (count files) " files. A pack is immutable: a changed byte is a new :rev, and its hash a new identity.\n"
         ";;\n"
         ";;   pack-rows     [id rev engine kind licence file-first file-count bytes]   a pack's id is its place (1-based); engine is `emon.engines`' `eng` (0: every engine), kind its `pkind`\n"
         ";;   pack-langs    [id lang]      the language (three letters packed into the top three bytes, as a request packs it) a recognizer reads\n"
         ";;   pack-scripts  [id script]    the ISO 15924 script (four letters packed, as `emon.ocrport` packs one)\n"
         ";;   pack-files    [id bytes name sha0 .. sha7]   a file's size, where its name starts in `pack-names`, and its SHA-256 as eight big-endian words\n"
         ";;   pack-names    file names, ASCII, each ended by 0; offset 0 is a pad so that 0 is no name\n"
         "(ns emon.packdata\n"
         "  (:require [emon.lang :refer [I32 defenum]]\n"
         "            [kin.lang :refer [defdata]]))\n\n"
         "(defenum ^:pub ^:parse pkid\n  [" (enum (for [p packs] [(sym "pkid-" (:id p)) (id-code (:id p)) (:id p)])) "])\n\n"
         "(defenum ^:pub ^:parse plic\n  [" (enum (for [l lics] [(sym "plic-" l) (lic-code l) l])) "])\n\n"
         "(defdata ^:pub pack-rows\n  :emitter kin.lang/flat-array\n  :stride 8\n  :data [" (rows-text 8 pack-rows) "]\n"
         "  :accessors {^I32 pack-row-count [^I32 ignored]\n              ^I32 pack-row-at [^I32 ignored ^I32 row ^I32 col]})\n\n"
         "(defdata ^:pub pack-langs\n  :emitter kin.lang/flat-array\n  :stride 2\n  :data [" (rows-text 2 lang-rows) "]\n"
         "  :accessors {^I32 pack-lang-count [^I32 ignored]\n              ^I32 pack-lang-at [^I32 ignored ^I32 row ^I32 col]})\n\n"
         "(defdata ^:pub pack-scripts\n  :emitter kin.lang/flat-array\n  :stride 2\n  :data [" (rows-text 2 script-rows) "]\n"
         "  :accessors {^I32 pack-script-count [^I32 ignored]\n              ^I32 pack-script-at [^I32 ignored ^I32 row ^I32 col]})\n\n"
         "(defdata ^:pub pack-files\n  :emitter kin.lang/flat-array\n  :stride 11\n  :data [" (rows-text 11 file-rows) "]\n"
         "  :accessors {^I32 pack-file-count [^I32 ignored]\n              ^I32 pack-file-at [^I32 ignored ^I32 row ^I32 col]})\n\n"
         "(defdata ^:pub pack-names\n  :emitter kin.lang/flat-array\n  :data [" (names-data-text names) "]\n"
         "  :accessors {^I32 pack-name-count [^I32 ignored]\n              ^I32 pack-name-at [^I32 ignored ^I32 i]})\n")))

(defn json-text
  "release/packs/packs.json: the catalogue, in the authored order, for hooks and tools."
  [cat kn]
  (let [kw #(when % (name %))
        pack (fn [i p]
               (array-map "id" (:id p) "code" (inc i) "rev" (:rev p) "engine" (kw (:engine p)) "kind" (kw (:kind p))
                          "langs" (vec (:langs p)) "scripts" (vec (:scripts p)) "licence" (:licence p)
                          "upstream" (into (array-map) (for [k [:repo :revision :url] :when (get (:upstream p) k)] [(name k) (get (:upstream p) k)]))
                          "bytes" (reduce + (map :bytes (:files p)))
                          "files" (mapv #(array-map "name" (:name %) "sha256" (:sha256 %) "bytes" (:bytes %)) (:files p))))
        runtime (fn [r]
                  (array-map "id" (:id r) "engine" (kw (:engine r)) "target" (:target r) "licence" (:licence r)
                             "upstream" (into (array-map) (for [k [:repo :revision :url] :when (get (:upstream r) k)] [(name k) (get (:upstream r) k)]))
                             "files" (mapv #(array-map "name" (:name %) "sha256" (:sha256 %) "bytes" (:bytes %)) (:files r))))]
    (str (json/generate-string (array-map "schema" (:schema cat) "packs" (vec (map-indexed pack (:packs cat))) "runtimes" (mapv runtime (:runtimes cat))) {:pretty true}) "\n")))

;; ------------------------------------------------------------------------------------------------------ the command
(defn read-catalogue [] (edn/read-string (slurp catalogue-file)))
(defn read-kin-names [] (kin-names (slurp engines-file)))

(defn all-problems [cat]
  (concat (pack-problems cat (read-kin-names))
          (legacy-problems cat (edn/read-string (slurp tess-pins)) (edn/read-string (slurp pp-pins)))))

(defn report! [ps]
  (doseq [p (take 30 ps)] (println "  FAIL" p))
  (when (> (count ps) 30) (println "  FAIL ... and" (- (count ps) 30) "more"))
  (when (seq ps) (System/exit 1)))

(defn generated [cat]
  (let [kn (read-kin-names)]
    {kin-file (kin-text cat kn)
     json-file (json-text cat kn)}))

(defn self-test []
  (let [kn {:engines {"tesseract" 1 "ppocr" 2} :kinds {"recognizer" 1 "osd" 2}}
        sha (apply str (repeat 64 "a"))
        ok {:schema 1
            :packs [{:id "tess-fast-eng" :rev 1 :engine :tesseract :kind :recognizer :langs ["eng"] :scripts ["Latn"] :licence "Apache-2.0"
                     :upstream {:repo "r" :revision "r"} :files [{:name "eng.traineddata" :sha256 sha :bytes 5}]}]}
        plant (fn [f] (pack-problems (update-in ok [:packs 0] f) kn))
        cases [["unknown engine, by name" #(assoc % :engine :nope) #"unknown engine :nope"]
               ["unknown kind, by name" #(assoc % :kind :weights) #"unknown kind :weights"]
               ["a bad hash" #(assoc-in % [:files 0 :sha256] "xyz") #"no 64-digit lowercase :sha256"]
               ["a missing size" #(assoc-in % [:files 0 :bytes] 0) #"no positive :bytes"]
               ["a licence outside the allowlist" #(assoc % :licence "GPL-3.0") #"not in the allowlist"]
               ["a recognizer without a language" #(dissoc % :langs) #"needs :langs"]
               ["a language on a detector" #(assoc % :kind :osd) #"only a recognizer has"]
               ["an id that is not lowercase" #(assoc % :id "Tess_Eng") #"the id is not"]]
        fails (concat
               (for [[what f rx] cases :when (not-any? #(re-find rx %) (plant f))] (str "a planted " what " was not refused"))
               (when (seq (pack-problems ok kn)) ["the sound catalogue was refused"])
               (when (seq (legacy-problems ok {:revision "r" :models {"eng" {:sha256 sha :bytes 5}}} {:assets []})) ["a catalogue that agrees with its pins was refused"])
               (when-not (some #(re-find #"listed 2 times" %) (pack-problems (update ok :packs #(vec (concat % %))) kn)) ["a duplicate id was not refused"])
               (when-not (some #(re-find #"disagree|says sha256" %)
                               (legacy-problems ok {:revision "x" :models {"eng" {:sha256 (apply str (repeat 64 "b")) :bytes 5}}} {:assets []}))
                 ["a legacy pin that disagrees was not refused"])
               (when-not (some #(re-find #"has no pack|which the catalogue has no pack" %)
                               (legacy-problems ok {:revision "x" :models {"spa" {:sha256 sha :bytes 5}}} {:assets []}))
                 ["a legacy pin with no pack was not refused"]))]
    (report! fails)
    (println "  ok   packs self-test:" (+ (count cases) 3) "planted failures refused")))

(let [args (set *command-line-args*)]
  (cond
    (args "--self-test") (self-test)

    (args "--list")
    (let [cat (read-catalogue)]
      (doseq [[i p] (map-indexed vector (:packs cat))]
        (println (format "%2d %-18s rev %d %-10s %-11s %-10s %9d bytes  %s" (inc i) (:id p) (:rev p) (name (:engine p)) (name (:kind p))
                         (str/join "," (:langs p)) (reduce + (map :bytes (:files p))) (:licence p)))))

    (args "--emit")
    (let [cat (read-catalogue)
          _ (report! (all-problems cat))
          want (generated cat)]
      (if (args "--check")
        (let [stale (for [[f text] want :when (not= text (when (fs/exists? f) (slurp f)))] f)]
          (when (seq stale)
            (die (str/join ", " stale) " " (if (= 1 (count stale)) "is" "are") " not what " catalogue-file " generates (bb packs --emit writes " (if (= 1 (count stale)) "it" "them") ")"))
          (println "  ok   packs:" (count (:packs cat)) "packs;" kin-file "and" json-file "are what" catalogue-file "generates"))
        (do (doseq [[f text] want] (spit f text))
            (println "  wrote" kin-file "and" json-file "from" (count (:packs cat)) "packs"))))

    (args "--check")
    (let [cat (read-catalogue)]
      (report! (all-problems cat))
      (println "  ok   packs:" (count (:packs cat)) "packs sound and agreeing with tools/tessdata/models.edn and tools/ppocr/assets.edn"))

    :else (die "usage: bb packs --check | --emit [--check] | --list | --self-test")))
