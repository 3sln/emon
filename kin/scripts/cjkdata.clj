;; The predefined CJK CMaps and the Adobe CID -> Unicode tables as kin tables (DEVLOG P3.4, D140).
;;
;;     bb kin/scripts/cjkdata.clj            write kin/cmapjp.kin cmapgb.kin cmapcns.kin cmapkr.kin
;;     bb kin/scripts/cjkdata.clj --check    regenerate and compare with what is committed
;;     bb kin/scripts/cjkdata.clj --stats    print what each CMap costs
;; (the first two also write and check test/fonts/cjk.txt, the rows kin/pdffonttest.kin reads)
;;
;; INPUT: the files `kin/scripts/cjkfetch.clj` fetched into `.cache/cjk/` and checked against
;; `test/fonts/cjk-sources.edn` (a checksum that does not match is an error here too). OUTPUT: one module per
;; Adobe collection (`emon.cmapjp` Japan1, `cmapgb` GB1, `cmapcns` CNS1, `cmapkr` Korea1; one module each so that
;; no Java class needs more than 65535 constants), each holding
;;
;;   `<p>-cmap`  one row per predefined CMap: name offset and length in `<p>-name`, the row of the CMap it is
;;               written against (-1 none), WMode, codespace offset and count, range offset and count
;;   `<p>-cs`    codespace ranges as [bytes, lower high 16, lower low 16, upper high 16, upper low 16]
;;   `<p>-rng`   code ranges as [first code, last - first, CID]; a code of four bytes is a signed 32-bit
;;               number (what the content interpreter builds), so sorted by that
;;   `<p>-uni`   the collection's CID -> Unicode: [first CID, last - first, first code point]
;;
;; A CMap is NOT stored whole when it is nearly another one: it is stored as the codes where it differs from a
;; BASE CMap of the same collection (a code the base maps and it does not maps to CID 0), and a lookup that
;; finds nothing in a CMap's own ranges asks its base. That is `usecmap` (the vertical CMaps say so), found
;; automatically for the rest (UniJIS-UCS2-H against UniJIS-UTF16-H, the GBK variants, ...); the chain is at
;; most four deep, and the generator verifies that every CMap resolves to exactly what its file says.
;;
;; CID -> UNICODE comes from `cid2code.txt`'s Unicode columns (UTF-32 first, then UCS-2, then UTF-16): the
;; first column that has a value for the CID, and within it the candidate that is not a vertical variant and
;; is its own NFKC form (so CID 1 is U+0020, not U+00A0). A CID no column covers has no Unicode: those are the
;; alternate glyphs (swashes, ruby forms) a text extractor cannot name. A value that is several code points
;; is skipped.
(ns cjkdata
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.security MessageDigest)))

(def collections
  [{:p "jp" :dir "Adobe-Japan1-7" :ordering "Japan1" :ns "emon.cmapjp" :file "kin/cmapjp.kin"}
   {:p "gb" :dir "Adobe-GB1-6" :ordering "GB1" :ns "emon.cmapgb" :file "kin/cmapgb.kin"}
   {:p "cns" :dir "Adobe-CNS1-7" :ordering "CNS1" :ns "emon.cmapcns" :file "kin/cmapcns.kin"}
   {:p "kr" :dir "Adobe-Korea1-2" :ordering "Korea1" :ns "emon.cmapkr" :file "kin/cmapkr.kin"}])

(defn sha256 [^bytes bs]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md bs)))))

(defn src-file [p] (io/file ".cache/cjk" (str/replace p "/" "__")))

(def manifest (edn/read-string (slurp "test/fonts/cjk-sources.edn")))

(defn read-src [p]
  (let [f (src-file p)
        e (some #(when (= p (:path %)) %) (:files manifest))]
    (when-not (and e (.exists f))
      (println "FAIL missing source" p "(run bb kin/scripts/cjkfetch.clj)") (System/exit 1))
    (let [bs (java.nio.file.Files/readAllBytes (.toPath f))]
      (when (not= (sha256 bs) (:sha256 e))
        (println "FAIL checksum mismatch for" p) (System/exit 1))
      (String. bs "ISO-8859-1"))))

;; ------------------------------------------------------------- CMap files

(defn hexv [s] (Long/parseLong (str/replace (subs s 1 (dec (count s))) #"\s" "") 16))
(defn ncode [s]
  (quot (count (str/replace (subs s 1 (dec (count s))) #"\s" "")) 2))
(defn signed [v] (if (>= v 0x80000000) (- v 0x100000000) v))

(defn tokens [txt]
  (re-seq #"<[0-9a-fA-F\s]*>|/[^\s/<>\[\]()]+|[^\s<>/\[\]()%]+|%[^\n\r]*" txt))

(defn parse-cmap [txt]
  (loop [ts (remove #(str/starts-with? % "%") (tokens txt))
         sec nil
         acc {:cs [] :rng [] :parent nil :wmode 0}]
    (if-let [t (first ts)]
      (cond
        (= t "begincodespacerange") (recur (rest ts) :cs acc)
        (= t "begincidrange") (recur (rest ts) :rng acc)
        (= t "begincidchar") (recur (rest ts) :chr acc)
        (or (= t "beginnotdefrange") (= t "beginnotdefchar") (= t "beginbfrange") (= t "beginbfchar"))
        (recur (rest ts) :skip acc)
        (str/starts-with? t "end") (recur (rest ts) nil acc)
        (and (str/starts-with? t "/") (= "usecmap" (second ts)))
        (recur (drop 2 ts) sec (assoc acc :parent (subs t 1)))
        (= t "/WMode") (recur (drop 2 ts) sec (assoc acc :wmode (Integer/parseInt (second ts))))
        (= sec :cs) (let [[a b] ts]
                      (recur (drop 2 ts) sec (update acc :cs conj {:n (ncode a) :lo (hexv a) :hi (hexv b)})))
        (= sec :rng) (let [[a b c] ts]
                       (recur (drop 3 ts) sec
                              (update acc :rng conj [(signed (hexv a)) (signed (hexv b)) (Long/parseLong c)])))
        (= sec :chr) (let [[a c] ts]
                       (recur (drop 2 ts) sec
                              (update acc :rng conj [(signed (hexv a)) (signed (hexv a)) (Long/parseLong c)])))
        :else (recur (rest ts) sec acc))
      acc)))

;; ----------------------------------------- mappings: sorted, disjoint [lo hi cid]

(defn coalesce [rs]
  (reduce (fn [acc [lo hi cid :as x]]
            (let [[plo phi pc] (peek acc)]
              (if (and (seq acc) (= lo (inc phi))
                       (if (neg? cid) (neg? pc) (= cid (+ pc (- lo plo)))))
                (conj (pop acc) [plo hi pc])
                (conj acc x))))
          [] rs))

(defn normalize
  "Ranges in definition order (a later one wins) to sorted disjoint ranges."
  [rs]
  (let [sm (reduce
            (fn [m [lo hi cid]]
              ;; remove the overlap with existing, then add
              (let [m (reduce (fn [m [elo ehi ecid]]
                                (let [m (dissoc m elo)
                                      m (if (< elo lo) (assoc m elo [elo (dec lo) ecid]) m)
                                      m (if (> ehi hi) (assoc m (inc hi) [(inc hi) ehi (if (neg? ecid) ecid (+ ecid (- (inc hi) elo)))]) m)]
                                  m))
                              m
                              (map val (take-while (fn [[k [_ ehi _]]] (>= ehi lo)) (rsubseq m <= hi))))]
                (assoc m lo [lo hi cid])))
            (sorted-map) rs)]
    (coalesce (vec (vals sm)))))

(defn cid-at [rs code]
  (some (fn [[lo hi cid]] (when (<= lo code hi) (+ cid (- code lo)))) rs))

(defn lookup-fn [rs]
  (let [m (into (sorted-map) (map (fn [[lo hi cid]] [lo [hi cid]])) rs)]
    (fn [code]
      (when-let [[lo [hi cid]] (first (rsubseq m <= code))]
        (when (<= code hi) (+ cid (- code lo)))))))

(def lookup-memo (memoize lookup-fn))

(defn diff-ranges
  "Where x maps differently from b (b missing -> and x missing = 0), as ranges."
  [x b]
  (let [cuts (sort (distinct (mapcat (fn [[lo hi _]] [lo (inc hi)]) (concat x b))))
        fx (lookup-memo x)
        fb (lookup-memo b)
        segs (map vector cuts (rest cuts))
        out (for [[s e] segs
                  :let [cx (fx s) cb (fb s)]
                  :when (not= cx cb)]
              [s (dec e) (or cx -1)])]
    (coalesce (vec out))))

(defn apply-diff
  "The mapping base b with the own ranges d on top; a range of CID -1 removes what the base maps."
  [b d]
  (coalesce (vec (remove (fn [[_ _ cid]] (neg? cid)) (normalize (concat b d))))))

;; ----------------------------------------------------------- per collection

(defn load-collection [{:keys [dir]}]
  (let [names (->> (:files manifest)
                   (map :path)
                   (filter #(re-matches (re-pattern (str dir "/CMap/[^/]+")) %))
                   sort)]
    (into {}
          (for [p names
                :let [nm (last (str/split p #"/"))
                      m (parse-cmap (read-src p))]]
            [nm (assoc m :name nm :own (normalize (:rng m)))]))))

(defn effective
  "name -> sorted disjoint ranges of the CMap with its usecmap resolved."
  [cmaps]
  (let [memo (atom {})]
    (letfn [(eff [nm]
              (or (@memo nm)
                  (let [c (get cmaps nm)
                        r (if (:parent c)
                            (apply-diff (eff (:parent c)) (:own c))
                            (:own c))]
                    (swap! memo assoc nm r)
                    r)))]
      (into {} (map (fn [nm] [nm (eff nm)])) (keys cmaps)))))

(defn range-count [rs] (count rs))

(defn plan-bases
  "Choose for each CMap a base among those already planned, by smallest difference; returns
  [{:name :base :own} ...] in dependency order (a base comes first)."
  [cmaps eff]
  (let [order (sort-by (fn [nm] [(- (count (get eff nm))) nm]) (keys cmaps))]
    (loop [todo order planned [] depth {}]
      (if (empty? todo)
        planned
        (let [nm (first todo)
              x (get eff nm)
              explicit (:parent (get cmaps nm))
              ;; an explicit usecmap parent must be planned first: defer when it is not yet
              ready? (or (nil? explicit) (some #(= explicit (:name %)) planned))]
          (if-not ready?
            (recur (concat (rest todo) [nm]) planned depth)
            (let [near (sort-by (fn [p] [(Math/abs (- (count (get eff (:name p))) (count x))) (:name p)])
                                (filter (fn [p] (< (get depth (:name p)) 4)) planned))
                  ;; the six nearest in size, and the usecmap parent: any base is CORRECT, this only bounds
                  ;; the search (a difference cannot be small between two CMaps of very different size)
                  cands (distinct (concat (map :name (take 6 near)) (when explicit [explicit])))
                  scored (for [b cands] [b (diff-ranges x (get eff b))])
                  best (first (sort-by (fn [[b d]] [(count d) b]) scored))
                  full (count x)]
              (if (and best (or (= (first best) explicit) (< (* 10 (count (second best))) (* 6 full))))
                (recur (rest todo)
                       (conj planned {:name nm :base (first best) :own (second best)})
                       (assoc depth nm (inc (get depth (first best)))))
                (recur (rest todo)
                       (conj planned {:name nm :base nil :own x})
                       (assoc depth nm 0))))))))))

(defn resolve-plan [plan]
  (let [by (into {} (map (juxt :name identity)) plan)
        memo (atom {})]
    (letfn [(res [nm]
              (or (@memo nm)
                  (let [p (by nm)
                        r (if (:base p) (apply-diff (res (:base p)) (:own p)) (:own p))]
                    (swap! memo assoc nm r)
                    r)))]
      (into {} (map (fn [p] [(:name p) (res (:name p))])) plan))))

;; ------------------------------------------------------------ CID -> Unicode

(def decomposable
  "Code points that UnicodeData.txt gives a decomposition mapping (canonical or compatibility): the ones
  NFKC would replace (Hangul syllables, decomposed algorithmically, are not in it and are kept)."
  (delay (into #{}
               (for [l (str/split-lines (slurp "test/normalize/UnicodeData.txt"))
                     :let [f (str/split l #";" -1)]
                     :when (and (> (count f) 5) (not= "" (nth f 5)))]
                 (Long/parseLong (first f) 16)))))

(defn nfkc-stable? [cp] (not (contains? @decomposable cp)))

(defn parse-cands
  "cid2code cell -> [{:cp :v}] ; '*' none."
  [cell]
  (if (= cell "*")
    []
    (vec (for [c (str/split cell #",")
               :let [v? (str/ends-with? c "v")
                     h (if v? (subs c 0 (dec (count c))) c)
                     h (str/replace h #"^[Vv]" "")]
               :when (not (str/includes? h "+"))
               :when (re-matches #"[0-9a-fA-F]{4,8}" h)
               :let [n (count h)
                     cp (cond
                          (= n 8)
                          (let [hi (Long/parseLong (subs h 0 4) 16) lo (Long/parseLong (subs h 4) 16)]
                            (if (and (<= 0xD800 hi 0xDBFF) (<= 0xDC00 lo 0xDFFF))
                              (+ 0x10000 (* (- hi 0xD800) 0x400) (- lo 0xDC00))
                              (Long/parseLong h 16)))
                          :else (Long/parseLong h 16))]
               :when (and (<= cp 0x10FFFF) (not (<= 0xD800 cp 0xDFFF)))]
           {:cp cp :v v?}))))

(defn pick [cands]
  (let [h (remove :v cands)
        pool (if (seq h) h cands)
        stable (filter #(nfkc-stable? (:cp %)) pool)]
    (:cp (first (sort-by :cp (if (seq stable) stable pool))))))

(defn cid-unicode [{:keys [dir]}]
  (let [lines (str/split-lines (read-src (str dir "/cid2code.txt")))
        lines (remove #(str/starts-with? % "#") lines)
        hdr (str/split (first lines) #"\t")
        rows (map #(str/split % #"\t") (rest lines))
        col (fn [re] (keep-indexed (fn [i h] (when (re-matches re h) i)) hdr))
        cols (concat (col #"Uni[A-Za-z0-9]+-UTF32") (col #"Uni[A-Za-z0-9]+-UCS2[A-Za-z-]*") (col #"Uni[A-Za-z0-9]+-UTF16"))
        m (into (sorted-map)
                (for [r rows
                      :let [cid (Long/parseLong (first r))
                            cp (some (fn [ci] (when (< ci (count r))
                                                (let [cs (parse-cands (nth r ci))]
                                                  (when (seq cs) (pick cs)))))
                                     cols)]
                      :when cp]
                  [cid cp]))]
    (coalesce (mapv (fn [[cid cp]] [cid cid cp]) m))))

;; ---------------------------------------------------------------- emission

(defn table [nm doc stride flat accessors]
  (let [per (if (> stride 1) stride 16)]
    (str ";; " doc "\n"
         "(defdata ^:pub " nm "\n"
         "  :emitter kin.lang/flat-array\n"
         (when (> stride 1) (str "  :stride " stride "\n"))
         "  :data [" (str/join "\n         " (map #(str/join " " %) (partition-all per flat))) "]\n"
         "  :accessors {" (str/join "\n              " accessors) "})\n")))

(defn render [{:keys [p dir ordering ns] :as coll}]
  (let [cmaps (load-collection coll)
        eff (effective cmaps)
        plan (plan-bases cmaps eff)
        res (resolve-plan plan)
        _ (doseq [nm (keys cmaps)]
            (when (not= (get res nm) (get eff nm))
              (println "FAIL plan does not resolve to the file's mapping:" nm) (System/exit 1)))
        plan (vec (sort-by :name plan))
        row-of (into {} (map-indexed (fn [i x] [(:name x) i])) plan)
        name-pool (vec (mapcat #(map int (:name %)) plan))
        ;; codespaces: a CMap's own, else its usecmap parent's chain
        cs-of (fn cs-of [nm]
                (let [c (get cmaps nm)]
                  (if (seq (:cs c)) (:cs c) (when (:parent c) (cs-of (:parent c))))))
        cs-rows (atom []) rng-rows (atom [])
        rows (loop [ps plan noff 0 out []]
               (if (empty? ps)
                 out
                 (let [x (first ps)
                       c (get cmaps (:name x))
                       cs (cs-of (:name x))
                       cs-off (quot (count @cs-rows) 5)
                       _ (doseq [{:keys [n lo hi]} cs]
                           (swap! cs-rows into [n (bit-and (bit-shift-right lo 16) 0xFFFF) (bit-and lo 0xFFFF)
                                                (bit-and (bit-shift-right hi 16) 0xFFFF) (bit-and hi 0xFFFF)]))
                       rng-off (quot (count @rng-rows) 3)
                       _ (doseq [[lo hi cid] (:own x)]
                           (swap! rng-rows into [lo (- hi lo) cid]))]
                   (recur (rest ps) (+ noff (count (:name x)))
                          (conj out [noff (count (:name x)) (if (:base x) (row-of (:base x)) -1) (:wmode c)
                                     cs-off (count cs) rng-off (count (:own x))])))))
        uni (cid-unicode coll)
        uni-flat (mapcat (fn [[lo hi cp]] [lo (- hi lo) cp]) uni)
        total-full (reduce + (map #(count (get eff %)) (keys cmaps)))
        total-own (reduce + (map #(count (:own %)) plan))]
    {:stats {:cmaps (count plan) :full total-full :own total-own :uni (count uni)
             :by (map (fn [x] [(:name x) (count (get eff (:name x))) (count (:own x)) (:base x)]) plan)}
     :text
     (str ";; GENERATED by kin/scripts/cjkdata.clj from the Adobe CMap resources (test/fonts/cjk-sources.edn) -- do not edit.\n"
          ";;\n"
          ";; The predefined CMaps of the Adobe-" ordering " collection and its CID -> Unicode table, for `emon.pdffont`\n"
          ";; (P3.4, D140): " (count plan) " CMaps, " total-own " ranges kept of " total-full " (a CMap near another is stored as\n"
          ";; its differences from that BASE: see kin/scripts/cjkdata.clj), and " (count uni) " CID ranges of Unicode.\n"
          "(ns " ns "\n"
          "  (:require [emon.lang :refer [I32]]\n"
          "            [kin.lang :refer [defdata]]))\n\n"
          (table (str p "-name") "the CMap names, back to back" 1 name-pool
                 [(str "^I32 " p "-name-at [^I32 ignored ^I32 i]")])
          "\n"
          (table (str p "-cmap")
                 "name offset, name length, base row (-1 none), WMode, codespace offset, count, range offset, count"
                 8 (apply concat rows)
                 [(str "^I32 " p "-cmap-count [^I32 ignored]")
                  (str "^I32 " p "-cmap-at [^I32 ignored ^I32 row ^I32 field]")])
          "\n"
          (table (str p "-cs") "bytes, lower code high 16 bits, low 16, upper code high 16, low 16" 5 @cs-rows
                 [(str "^I32 " p "-cs-at [^I32 ignored ^I32 row ^I32 field]")])
          "\n"
          (table (str p "-rng") "first code, last - first, CID (a code of four bytes is a signed 32-bit number)" 3 @rng-rows
                 [(str "^I32 " p "-rng-at [^I32 ignored ^I32 row ^I32 field]")])
          "\n"
          (table (str p "-uni") "first CID, last - first, first code point" 3 uni-flat
                 [(str "^I32 " p "-uni-count [^I32 ignored]")
                  (str "^I32 " p "-uni-at [^I32 ignored ^I32 row ^I32 field]")]))}))

(defn sample-rows
  "Rows for kin/pdffonttest.kin (test/fonts/cjk.txt), from the CMaps AS THEIR FILES SAY (usecmap resolved,
  no diff compression): per CMap the first, a middle and the last code of up to 14 evenly spaced ranges, and
  the code just past a range that nothing maps (CID 0); per collection some CID -> Unicode rows. A four-byte
  code is a signed decimal."
  [{:keys [p ordering] :as coll}]
  (let [cmaps (load-collection coll)
        eff (effective cmaps)
        code-str (fn [c] (if (neg? c) (str "d" c) (format "%X" c)))
        cmap-rows
        (mapcat
         (fn [nm]
           (let [rs (vec (get eff nm))
                 n (count rs)
                 step (max 1 (quot n 14))
                 picks (take 14 (take-nth step rs))
                 covered (lookup-fn rs)]
             (concat
              (mapcat (fn [[lo hi cid]]
                        (let [mid (+ lo (quot (- hi lo) 2))]
                          (concat
                           [[nm lo cid] [nm mid (+ cid (- mid lo))] [nm hi (+ cid (- hi lo))]]
                           (when (and (not (covered (inc hi))) (<= (inc hi) 0xFFFF) (not (neg? lo)))
                             [[nm (inc hi) 0]]))))
                      picks))))
         (sort (keys cmaps)))
        uni (cid-unicode coll)
        uni-rows (for [[lo hi cp] (take 160 (take-nth (max 1 (quot (count uni) 160)) uni))
                       :let [mid (+ lo (quot (- hi lo) 2))]
                       [cid c] [[lo cp] [mid (+ cp (- mid lo))] [hi (+ cp (- hi lo))]]]
                   [ordering cid c])]
    (concat
     (for [[nm code cid] cmap-rows] (str "cid\t/" nm "\t" (code-str code) "\t" cid))
     (for [[o cid c] uni-rows] (str "uni\t" o "\t" cid "\t" (format "%04X" c))))))

(defn rows-text []
  (str "# GENERATED by kin/scripts/cjkdata.clj -- do not edit. Read by kin/pdffonttest.kin (P3.4, D140).\n"
       "# cid: a predefined CMap's name, a code (hex; `dNNN` a four-byte code as the signed 32-bit number the\n"
       "# content interpreter builds), the CID the CMap's own file gives it (0: not mapped).\n"
       "# uni: a collection's ordering, a CID, the code point cid2code.txt's Unicode columns give it.\n"
       (str/join "\n" (mapcat sample-rows collections)) "\n"))

(defn -main [& args]
  (let [check? (some #{"--check"} args)
        stats? (some #{"--stats"} args)
        bad (atom 0)]
    (doseq [c collections]
      (let [{:keys [text stats]} (render c)]
        (when stats?
          (println (:p c) (dissoc stats :by))
          (doseq [[n full own base] (:by stats)] (println "  " n full own base)))
        (cond
          check? (if (= text (slurp (:file c)))
                   (println "  ok  " (:file c) "matches its sources")
                   (do (println "FAIL" (:file c) "differs from what cjkdata.clj generates") (swap! bad inc)))
          (not stats?) (do (spit (:file c) text) (println "  wrote" (:file c) (count text) "bytes")))))
    (when-not stats?
      (let [text (rows-text)]
        (cond
          check? (if (= text (slurp "test/fonts/cjk.txt"))
                   (println "  ok   test/fonts/cjk.txt matches its sources")
                   (do (println "FAIL test/fonts/cjk.txt differs from what cjkdata.clj generates") (swap! bad inc)))
          :else (do (spit "test/fonts/cjk.txt" text) (println "  wrote test/fonts/cjk.txt" (count text) "bytes")))))
    (when (pos? @bad) (System/exit 1))))

(apply -main *command-line-args*)
