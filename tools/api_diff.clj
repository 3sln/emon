;; The API freeze check (P11.2, DEVLOG D527): what every shipped package exposes, written down in api/surface.edn and held there.
;;
;;   bb api-diff                     extract the current surface from the SOURCE (no build) and diff it against api/surface.edn;
;;                                   exit 1 on any removal or signature change of a :public entry; additions to the public
;;                                   surface are reported and fail too
;;   bb api-diff --allow-additions   the same, but a new :public entry passes (a removal or a change still fails)
;;   bb api-diff --write             regenerate api/surface.edn from the source (after a deliberate change: DEVLOG D527's policy)
;;   bb api-diff --list [HOST]       print the extracted surface (all hosts, or one) instead of diffing
;;   bb api-diff --self-test         plant a removal, a change and an addition in each host on a temporary COPY and require the
;;                                   check to name them; the real tree is not touched
;;
;; WHAT IS READ (nothing is built: every extractor reads source, headers, manifests and kin's tables as TEXT):
;;   rust    host/rust/src/*.rs and lib.rs: the crate's public items, found by their ROOT path (`emon::Engine::convert`):
;;           what lib.rs re-exports, followed through `pub use` to the definition; the other `pub` items of a `pub mod` are
;;           listed under their module path as :accidental (they are public only because the sibling bindings and the driver
;;           are separate crates of the package)
;;   go      host/go/*.go, port/, pngenc/, planrun/ (the module `emon` kin/scripts/emon-go-build assembles): exported names
;;   java    host/java/emon/**: public and protected declarations (and an interface's members)
;;   csharp  host/csharp/Emon/**: public and protected declarations
;;   js      host/js/package.json `exports` and the files it points at: exported functions and classes, a class's public
;;           methods, getters and constructor-assigned properties
;;   c       host/c/include/emon.h: macros, types, functions; and the `#[no_mangle]` symbols of host/c/src/capi.rs (a symbol the
;;           header does not declare is :accidental; a header function the library does not export fails the check)
;;   pod     host/pod/src/main.rs: the namespace and vars `describe` lists, the result and spec keys
;;   options kin/config.kin (the `key=value` options), kin/limits.kin (the limit names, defaults and ranges), the HTML option
;;           words, the driver's flags, schema/emon-1.json (the Model's version and keys)
;;   gen     what kin generates is counted, one :internal entry per host (the modules a host's `emon` namespace carries)
;;
;; TIERS (the second field of an entry):
;;   :public       the frozen API. A removal or a change fails the check; an addition fails it unless --allow-additions.
;;   :provisional  shipped but not frozen (live-only adapters behind a feature or build tag, the test driver's flags).
;;   :accidental   public in the language, not meant to be: generated internals and host plumbing that leak out. Listed so
;;                 they can be closed; never frozen.
;;   :internal     generated modules, counted.
;;   A change to a :provisional, :accidental or :internal entry is printed as a NOTE and does not fail.
;;
;; THE FILE is `[[host tier id sig] ...]` sorted by host then id, one entry per line. An entry's KEY is [host id].
(require '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str])

(def surface-file "api/surface.edn")

;; ------------------------------------------------------------------------------------------------ small helpers

(defn- path [root f] (str (fs/path root f)))
;; The test hooks (P11.4, D542) exist only under the crate's `__test_hooks` feature, which a published crate never has: they are not read
;; as part of the surface (a field `Options::no_retarget`, the C symbol `emon_test_panic`).
(defn- strip-test-hooks [s]
  (-> s
      (str/replace #"#\[cfg\(feature = \"__test_hooks\"\)\]\s*pub \w+: [^,\n]+,[ \t]*\n?" "")
      (str/replace #"#\[cfg\(feature = \"__test_hooks\"\)\]\s*#\[no_mangle\]" "")))
(defn- rd [root f]
  (let [s (slurp (path root f))]
    (if (str/ends-with? f ".rs") (strip-test-hooks s) s)))
(defn- exists? [root f] (fs/exists? (path root f)))
(defn- norm-ws [s] (str/trim (str/replace (or s "") #"\s+" " ")))
(defn- die [code & msg]
  (binding [*out* *err*] (println (str/join " " msg)))
  (System/exit code))

(defn- list-files
  "Sorted paths (relative to root) of the regular files directly in `dir` ending in `ext`."
  [root dir ext]
  (if (fs/directory? (path root dir))
    (->> (fs/list-dir (path root dir))
         (filter fs/regular-file?)
         (map #(str dir "/" (fs/file-name %)))
         (filter #(str/ends-with? % ext))
         sort)
    []))

(defn- list-dirs [root dir]
  (if (fs/directory? (path root dir))
    (->> (fs/list-dir (path root dir)) (filter fs/directory?) (map #(str (fs/file-name %))) sort)
    []))

(defn- stem [f] (str/replace (str (fs/file-name f)) #"\.[^.]*$" ""))

(defn- gen-skipped?
  "kin/scripts/emon-*-build leave out a law or a probe: a module whose name ends in test or probe."
  [name] (boolean (re-find #"(?i)(test|probe)$" name)))

;; ------------------------------------------------------------------------------------------------ cleaning source text

(defn- str-end
  "The index after the string that opens at i (quote q). verb?: doubled quotes escape, backslashes do not."
  [^String s i q verb?]
  (let [n (count s)]
    (loop [j (inc i)]
      (if (>= j n)
        n
        (let [c (.charAt s (int j))]
          (cond
            (and verb? (= c q) (< (inc j) n) (= (.charAt s (int (inc j))) q)) (recur (+ j 2))
            (= c q) (inc j)
            (and (not verb?) (= c \\)) (recur (+ j 2))
            :else (recur (inc j))))))))

(defn- clean
  "Source with comments removed and the inside of string and character literals blanked (newlines kept). lang: :rust :go
  :java :cs :js :c."
  [^String s lang]
  (let [n (count s)
        sb (StringBuilder. n)
        m (re-matcher #"[/\"'`]" s)
        at (fn [i] (when (< -1 i n) (.charAt s (int i))))]
    (loop [i0 0]
      (when (< i0 n)
        ;; jump to the next character that can start a comment, string or character literal
        (let [i (long (if (.find m (int i0)) (.start m) n))
              _ (.append sb s (int i0) (int i))
              c (when (< i n) (.charAt s (int i)))
              d (at (inc i))]
          (when c
           (cond
            (and (= c \/) (= d \/)) (recur (long (or (str/index-of s "\n" i) n)))
            (and (= c \/) (= d \*))
            (let [e (if-let [k (str/index-of s "*/" (+ i 2))] (+ k 2) n)]
              (.append sb " ")
              (doseq [ch (subs s i e)] (when (= ch \newline) (.append sb "\n")))
              (recur (long e)))
            ;; JS keeps its strings (a default parameter's value is part of the signature); only brackets inside them are hidden
            (and (#{\" \'} c) (= lang :js))
            (let [e (str-end s i c false)]
              (.append sb (str/replace (subs s i e) #"[{}()\[\]]" "_"))
              (recur (long e)))
            (= c \")
            (let [e (str-end s i \" (and (= lang :cs) (= (at (dec i)) \@)))]
              (.append sb "\"\"")
              (recur (long e)))
            (and (= c \`) (#{:go :js} lang))
            (let [e (str-end s i \` true)]
              (.append sb "``")
              (recur (long e)))
            (and (= c \') (= lang :js))
            (let [e (str-end s i \' false)]
              (.append sb "''")
              (recur (long e)))
            (and (= c \') (not= lang :js))
            (cond
              (= d \\) (let [e (inc (or (str/index-of s "'" (+ i 3)) (dec n)))]
                         (.append sb "''")
                         (recur (long e)))
              (= (at (+ i 2)) \') (do (.append sb "''") (recur (long (+ i 3))))
              :else (do (.append sb c) (recur (inc i))))
            :else (do (.append sb c) (recur (inc i))))))))
    (str sb)))

(defn- match-close
  "The index of the bracket closing the one at i (open/close given). `->` and `=>` are not closing angles."
  [^String s i open close]
  (let [n (count s)
        m (re-matcher (re-pattern (str "[\\" open "\\" close "]")) s)]
    (loop [from (inc i) d 1]
      (if (or (>= from n) (not (.find m (int from))))
        (dec n)
        (let [j (.start m)
              c (.charAt s (int j))
              prev (when (pos? j) (.charAt s (int (dec j))))]
          (cond
            (= c open) (recur (inc j) (inc d))
            (and (= c close) (not (and (= close \>) (#{\- \=} prev)))) (if (= d 1) j (recur (inc j) (dec d)))
            :else (recur (inc j) d)))))))

(defn- match-brace
  "The index just after the } closing the { at i."
  [s i]
  (inc (match-close s i \{ \})))

(defn- depth0
  "s with the contents of (), [] and {} removed (the brackets stay)."
  [s]
  (let [sb (StringBuilder.)]
    (loop [cs (seq s) d 0]
      (when cs
        (let [c (first cs)]
          (cond
            (#{\( \[ \{} c) (do (when (zero? d) (.append sb c)) (recur (next cs) (inc d)))
            (#{\) \] \}} c) (let [d2 (dec d)] (when (zero? d2) (.append sb c)) (recur (next cs) d2))
            :else (do (when (zero? d) (.append sb c)) (recur (next cs) d))))))
    (str sb)))

(defn- split-top
  "s split at the sep characters that are outside (), [], {} and <>."
  [^String s sep]
  (let [n (count s)]
    (loop [i 0 start 0 d 0 out []]
      (if (>= i n)
        (conj out (subs s start))
        (let [c (.charAt s (int i))
              prev (when (pos? i) (.charAt s (int (dec i))))]
          (cond
            (#{\( \[ \{ \<} c) (recur (inc i) start (inc d) out)
            (and (#{\) \] \}} c)) (recur (inc i) start (dec d) out)
            (and (= c \>) (not (#{\- \=} prev))) (recur (inc i) start (dec d) out)
            (and (= c sep) (<= d 0)) (recur (inc i) (inc i) 0 (conj out (subs s start i)))
            :else (recur (inc i) start d out)))))))

(defn- norm-type [s]
  (-> (norm-ws s)
      (str/replace #"\s*,\s*" ",")
      (str/replace #"\s*<\s*" "<")
      (str/replace #"\s+>" ">")
      (str/replace #"\s*\[\s*\]" "[]")))

(defn- clip [s n] (if (> (count s) n) (str (subs s 0 n) "...") s))

(defn- entry [host tier id sig] {:host host :tier tier :id id :sig (str/trim (or sig ""))})

;; ------------------------------------------------------------------------------------------------ Rust

(defn- rs-attrs [hdr] (re-seq #"#!?\[[^\]]*\]" hdr))
(defn- rs-body [hdr] (norm-ws (str/replace hdr #"#!?\[[^\]]*\]" " ")))
(defn- rs-pub? [h] (boolean (re-find #"^pub\s+" h)))
(defn- rs-strip-pub [h] (str/replace-first h #"^pub\s+" ""))

(defn- rs-derives [attrs]
  (->> attrs
       (keep #(second (re-find #"derive\(([^)]*)\)" %)))
       (mapcat #(map str/trim (str/split % #",")))
       (remove str/blank?)
       sort
       vec))

(defn- colon-index
  "Index of the first single `:` (not part of `::`) outside brackets, or nil."
  [^String p]
  (let [n (count p)]
    (loop [i 0 d 0]
      (when (< i n)
        (let [c (.charAt p (int i))]
          (cond
            (#{\( \[ \{ \<} c) (recur (inc i) (inc d))
            (and (= c \>) (not= (when (pos? i) (.charAt p (int (dec i)))) \-)) (recur (inc i) (dec d))
            (#{\) \] \}} c) (recur (inc i) (dec d))
            (and (= c \:) (zero? d)
                 (not= (when (< (inc i) n) (.charAt p (int (inc i)))) \:)
                 (not= (when (pos? i) (.charAt p (int (dec i)))) \:))
            i
            :else (recur (inc i) d)))))))

(defn- rs-param-type [p]
  (let [p (norm-ws p)]
    (cond
      (str/blank? p) nil
      (re-find #"^(?:&\s*(?:'\w+\s*)?(?:mut\s+)?|mut\s+)?self\b" p) p
      :else (if-let [k (colon-index p)] (norm-ws (subs p (inc k))) p))))

(defn- rs-fn-sig
  "Modifiers, generics, parameter TYPES (not names) and return type of a fn header (the text from the modifiers on)."
  [h1]
  (let [m (re-find #"^((?:(?:const|unsafe|async|extern(?:\s+\"\")?)\s+)*)fn\s+(\w+)" h1)
        mods (norm-ws (nth m 1))
        nm (nth m 2)
        rest (subs h1 (count (first m)))
        [g rest] (if (str/starts-with? rest "<")
                   (let [e (match-close rest 0 \< \>)] [(subs rest 0 (inc e)) (subs rest (inc e))])
                   ["" rest])
        rest (str/trim rest)
        pe (when (str/starts-with? rest "(") (match-close rest 0 \( \)))
        params (when pe (subs rest 1 pe))
        tail (norm-ws (if pe (subs rest (inc pe)) rest))
        types (->> (split-top (or params "") \,) (keep rs-param-type))]
    {:name nm
     :sig (norm-ws (str mods " " (norm-type g) "(" (str/join ", " types) ")" (when-not (str/blank? tail) (str " " tail))))}))

(defn- rs-type-members
  "The decls of a struct or enum body: its fields (pub ones) and variants."
  [kind owner body-inner pub?]
  (let [segs (->> (split-top (str/replace body-inner #"#!?\[[^\]]*\]" " ") \,) (map norm-ws) (remove str/blank?))]
    (if (= kind :struct)
      (keep (fn [s]
              (when-let [[_ nm ty] (re-find #"^pub\s+(\w+)\s*:\s*(.*)$" s)]
                {:kind :field :owner owner :name nm :sig (str "field " (norm-type ty)) :pub pub?}))
            segs)
      (keep (fn [s]
              (when-let [[_ nm rest] (re-find #"^(\w+)\s*(.*)$" s)]
                {:kind :variant :owner owner :name nm :sig (str "variant " (norm-type rest)) :pub pub?}))
            segs))))

(defn- rs-private-fields? [body-inner]
  (->> (split-top (str/replace body-inner #"#!?\[[^\]]*\]" " ") \,)
       (map norm-ws)
       (remove str/blank?)
       (some #(not (re-find #"^pub\s+\w+\s*:" %)))
       boolean))

(defn- rs-use-names
  "[[exposed-name target-path] ...] of the tree after `use`."
  [tree prefix]
  (let [tree (norm-ws tree)]
    (cond
      (re-find #"::\{" tree)
      (let [k (str/index-of tree "::{")
            pre (subs tree 0 k)
            inner (subs tree (+ k 3) (str/last-index-of tree "}"))]
        (mapcat #(rs-use-names % (if (str/blank? prefix) pre (str prefix "::" pre))) (split-top inner \,)))
      (str/blank? tree) []
      :else
      (let [[p alias] (str/split tree #"\s+as\s+")
            full (if (str/blank? prefix) p (str prefix "::" p))
            nm (last (str/split full #"::"))]
        [[(or alias nm) full]]))))

(defn- rs-impl-head [h1]
  (let [s (-> h1 (str/replace #"^unsafe\s+" "") (str/replace-first #"^impl\s*" ""))
        s (if (str/starts-with? s "<") (str/trim (subs s (inc (match-close s 0 \< \>)))) s)
        s (first (str/split s #"\s+where\s+" 2))
        k (str/index-of s " for ")
        trait (when k (norm-type (subs s 0 k)))
        self (if k (subs s (+ k 5)) s)
        base (second (re-find #"(?:[A-Za-z_]\w*::)*([A-Za-z_]\w*)" (str/replace self #"^[&\s]*(?:'\w+\s+)?(?:mut\s+|dyn\s+)?" "")))]
    {:trait trait :owner base}))

(defn- rs-open!
  "A `{` ends header `hdr` at index i of text. Emit what the header declares; answer {:stack :next}."
  [emit! stack hdr ^String text i]
  (let [attrs (rs-attrs hdr)
        h (rs-body hdr)
        pub (rs-pub? h)
        h1 (rs-strip-pub h)
        top (peek stack)
        pvis (:vis top)
        skip {:stack stack :next (match-brace text i)}]
    (cond
      (= :skipimpl (:kind top)) skip
      (re-find #"^(?:unsafe\s+)?mod\s+\w+" h1)
      (let [nm (second (re-find #"mod\s+(\w+)" h1))]
        (emit! {:kind :mod :name nm :pub (and pvis pub) :attrs (map norm-ws attrs)})
        {:stack (conj stack {:kind :mod :name nm :vis (boolean (and pvis pub))}) :next (inc i)})
      (re-find #"^(?:unsafe\s+)?trait\s+\w+" h1)
      (let [nm (second (re-find #"trait\s+(\w+)" h1))
            tail (norm-type (subs h1 (+ (str/index-of h1 nm) (count nm))))]
        (emit! {:kind :trait :name nm :sig (norm-ws (str "trait" tail)) :pub (and pvis pub)})
        {:stack (conj stack {:kind :trait :name nm :vis (boolean (and pvis pub))}) :next (inc i)})
      (re-find #"^(?:unsafe\s+)?impl\b" h1)
      (let [{:keys [trait owner]} (rs-impl-head h1)]
        (if trait
          (do (emit! {:kind :impl :owner owner :name (str "impl " trait) :sig "impl" :pub pvis})
              {:stack (conj stack {:kind :skipimpl}) :next (inc i)})
          {:stack (conj stack {:kind :impl :owner owner :vis pvis}) :next (inc i)}))
      (re-find #"^(?:struct|enum|union)\s+\w+" h1)
      (let [[_ kw nm] (re-find #"^(struct|enum|union)\s+(\w+)" h1)
            e (match-brace text i)
            inner (subs text (inc i) (dec e))
            kind (if (= kw "enum") :enum :struct)
            vis (boolean (and pvis pub))
            g (norm-type (subs h1 (+ (str/index-of h1 nm) (count nm))))
            dv (rs-derives attrs)
            nonex (some #(str/includes? % "non_exhaustive") attrs)]
        (emit! {:kind kind :name nm
                :sig (norm-ws (str kw g (when (seq dv) (str " derive(" (str/join "," dv) ")")) (when nonex " non_exhaustive")
                                   (when (and (= kind :struct) (rs-private-fields? inner)) " +private")))
                :pub vis})
        (doseq [m (rs-type-members kind nm inner vis)] (emit! m))
        {:stack stack :next e})
      (re-find #"^(?:(?:const|unsafe|async|extern(?:\s+\"\")?)\s+)*fn\s+\w+" h1)
      (let [{:keys [name sig]} (rs-fn-sig h1)
            owner (when (#{:impl :trait} (:kind top)) (or (:owner top) (:name top)))
            in-trait (= :trait (:kind top))]
        (emit! {:kind :fn :owner owner :name name
                :sig (str "fn " (when in-trait "provided ") sig)
                :pub (if in-trait pvis (boolean (and pvis pub)))
                :member (boolean owner)})
        skip)
      :else skip)))

(defn- rs-stmt!
  "A `;` ends header `hdr`."
  [emit! stack hdr]
  (let [top (peek stack)
        attrs (rs-attrs hdr)
        h (rs-body hdr)
        pub (rs-pub? h)
        h1 (rs-strip-pub h)
        pvis (:vis top)
        in-trait (= :trait (:kind top))
        owner (when (#{:impl :trait} (:kind top)) (or (:owner top) (:name top)))
        eff (if in-trait pvis (boolean (and pvis pub)))]
    (when-not (or (= :skipimpl (:kind top)) (str/blank? h))
      (cond
        (re-find #"^use\s" h1)
        (when eff
          (doseq [[nm full] (rs-use-names (second (re-find #"^use\s+(.*)$" h1)) "")]
            (emit! {:kind :use :name nm :target full :pub eff})))
        (re-find #"^(?:unsafe\s+)?mod\s+\w+" h1)
        (emit! {:kind :mod :name (second (re-find #"mod\s+(\w+)" h1)) :pub (and pvis pub) :attrs (map norm-ws attrs)})
        (re-find #"^(?:(?:const|unsafe|async|extern(?:\s+\"\")?)\s+)*fn\s+\w+" h1)
        (let [{:keys [name sig]} (rs-fn-sig h1)]
          (emit! {:kind :fn :owner owner :name name :sig (str "fn " (when in-trait "required ") sig) :pub eff :member (boolean owner)}))
        (re-find #"^(?:const|static)\s+(?:mut\s+)?\w+\s*:" h1)
        (let [[_ nm rest] (re-find #"^(?:const|static)\s+(?:mut\s+)?(\w+)\s*:\s*(.*)$" h1)
              parts (split-top rest \=)
              ty (norm-type (first parts))
              v (when (> (count parts) 1) (norm-ws (str/join "=" (next parts))))]
          (emit! {:kind :const :owner owner :name nm :sig (str "const " ty (when v (str " = " (clip v 100)))) :pub eff :member (boolean owner)}))
        (re-find #"^type\s+\w+" h1)
        (let [[_ nm rest] (re-find #"^type\s+(\w+)\s*(.*)$" h1)]
          (emit! {:kind :type :owner owner :name nm :sig (str "type " (norm-type rest)) :pub eff :member (boolean owner)}))
        (re-find #"^struct\s+\w+" h1)
        (let [[_ nm rest] (re-find #"^struct\s+(\w+)\s*(.*)$" h1)
              dv (rs-derives attrs)]
          (emit! {:kind :struct :name nm :sig (norm-ws (str "struct " (norm-type rest) (when (seq dv) (str " derive(" (str/join "," dv) ")")))) :pub eff}))
        :else nil))))

(defn- rs-scan
  "The declarations of a Rust source (cleaned): a vector of maps."
  [^String text]
  (let [n (count text)
        mm (re-matcher #"[;{}()\[\]]" text)
        out (volatile! [])
        emit! (fn [m] (vswap! out conj m))]
    (loop [i 0 stack [{:kind :mod :name nil :vis true}] hdr "" pd 0]
      (if (>= i n)
        @out
        (let [p (long (if (.find mm (int i)) (.start mm) n))]
         (if (> p i)
          (recur p stack (str hdr (subs text i p)) pd)
        (let [c (.charAt text (int i))]
          (cond
            (and (= c \;) (zero? pd)) (do (rs-stmt! emit! stack hdr) (recur (inc i) stack "" 0))
            (and (= c \{) (pos? pd)) (recur (long (match-brace text i)) stack (str hdr " {} ") pd)
            (= c \{)
            (let [h (rs-body hdr)]
              (cond
                (re-find #"^(?:pub(?:\([^)]*\))?\s+)?use\s" h)
                (let [e (match-brace text i)] (recur (long e) stack (str hdr (subs text i e)) 0))
                (and (re-find #"^(?:pub\s+)?(?:const|static)\s" h) (str/includes? (depth0 h) "="))
                (recur (long (match-brace text i)) stack (str hdr " {} ") 0)
                :else
                (let [r (rs-open! emit! stack hdr text i)]
                  (recur (long (:next r)) (:stack r) "" 0))))
            (= c \}) (recur (inc i) (if (> (count stack) 1) (pop stack) stack) "" 0)
            :else (recur (inc i) stack (str hdr c)
                         (case c (\( \[) (inc pd) (\) \]) (max 0 (dec pd)) pd))))))))))

(def ^:private rust-provisional-mods #{"pdfium" "pdfcodecs" "tess" "par"})

(defn- rust-entries [root]
  (let [lib-decls (rs-scan (clean (rd root "host/rust/src/lib.rs") :rust))
        mods (filter #(= :mod (:kind %)) lib-decls)
        uses (filter #(= :use (:kind %)) lib-decls)
        mod-names (set (map :name mods))
        cfgs (into {} (map (fn [[_ c m]] [m c]))
                   (re-seq #"#\[(cfg\([^\n]*\))\]\s*(?:pub\s+)?mod\s+(\w+)" (rd root "host/rust/src/lib.rs")))
        parse (fn [m]
                (let [f (str "host/rust/src/" m ".rs")]
                  (when (exists? root f)
                    (->> (rs-scan (clean (rd root f) :rust)) (map #(assoc % :mod m))))))
        decls-by-mod (into {} (map (fn [m] [m (vec (parse m))])) (conj mod-names "imgdec"))
        ;; module-level re-exports: {[mod exposed] [target-mod target-name]}
        aliases (into {}
                      (for [[m ds] decls-by-mod, d ds :when (= :use (:kind d))
                            :let [segs (str/split (:target d) #"::")
                                  segs (if (= "crate" (first segs)) (rest segs) segs)]]
                        [[m (:name d)] [(nth segs (- (count segs) 2)) (last segs)]]))
        resolve (fn [[m nm]] (get aliases [m nm] [m nm]))
        ;; the crate root's re-exports: {definition-key exposed-name}
        exposed (into {}
                      (for [u uses
                            :let [segs (str/split (:target u) #"::")
                                  m (first segs)
                                  nm (second segs)]]
                        [(resolve [m nm]) (:name u)]))
        all-decls (vec (for [[m ds] decls-by-mod, d ds :when (and (:pub d) (not (#{:use :mod} (:kind d))))] d))
        top-key (fn [d] [(:mod d) (or (:owner d) (:name d))])
        member-id (fn [d] (str (when (:owner d) "::") (when (:owner d) (:name d))))
        ;; Types that a public signature names but lib.rs does not re-export (`Ocr::fulfil` takes a `plan::Request`): an
        ;; implementer has to name them, so they are public too, under their module path. Found by closure over the signatures.
        pool (->> all-decls
                  (filter #(and (#{:struct :enum :trait :type} (:kind %)) (nil? (:owner %)) (contains? mod-names (:mod %))
                                (not (contains? exposed (top-key %)))
                                (not (contains? (set (vals exposed)) (:name %)))))
                  (group-by :name))
        hidden-members #{"emon::Engine::start_pushed"}
        seed-sigs (for [d all-decls
                        :let [ex (get exposed (top-key d))]
                        :when (and ex (not (hidden-members (str "emon::" ex (member-id d)))))]
                    (:sig d))
        promoted (loop [prom #{}]
                   (let [sigs (concat seed-sigs (for [d all-decls :when (prom (top-key d))] (:sig d)))
                         found (set (for [s sigs
                                          [_ q nm] (re-seq #"(?:\b([a-z_]\w*)::)?\b([A-Z]\w*)" s)
                                          :let [cands (get pool nm)
                                                c (cond (nil? cands) nil
                                                        q (first (filter #(= q (:mod %)) cands))
                                                        (= 1 (count cands)) (first cands))]
                                          :when c]
                                      (top-key c)))
                         prom2 (into prom found)]
                     (if (= prom2 prom) prom (recur prom2))))
        root-id (fn [d]
                  (let [k (top-key d)
                        top (second k)]
                    (cond
                      (get exposed k) {:tier :public :id (str "emon::" (get exposed k) (member-id d))}
                      (not (contains? mod-names (:mod d))) nil
                      (contains? promoted k) {:tier :public :id (str "emon::" (:mod d) "::" top (member-id d))}
                      :else {:tier (if (rust-provisional-mods (:mod d)) :provisional :accidental)
                             :id (str (:mod d) "::" top (member-id d))})))
        items (for [d all-decls
                    :let [r (root-id d)]
                    :when r]
                (entry :rust (:tier r) (:id r) (:sig d)))
        reexports (for [u uses] (entry :rust :public (str "emon::" (:name u) " (re-export)") (str "pub use " (:target u))))
        modent (for [m mods :when (:pub m)]
                 (entry :rust (cond (= "emon" (:name m)) :internal
                                    (rust-provisional-mods (:name m)) :provisional
                                    :else :accidental)
                        (str "mod " (:name m))
                        (norm-ws (str "pub mod" (when-let [c (get cfgs (:name m))] (str " #[" c "]"))
                                      (when (= "emon" (:name m)) " #[doc(hidden)]")))))
        gen (->> (list-files root "gen/rust/emon" ".rs") (map stem) (remove gen-skipped?) count)]
    (concat items reexports modent
            [(entry :rust :internal "emon::emon::*" (str gen " generated modules, doc(hidden)"))])))

;; ------------------------------------------------------------------------------------------------ Go

(defn- go-exported? [s] (boolean (re-find #"^[A-Z]" s)))

(defn- go-sig-cut
  "Cut a func header at the { that opens its body (not an interface{} or struct{})."
  [h]
  (let [h2 (-> h (str/replace "interface{}" "\u0001") (str/replace "struct{}" "\u0002"))
        h3 (first (str/split h2 #"\{" 2))]
    (-> h3 (str/replace "\u0001" "interface{}") (str/replace "\u0002" "struct{}") norm-ws)))

(defn- go-entries [text pkg tier]
  (let [lines (vec (str/split-lines text))
        n (count lines)
        id (fn [& parts] (str/join "." (cons pkg parts)))
        pdepth (fn [s] (- (count (re-seq #"\(" s)) (count (re-seq #"\)" s))))]
    (loop [i 0 out []]
      (if (>= i n)
        out
        (let [l (lines i)]
          (cond
            ;; func
            (re-find #"^func[\s(]" l)
            (let [j (loop [j i d (pdepth l)]
                      (if (and (pos? d) (< (inc j) n))
                        (recur (inc j) (+ d (pdepth (lines (inc j)))))
                        j))
                  hdr (go-sig-cut (str/join " " (subvec lines i (inc j))))
                  m (or (re-find #"^func\s*\(([^)]*)\)\s*([A-Za-z_]\w*)(.*)$" hdr)
                        (let [[a b c] (re-find #"^func\s+([A-Za-z_]\w*)(.*)$" hdr)] (when a [a nil b c])))]
              (recur (inc j)
                     (if (and m (go-exported? (nth m 2)))
                       (let [recv (nth m 1)
                             rt (when recv (second (re-find #"\*?\s*([A-Za-z_]\w*)(?:\[.*\])?\s*$" recv)))
                             ptr (when recv (boolean (re-find #"\*" recv)))]
                         (cond
                           (and recv (not (and rt (go-exported? rt)))) out
                           recv (conj out (entry :go tier (id rt (nth m 2)) (str (when ptr "ptr ") (norm-ws (nth m 3)))))
                           :else (conj out (entry :go tier (id (nth m 2)) (norm-ws (nth m 3))))))
                       out)))
            ;; type
            (re-find #"^type\s+[A-Z]" l)
            (let [[_ nm rest] (re-find #"^type\s+(\w+)\s*(.*)$" l)]
              (cond
                (re-find #"^(?:\[.*?\]\s*)?struct\s*\{\s*$" rest)
                (let [e (loop [j (inc i)] (if (or (>= j n) (re-find #"^\}" (lines j))) j (recur (inc j))))
                      body (subvec lines (inc i) e)
                      fields (mapcat
                               (fn [bl]
                                 (when-let [[_ names ty] (re-find #"^\t([A-Za-z_]\w*(?:\s*,\s*[A-Za-z_]\w*)*)\s+(\S.*)$" bl)]
                                   (for [f (map str/trim (str/split names #","))
                                         :when (go-exported? f)]
                                     (entry :go tier (id nm f) (str "field " (norm-ws ty))))))
                               body)
                      embedded (keep (fn [bl]
                                       (when-let [[_ t] (re-find #"^\t\*?([A-Z][\w.]*)\s*$" bl)]
                                         (entry :go tier (id nm (last (str/split t #"\."))) (str "embedded " t))))
                                     body)
                      unexported (some #(re-find #"^\t[a-z_]\w*(?:\s*,\s*[a-z_]\w*)*\s+\S" %) body)]
                  (recur (inc e) (-> out (conj (entry :go tier (id nm) (str "struct" (when unexported " +unexported"))))
                                     (into fields) (into embedded))))
                (re-find #"^interface\s*\{\s*$" rest)
                (let [e (loop [j (inc i)] (if (or (>= j n) (re-find #"^\}" (lines j))) j (recur (inc j))))
                      body (subvec lines (inc i) e)
                      ms (keep (fn [bl]
                                 (if-let [[_ nm2 sig] (re-find #"^\t([A-Za-z_]\w*)(\(.*)$" bl)]
                                   (when (go-exported? nm2) (entry :go tier (id nm nm2) (norm-ws sig)))
                                   (when-let [[_ t] (re-find #"^\t([A-Za-z_][\w.]*)\s*$" bl)]
                                     (entry :go tier (id nm t) "embedded"))))
                               body)
                      unexp (some #(re-find #"^\t[a-z_]\w*\(" %) body)]
                  (recur (inc e) (-> out (conj (entry :go tier (id nm) (str "interface" (when unexp " +unexported")))) (into ms))))
                :else
                (recur (inc i) (conj out (entry :go tier (id nm) (str "type " (norm-ws (first (str/split rest #"\{" 2)))))))))
            ;; const / var blocks and singles
            (re-find #"^(const|var)\s*\($" l)
            (let [kw (second (re-find #"^(const|var)" l))
                  e (loop [j (inc i)] (if (or (>= j n) (re-find #"^\)" (lines j))) j (recur (inc j))))
                  body (subvec lines (inc i) e)
                  iota? (some #(str/includes? % "iota") body)
                  ents (keep-indexed
                         (fn [k bl]
                           (when-let [[_ nm ty v] (re-find #"^\t([A-Za-z_]\w*)(?:\s+([^=\s][^=]*?))?(?:\s*=\s*(.*))?\s*$" bl)]
                             (when (go-exported? nm)
                               (entry :go tier (id nm)
                                      (norm-ws (str kw (when ty (str " " (norm-ws ty)))
                                                    (when v (str " = " (clip (norm-ws v) 80)))
                                                    (when (and iota? (not v)) (str " [iota+" k "]")))))))) body)]
              (recur (inc e) (into out ents)))
            (re-find #"^(const|var)\s+[A-Z]" l)
            (let [[_ kw nm rest] (re-find #"^(const|var)\s+(\w+)\s*(.*)$" l)]
              (recur (inc i) (conj out (entry :go tier (id nm) (norm-ws (str kw " " (clip (norm-ws rest) 80)))))))
            :else (recur (inc i) out)))))))

(defn- go-file-entries [root files pkg tier]
  (mapcat #(go-entries (clean (rd root %) :go) pkg tier) files))

(defn- go-all [root]
  (let [src (fn [dir] (->> (list-files root dir ".go")
                           (remove #(or (str/ends-with? % "_test.go") (str/ends-with? % "/testport.go") (str/ends-with? % "/testhooks.go")))))
        root-files (src "host/go")
        stub (filter #(str/ends-with? % "/tess_stub.go") root-files)
        tagged (filter #(str/ends-with? % "/tess.go") root-files)
        plain (remove (set (concat stub tagged)) root-files)
        ents (concat (go-file-entries root plain "emon" :public)
                     (go-file-entries root stub "emon" :public)
                     (go-file-entries root tagged "emon" :provisional)
                     (go-file-entries root (src "host/go/port") "port" :accidental)
                     (go-file-entries root (src "host/go/pngenc") "pngenc" :accidental)
                     (go-file-entries root (src "host/go/planrun") "planrun" :accidental)
                     (go-file-entries root (src "host/go/pdfium") "pdfium" :provisional))
        gen (->> (list-dirs root "gen/go") (remove gen-skipped?) count)]
    (concat (reduce (fn [acc e] (if (some #(= (:id %) (:id e)) acc) acc (conj acc e))) [] ents)
            [(entry :go :internal "emon/*" (str gen " generated packages"))])))

;; ------------------------------------------------------------------------------------------------ Java and C#

(def ^:private c-mods
  #{"public" "protected" "private" "internal" "static" "final" "abstract" "sealed" "readonly" "const" "virtual" "override"
    "partial" "extern" "unsafe" "new" "synchronized" "native" "default" "volatile" "transient" "async" "strictfp" "required"})

(defn- c-strip-annotations [h lang]
  (let [h (str/replace h #"@[A-Za-z_][\w.]*(?:\s*\([^)]*\))?" " ")
        h (if (= lang :cs) (str/replace h #"^\s*(?:\[[^\]]*\]\s*)+" "") h)]
    (norm-ws h)))

(defn- c-leading-mods [h]
  (loop [ws (str/split h #"\s+") mods []]
    (if (and (seq ws) (c-mods (first ws)))
      (recur (rest ws) (conj mods (first ws)))
      {:mods mods :rest (str/join " " ws)})))

(defn- c-param-types [params]
  (->> (split-top params \,)
       (map norm-ws)
       (remove str/blank?)
       (map (fn [p]
              (let [p (-> p (str/replace #"^(?:final|params|ref|out|in|this|readonly)\s+" "")
                          (str/replace #"^(?:final|params|ref|out|in|this)\s+" ""))
                    p (first (split-top p \=))
                    p (norm-ws p)
                    m (re-find #"^(.*\S)\s+[A-Za-z_]\w*$" p)]
                (norm-type (if m (second m) p)))))
       (str/join ",")))

(defn- c-name-before-paren
  "[ret-type name generics] of the text before a method's `(`."
  [before]
  (if-let [[_ ret nm g] (re-find #"^(.*?)([A-Za-z_]\w*)\s*(<[^()]*>)?\s*$" before)]
    [(norm-type ret) nm (or g "")]
    [(norm-type before) "" ""]))

(defn- c-visible? [mods owner-vis iface-owner? top-level?]
  (let [pub (boolean (some #{"public" "protected"} mods))]
    (and owner-vis
         (or pub (and iface-owner? (not (some #{"private" "internal"} mods)))
             false))))

(defn- c-scan
  "Declarations of a Java or C# source (cleaned). Returns entries (host given) with :tier :public."
  [^String text lang host]
  (let [n (count text)
        mm (re-matcher #"[;{},}]" text)
        out (volatile! [])
        sig-mods (fn [mods] (str/join " " (remove #{"public" "internal" "private"} mods)))
        owner-of (fn [stack] (last (filter #(not= :ns (:kind %)) stack)))
        prefix-of (fn [stack] (str/join "." (keep :name stack)))
        emit! (fn [id sig] (vswap! out conj (entry host :public id sig)))
        member! (fn [stack h acc]
                  ;; h: a member header (annotations stripped); acc: "{ get; set; }" for a C# property, or nil
                  (let [owner (owner-of stack)
                        iface? (= :interface (:kind owner))
                        {:keys [mods rest]} (c-leading-mods h)
                        rst rest
                        vis (c-visible? mods (if owner (:vis owner) true) iface? false)]
                    (when (and owner vis (not (str/blank? rst)))
                      (let [oid (prefix-of stack)
                            d0 (depth0 rst)
                            ms (sig-mods mods)]
                        (cond
                          ;; method, constructor, delegate
                          (and (nil? acc) (re-find #"\(" d0) (not (re-find #"^[^(]*=[^=>]" d0)))
                          (let [pi (str/index-of rst "(")
                                pe (match-close rst pi \( \))
                                [ret nm g] (c-name-before-paren (subs rst 0 pi))
                                tail (norm-ws (subs rst (inc pe)))
                                tail (if (str/starts-with? tail ":") "" (first (str/split tail #"=>")))
                                ptypes (c-param-types (subs rst (inc pi) pe))
                                ctor? (str/blank? ret)]
                            (emit! (str oid "#" (if ctor? (if (= lang :java) "<init>" ".ctor") nm) "(" ptypes ")")
                                   (norm-ws (str ms " " (if ctor? "ctor" ret) g (when-not (str/blank? tail) (str " " (norm-ws tail)))))))
                          ;; property (C#)
                          acc
                          (let [[ty nm] (let [[_ t nm] (re-find #"^(.*\S)\s+([A-Za-z_]\w*)$" rst)] [t nm])]
                            (when nm
                              (emit! (str oid "." nm)
                                     (norm-ws (str ms " " (norm-type ty) " "
                                                   (let [inner (depth0 (subs acc 1 (max 1 (dec (count acc)))))
                                                         accs (keep (fn [[_ vis kw]] (when-not (#{"private" "internal"} vis) (str (when vis (str vis " ")) kw ";")))
                                                                    (re-seq #"(?:\b(private|internal|protected)\s+)?\b(get|set|init)\b" inner))]
                                                     (if (seq accs) (str "{ " (str/join " " accs) " }") "{ }")))))))
                          ;; field or constant
                          :else
                          (let [parts (split-top rst \,)
                                first-part (first parts)
                                cut (first (split-top first-part \=))
                                val (second (split-top first-part \=))
                                [_ ty nm] (re-find #"^(.*\S)\s+([A-Za-z_]\w*)\s*$" cut)
                                const? (some #{"const" "final"} mods)
                                names (cons nm (map #(first (str/split (norm-ws (first (split-top % \=))) #"\s+")) (next parts)))]
                            (when nm
                              (doseq [nn names :when (not (str/blank? nn))]
                                (emit! (str oid "." nn)
                                       (norm-ws (str ms " " (norm-type ty)
                                                     (when (and const? val (= nn nm) (re-matches #"[\w\s.\"'+\-]*" (str val)) (< (count (norm-ws val)) 40))
                                                       (str " = " (norm-ws val))))))))))))))
        enum-const! (fn [stack h]
                      (let [owner (owner-of stack)
                            h (c-strip-annotations h lang)]
                        (when (and (:vis owner) (not (str/blank? h)))
                          (let [[_ nm args] (re-find #"^([A-Za-z_]\w*)\s*(.*)$" h)]
                            (emit! (str (prefix-of stack) "." nm) (norm-ws (str "const " args)))))))]
    (loop [i 0 stack [] hdr ""]
      (if (>= i n)
        @out
        (let [p (long (if (.find mm (int i)) (.start mm) n))]
         (if (> p i)
          (recur p stack (str hdr (subs text i p)))
        (let [c (.charAt text (int i))
              top (peek stack)
              consts-open? (and top (= :enum (:kind top)) (not (:consts-done top)))
              pd (if (and consts-open? (= c \,)) (- (count (filter #{\( \[} hdr)) (count (filter #{\) \]} hdr))) 0)
              mark-done (fn [st] (if consts-open? (assoc-in st [(dec (count st)) :consts-done] true) st))]
          (cond
            (= c \;)
            (let [h (c-strip-annotations hdr lang)]
              (cond
                consts-open? (do (enum-const! stack hdr) (recur (inc i) (mark-done stack) ""))
                (re-find #"^(?:package|namespace)\s+[\w.]+$" h)
                (recur (inc i) (conj stack {:kind :ns :name (second (re-find #"^(?:package|namespace)\s+([\w.]+)$" h))}) "")
                (or (re-find #"^(?:import|using)\s" h) (str/blank? h) (str/starts-with? h "=")) (recur (inc i) stack "")
                :else (do (member! stack h nil) (recur (inc i) stack ""))))
            (and (= c \,) consts-open? (zero? pd))
            (do (enum-const! stack hdr) (recur (inc i) stack ""))
            (= c \{)
            (let [h (c-strip-annotations hdr lang)
                  d0 (depth0 h)
                  tm (re-find #"(?:^|\s)(class|interface|enum|struct|record)\s+([A-Za-z_]\w*)" d0)
                  tm (when tm (let [hh (str h " ")] tm))]
              (cond
                (str/blank? h) (recur (long (match-brace text i)) stack "")
                (re-find #"(?:^|\s)static$" h) (recur (long (match-brace text i)) stack "")
                ;; an expression's brace: array initialiser, lambda, object initialiser
                (and (re-find #"[^=!<>]=[^=>]" d0) (not tm))
                (recur (long (match-brace text i)) stack (str hdr " {} "))
                (and tm (not (re-find #"^[^(]*\([^)]*\)\s*(?:=>)?\s*$" d0)) true)
                (let [[_ kw nm] tm
                      {:keys [mods rest]} (c-leading-mods h)
                      owner (owner-of stack)
                      iface? (= :interface (:kind owner))
                      vis (and (if owner (:vis owner) true)
                               (or (boolean (some #{"public" "protected"} mods)) iface?))
                      id (str (prefix-of stack) (when (seq stack) ".") nm)
                      after (norm-ws (subs rest (+ (str/index-of rest (str kw " " nm)) (count (str kw " " nm)))))]
                  (when vis (emit! id (norm-ws (str (sig-mods mods) " " kw (when-not (str/blank? after) (str " " (norm-type after)))))))
                  (recur (inc i) (conj stack {:kind (keyword kw) :name nm :vis (boolean vis)}) ""))
                (re-find #"^namespace\s+[\w.]+" h)
                (recur (inc i) (conj stack {:kind :ns :name (second (re-find #"^namespace\s+([\w.]+)" h))}) "")
                (and (re-find #"\(" d0) (not (re-find #"^[^(]*\{" d0)))
                (do (member! stack h nil)
                    (recur (long (match-brace text i)) stack ""))
                :else
                (let [e (match-brace text i)
                      acc (subs text i e)]
                  (member! stack h acc)
                  (recur (long e) stack ""))))
            (= c \})
            (let [st (if consts-open?
                       (do (enum-const! stack hdr) stack)
                       stack)]
              (recur (inc i) (if (seq st) (pop st) st) ""))
            :else (recur (inc i) stack (str hdr c))))))))))

(defn- c-file-entries [root files lang host]
  (mapcat #(c-scan (clean (rd root %) lang) lang host) files))

(defn- java-all [root]
  (let [dirs ["host/java/emon" "host/java/emon/api" "host/java/emon/ffi"]
        fs* (fn [d] (->> (list-files root d ".java") (remove #(str/ends-with? % "/Testport.java"))))
        ents (mapcat (fn [d] (c-file-entries root (fs* d) :java :java)) dirs)
        gen (->> (list-files root "gen/java/emon" ".java") (map stem) (remove gen-skipped?) count)]
    (concat ents [(entry :java :internal "emon.*" (str gen " generated classes in package emon"))])))

(defn- cs-all [root]
  (let [dirs ["host/csharp/Emon" "host/csharp/Emon/Api" "host/csharp/Emon/Ffi"]
        fs* (fn [d] (->> (list-files root d ".cs") (remove #(str/ends-with? % "/Testport.cs"))))
        ents (mapcat (fn [d] (c-file-entries root (fs* d) :cs :csharp)) dirs)
        gen (->> (list-files root "gen/csharp/Emon" ".cs") (map stem) (remove gen-skipped?) count)]
    (concat ents [(entry :csharp :internal "emon.*" (str gen " generated files in namespace emon"))])))

;; ------------------------------------------------------------------------------------------------ JavaScript

(defn- js-params
  "The parameter text of the call whose ( is at index i of text, whitespace normalised."
  [text i]
  (let [e (match-close text i \( \))]
    (norm-ws (subs text (inc i) e))))

(defn- js-class-members [text cls-start]
  ;; text: cleaned file; cls-start: index of the class's {
  (let [e (match-brace text cls-start)
        body (subs text (inc cls-start) (dec e))
        m (re-matcher #"(?m)^  (static\s+)?(async\s+)?(get\s+|set\s+)?(\*\s*)?([A-Za-z_$][\w$]*|\[[^\]\n]+\])\s*\(" body)]
    (loop [out []]
      (if (.find m)
        (let [nm (.group m 5)
              pi (dec (.end m))
              pe (match-close body pi \( \))
              params (norm-ws (subs body (inc pi) pe))
              bi (str/index-of body "{" pe)
              fields (when (and (= nm "constructor") bi)
                       (let [be (match-brace body bi)]
                         (->> (re-seq #"this\.([A-Za-z][\w$]*)\s*=[^=]" (subs body bi be)) (map second) distinct)))]
          (recur (conj out {:name nm :static (.group m 1) :async (.group m 2) :acc (some-> (.group m 3) str/trim)
                            :params params :fields fields})))
        out))))

(defn- js-parse [text]
  ;; {name {:kind :fn|:class|:value :exported? :params :extends :members}} and the names of `export { }` lists
  (let [fns (re-seq #"(?m)^(export\s+)?(async\s+)?function\s*\*?\s*([\w$]+)\s*\(" text)
        fn-defs (let [m (re-matcher #"(?m)^(export\s+)?(async\s+)?function\s*\*?\s*([\w$]+)\s*\(" text)]
                  (loop [out {}]
                    (if (.find m)
                      (recur (assoc out (.group m 3)
                                    {:kind :fn :exported (boolean (.group m 1)) :async (boolean (.group m 2))
                                     :params (js-params text (dec (.end m)))}))
                      out)))
        class-defs (let [m (re-matcher #"(?m)^(export\s+)?class\s+([\w$]+)(?:\s+extends\s+([\w$.]+))?\s*\{" text)]
                     (loop [out {}]
                       (if (.find m)
                         (recur (assoc out (.group m 2)
                                       {:kind :class :exported (boolean (.group m 1)) :extends (.group m 3)
                                        :members (js-class-members text (dec (.end m)))}))
                         out)))
        val-defs (let [m (re-matcher #"(?m)^export\s+(?:const|let|var)\s+([\w$]+)\s*=\s*(async\s*)?(\([^)]*\)|[\w$]+)?\s*(=>)?" text)]
                   (loop [out {}]
                     (if (.find m)
                       (recur (assoc out (.group m 1)
                                     {:kind (if (.group m 4) :fn :value) :exported true :async (boolean (.group m 2))
                                      :params (when (.group m 4) (norm-ws (str/replace (.group m 3) #"^\(|\)$" "")))}))
                       out)))
        lists (mapcat (fn [[_ names]] (map #(last (str/split (norm-ws %) #"\s+as\s+")) (remove str/blank? (str/split names #","))))
                      (re-seq #"(?m)^export\s*\{([^}]*)\}" text))]
    {:defs (merge fn-defs class-defs val-defs) :list (vec lists)}))

(defn- js-entries [root pkg-path tier parsed-by-file exports-of-file]
  (let [all-defs (apply merge (map (comp :defs val) parsed-by-file))]
    (mapcat
      (fn [name]
        (when-let [d (get all-defs name)]
          (let [id (str pkg-path "::" name)]
            (case (:kind d)
              :fn [(entry :js tier id (str (when (:async d) "async ") "function(" (:params d) ")"))]
              :value [(entry :js tier id "value")]
              :class (concat
                       [(entry :js tier id (str "class" (when (:extends d) (str " extends " (:extends d)))))]
                       (mapcat
                         (fn [{:keys [name static async acc params fields]}]
                           (cond
                             (str/starts-with? name "_") nil
                             :else
                             (concat
                               [(entry :js tier (str id (if static "." "#") name)
                                       (str/trim (str (when static "static ") (when async "async ") (when acc (str acc " "))
                                                      (if acc "accessor" (str "(" params ")")))))]
                               (map (fn [f] (entry :js tier (str id "#" f) "property")) fields))))
                         (:members d)))))))
      exports-of-file)))

(defn- js-all [root]
  (let [pj (json/parse-string (rd root "host/js/package.json"))
        exp (get pj "exports")
        pkg (get pj "name")
        files (->> (list-files root "host/js/src" ".js"))
        parsed (into {} (map (fn [f] [(str (fs/file-name f)) (js-parse (clean (rd root f) :js))])) files)
        exp-ents (cons (entry :js :public "package.json#main" (str (get pj "main")))
                       (for [[k v] (sort-by key exp)] (entry :js :public (str "package.json#exports[" k "]") (str v))))
        pkg-entries (mapcat
                      (fn [[k v]]
                        (let [file (str (fs/file-name v))
                              p (get parsed file)
                              direct (keep (fn [[n d]] (when (:exported d) n)) (:defs p))
                              listed (:list p)
                              names (sort (distinct (concat direct listed)))]
                          (js-entries root (if (= k ".") pkg (str pkg (subs k 1))) :public parsed names)))
                      (sort-by key exp))
        wasm-exports (let [f "host/wasm/src/wasm.rs"]
                       (when (exists? root f)
                         (->> (re-seq #"pub (?:unsafe )?extern \"C\" fn (\w+)" (clean (rd root f) :rust)) (map second) sort)))]
    (concat exp-ents
            [(entry :js :public "package.json#name" (str pkg))]
            pkg-entries
            (when (seq wasm-exports)
              [(entry :js :internal "emon.wasm exports" (str/join "," wasm-exports))]))))

;; @3sln/pdfium-wasm (D553): pdfium's WebAssembly build as a package of its own. Its manifest's name, main and exports, and what its index.js exports
;; (the default factory and the .wasm's URL); and @3sln/emon's OPTIONAL peer on it (the range is a version, so it is not in the entry).
(defn- pdfium-wasm-all [root]
  (let [f "release/pkg/pdfium-wasm/package.json"]
    (when (exists? root f)
      (let [pj (json/parse-string (rd root f))
            pkg (get pj "name")
            js (clean (rd root "release/pkg/pdfium-wasm/index.js") :js)
            fns (->> (re-seq #"export default (async )?function (\w+)\(([^)]*)\)" js)
                     (map (fn [[_ a _ params]] (entry :js :public (str pkg "::default") (str (when a "async ") "function(" (str/trim params) ")")))))
            named (->> (re-seq #"export (async )?function (\w+)\(([^)]*)\)" js)
                       (map (fn [[_ a n params]] (entry :js :public (str pkg "::" n) (str (when a "async ") "function(" (str/trim params) ")")))))
            values (->> (re-seq #"export const (\w+)" js) (map (fn [[_ n]] (entry :js :public (str pkg "::" n) "value"))))
            peer (get-in (json/parse-string (rd root "host/js/package.json")) ["peerDependenciesMeta" pkg "optional"])]
        (concat [(entry :js :public (str pkg " package.json#name") pkg)
                 (entry :js :public (str pkg " package.json#main") (str (get pj "main")))]
                (for [[k v] (sort-by key (get pj "exports"))]
                  (entry :js :public (str pkg " package.json#exports[" k "]") (if (map? v) (str/join "," (map (fn [[a b]] (str a "=" b)) (sort-by key v))) (str v))))
                fns named values
                (when peer [(entry :js :public "package.json#peerDependencies[@3sln/pdfium-wasm]" "optional")]))))))

;; ------------------------------------------------------------------------------------------------ C

(defn- c-header-entries [root]
  (let [raw (rd root "host/c/include/emon.h")
        defines (for [[_ nm v] (re-seq #"(?m)^#define\s+(\w+)(?:[ \t]+(.*))?$" (str/replace raw #"/\*.*?\*/" ""))
                      :when (not= nm "EMON_H")]
                  (entry :c :public nm (str "#define " (norm-ws (first (str/split (or v "") #"/\*"))))))
        text (-> (clean raw :c) (str/replace #"(?m)^\s*#.*$" "") (str/replace #"extern\s+\"\"\s*\{" ""))
        stmts (loop [i 0 start 0 d 0 out []]
                (if (>= i (count text))
                  out
                  (let [c (.charAt ^String text (int i))]
                    (cond
                      (= c \{) (recur (inc i) start (inc d) out)
                      (= c \}) (recur (inc i) start (dec d) out)
                      (and (= c \;) (zero? d)) (recur (inc i) (inc i) d (conj out (norm-ws (subs text start i))))
                      :else (recur (inc i) start d out)))))
        stmts (map #(str/replace % #"^\}\s*" "") stmts)
        ents (mapcat
               (fn [s]
                 (cond
                   (re-find #"^typedef struct (\w+)?\s*\{" s)
                   (let [[_ body nm] (re-find #"\{(.*)\}\s*(\w+)$" s)
                         fields (->> (str/split body #";") (map norm-ws) (remove str/blank?))
                         ;; [name type] in declaration order: the order is the struct's layout, so it is part of the signature
                         flat (mapcat (fn [f]
                                        (let [segs (str/split f #",")
                                              [_ ty n1] (re-find #"^(.*?[\s*])(\w+)$" (norm-ws (first segs)))]
                                          (when n1
                                            (cons [n1 (norm-ws ty)]
                                                  (map (fn [x] [(norm-ws x) (norm-ws ty)]) (next segs))))))
                                      fields)]
                     (cons (entry :c :public nm "struct")
                           (map-indexed (fn [i [n ty]] (entry :c :public (str nm "." n) (str "@" i " " ty))) flat)))
                   (re-find #"^typedef .*\(\*\s*(\w+)\s*\)" s)
                   (let [[_ nm] (re-find #"\(\*\s*(\w+)\s*\)" s)]
                     [(entry :c :public nm (norm-ws s))])
                   (re-find #"^typedef struct (\w+) (\w+)$" s)
                   (let [[_ _ nm] (re-find #"^typedef struct (\w+) (\w+)$" s)] [(entry :c :public nm "opaque struct")])
                   (re-find #"^typedef " s) []
                   (re-find #"\b(emon_\w+)\s*\(" s)
                   (let [pi (str/index-of s "(")
                         nm (second (re-find #"(\w+)\s*$" (subs s 0 pi)))
                         ret (norm-ws (subs s 0 (- pi (count nm))))
                         params (norm-ws (subs s (inc pi) (str/last-index-of s ")")))]
                     [(entry :c :public nm (str ret " (" params ")"))])
                   :else []))
               stmts)
        header-fns (set (keep #(when (and (= (:host %) :c) (str/includes? (:sig %) "(") (not (str/starts-with? (:sig %) "#")) (not (str/starts-with? (:sig %) "typedef")) (re-find #"^emon_[a-z]" (:id %)) (not (str/includes? (:id %) "."))) (:id %)) ents))
        capi (clean (rd root "host/c/src/capi.rs") :rust)
        symbols (set (map second (re-seq #"#\[no_mangle\]\s*pub\s+(?:unsafe\s+)?extern\s+\"\"\s+fn\s+(\w+)" capi)))
        extra (for [s (sort symbols) :when (not (contains? header-fns s))]
                (entry :c :accidental (str "symbol " s) "exported by libemon, not declared in emon.h"))
        missing (for [f (sort header-fns) :when (not (contains? symbols f))] f)]
    {:entries (concat defines ents extra) :missing missing}))

;; ------------------------------------------------------------------------------------------------ pod

(defn- region [text start-re]
  (when-let [m (re-matcher start-re text)]
    (when (.find m)
      (let [s (.start m)
            e (or (str/index-of text "\nfn " (inc s)) (count text))]
        (subs text s e)))))

(defn- pod-entries [root]
  (let [text (rd root "host/pod/src/main.rs")
        desc (region text #"fn describe\(\)")
        vars (some-> (re-find #"let vars: Vec<Bv> = \[([^\]]*)\]" desc) second (str/split #",") (->> (map #(str/replace (str/trim %) "\"" ""))))
        ns (second (re-find #"\(\"name\", bs\(\"(\w+)\"\)\), \(\"vars\"" desc))
        fmt (second (re-find #"\(\"format\", bs\(\"(\w+)\"\)\)" desc))
        caps (some->> (re-find #"\"capabilities\" => Ok\(map\(vec!\[(.*?)\]\)\)" (str/replace text "\n" " "))
                      second (re-seq #"\(\"([\w-]+)\"") (map second))
        conv (region text #"fn convert\(")
        spec (->> (re-seq #"get\(&spec, \"([\w-]+)\"\)" conv) (map second) distinct)
        models (->> (re-seq #"Edn::Kw\(k\) if k == \"([\w-]+)\"" conv) (map second) distinct)
        results (->> (re-seq #"out\.push\(\(\s*\"([\w-]+)\"" conv) (map second) distinct)
        stats (some->> (re-find #"\"stats\",\s*map\(vec!\[(.*?)\]\)" (str/replace conv "\n" " ")) second (re-seq #"\(\"([\w-]+)\"") (map second))
        intake (some->> (region text #"fn intake_edn") (re-seq #"\(\"([\w-]+)\"") (map second))
        assets (some->> (region text #"fn assets_of") (re-seq #"\(\"([\w-]+)\"") (map second) distinct)
        err (when-let [i (str/index-of text "(\"name\", s(&e.name))")]
              (->> (re-seq #"\(\"([a-z]+)\", " (subs text i (min (count text) (+ i 300)))) (map second) distinct))]
    (concat
      [(entry :pod :public (str "namespace " ns) (str "format " fmt))]
      (for [v vars] (entry :pod :public (str ns "/" v) "var"))
      (for [k caps] (entry :pod :public (str ns "/capabilities :" k) "key"))
      (for [k spec] (entry :pod :public (str ns "/convert spec :" k) "key"))
      (for [k models] (entry :pod :public (str ns "/convert value :" k) "word"))
      (for [k results] (entry :pod :public (str ns "/convert result :" k) "key"))
      (for [k stats] (entry :pod :public (str ns "/convert result :stats :" k) "key"))
      (for [k intake] (entry :pod :public (str ns "/intake result :" k) "key"))
      (for [k assets] (entry :pod :public (str ns "/convert result :assets :" k) "key"))
      (for [k err] (entry :pod :public (str "error ex-data :" k) "key")))))

;; ------------------------------------------------------------------------------------------------ options, limits, flags, schema

(defn- kin-data
  "The numbers of the `:data [..]` of `(defdata ^:pub NAME`, comments dropped."
  [text nm]
  (let [i (str/index-of text (str "(defdata ^:pub " nm "\n"))
        _ (when-not i (throw (ex-info (str "no defdata " nm) {})))
        j (str/index-of text ":data [" i)
        e (str/index-of text "]" j)
        body (str/replace (subs text (+ j 7) e) #";;[^\n]*" "")]
    (mapv #(Long/parseLong %) (re-seq #"\d+" body))))

(defn- code-name [chars off len] (apply str (map char (subvec chars off (+ off len)))))

;; THE `emon` COMMAND (P11.4b, D541): its commands and the flags of `emon convert`, read from the USAGE text of host/cli/src/main.rs. These are the
;; user-facing interface of the release archive, so they are :public (the test driver's flags above are :provisional).
(defn- cli-entries [root]
  (when (exists? root "host/cli/src/main.rs")
    (let [usage (second (re-find #"(?s)const USAGE: &str = \"(.*?)\";" (rd root "host/cli/src/main.rs")))
          convert (second (re-find #"(?s)(emon convert .*?)\n\s*emon version" (or usage "")))
          flags (re-seq #"\[(-o|--[a-z][a-z-]*)(?: ([^\]\[]+))?\]" (or convert ""))]
      (concat
        (for [[_ c] (re-seq #"(?m)^  emon ([a-z]+)" (or usage ""))]
          (entry :options :public (str "emon " c) "command"))
        (for [[_ f v] flags]
          (entry :options :public (str "emon convert " f) (or v "flag")))
        ;; an enumerated value is an entry of its own: a new word is an addition, a lost one a removal
        (for [[_ f v] flags :when (and v (str/includes? v "|")), w (str/split v #"\|")]
          (entry :options :public (str "emon convert " f "=" w) "value"))))))

(defn- options-entries [root]
  (let [cfg (rd root "kin/config.kin")
        lim (rd root "kin/limits.kin")
        ck-chars (kin-data cfg "ck-chars")
        ck-rows (partition 5 (kin-data cfg "ck-rows"))
        cv-chars (kin-data cfg "cv-chars")
        cv-rows (partition 4 (kin-data cfg "cv-rows"))
        shape ["string" "number" "enum" "flag" "prefix" "prefix"]
        keyrows (map-indexed (fn [i [sh lo hi off len]] {:i i :sh sh :lo lo :hi hi :name (code-name ck-chars off len)}) ck-rows)
        words (fn [i] (->> cv-rows (filter #(= i (first %))) (sort-by second >) (map (fn [[_ code off len]] [(code-name cv-chars off len) code]))))
        keys* (concat
                (for [{:keys [i sh lo hi name]} keyrows]
                  (entry :options :public (str "option " name)
                         (case (long sh)
                           0 "string"
                           1 (str "number " lo ".." hi)
                           2 "enum"
                           3 "flag"
                           4 "prefix: limit.NAME=N"
                           5 "prefix: producer.NAME=VALUE")))
                ;; an enum's words are entries of their own: a new word is an addition, a lost one a removal
                (for [{:keys [i sh name]} keyrows :when (= 2 sh), [w code] (words i)]
                  (entry :options :public (str "option " name "=" w) (str "code " code))))
        l-chars (kin-data lim "lm-chars")
        l-rows (partition 6 (kin-data lim "lm-rows"))
        limits (for [[idx [dflt lo hi off len on-hit]] (map-indexed vector l-rows)]
                 (entry :options :public (str "limit " (code-name l-chars off len))
                        (str "kind " idx " default " dflt " range " lo ".." hi " " (["breach" "budget" "reserved"] on-hit))))
        session (rd root "host/rust/src/session.rs")
        hreg (region session #"pub fn parse_html")
        hwords (concat (map second (re-seq #"\"([a-z][a-z=-]*)\" =>" hreg))
                       (map #(str % "N") (map second (re-seq #"strip_prefix\(\"(confidence=)\"" hreg)))
                       (map #(str % "TEXT") (map second (re-seq #"strip_prefix\(\"(prefix=)\"" hreg))))
        html-opts (for [w (sort (distinct hwords))] (entry :options :public (str "html-option " w) "word"))
        outs (for [o ["json" "html" "evidence" "intake"]] (entry :options :public (str "output " o) "format"))
        drv (rd root "test/driver/rust/main.rs")
        usage (second (re-find #"eprintln!\(\"usage: emon-rs (.*?) FILE\"\)" drv))
        flags (for [[_ f v] (re-seq #"(--[a-z][a-z-]*)((?:\[?=[A-Za-z|_.-]+\]?)?)" (or usage ""))]
                (entry :options :provisional (str "cli " f) (if (str/blank? v) "flag" (str/replace v #"^\[|\]$" ""))))
        schema (json/parse-string (rd root "schema/emon-1.json"))
        defs (get schema "$defs")
        no-enum (fn no-enum [x] (cond (map? x) (into (sorted-map) (map (fn [[k v]] [k (no-enum v)])) (dissoc x "enum"))
                                      (sequential? x) (mapv no-enum x)
                                      :else x))
        jsig (fn [p] (json/generate-string (no-enum (select-keys p ["type" "const" "$ref" "items" "minimum" "maximum"]))))
        enum-of (fn [p] (or (get p "enum") (get-in p ["items" "enum"])))
        prop-ents (fn [prefix req props]
                    (mapcat (fn [[k p]]
                              (cons (entry :options :public (str prefix " " k) (str (if (some #{k} req) "required " "optional ") (jsig p)))
                                    ;; the values of an enum are entries of their own (a new value is an addition)
                                    (for [v (enum-of p)] (entry :options :public (str prefix " " k "=" v) "value"))))
                            props))
        top (prop-ents "model.json" (get schema "required") (get schema "properties"))
        dprops (mapcat (fn [[dn d]] (prop-ents (str "model.json $defs/" dn) (get d "required") (get d "properties"))) defs)
        meta* [(entry :options :public "model.json $id" (get schema "$id"))
               (entry :options :public "model.json versions" (str/join "," (get-in schema ["properties" "emon" "enum"])))]]
    (concat keys* limits html-opts outs flags (cli-entries root) top dprops meta*)))

;; ------------------------------------------------------------------------------------------------ tiers

;; [host id-regex tier]: the first rule that matches an entry's id decides its tier, over what the extractor said. This is the
;; list of what is public on purpose (nothing here is a guess: each rule says why).
(def tier-rules
  [;; binding hooks that are public by accident. (The hidden test setting `no_retarget`/`NoRetarget`/`noRetarget` and the C symbol `emon_test_panic` were closed
   ;; in P11.4, D542: a feature, a build tag, package-private, internal, and a test header; they are not in the surface at all.)
   [:rust #"^emon::Engine::start_pushed$" :accidental]
   ;; Rust: the lib.rs re-exports are the API (and the types their signatures name: see rust-entries). Tiered by the extractor.
   ;; Go: the root package is the library. `port` mixes the plan/render types an embedder names (a Renderer, an Ocr's
   ;; request and answer) with the kin plumbing the generated code calls; `planrun.Transcript` is what emon.Transcript aliases.
   [:go #"^port\.(Bitmap$|Bitmap\.|Plan(Answer|Bitmaps|Request|Error|Entry|Fulfiller|Misses|Stats|Config)|Render|NewRender|Layer|FulfilError|KindMux|ParseRender)" :public]
   [:go #"^planrun\.(Transcript|File)" :public]
   ;; Java and C#: the Api package/namespace is the library; ffi/ are the live adapters (not frozen); the root package holds the
   ;; kin port plumbing the generated code calls (Port, Planrun), minus what the Api names: the Renderer port an embedder
   ;; implements (RenderPort), the transcript, plan request/answer/error of an Ocr, and the Bitmap.
   [:java #"^emon\.api\." :public]
   [:java #"^emon\.ffi\." :provisional]
   [:java #"^emon\.RenderPort" :public]
   [:java #"^emon\.Planrun\.(Transcript|Request|Answer|PlanError|Entry|File|Fulfiller)(\.|#|$)" :public]
   [:java #"^emon\.Port\.Bitmap([.#]|$)" :public]
   [:java #"^emon\.(Port|Planrun)" :accidental]
   [:csharp #"^emon\.Api\." :public]
   [:csharp #"^emon\.Ffi\." :provisional]
   [:csharp #"^emon\.RenderPort" :public]
   [:csharp #"^emon\.Planrun\.(Transcript|Request|Answer|PlanError|Entry|File|Fulfiller)(\.|#|$)" :public]
   [:csharp #"^emon\.Port\.Bitmap([.#]|$)" :public]
   [:csharp #"^emon\.(Port|Planrun)" :accidental]
   ;; JS: the constructors only `Emon.load` and `convert` call. (The handles `w`, `h`, `Asset.model` are non-enumerable since P11.4 and `tesseract.resultOf` is not exported.)
   [:js #"^@3sln/emon::(Model|Asset|Emon)#constructor$" :accidental]])

(defn- apply-tier [{:keys [host id] :as e}]
  (if (= :internal (:tier e))
    e
    (if-let [[_ _ t] (first (filter (fn [[h re _]] (and (= h host) (re-find re id))) tier-rules))]
      (assoc e :tier t)
      e)))

;; ------------------------------------------------------------------------------------------------ extraction

(defn extract
  "{:entries [...] :problems [...]} -- the whole surface read from the source under root."
  [root]
  (let [c (c-header-entries root)
        ents (concat (rust-entries root) (go-all root) (java-all root) (cs-all root) (js-all root) (pdfium-wasm-all root)
                     (:entries c) (pod-entries root) (options-entries root))
        ents (map apply-tier ents)
        dup (->> ents (group-by (juxt :host :id)) (filter #(> (count (val %)) 1)) (map key))
        ;; a duplicate key whose entries agree is one entry; otherwise it is a problem
        dedup (->> ents (group-by (juxt :host :id)) vals
                   (map (fn [g] (if (apply = (map (juxt :tier :sig) g)) (first g) (first (sort-by #(:sig %) g)))))
                   (sort-by (juxt (comp name :host) :id)))
        conflicts (->> ents (group-by (juxt :host :id)) (filter (fn [[_ g]] (not (apply = (map (juxt :tier :sig) g))))) (map key))]
    {:entries (vec dedup)
     :problems (concat (map #(str "header declares `" % "` but libemon (capi.rs) does not export it") (:missing c))
                       (map #(str "two different entries for " (pr-str %)) conflicts))}))

;; ------------------------------------------------------------------------------------------------ file and diff

(defn- fmt-entry [{:keys [host tier id sig]}] (pr-str [host tier id sig]))

(defn- file-text [entries]
  (str ";; api/surface.edn -- the frozen public surface of every shipped package (P11.2, DEVLOG D527). GENERATED by `bb api-diff --write`;\n"
       ";; `bb api-diff` extracts the surface from source and diffs it against this file. [host tier id sig], sorted by host then id.\n"
       ";; tiers: :public (frozen) :provisional (shipped, not frozen) :accidental (public by leak, to be closed) :internal (counted).\n"
       "[\n"
       (str/join "\n" (map #(str " " (fmt-entry %)) entries))
       "\n]\n"))

(defn- read-surface [root]
  (let [p (path root surface-file)]
    (when (fs/exists? p)
      (mapv (fn [[host tier id sig]] {:host host :tier tier :id id :sig sig}) (edn/read-string (slurp p))))))

(defn diff-surfaces
  "{:removed :changed :added :notes} between the frozen entries and the current ones."
  [old new]
  (let [k (juxt :host :id)
        om (into {} (map (juxt k identity)) old)
        nm (into {} (map (juxt k identity)) new)
        removed (for [[key o] om :when (and (= :public (:tier o)) (not (contains? nm key)))] o)
        changed (for [[key o] om
                      :let [n (get nm key)]
                      :when (and n (= :public (:tier o)) (or (not= (:sig o) (:sig n)) (not= :public (:tier n))))]
                  [o n])
        added (for [[key n] nm :when (and (= :public (:tier n)) (or (not (contains? om key)) (not= :public (:tier (get om key)))))] n)
        note-rem (for [[key o] om :when (and (not= :public (:tier o)) (not (contains? nm key)))] o)
        note-chg (for [[key o] om :let [n (get nm key)] :when (and n (not= :public (:tier o)) (not= :public (:tier n)) (not= [(:tier o) (:sig o)] [(:tier n) (:sig n)]))] [o n])
        note-add (for [[key n] nm :when (and (not= :public (:tier n)) (not (contains? om key)))] n)]
    {:removed (sort-by k removed) :changed (sort-by (comp k first) changed) :added (sort-by k added)
     :notes {:removed (count note-rem) :changed (count note-chg) :added (count note-add)}}))

(defn- show [{:keys [host id sig tier]}] (str (name host) " " id (when-not (str/blank? sig) (str "  [" sig "]")) (when (not= :public tier) (str " <" (name tier) ">"))))

(defn- summary [entries]
  (->> entries (group-by :host) (sort-by (comp name key))
       (map (fn [[h es]] (str (name h) " " (count es) " (" (str/join ", " (map (fn [[t g]] (str (count g) " " (name t))) (sort-by (comp name key) (group-by :tier es)))) ")")))))

;; ------------------------------------------------------------------------------------------------ run

(defn run
  "Run the check on `root`. Returns {:code n :out string}."
  [root args]
  (let [sb (StringBuilder.)
        say (fn [& xs] (.append sb (str (str/join " " xs) "\n")))
        flags (set args)
        known #{"--allow-additions" "--write" "--list" "--self-test"}
        bad (remove #(or (known %) (not (str/starts-with? % "--"))) args)]
    (cond
      (seq bad) {:code 2 :out (str "usage: bb api-diff [--allow-additions | --write | --list [HOST] | --self-test]; unknown: " (str/join " " bad) "\n")}
      :else
      (let [{:keys [entries problems error]} (try (extract root) (catch Exception e {:error e}))]
        (cond
          (nil? entries) {:code 2 :out (str "api-diff: extraction failed: " (ex-message error) " " (pr-str (ex-data error))
                                            "\n" (str/join "\n" (take 6 (map str (.getStackTrace ^Throwable error)))) "\n")}
          (flags "--list")
          (let [host (first (remove #(str/starts-with? % "--") args))]
            (doseq [e entries :when (or (nil? host) (= host (name (:host e))))] (say (fmt-entry e)))
            {:code 0 :out (str sb)})
          (flags "--write")
          (do (when (seq problems) (doseq [p problems] (say "  WARN" p)))
              (spit (path root surface-file) (file-text entries))
              (say "  wrote" surface-file "--" (count entries) "entries:")
              (doseq [l (summary entries)] (say "      " l))
              {:code 0 :out (str sb)})
          :else
          (let [old (read-surface root)]
            (if-not old
              {:code 1 :out (str "  FAIL " surface-file " is missing (bb api-diff --write)\n")}
              (let [{:keys [removed changed added notes]} (diff-surfaces old entries)
                    fails (+ (count removed) (count changed) (count problems)
                             (if (flags "--allow-additions") 0 (count added)))]
                (doseq [p problems] (say "  FAIL" p))
                (doseq [e removed] (say "  FAIL removed:" (show e)))
                (doseq [[o n] changed] (say "  FAIL changed:" (show o) "->" (str "[" (:sig n) "]" (when (not= :public (:tier n)) (str " <" (name (:tier n)) ">")))))
                (doseq [e added] (say (if (flags "--allow-additions") "  note added (allowed):" "  FAIL added (--allow-additions, then --write):") (show e)))
                (when (pos? (+ (:removed notes) (:changed notes) (:added notes)))
                  (say (str "  note  non-public entries moved: " (:removed notes) " removed, " (:changed notes) " changed, " (:added notes) " added (bb api-diff --write refreshes them)")))
                (if (pos? fails)
                  (do (say (str "api-diff FAILED: " fails " finding(s). A removal or change of the public API is a breaking change: DEVLOG D527's policy decides whether this release may carry it; then `bb api-diff --write`."))
                      {:code 1 :out (str sb)})
                  (do (say (str "  ok   public API unchanged: " (count (filter #(= :public (:tier %)) old)) " frozen entries, "
                                (count entries) " in all (" (str/join "; " (summary entries)) ")"))
                      {:code 0 :out (str sb)}))))))))))

;; ------------------------------------------------------------------------------------------------ self-test

(defn- tree-hash [root files]
  (mapv #(when (exists? root %) (hash (rd root %))) files))

(def ^:private copy-dirs
  ["host/rust/src" "host/go" "host/java" "host/csharp" "host/js/src" "host/c" "host/pod/src" "host/wasm/src" "api"])
(def ^:private copy-files
  ["host/js/package.json" "release/pkg/pdfium-wasm/package.json" "release/pkg/pdfium-wasm/index.js" "kin/config.kin" "kin/limits.kin" "schema/emon-1.json" "test/driver/rust/main.rs" "host/cli/src/main.rs"])

(defn- list-gen [root]
  (concat (map #(str "gen/rust/emon/" (fs/file-name %)) (fs/glob (path root "gen/rust/emon") "*.rs"))
          (map #(str "gen/java/emon/" (fs/file-name %)) (fs/glob (path root "gen/java/emon") "*.java"))
          (map #(str "gen/csharp/Emon/" (fs/file-name %)) (fs/glob (path root "gen/csharp/Emon") "*.cs"))))

(defn self-test []
  (let [real (str (fs/absolutize "."))
        tmp (str (fs/create-temp-dir {:prefix "api-diff-"}))
        sentinel ["api/surface.edn" "host/rust/src/engine.rs" "host/java/emon/api/Engine.java" "host/c/include/emon.h"]
        before (tree-hash real sentinel)
        fails (atom [])
        edit! (fn [f g] (let [p (path tmp f) s (slurp p) s2 (g s)]
                          (when (= s s2) (swap! fails conj (str "self-test: the edit of " f " changed nothing")))
                          (spit p s2)))
        fresh! (fn []
                 (doseq [d copy-dirs] (when (fs/exists? (path real d)) (fs/delete-tree (path tmp d)) (fs/create-dirs (fs/parent (path tmp d))) (fs/copy-tree (path real d) (path tmp d))))
                 (doseq [f copy-files] (fs/create-dirs (fs/parent (path tmp f))) (fs/copy (path real f) (path tmp f) {:replace-existing true}))
                 ;; only the NAMES of the generated modules are read
                 (doseq [g (list-gen real)] (fs/create-dirs (fs/parent (path tmp g))) (spit (path tmp g) ""))
                 (doseq [d (list-dirs real "gen/go")] (fs/create-dirs (path tmp (str "gen/go/" d)))))
        t! (fn [what ok?] (when-not ok? (swap! fails conj what)))
        case! (fn [what args re mutate!]
                (fresh!)
                (mutate!)
                (let [{:keys [code out]} (run tmp args)]
                  (t! (str what ": exit " code " and output matching " re " wanted, got\n" out)
                      (and (= 1 code) (re-find re out)))))]
    (try
      (fresh!)
      (let [{:keys [code out]} (run tmp [])]
        (t! (str "the unchanged copy must pass:\n" out) (= 0 code)))
      ;; removals and changes, one per host kind
      (case! "a removed Rust method" [] #"FAIL removed: rust emon::Engine::convert\b"
             #(edit! "host/rust/src/engine.rs" (fn [s] (str/replace-first s "pub fn convert(" "fn convert("))))
      (case! "a changed Rust signature" [] #"FAIL changed: rust emon::Engine::convert\b"
             #(edit! "host/rust/src/engine.rs" (fn [s] (str/replace-first s #"pub fn convert\(&self, " "pub fn convert(&self, extra: u8, "))))
      (case! "a removed Rust re-export" [] #"FAIL removed: rust emon::Parsed \(re-export\)"
             #(edit! "host/rust/src/lib.rs" (fn [s] (str/replace-first s "Parsed, " ""))))
      (case! "a removed Java method" [] #"FAIL removed: java emon\.api\.Engine#convert"
             #(edit! "host/java/emon/api/Engine.java" (fn [s] (str/replace-first s "public Model convert(" "Model convert("))))
      (case! "a changed Java return type" [] #"FAIL changed: java emon\.api\.Engine#kinds"
             #(edit! "host/java/emon/api/Engine.java" (fn [s] (str/replace-first s "public static Map<String, Model.Kind> kinds()" "public static Object kinds()"))))
      (case! "a removed C# method" [] #"FAIL removed: csharp emon\.Api\.Engine#Convert"
             #(edit! "host/csharp/Emon/Api/Engine.cs" (fn [s] (str/replace-first s "public Model Convert(" "internal Model Convert("))))
      (case! "a removed Go function" [] #"FAIL removed: go emon\.NewEngine"
             #(edit! "host/go/engine.go" (fn [s] (str/replace-first s "func NewEngine(" "func newEngine("))))
      (case! "a removed JS method" [] #"FAIL removed: js @3sln/emon::Model#html\b"
             #(edit! "host/js/src/index.js" (fn [s] (str/replace-first s "  html(o = {}) {" "  _html(o = {}) {"))))
      (case! "a changed JS signature" [] #"FAIL changed: js @3sln/emon::Emon#convert\b"
             #(edit! "host/js/src/index.js" (fn [s] (str/replace-first s "async convert(input, options = {}) {" "async convert(input) {"))))
      (case! "a removed JS export" [] #"FAIL removed: js @3sln/emon::bytesSource"
             #(edit! "host/js/src/index.js" (fn [s] (str/replace-first s "export { bytesSource, " "export { "))))
      (case! "a removed C function" [] #"FAIL removed: c emon_intake\b"
             #(edit! "host/c/include/emon.h" (fn [s] (str/replace-first s "int emon_intake(" "int emon_intake_old("))))
      (case! "a changed C macro" [] #"FAIL changed: c EMON_ERR_IO\b"
             #(edit! "host/c/include/emon.h" (fn [s] (str/replace-first s "EMON_ERR_IO                     10" "EMON_ERR_IO                     99"))))
      (case! "a removed pod var" [] #"FAIL removed: pod emon/intake"
             #(edit! "host/pod/src/main.rs" (fn [s] (str/replace-first s "\"convert\", \"intake\"," "\"convert\","))))
      (case! "a changed option code" [] #"FAIL changed: options option ocr=always"
             #(edit! "kin/config.kin" (fn [s] (str/replace-first s ":data [1 2 0 6 " ":data [1 3 0 6 "))))
      (case! "a removed Model property" [] #"FAIL removed: options model\.json "
             #(edit! "schema/emon-1.json" (fn [s] (str/replace-first s "\"generator\": {" "\"generatorx\": {"))))
      ;; an addition fails by default and passes with --allow-additions
      (fresh!)
      (edit! "host/rust/src/engine.rs" (fn [s] (str/replace-first s "pub fn convert(" "pub fn convert_all(&self) {}\n    pub fn convert(")))
      (let [a (run tmp []) b (run tmp ["--allow-additions"])]
        (t! (str "a new public method must fail without --allow-additions:\n" (:out a)) (and (= 1 (:code a)) (re-find #"FAIL added.*emon::Engine::convert_all" (:out a))))
        (t! (str "a new public method must pass with --allow-additions:\n" (:out b)) (= 0 (:code b)))
        ;; --write accepts it
        (run tmp ["--write"])
        (t! "after --write the tree must pass" (= 0 (:code (run tmp [])))))
      ;; a header function the library does not export
      (case! "a header function with no symbol" [] #"does not export it"
             #(edit! "host/c/include/emon.h" (fn [s] (str/replace-first s "void emon_free_error(emon_error *e);" "void emon_free_error(emon_error *e);\nvoid emon_ghost(void);"))))
      ;; an accidental change is a note, not a failure
      (fresh!)
      (edit! "host/rust/src/plan.rs" (fn [s] (str s "\npub fn api_diff_self_test_probe() {}\n")))
      (let [{:keys [code out]} (run tmp [])]
        (t! (str "a new accidental item must not fail the check:\n" out) (= 0 code)))
      (finally (fs/delete-tree tmp)))
    (t! "the real tree changed under the self-test" (= before (tree-hash real sentinel)))
    (if (seq @fails)
      (do (doseq [f @fails] (println "  FAIL" f)) (println "self-test FAILED") (System/exit 1))
      (println "  ok   self-test: removals, changes and additions in every host were caught on a temporary copy, the real tree was not touched"))))

;; ------------------------------------------------------------------------------------------------ main

(defn -main [args]
  (if (= ["--self-test"] args)
    (self-test)
    (let [{:keys [code out]} (run (str (fs/absolutize ".")) args)]
      (print out)
      (flush)
      (System/exit code))))

(when (= *file* (System/getProperty "babashka.file"))
  (-main *command-line-args*))
