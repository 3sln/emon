#!/usr/bin/env bb
;; What did alignment do?  Alignment coverage, precision, splits, merges and mismatch rates from the dumps of the fixtures
;; (test/fusion/align/ID.dump) (DEVLOG P7.1, D250-D252). NOT a gate by itself: `kin/scripts/align` calls it and holds the numbers.
;;
;;     bb -cp tools:tools/lib tools/fusion/align_report.clj [--self-test] [DUMP...]      default every dump of test/fusion/align/
;;
;; A dump is the alignment probe's: one line a group, `g<n> p<page> t<text words> o<OCR words> h<hidden words> f<flags> s<sim>
;; c<simfold> l<tracer line> | T ... | O ... | H ...` (SPEC 9.2). The measures, over a document's groups:
;;
;;   coverage    the visible text-layer words that sit in a group with an OCR candidate, over all visible text-layer words
;;   precision   of the 1:1 groups (one text word, one OCR word, no flag), the share whose case-folded similarity is at least 800
;;   agreement   of the groups that have both sides, the share whose case-folded similarity is at least `agree-sim` 900 (SPEC 9.3)
;;   split, merged, text-only, ocr-only   the groups with that flag (SPEC 9.7 `plg`)
;;   grouped-low the share of the groups that have both sides whose case-folded similarity is under 300 (a broken mapping's
;;               signature: the geometry pairs the words and the letters disagree)
(ns fusion.align-report
  (:require [clojure.string :as str]
            [babashka.fs :as fs]))

(def line-re #"^g(\d+) p(\d+) t(\d+) o(\d+) h(\d+) f(\d+) s(-?\d+) c(-?\d+) l(-?\d+) n(-?\d+) \| T (.*) \| O (.*) \| H (.*)$")

(defn parse-group [line]
  (when-let [[_ g p t o h f s c l fn tt oo hh] (re-matches line-re line)]
    (let [n #(Long/parseLong %)]
      {:g (n g) :page (n p) :t (n t) :o (n o) :h (n h) :flags (n f) :sim (n s) :fold (n c) :tline (n l) :font (n fn) :T tt :O oo :H hh})))

(defn parse-dump [text] (vec (keep parse-group (str/split-lines text))))

(declare show*)

(defn pct [a b] (if (pos? b) (/ (double a) b) 0.0))

(defn measure [groups]
  (let [tw (reduce + (map :t groups))
        covered (reduce + (map :t (filter #(and (pos? (:t %)) (pos? (:o %))) groups)))
        one (filter #(and (= 1 (:t %)) (= 1 (:o %)) (zero? (:flags %))) groups)
        both (filter #(and (pos? (:t %)) (pos? (:o %))) groups)
        flagged (fn [bit] (count (filter #(pos? (bit-and bit (:flags %))) groups)))]
    {:groups (count groups) :pages (count (distinct (map :page groups)))
     :text-words tw :covered covered :coverage (pct covered tw)
     :one-to-one (count one) :precision (pct (count (filter #(>= (:fold %) 800) one)) (count one))
     :both (count both) :agreement (pct (count (filter #(>= (:fold %) 900) both)) (count both))
     :low (pct (count (filter #(< (:fold %) 300) both)) (count both))
     :split (flagged 4) :merged (flagged 8) :text-only (flagged 1) :ocr-only (flagged 2)
     :hidden-words (reduce + (map :h groups))
     :hidden-covered (reduce + (map :h (filter #(pos? (:o %)) groups)))
     :hidden-agreement (pct (count (filter #(and (zero? (:t %)) (pos? (:h %)) (pos? (:o %)) (>= (:fold %) 900)) groups))
                            (count (filter #(and (zero? (:t %)) (pos? (:h %)) (pos? (:o %))) groups)))}))

(defn measure-font
  "The measures of the text-layer words whose group's first word is in font row `font`: how many, the share grouped with an OCR
  candidate, and the share grouped with one whose case-folded similarity is under 300."
  [groups font]
  (let [gs (filter #(and (pos? (:t %)) (= font (:font %))) groups)
        n (reduce + (map :t gs))
        grouped (reduce + (map :t (filter #(pos? (:o %)) gs)))
        low (reduce + (map :t (filter #(and (pos? (:o %)) (< (:fold %) 300)) gs)))]
    {:words n :grouped (pct grouped n) :low (pct low n)}))

(defn pool [ms]
  (let [s #(reduce + (map % ms))
        w (fn [num den] (pct (reduce + (map (fn [m] (* (num m) (den m))) ms)) (reduce + (map den ms))))]
    {:groups (s :groups) :pages (s :pages) :text-words (s :text-words) :covered (s :covered)
     :coverage (pct (s :covered) (s :text-words)) :one-to-one (s :one-to-one)
     :precision (w :precision :one-to-one) :both (s :both) :agreement (w :agreement :both) :low (w :low :both)
     :split (s :split) :merged (s :merged) :text-only (s :text-only) :ocr-only (s :ocr-only)
     :hidden-words (s :hidden-words) :hidden-covered (s :hidden-covered)}))

(defn show [label m]
  (str (show* label m)
       (when (pos? (:hidden-words m))
         (format "  hidden %d words, %.4f covered" (:hidden-words m) (pct (:hidden-covered m) (:hidden-words m))))))

(defn show* [label m]
  (format "%-20s %5d groups %2d pages  coverage %.4f (%d of %d)  precision %.4f of %d 1:1  agreement %.4f of %d  low %.4f  split %d merged %d  text-only %d ocr-only %d"
          label (:groups m) (:pages m) (:coverage m) (:covered m) (:text-words m) (:precision m) (:one-to-one m)
          (:agreement m) (:both m) (:low m) (:split m) (:merged m) (:text-only m) (:ocr-only m)))

(defn self-test []
  (let [gs (parse-dump (str "g0 p1 t1 o1 h0 f0 s1000 c1000 l-1 n0 | T a[1,2,3,4] | O a[1,2,3,4] | H \n"
                            "g1 p1 t1 o1 h0 f0 s500 c500 l-1 n0 | T b | O c | H \n"
                            "g2 p1 t2 o1 h0 f8 s900 c900 l-1 n0 | T d e | O de | H \n"
                            "g3 p1 t1 o0 h0 f1 s-1 c-1 l-1 n0 | T f | O  | H \n"
                            "g4 p1 t0 o1 h0 f2 s-1 c-1 l-1 n0 | T  | O g | H \n"))
        m (measure gs)]
    (assert (= 5 (:groups m)))
    (assert (= 5 (:text-words m)) (:text-words m))
    (assert (= 4 (:covered m)))
    (assert (= 0.5 (:precision m)))
    (assert (= 1 (:merged m)))
    (assert (= 1 (:text-only m)))
    (assert (= 1 (:ocr-only m)))
    (println "  ok   align-report self-test: the measures of a hand-made dump")))

(defn -main [& args]
  (let [args (vec args)]
    (cond
      (some #{"--self-test"} args) (self-test)
      :else
      (let [files (or (seq (remove #(str/starts-with? % "--") args))
                      (sort (map str (fs/glob "test/fusion/align" "*.dump"))))
            ms (for [f files] [(str/replace (fs/file-name f) #"\.dump$" "") (measure (parse-dump (slurp f)))])]
        (doseq [[id m] ms] (println (show id m)))
        (when (> (count ms) 1) (println (show "pooled" (pool (map second ms)))))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))
