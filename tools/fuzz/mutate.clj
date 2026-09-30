;; The mutators of `bb fuzz` (DEVLOG P2.12, D096), and the SplitMix64 that drives
;; them. Babashka only.
;;
;; EVERY CASE IS A PURE FUNCTION OF (seed, index, fixture bytes): case i seeds its
;; own SplitMix64 with the i-th output of the run's seed (`kin/splitmix.kin`'s
;; `sm-at`), picks a fixture and 1..3 operations, and applies them. So a failure is
;; reproduced from its seed and index alone (`bb fuzz --seed S --only I`), no
;; matter how many cases ran before it or on how many hosts.
;;
;; THREE FAMILIES. Blind ones: truncate at a chosen point, flip bytes, splice
;; (delete / duplicate / insert / swap a span). Structure-aware ones, found by
;; regex over the file as Latin-1 (byte i is char i): a number in an xref entry
;; (`0000000017 00000 n`), a `/Length`, an object header (`12 0 obj`), a stream
;; boundary (`stream` / `endstream` and the EOL after it), and the trailer and
;; object-stream numbers (`/Size /Prev /Root /First /N /Count /XRefStm`,
;; `startxref`, `/W` and `/Index` arrays).
(ns fuzz.mutate
  (:require [clojure.string :as str]))

;; ------------------------------------------------------------- SplitMix64

(def ^:private golden -7046029254386353131)   ;; 0x9E3779B97F4A7C15
(def ^:private mix-a -4658895280553007687)    ;; 0xBF58476D1CE4E5B9
(def ^:private mix-b -7723592293110705685)    ;; 0x94D049BB133111EB

(defn sm-mix [z]
  (let [a (unchecked-multiply (bit-xor z (unsigned-bit-shift-right z 30)) mix-a)
        b (unchecked-multiply (bit-xor a (unsigned-bit-shift-right a 27)) mix-b)]
    (bit-xor b (unsigned-bit-shift-right b 31))))

(defn sm-at
  "The i-th output (0-based) of a generator seeded with `seed`."
  [seed i]
  (sm-mix (unchecked-add seed (unchecked-multiply (inc i) golden))))

(defn rng [seed] (atom seed))

(defn next! [r]
  (sm-mix (swap! r unchecked-add golden)))

(defn below
  "Uniform in [0, n); 0 when n <= 0."
  [r n]
  (if (<= n 0) 0 (Long/remainderUnsigned (next! r) (long n))))

(defn between [r lo hi] (+ lo (below r (inc (- hi lo)))))
(defn pick [r xs] (nth xs (below r (count xs))))

;; ------------------------------------------------------------------ bytes

(defn ->latin1 ^String [^bytes bs] (String. bs "ISO-8859-1"))
(defn latin1-> ^bytes [^String s] (.getBytes s "ISO-8859-1"))

(defn splice
  "bs with [from, to) replaced by `repl` (a byte array)."
  ^bytes [^bytes bs from to ^bytes repl]
  (let [n (alength bs)
        from (max 0 (min n from))
        to (max from (min n to))
        out (byte-array (+ from (alength repl) (- n to)))]
    (System/arraycopy bs 0 out 0 from)
    (System/arraycopy repl 0 out from (alength repl))
    (System/arraycopy bs to out (+ from (alength repl)) (- n to))
    out))

(defn sub ^bytes [^bytes bs from to]
  (java.util.Arrays/copyOfRange bs (int from) (int to)))

(defn find-all
  "[[start end group1-start group1-end] ...] of every match of `re` in bs."
  [^bytes bs re]
  (let [m (re-matcher re (->latin1 bs))]
    (loop [acc []]
      (if (.find m)
        (recur (conj acc (if (pos? (.groupCount m))
                           [(.start m) (.end m) (.start m 1) (.end m 1)]
                           [(.start m) (.end m) (.start m) (.end m)])))
        acc))))

(defn- digits [r n] (apply str (repeatedly n #(below r 10))))

(defn num-variant
  "A replacement for the decimal literal `s`."
  [r s]
  (let [v (try (Long/parseLong s) (catch Exception _ 0))]
    (case (int (below r 14))
      0 "0"
      1 "1"
      2 "-1"
      3 (str (below r 21))
      4 (str (+ v 1))
      5 (str (max 0 (- v 1)))
      6 (str (max 0 (+ v (between r -100 100))))
      7 "2147483647"
      8 "2147483648"
      9 "4294967295"
      10 "99999999999999999999"
      11 (digits r (max 1 (count s)))
      12 (subs s 0 (max 0 (dec (count s))))          ;; a digit short
      13 (str s (below r 10)))))                      ;; a digit long

;; ---------------------------------------------------------------- mutators
;; Each takes (r bs) and answers [bs' description], or nil when the file has
;; nothing of that shape (the caller falls back to a blind mutator).

(defn m-truncate [r ^bytes bs]
  (let [n (alength bs)
        marks (mapv first (find-all bs #"(?:startxref|endobj|endstream|xref|trailer)"))
        p (case (int (below r 4))
            0 (below r (inc n))
            1 (max 0 (- n (below r 80)))
            2 (if (seq marks) (max 0 (+ (pick r marks) (between r -3 12))) (below r (inc n)))
            3 (min n (below r 300)))]
    [(sub bs 0 (min n p)) (str "truncate@" p)]))

(defn m-flip [r ^bytes bs]
  (let [n (alength bs)
        k (between r 1 8)
        out (aclone bs)]
    (if (zero? n)
      [out "flip@empty"]
      (do (dotimes [_ k]
            (let [i (below r n)]
              (aset-byte out i
                         (unchecked-byte
                          (case (int (below r 4))
                            0 (bit-xor (aget out i) (bit-shift-left 1 (below r 8)))
                            1 (below r 256)
                            2 0
                            3 255)))))
          [out (str "flip x" k)]))))

(defn m-splice [r ^bytes bs]
  (let [n (alength bs)
        at (below r (inc n))
        len (between r 1 (min 64 (max 1 n)))
        to (min n (+ at len))]
    (case (int (below r 4))
      0 [(splice bs at to (byte-array 0)) (str "delete[" at "," to ")")]
      1 [(splice bs at at (sub bs at to)) (str "dup[" at "," to ")")]
      2 [(splice bs at at (byte-array (repeatedly len #(unchecked-byte (below r 256)))))
         (str "insert@" at " x" len)]
      3 (let [b (below r (inc n))
              b2 (min n (+ b len))]
          (if (<= to b)
            [(splice (splice bs b b2 (sub bs at to)) at to (sub bs b b2))
             (str "swap[" at "," to ")<->[" b "," b2 ")")]
            [bs "swap-noop"])))))

(defn- replace-group
  "Replace the match's group with `txt`."
  [^bytes bs [_ _ gs ge] ^String txt]
  (splice bs gs ge (latin1-> txt)))

(defn m-xref-entry [r ^bytes bs]
  (when-let [ms (seq (find-all bs #"(?m)^(\d{10}) \d{5} [nf]"))]
    (let [[s e :as m] (pick r (vec ms))]
      (case (int (below r 4))
        0 (let [v (num-variant r (subs (->latin1 bs) s (+ s 10)))]
            [(replace-group bs m v) (str "xref-entry@" s " offset->" v)])
        1 [(splice bs (+ s 11) (+ s 16) (latin1-> (digits r 5))) (str "xref-entry@" s " gen")]
        2 [(splice bs (+ s 17) (+ s 18) (latin1-> (pick r ["f" "n" "x" " "]))) (str "xref-entry@" s " type")]
        3 [(splice bs s e (latin1-> (str (digits r 10) " " (digits r 5) " n"))) (str "xref-entry@" s " whole")]))))

(defn m-length [r ^bytes bs]
  (when-let [ms (seq (find-all bs #"/Length\s+(\d+)(?!\s+\d+\s+R)"))]
    (let [[s _ gs ge :as m] (pick r (vec ms))
          n (alength bs)
          old (subs (->latin1 bs) gs ge)
          v (case (int (below r 5))
              0 "0"
              1 (str n)
              2 (num-variant r old)
              3 (str (max 0 (+ (try (Long/parseLong old) (catch Exception _ 0)) (between r -5 5))))
              4 "-5")]
      [(replace-group bs m v) (str "length@" s " " old "->" v)])))

(defn m-length-ref [r ^bytes bs]
  (when-let [ms (seq (find-all bs #"/Length\s+(\d+)\s+\d+\s+R"))]
    (let [[s _ gs ge :as m] (pick r (vec ms))
          old (subs (->latin1 bs) gs ge)
          v (num-variant r old)]
      [(replace-group bs m v) (str "length-ref@" s " " old "->" v)])))

(defn m-objhdr [r ^bytes bs]
  (when-let [ms (seq (find-all bs #"(?m)^(\d+) \d+ obj"))]
    (let [[s e gs ge :as m] (pick r (vec ms))
          txt (subs (->latin1 bs) s e)]
      (case (int (below r 5))
        0 [(replace-group bs m (num-variant r (subs txt 0 (- ge gs)))) (str "objhdr@" s " number")]
        1 (let [gen-at (+ ge 1)]
            [(splice bs gen-at (+ gen-at 1) (latin1-> (str (below r 10)))) (str "objhdr@" s " gen")])
        2 [(splice bs (- e 3) e (latin1-> (pick r ["ob" "0bj" "obk" "obj2" "oobj" ""]))) (str "objhdr@" s " keyword")]
        3 [(splice bs s s (latin1-> (str txt "\n"))) (str "objhdr@" s " duplicated")]
        4 [(splice bs s e (byte-array 0)) (str "objhdr@" s " removed")]))))

(defn m-stream-boundary [r ^bytes bs]
  (let [sm (find-all bs #"stream(?:\r\n|\n|\r)?")
        em (find-all bs #"endstream")
        all (vec (concat (map #(conj % :s) sm) (map #(conj % :e) em)))]
    (when (seq all)
      (let [[s e _ _ kind] (pick r all)]
        (if (= kind :s)
          (case (int (below r 5))
            0 [(splice bs s e (latin1-> "stream")) (str "stream@" s " EOL removed")]
            1 [(splice bs s e (latin1-> "stream\r")) (str "stream@" s " CR only")]
            2 [(splice bs s (+ s 6) (latin1-> "strea")) (str "stream@" s " keyword broken")]
            3 [(splice bs s s (latin1-> (apply str (repeat (between r 1 9) " ")))) (str "stream@" s " shifted")]
            4 [(splice bs e e (latin1-> (digits r (between r 1 6)))) (str "stream@" s " data shifted")])
          (case (int (below r 5))
            0 [(splice bs s e (byte-array 0)) (str "endstream@" s " removed")]
            1 [(splice bs s (+ s 3) (latin1-> "end")) (str "endstream@" s " truncated")]
            2 [(splice bs s s (latin1-> (digits r (between r 1 8)))) (str "endstream@" s " data grown")]
            3 [(splice bs (max 0 (- s (between r 1 6))) s (byte-array 0)) (str "endstream@" s " data shrunk")]
            4 [(splice bs s e (latin1-> "endobj")) (str "endstream@" s " -> endobj")]))))))

(defn m-trailer-num [r ^bytes bs]
  (let [keyed (find-all bs #"/(?:Size|Prev|Root|First|N|Count|XRefStm|Info|Parent)\s+(\d+)")
        sx (find-all bs #"startxref\s+(\d+)")
        arr (find-all bs #"/(?:W|Index)\s*\[([^\]]*)\]")
        kids (find-all bs #"/Kids\s*\[([^\]]*)\]")
        all (vec (concat (map #(conj % :num) keyed) (map #(conj % :num) sx)
                         (map #(conj % :arr) arr) (map #(conj % :kids) kids)))]
    (when (seq all)
      (let [[s _ gs ge kind :as m] (pick r all)
            txt (subs (->latin1 bs) gs ge)]
        (case kind
          :num [(replace-group bs m (num-variant r txt)) (str "trailer-num@" s " " txt)]
          :arr (let [toks (vec (re-seq #"-?\d+" txt))]
                 (if (empty? toks)
                   [bs "arr-noop"]
                   (let [i (below r (count toks))
                         toks' (assoc toks i (num-variant r (toks i)))]
                     [(replace-group bs m (str/join " " toks')) (str "xref-array@" s " #" i)])))
          :kids (case (int (below r 3))
                  0 [(replace-group bs m "") (str "kids@" s " emptied")]
                  1 [(replace-group bs m (str txt " " txt)) (str "kids@" s " doubled")]
                  2 [(replace-group bs m (str/replace txt #"\d+" (fn [x] (num-variant r x))))
                     (str "kids@" s " renumbered")]))))))

(def structure-mutators
  [m-xref-entry m-length m-length-ref m-objhdr m-stream-boundary m-trailer-num])

(def mutators
  "Weighted: structure-aware mutators are most of the picks."
  (vec (concat [m-truncate m-truncate m-flip m-flip m-splice]
               structure-mutators structure-mutators)))

(defn mutate
  "Apply 1..3 mutations. Answers [bytes [descriptions]]."
  [r ^bytes bs]
  (loop [k (between r 1 3) bs bs descs []]
    (if (zero? k)
      [bs descs]
      (let [f (pick r mutators)
            [bs' d] (or (f r bs) (m-flip r bs))]
        (recur (dec k) bs' (conj descs d))))))
