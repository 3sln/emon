#!/usr/bin/env bb
;; The Unicode bidirectional classes as a kin table, and a conformance subset (DEVLOG, D162).
;;
;;     bb kin/scripts/bididata.clj            write kin/bidiclass.kin
;;     bb kin/scripts/bididata.clj --check    regenerate it and compare; check test/bidi/conformance.txt's rows
;;                                            against the table (offline: reads only committed files)
;;     bb kin/scripts/bididata.clj --fetch    download BidiCharacterTest.txt (Unicode 18.0.0) into .cache/bidi/
;;     bb kin/scripts/bididata.clj --rebuild  rewrite test/bidi/conformance.txt from .cache/bidi/ (needs --fetch first)
;;
;; THE TABLES come from `test/normalize/UnicodeData.txt` (field 4, the bidi class) and `test/bidi/BidiBrackets.txt`
;; (the paired brackets, for N0) and `test/bidi/BidiMirroring.txt` (L4), with the defaults UCD gives to
;; unassigned code points of the right-to-left blocks (DerivedBidiClass). Stored as the start of each run and its
;; class, with ONE code per class (kin/bidi.kin names them). THE CONFORMANCE SUBSET is every row of Unicode's
;; BidiCharacterTest.txt that emon's resolver claims: paragraph level 1 (right to left), and only the classes
;; L R AL EN AN ES ET CS NSM WS ON (brackets included: N0), no explicit embeddings, isolates or separators; every
;; STRIDE-th row of them, so that the file is a size a repository can carry. Each row keeps the code points, the resolved levels and the visual order as the
;; Unicode file gives them: those two are the witness, not anything emon computed.
(require '[clojure.string :as str] '[clojure.java.io :as io] '[babashka.fs :as fs])

(def root (-> *file* io/file .getCanonicalFile .getParentFile .getParentFile .getParent))
(def url "https://www.unicode.org/Public/UCD/latest/ucd/BidiCharacterTest.txt")
(def cache (str root "/.cache/bidi/BidiCharacterTest.txt"))
(def class-code {"ON" 0 "R" 1 "L" 2 "EN" 3 "AN" 4 "ES" 5 "ET" 6 "CS" 7 "AL" 8 "WS" 9 "NSM" 10 "BN" 11})
(defn code-of [n] (get class-code n 12))                  ; B S and the explicit controls: 12
(def in-scope #{0 1 2 3 4 5 6 7 8 9 10})
(def stride 12)

(defn hex [s] (Integer/parseInt s 16))

(def defaults                                                ; unassigned code points (DerivedBidiClass.txt)
  [[0x0590 0x05FF "R"] [0x07C0 0x085F "R"] [0xFB1D 0xFB4F "R"] [0x10800 0x10CFF "R"] [0x10D40 0x10EBF "R"]
   [0x10F00 0x10F2F "R"] [0x10F70 0x10FFF "R"] [0x1E800 0x1EC6F "R"] [0x1ECC0 0x1ECFF "R"]
   [0x1ED50 0x1EDFF "R"] [0x1EF00 0x1EFFF "R"]
   [0x0600 0x07BF "AL"] [0x0860 0x08FF "AL"] [0xFB50 0xFDCF "AL"] [0xFDF0 0xFDFF "AL"] [0xFE70 0xFEFF "AL"]
   [0x10D00 0x10D3F "AL"] [0x10EC0 0x10EFF "AL"] [0x10F30 0x10F6F "AL"] [0x1EC70 0x1ECBF "AL"]
   [0x1ED00 0x1ED4F "AL"] [0x1EE00 0x1EEFF "AL"]
   [0x20A0 0x20CF "ET"]])

(defn unicode-data []
  (let [lines (->> (str/split-lines (slurp (str root "/test/normalize/UnicodeData.txt")))
                   (remove str/blank?) (map #(str/split % #";" -1)))]
    (loop [ls lines, out [], pending nil]
      (if-let [[cp nm gc _ bc] (first ls)]
        (let [c (hex cp)]
          (cond (str/ends-with? nm ", First>") (recur (rest ls) out [c gc bc])
                (str/ends-with? nm ", Last>") (recur (rest ls) (conj out [(first pending) c (nth pending 1) (nth pending 2)]) nil)
                :else (recur (rest ls) (conj out [c c gc bc]) nil)))
        out))))

(defn tables []
  (let [^ints arr (int-array 0x110000 -1)                          ; -1 unassigned
        ^objects gcs (object-array 0x110000)]
    (doseq [[lo hi gc bc] (unicode-data) c (range lo (inc hi))]
      (aset arr c (int (code-of bc))) (aset gcs c gc))
    (doseq [[lo hi bc] defaults c (range lo (inc hi))
            :when (neg? (aget arr c))]
      (aset arr c (int (code-of bc))))
    (dotimes [c 0x110000] (when (neg? (aget arr c)) (aset arr c (int 2))))   ; every other unassigned code point: L
    {:cls arr :gc gcs}))

(defn runs [^ints arr]
  (loop [c 0, prev -1, out []]
    (if (= c 0x110000) out
        (let [k (aget arr c)] (recur (inc c) k (if (= k prev) out (conj out c k)))))))

(defn brackets []                                           ; [cp kind key] sorted by cp: kind 0 open 1 close; key is the canonical id of the pair's opening bracket
  (let [decomp (into {} (for [l (str/split-lines (slurp (str root "/test/normalize/UnicodeData.txt")))
                              :let [fs (str/split l #";" -1)]
                              :when (re-matches #"[0-9A-F]{4,6}" (nth fs 5))]
                          [(hex (first fs)) (hex (nth fs 5))]))
        cn (fn [c] (get decomp c c))
        rows (for [l (str/split-lines (slurp (str root "/test/bidi/BidiBrackets.txt")))
                   :when (not (or (str/blank? l) (str/starts-with? l "#")))
                   :let [[cp pair kind] (map str/trim (str/split (first (str/split l #"#")) #";"))
                         c (hex cp) p (hex pair) open? (= kind "o")]]
               [c (if open? 0 1) (if open? (cn c) (cn p))])]
    (vec (sort-by first rows))))

(defn mirrors []                                            ; [cp mirror] sorted by cp (BidiMirroring.txt)
  (vec (sort-by first
        (for [l (str/split-lines (slurp (str root "/test/bidi/BidiMirroring.txt")))
              :when (not (or (str/blank? l) (str/starts-with? l "#")))
              :let [[a b] (map str/trim (str/split (first (str/split l #"#")) #";"))]]
          [(hex a) (hex b)]))))

(defn rows [xs n] (str/join "\n         " (map #(str/join " " %) (partition-all n xs))))

(defn render [^ints arr]
  (let [r (runs arr)]
    (str ";; GENERATED by kin/scripts/bididata.clj -- do not edit.\n"
         ";;\n"
         ";; The Unicode 18.0.0 bidirectional class of every code point (UnicodeData.txt field 4; the unassigned ones of the\n"
         ";; right-to-left blocks as DerivedBidiClass gives them, every other as L), as the start of each run of one class\n"
         ";; and that class: " (quot (count r) 2) " runs. Class codes: 0 ON, 1 R, 2 L, 3 EN, 4 AN, 5 ES, 6 ET, 7 CS, 8 AL, 9 WS,\n"
         ";; 10 NSM, 11 BN, 12 anything else (B, S, the embedding, override and isolate controls).\n"
         "(ns emon.bidiclass\n  (:require [emon.lang :refer [I32]]\n            [kin.lang :refer [defdata]]))\n\n"
         "(defdata ^:pub bc-runs\n  :emitter kin.lang/flat-array\n  :stride 2\n  :data [" (rows r 16) "]\n"
         "  :accessors {^I32 bc-run-count [^I32 ignored]\n"
         "              ^I32 bc-run-at [^I32 ignored ^I32 row ^I32 field]})\n\n"
         ";; the paired brackets (BidiBrackets.txt), sorted by code point: the code point, 0 for an opening and 1 for a closing\n"
         ";; bracket, and the pair's key (the canonical form of its opening bracket: U+2329 and U+3008 are one pair)\n"
         "(defdata ^:pub bc-brackets\n  :emitter kin.lang/flat-array\n  :stride 3\n  :data [" (rows (apply concat (brackets)) 15) "]\n"
         "  :accessors {^I32 bc-bracket-count [^I32 ignored]\n"
         "              ^I32 bc-bracket-at [^I32 ignored ^I32 row ^I32 field]})\n\n"
         ";; the mirrored characters (BidiMirroring.txt), sorted by code point: the code point and the one whose glyph is its mirror image\n"
         "(defdata ^:pub bc-mirrors\n  :emitter kin.lang/flat-array\n  :stride 2\n  :data [" (rows (apply concat (mirrors)) 16) "]\n"
         "  :accessors {^I32 bc-mirror-count [^I32 ignored]\n"
         "              ^I32 bc-mirror-at [^I32 ignored ^I32 row ^I32 field]})\n")))

(defn subset-rows []
  (let [{:keys [cls gcs]} (let [t (tables)] {:cls (:cls t) :gcs (:gc t)})]
    (for [l (str/split-lines (slurp cache))
          :when (and (not (str/blank? l)) (not (str/starts-with? l "#")))
          :let [[cps dir plevel levels visual] (str/split l #";")
                cs (map hex (str/split cps #" "))]
          :when (and (#{"1" "2"} dir) (= plevel "1"))
          :when (every? (fn [c] (and (in-scope (aget ^ints cls c))
                                                                          (some? (aget ^objects gcs c))))
                        cs)
          :when (not (str/includes? levels "x"))]
      (str cps ";" levels ";" visual))))

(defn thin [rs] (take-nth stride (distinct rs)))

(defn check-subset []
  (let [{:keys [cls gc]} (tables)
        f (io/file root "test/bidi/conformance.txt")]
    (when-not (.exists f) (println "no test/bidi/conformance.txt") (System/exit 1))
    (doseq [l (remove #(or (str/blank? %) (str/starts-with? % "#")) (str/split-lines (slurp f)))
            :let [[cps] (str/split l #";")]
            c (map hex (str/split cps #" "))]
      (when-not (and (in-scope (aget ^ints cls c)) (some? (aget ^objects gc c)))
        (println "row out of scope:" l) (System/exit 1)))))

(let [arg (first *command-line-args*)
      out (str root "/kin/bidiclass.kin")]
  (case arg
    "--fetch" (do (fs/create-dirs (fs/parent cache))
                  (spit cache (slurp url))
                  (println "fetched" (fs/size cache) "bytes"))
    "--rebuild" (let [rs (thin (subset-rows))
                      sha (let [md (java.security.MessageDigest/getInstance "SHA-256")]
                            (apply str (map #(format "%02x" %) (.digest md (fs/read-all-bytes cache)))))]
                  (fs/create-dirs (str root "/test/bidi"))
                  (spit (str root "/test/bidi/conformance.txt")
                        (str "# The rows of Unicode's BidiCharacterTest-18.0.0.txt (" url ", sha256 " sha ") that\n"
                             "# emon's resolver claims (kin/scripts/bididata.clj): paragraph level 1, classes L R AL EN AN ES ET CS NSM WS ON,\n"
                             "# no brackets. Each row: code points; resolved levels; visual order (the Unicode file's own columns).\n"
                             (str/join "\n" rs) "\n"))
                  (println (count rs) "rows"))
    "--check" (let [want (render (:cls (tables)))]
                (if (= want (slurp out))
                  (do (check-subset) (println "  ok   kin/bidiclass.kin matches the UCD files; test/bidi/conformance.txt is in scope"))
                  (do (println "kin/bidiclass.kin is stale; run bb kin/scripts/bididata.clj") (System/exit 1))))
    nil (do (spit out (render (:cls (tables)))) (println "wrote" out))
    (do (println "usage: bididata.clj [--check|--fetch|--rebuild]") (System/exit 2))))
