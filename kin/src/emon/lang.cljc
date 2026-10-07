(ns emon.lang
  "emon's own vocabulary: the tags its sources annotate with, and the forms
  that are emon's rather than kin's. Mirrors `nome.lang` (D026, D028).

  THE SHAPE FORMS ARE NOT HERE. `defn`, `let`, `if`, `case`, `for`, `return`
  and the operators come from `kin.lang`, which speaks all four targets.

  THERE IS NO FLOAT TAG AND NO STRING TAG, on purpose. Geometry is integers
  (D009), so no `F64`; emon only ever WRITES literals, through the `Text`
  sink (D028), so no `Str`."
  (:require [kin]
            [clojure.string :as str]
            [kin.lang :as kl]))

;; ---------------------------------------------------------------- the tags
;;
;; FOUR TARGETS, AND EXPLICIT WIDTHS, for nome's reason: `int` in Go is
;; platform-sized, so every integer tag names its width. `:zero` IS GO'S
;; ALONE, and every tag carries one so any may be the return of a `^:throws`
;; function.

(def I32
  {:name 'I32
   :types {:rust "i32" :go "int32" :java "int" :csharp "int"}
   :zero {:go "0"}})

(def U32
  "Unsigned, and a DIFFERENT TAG from `I32` even where the spelling agrees.

  Java and C# have no unsigned 32-bit int, so both spell this `int` and the
  high bit means the value is negative there. A form that reads its arguments'
  tags can emit `Integer.compareUnsigned` for two of these and a plain `<`
  otherwise -- which is the whole reason kin lets two tags share a type."
  {:name 'U32
   :types {:rust "u32" :go "uint32" :java "int" :csharp "int"}
   :zero {:go "0"}})

(def I64
  {:name 'I64
   :types {:rust "i64" :go "int64" :java "long" :csharp "long"}
   :zero {:go "0"}})

(def Bool
  {:name 'Bool
   :types {:rust "bool" :go "bool" :java "boolean" :csharp "bool"}
   :zero {:go "false"}})

(def Blob
  "An immutable run of bytes, OPAQUE to kin.

  Each language spells it as whatever it already has -- a Rust slice, a Go
  slice, a Java or C# array -- and nothing in a kin source ever indexes one
  directly. Reads go through `emon.port`, which is where the four languages'
  disagreement about a byte disappears: Java's `byte` is SIGNED, so reading
  one there needs `& 0xFF` and reading one anywhere else does not. A source
  that knew that would be a source that knew which language it was."
  {:name 'Blob
   :types {:rust "&[u8]" :go "[]byte" :java "byte[]" :csharp "byte[]"}
   :zero {:go "nil"}})

(def Sink
  "Where the box model IR is WRITTEN, opaque to kin.

  THE IR IS A STREAM, NOT A STRUCTURE, and that is forced rather than
  preferred. `kin.lang` cannot allocate -- it has no arrays, no collections
  and no constructor form -- so a layout engine written in it cannot BUILD a
  tree and hand it back. What it can do is emit one, in order, through a sink
  the host supplies.

  That turns out to be what SPEC 9.1 wants anyway. The cross-target identity
  gate compares SERIALIZED IR, so a format whose bytes are produced directly
  by the layout code is the thing being compared, with no structure in
  between for four languages to disagree about.

  EVERYTHING IN THE STREAM IS AN INTEGER, which keeps the hosts trivial: each
  one appends a decimal and a space, and the FORMAT lives entirely in the
  order kin emits. A host that formatted records would be four hand-written
  implementations that have to agree byte for byte, which is the risk this
  design exists to remove."
  {:name 'Sink
   :types {:rust "&mut String" :go "*strings.Builder"
           :java "StringBuilder" :csharp "System.Text.StringBuilder"}
   ;; THE TYPE SAYS WHAT IT NEEDS. Only Go does: `StringBuilder` needs no
   ;; import in Java, C# takes it fully qualified, and `String` is in Rust's
   ;; prelude. `kin.lang/type-of` asks every tag this wherever a type becomes
   ;; text, so a parameter, a return, a local and a struct field all register
   ;; it without any of them knowing which language is reading.
   :needs {:go "strings"}
   :zero {:go "nil"}})

(def Cells
  "A growable run of 32-bit integers that is READ AND WRITTEN, opaque to kin.

  THE THIRD THING, after `Blob` and `Sink`. A blob is read and never written;
  a sink is written and never read. Tree construction needs the one that is
  both -- a stack of open elements is pushed, popped, and INDEXED INTO, since
  \"has an element in button scope\" walks it and the adoption agency algorithm
  reaches into the middle of it.

  One primitive serves the node arena, that stack, and the list of active
  formatting elements, because all three are a growable run of integers and
  SPEC 4 already says the DOM is arenas of INDICES rather than pointers. A
  node is its index; that is what lets four languages hold the same shape
  without four memory models arguing about it.

  A READ NESTED INSIDE A WRITE DOES NOT COMPILE IN RUST -- `(cells-set c i
  (cells-at c j))` borrows it mutably and then immutably inside that borrow --
  so bind the read to a `let` first. That is Rust holding the line for the
  other three, and the identity gate compiles the generated Rust, so it is
  caught at the gate rather than in somebody's build."
  {:name 'Cells
   :types {:rust "&mut crate::port::Cells" :go "*port.Cells"
           :java "emon.Port.Cells" :csharp "emon.Port.Cells"}
   ;; Go alone, and for the same reason `Sink` says `strings`: the TYPE is
   ;; what needs the import, and `kin.lang/type-of` is where a type becomes
   ;; text. The link templates import the same package; it is one import.
   :needs {:go "emon/port"}
   :zero {:go "nil"}})


(def Buf
  "A growable run of BYTES, read and written, opaque to kin (D117, SPEC 10.11).

  `Cells` at one byte an element, for output that is bytes: a PNG, an asset.
  Its elements are 0..255 (a write keeps the low eight bits) and, like
  `Cells`, it is `&mut` in Rust: bind a read before a write that nests it.
  `buf-blob` lends the contents as a `Blob` (valid until the next write)."
  {:name 'Buf
   :types {:rust "&mut crate::port::Buf" :go "*port.Buf"
           :java "emon.Port.Buf" :csharp "emon.Port.Buf"}
   :needs {:go "emon/port"}
   :zero {:go "nil"}})

(def Src
  "An INPUT BYTE SOURCE that may MISS, opaque to kin (SPEC 7.2, 13.1, D058).

  `Blob` is bytes in memory; `Src` is what the PDF and image readers are
  given instead: a per-unit VIEW over a host chunk cache. A read of a byte
  whose chunk is not resident answers 0 and sets the view's missed flag
  (`src-missed`); kin checks the flag at its checkpoints and stops, and the
  host fetches what was missed and re-runs the unit on a fresh view. A sync
  source never misses. `src-of-blob` and `src-of-cells` make an in-memory
  one, `src-slice` cuts a span out as a `Blob`.

  IN RUST IT IS A SHARED BORROW, and its state is interior (an atomic flag),
  so one source can be handed to any number of calls and to two parameters of
  the same call, which `Cells`' `&mut` cannot. The forms that MAKE one
  (`src-of-blob`, `src-of-cells`) borrow a temporary, like `cells-bytes`: nest
  them in the call that uses them, or bind them with `let` (Rust extends a
  let-bound borrow of a temporary)."
  {:name 'Src
   :types {:rust "&crate::port::Src" :go "*port.Src"
           :java "emon.Port.Src" :csharp "emon.Port.Src"}
   :needs {:go "emon/port"}
   :zero {:go "nil"}})

(def Bitmap
  "An OPAQUE 8-BIT PIXEL BUFFER, read and written through `emon.port`
  (SPEC 7.2, P2.7): `channels` bytes per pixel, row-major, top-left, `stride`
  bytes per row. Decoding is a codec's job and rendering is the Renderer's
  (D007): kin only ever receives one and reads or writes its pixels.

  IN RUST IT IS A SHARED BORROW and its bytes are `Cell`s, for `Src`'s reason:
  a bitmap is handed to several parameters of one call (a source and a mask)
  and `Cells`' `&mut` cannot be. The form that MAKES one (`bitmap-new`) borrows
  a temporary: bind it with `let` or nest it in the call that uses it."
  {:name 'Bitmap
   :types {:rust "&crate::port::Bitmap" :go "*port.Bitmap"
           :java "emon.Port.Bitmap" :csharp "emon.Port.Bitmap"}
   :needs {:go "emon/port"}
   :zero {:go "nil"}})

(def BitmapSet
  "The pixels a PAGE UNIT may read (P7.U, D196): the bitmaps its answers carried (a render's, a decode's) by request id,
  and the ones kin makes itself (`bms-put`, ids from `KIN_VIEW_BASE`). The Engine makes one for each attempt of a page
  unit and drops it when the unit ends, so a conversion holds one page's bitmaps. Opaque: `bms-get`, `bms-has`,
  `bms-put`. IN RUST IT IS A SHARED BORROW, for the Bitmap's reason (`bms-put` writes through it)."
  {:name 'BitmapSet
   :types {:rust "&crate::port::BitmapSet" :go "*port.BitmapSet"
           :java "emon.Port.BitmapSet" :csharp "emon.Port.BitmapSet"}
   :needs {:go "emon/port"}
   :zero {:go "nil"}})

(def Cpt
  "Centipoints, 1/100 pt, as a 32-bit integer (SPEC 3.4, D009).

  THE SAME MACHINE TYPE AS `I32` AND A DIFFERENT TAG, for `U32`'s reason: a
  geometry value is visible in a signature, and a form may refuse to add a
  `Cpt` to a pixel count."
  {:name 'Cpt
   :types {:rust "i32" :go "int32" :java "int" :csharp "int"}
   :zero {:go "0"}})

(def Px
  "Raster pixels. Converting `Px` <-> `Cpt` goes through a named form that
  carries the resolution; there is no implicit mix (SPEC 6.3)."
  {:name 'Px
   :types {:rust "i32" :go "int32" :java "int" :csharp "int"}
   :zero {:go "0"}})

(def Fx
  "Fixed point held in an `I64`, 24 fractional bits (PDF reals, matrices; SPEC 6.3, D103)."
  {:name 'Fx
   :types {:rust "i64" :go "int64" :java "long" :csharp "long"}
   :zero {:go "0"}})

(def Conf
  "Per-mille confidence, 0..1000 (D010)."
  {:name 'Conf
   :types {:rust "i32" :go "int32" :java "int" :csharp "int"}
   :zero {:go "0"}})

(def Text
  "A TEXT SINK: where the JSON, the HTML and the evidence dump are WRITTEN,
  opaque to kin (SPEC 6.3.1). `Sink`'s rule generalised from integers to
  text: the FORMAT lives in the order kin emits, the host only appends. Each
  host holds UTF-8 semantics -- Rust's `String` natively, the others a
  builder that is encoded to UTF-8 when it leaves -- and `text-cp` is what
  makes a non-ASCII code point representable without a string type in kin.
  `:needs` is Go's `strings`, for `Sink`'s reason."
  {:name 'Text
   :types {:rust "&mut String" :go "*strings.Builder"
           :java "StringBuilder" :csharp "System.Text.StringBuilder"}
   :needs {:go "strings"}
   :zero {:go "nil"}})

;; ------------------------------------------------- the two emon-only forms
;;
;; `emit-ascii` and `defenum` (SPEC 6.3.1 items 1 and 4, D028, D038, D039).
;; Both are ordinary kin forms: `(fn [ctx form])`, or a map of `:declare`
;; and `:generate` slots. Neither changes kin.
;;
;; BOTH WORK BY REWRITING INTO FORMS THAT ALREADY EXIST and dispatching the
;; result through kin, so the four targets' spellings stay the business of
;; the forms that own them (`text-ascii` for the call, `defn`/`if`/`return`
;; for the functions). The rewritten heads are written FULLY QUALIFIED
;; (`kin.lang/defn`) and put into the context's scope table here, so a source
;; need not have referred `text-ascii` or `if` for a form of ours to use it.

(def ^:private scope-additions
  (into {}
        (concat
         (for [s '[defn return if do ==]]
           [(symbol "kin.lang" (str s)) ['kin.lang s]])
         (for [s '[emit-ascii]]
           [(symbol "emon.lang" (str s)) ['emon.lang s]])
         (for [s '[I32 Text]]
           [(symbol "emon.lang" (str s)) ['emon.lang s]])
         [['emon.port/text-ascii ['emon.port 'text-ascii]]])))

(defn- with-scope [ctx]
  (update ctx :scope-syms #(merge % scope-additions)))

(defn- refuse!
  "Generation-time refusal, by form and by namespace. The namespace is the
  most kin's context carries (it has no file label), and a namespace names
  its file (`emon.foo` is `kin/foo.kin`)."
  [ctx what msg data]
  (throw (ex-info (str "emon: " what " in " (or (:kin/ns ctx) "<source>")
                       ": " msg)
                  (merge {:in (:kin/ns ctx)} data))))

(defn- ascii-problem
  "Nil when `s` is printable ASCII (0x20-0x7E) throughout, else a sentence
  naming the first offending character."
  [s]
  (when-let [i (first (keep-indexed
                       (fn [i c] (when-not (<= 0x20 (int c) 0x7E) i)) s))]
    (str "the literal " (pr-str s) " is not printable ASCII (0x20-0x7E): "
         (format "U+%04X" (int (nth s i))) " at index " i)))

(defn- emit-ascii-form
  "`(emit-ascii sink \"literal\")`: append a literal to a text sink. The
  literal must be a string LITERAL in the source (not an expression) of
  printable ASCII, which is checked here -- at generation time, on every
  target -- and then spelled by `pr-str`, correct for printable ASCII in all
  four languages (`\"` and `\\` are the only escapes it produces). The
  append itself is the host's `text-ascii`; the host trusts the check."
  [ctx form]
  (let [[_ sink lit & extra] form]
    (when (or (nil? sink) (nil? lit) (seq extra))
      (refuse! ctx (str "(emit-ascii " (pr-str sink) " ...)")
               "takes a text sink and one string literal" {:form form}))
    (when-not (string? lit)
      (refuse! ctx (str "(emit-ascii " sink " " (pr-str lit) ")")
               "the second argument must be a string literal" {:form form}))
    (when-let [why (ascii-problem lit)]
      (refuse! ctx (str "(emit-ascii " sink " " (pr-str lit) ")") why {:form form}))
    (kin/dispatch (with-scope ctx) (list 'emon.port/text-ascii sink lit))))

(defn- enum-problem
  "Nil when the rows of `(defenum nm rows)` are sound, else a sentence."
  [nm rows]
  (let [row-str (fn [r] (str "[" (str/join " " (map pr-str r)) "]"))
        dup (fn [f label]
              (some (fn [[k rs]] (when (< 1 (count rs))
                                   (str "duplicate " label " " (pr-str k) " in rows "
                                        (str/join " and " (map row-str rs)))))
                    (group-by f rows)))]
    (or (when-not (and (symbol? nm) (vector? rows) (seq rows))
          "the shape is (defenum name [[symbol code \"spelling\"] ...]) with at least one row")
        (some (fn [r]
                (cond (not (and (vector? r) (= 3 (count r))
                                (symbol? (nth r 0)) (integer? (nth r 1))
                                (string? (nth r 2))))
                      (str "row " (pr-str r) " is not [symbol integer-code \"spelling\"]")
                      (or (str/includes? (nth r 2) "\"") (str/includes? (nth r 2) "\\"))
                      (str "the spelling in row " (row-str r)
                           " holds a quote or backslash; it is written between JSON quotes as-is")
                      (ascii-problem (nth r 2))
                      (str "row " (row-str r) ": " (ascii-problem (nth r 2)))))
              rows)
        (dup first "symbol")
        (dup second "code")
        (dup #(nth % 2) "spelling")
        (let [emit (symbol (str nm "-emit-name"))]
          (when (some #(= emit (first %)) rows)
            (str "row symbol " emit " collides with the generated emitter"))))))

(defn- enum-forms
  "The kin forms `(defenum nm rows)` stands for: one `defn` per row answering
  its code, and `nm-emit-name`, which writes the row's spelling for a code to
  a text sink and answers 1, or answers 0 and writes nothing for an unknown
  code. The emitter is a flat run of `if`s, not a `case`: a `case` arm is a
  value, and an arm here is a statement (D039)."
  [ctx [_ nm rows :as form]]
  (when-let [why (enum-problem nm rows)]
    (refuse! ctx (str "(defenum " (pr-str nm) " ...)") why {:form form}))
  (let [pub? (boolean (:pub (meta nm)))
        i32 'emon.lang/I32
        fn-name (fn [s] (with-meta s {:tag i32 :pub pub?}))
        emit-fn (fn-name (symbol (str nm "-emit-name")))
        t (with-meta 't {:mut true :tag 'emon.lang/Text})
        code (with-meta 'code {:tag i32})]
    (concat
     (for [[s c _] rows]
       (list 'kin.lang/defn (fn-name s) [] (list 'kin.lang/return c)))
     [(concat
       (list 'kin.lang/defn emit-fn [t code])
       (for [[_ c sp] rows]
         (list 'kin.lang/if (list 'kin.lang/== 'code c)
               (list 'kin.lang/do
                     (list 'emon.lang/emit-ascii 't sp)
                     (list 'kin.lang/return 1))))
       [(list 'kin.lang/return 0)])])))

(def ^:private defenum-form
  "TWO SLOTS, for `defn`'s reason: `:declare` walks the rewritten forms so
  link knows every constant and the emitter are in scope from here on (and
  `declared-names` and exports see them), and `:generate` emits them."
  {:declare (fn [ctx form]
              (let [ctx (with-scope ctx)]
                (doseq [f (enum-forms ctx form)] (kin/link! ctx f))))
   :generate (fn [ctx form]
               (let [ctx (with-scope ctx)]
                 (doseq [f (enum-forms ctx form)] (kin/statement! ctx f))))})

;; ----------------------------------------------------- defctx and fd (D620)
;;
;; A CONTEXT IS A RECORD THE HOST CONSTRUCTS AND KIN ONLY BORROWS FROM. The
;; engine's stages share arenas, and today each of the four hand-written
;; Engines names them, owns them and sequences the stage calls. A `defctx`
;; declares that record ONCE, in kin, as a public type in every target:
;;
;;     (defctx ^:pub Doc [^Cells grl ^Buf png ^Text out ^I32 n])
;;
;;   Rust    #[derive(Clone)] pub struct Doc { pub grl: Cells, .. }  Doc::new()
;;   Go      type Doc struct { Grl *port.Cells; .. }   NewDoc()   (d *Doc) Clone()
;;   Java    public static final class Doc { public .. grl; .. }  new Doc()  clone()
;;   C#      public sealed class Doc { public .. Grl; .. }        new Doc()  Clone()
;;
;; KIN STILL NEVER ALLOCATES. Production kin takes a `^Doc d` PARAMETER; the
;; host calls the generated constructor. (`new-ctx` and `clone-ctx`, in
;; `emon.test`, are the laws' way to make and copy one, as `new-cells` is.)
;;
;; IT IS PROJECT-LOCAL, LIKE `defenum` (D042): kin's own `defstruct` cannot do
;; this -- no public fields in Go and C#, no constructor, no clone -- and a
;; kin patch was the rejected alternative. THE FIELDS ARE OWNED, a `Cells`, a
;; `Buf` or a `Text` (Rust `String`, the others a builder) or an `I32`; a
;; field never holds a borrow, so a context has no lifetime and clones.
;;
;; `(fd d grl)` LENDS A FIELD TO A CALL: `&mut d.grl` in Rust, `d.Grl` in Go.
;; Rust accepts two disjoint fields of one `&mut Doc` in one call
;; (`f(&mut d.a, &mut d.b)`), which is why a field is lent and not fetched
;; through a method. An `I32` field is a plain place: `(fd d n)` reads it and
;; `(set (fd d n) v)` writes it.

(def ^:private ctx-kinds
  "What a field may be, and how each target builds an empty one. The type text
  is the tag's own with Rust's `&mut ` taken off: a field OWNS."
  {'Cells {:rust "crate::port::Cells::new()" :go "port.NewCells()"
           :java "emon.Port.newCells()" :csharp "emon.Port.NewCells()"}
   'Buf {:rust "crate::port::Buf::new()" :go "port.NewBuf()"
         :java "emon.Port.newBuf()" :csharp "emon.Port.NewBuf()"}
   'Text {:rust "String::new()" :go "&strings.Builder{}"
          :java "new StringBuilder()" :csharp "new System.Text.StringBuilder()"}
   'I32 {:rust "0" :go "0" :java "0" :csharp "0"}})

(defn- ctx-pascal [nm]
  (let [s (str nm)]
    (if (Character/isUpperCase (first s))
      s
      (str/join (map str/capitalize (str/split s #"-"))))))

(defn- ctx-field-name
  "A field as the target spells it. Go and C# EXPORT, so capitalise what the
  target's namer made (`ev-arenas` -> `evArenas` -> `EvArenas`); Rust and Java
  keep it."
  [ctx f]
  (let [n (kin/local-name ctx f)]
    (if (#{:go :csharp} (:target ctx))
      ;; Capitalised, a word the language reserves (`out`, `type`) needs no
      ;; escape: drop the namer's (C#'s `@out`).
      (let [n (str/replace n #"^[@_]+" "")]
        (str (str/upper-case (subs n 0 1)) (subs n 1)))
      n)))

(defn- ctx-problem
  "Nil when `(defctx nm fields)` is sound, else a sentence."
  [ctx nm fields]
  (or (when-not (and (symbol? nm) (vector? fields) (seq fields) (every? symbol? fields))
        "the shape is (defctx Name [^Tag field ...]) with at least one field")
      (some (fn [f]
              (let [tag (kin/tag ctx (:tag (meta f)))]
                (cond
                  (not (contains? ctx-kinds (:name tag)))
                  (str "field " f " is tagged " (pr-str (:tag (meta f)))
                       "; a field is an OWNED Cells, Buf, Text or I32")
                  (#{"clone" "new"} (str f))
                  (str "field " f " collides with the generated " f "()"))))
            fields)
      (some (fn [[k fs]] (when (< 1 (count fs)) (str "duplicate field " k)))
            (group-by identity fields))))

(defn- ctx-unit
  "How `ctx`'s target names the namespace that declares the context: Go the
  package name and its import path, the rest the qualified unit."
  [c]
  (let [ns (:kin/ns c)
        segs (str/split (str ns) #"\.")
        pascal (fn [s] (str/join (map str/capitalize (str/split s #"-"))))]
    ;; THE TARGETS' OWN `:path` RULES, restated: Java and C# declare no
    ;; `:unit`, so the package is the namespace's parents and the class is the
    ;; last segment capitalised (`emon.docprobe` is `emon.Docprobe`).
    (case (:target c)
      :go {:name (last segs) :import (str/join "/" (cons "emon" (rest segs)))}
      :rust {:qualified (str "crate::" (str/join "::" segs))}
      {:qualified (str (str/join "." (butlast segs)) "." (pascal (last segs)))})))

(defn- ctx-tags
  "The tag a `defctx` defines, twice: LOCAL (what the declaring file says) and
  PUBLIC (what a file that requires it says). They differ in Go alone, where
  a type in another package is `ctxtest.Doc` and in its own is `Doc`. Both
  carry `:ctx-fields` (field -> kind) for `fd`, and `:ctx-new` for `new-ctx`."
  [c nm fields]
  (let [p (ctx-pascal nm)
        kinds (into {} (map (fn [f] [f (:name (kin/tag c (:tag (meta f))))])) fields)
        per (fn [tgt]
              (let [c (assoc c :target tgt)
                    u (ctx-unit c)]
                (case tgt
                  :go {:local {:type (str "*" p) :new (str "New" p "()")}
                       :public {:type (str "*" (:name u) "." p)
                                :new (str (:name u) ".New" p "()")
                                :needs (:import u)}}
                  :rust (let [q (str (:qualified u) "::" p)]
                          {:local {:type (str "&mut " q) :new (str "(&mut " q "::new())")}
                           :public {:type (str "&mut " q) :new (str "(&mut " q "::new())")}})
                  (let [q (str (:qualified u) "." p)]
                    {:local {:type q :new (str "new " q "()")}
                     :public {:type q :new (str "new " q "()")}}))))
        tgts (keys (:targets c))
        by (into {} (map (fn [t] [t (per t)])) tgts)
        mk (fn [scope]
             {:name nm
              :types (into {} (map (fn [t] [t (get-in by [t scope :type])])) tgts)
              :needs (into {} (keep (fn [t] (when-let [n (get-in by [t scope :needs])] [t n]))) tgts)
              :ctx-fields kinds
              :ctx-new (into {} (map (fn [t] [t (get-in by [t scope :new])])) tgts)})]
    {:local (mk :local) :public (mk :public)}))

(defn- ctx-field-lines
  "The declaration, constructor and clone of the context, as lines at no
  indent, for the target being emitted."
  [c nm fields]
  (let [p (ctx-pascal nm)
        tgt (:target c)
        kind (fn [f] (:name (kin/tag c (:tag (meta f)))))
        fty (fn [f] (let [ty (kl/type-of c (kin/tag c (:tag (meta f))))]
                      (str/replace ty #"^&mut " "")))
        fname (fn [f] (ctx-field-name c f))
        w (apply max (map #(count (fname %)) fields))
        pad (fn [s n] (str s (apply str (repeat (- n (count s)) " "))))
        init (fn [f] (get-in ctx-kinds [(kind f) tgt]))
        ;; Go, Java and C# copy a field by kind; Rust derives Clone.
        copy (fn [f]
               (let [n (fname f)]
                 (case [tgt (kind f)]
                   [:go Cells] (str "port.CellsCopyInto(c." n ", d." n ")")
                   [:go Buf] (str "port.BufCopyInto(c." n ", d." n ")")
                   [:go Text] (str "c." n ".WriteString(d." n ".String())")
                   [:go I32] (str "c." n " = d." n)
                   [:java Cells] (str "emon.Port.cellsCopyInto(c." n ", this." n ");")
                   [:java Buf] (str "emon.Port.bufCopyInto(c." n ", this." n ");")
                   [:java Text] (str "c." n ".append(this." n ".toString());")
                   [:java I32] (str "c." n " = this." n ";")
                   [:csharp Cells] (str "emon.Port.CellsCopyInto(c." n ", this." n ");")
                   [:csharp Buf] (str "emon.Port.BufCopyInto(c." n ", this." n ");")
                   [:csharp Text] (str "c." n ".Append(this." n ".ToString());")
                   [:csharp I32] (str "c." n " = this." n ";"))))]
    (case tgt
      :rust (concat
             ["#[derive(Clone)]" (str "pub struct " p " {")]
             (for [f fields] (str "    pub " (fname f) ": " (fty f) ","))
             ["}"
              (str "impl " p " {")
              (str "    pub fn new() -> " p " {")
              (str "        " p " {")]
             (for [f fields] (str "            " (fname f) ": " (init f) ","))
             ["        }" "    }" "}"
              (str "impl Default for " p " {")
              (str "    fn default() -> " p " {")
              (str "        " p "::new()")
              "    }" "}"])
      :go (concat
           [(str "type " p " struct {")]
           ;; gofmt's alignment, because `bb check` runs gofmt -l: a field
           ;; type and a keyed value start one space past the longest name.
           (for [f fields] (str "\t" (pad (fname f) w) " " (fty f)))
           ["}"
            (str "func New" p "() *" p " {")
            (str "\treturn &" p "{")]
           (for [f fields] (str "\t\t" (pad (str (fname f) ":") (inc w)) " " (init f) ","))
           ["\t}" "}"
            (str "func (d *" p ") Clone() *" p " {")
            (str "\tc := New" p "()")]
           (for [f fields] (str "\t" (copy f)))
           ["\treturn c" "}"])
      (let [[pubc cl] (if (= tgt :java)
                        [(str "public static final class " p " {") "clone"]
                        [(str "public sealed class " p " {") "Clone"])]
        (concat
         [pubc]
         (for [f fields] (str "    public " (fty f) " " (fname f) " = " (init f) ";"))
         [(str "    public " p "() {")
          "    }"]
         (when (= tgt :java) ["    @Override"])
         [(str "    public " p " " cl "() {")
          (str "        " p " c = new " p "();")]
         (for [f fields] (str "        " (copy f)))
         ["        return c;" "    }" "}"])))))

(defn- defctx-parts [ctx [_ nm fields :as form]]
  (when-let [why (ctx-problem ctx nm fields)]
    (refuse! ctx (str "(defctx " (pr-str nm) " ...)") why {:form form}))
  (ctx-tags ctx nm fields))

(def ^:private defctx-form
  "TWO SLOTS, for `defenum`'s reason: `:declare` makes the tag (so a later
  form, and every file that requires this one, can say `^Doc`) and `:generate`
  writes the type. The public tag goes in first and the local one over it:
  registration is cumulative, so the declaring file ends up with its own
  spelling and the exports keep theirs."
  {:declare (fn [ctx [_ nm _ :as form]]
              (let [{:keys [local public]} (defctx-parts ctx form)]
                (kin/define-tag! ctx {:scope (if (:pub (meta nm)) :public :private)} nm public)
                (kin/define-tag! ctx {:scope :private} nm local)))
   :generate (fn [ctx [_ nm fields :as form]]
               (let [{:keys [local]} (defctx-parts ctx form)]
                 (kin/define-tag! ctx {:scope :private} nm local)
                 (doseq [line (ctx-field-lines ctx nm fields)]
                   (kin/emit! ctx (kin/indent-of ctx) line "\n"))))})

(defn- fd-form
  "`(fd d field)`: see the section comment. The object must carry a context
  tag (`^Doc d`); a handle field is lent as `&mut` in Rust, an `I32` is a place."
  [ctx [_ obj f :as form]]
  (when-not (and obj (symbol? f))
    (refuse! ctx "(fd ...)" "takes a context and a field name" {:form form}))
  (let [{:keys [text tag]} (kin/render-tagged ctx obj)
        kind (get (:ctx-fields tag) f)
        kind-tag (case kind Cells Cells Buf Buf Text Text I32 I32 nil)]
    (when-not (:ctx-fields tag)
      (refuse! ctx (str "(fd " obj " " f ")")
               "the first argument is not a defctx context (tag it, `^Doc d`)" {:form form}))
    (when-not kind
      (refuse! ctx (str "(fd " obj " " f ")")
               (str "the context has no field " f " (it has "
                    (str/join ", " (keys (:ctx-fields tag))) ")")
               {:form form}))
    (kin/tagged! ctx kind-tag)
    (kin/emit! ctx
               (if (and (= :rust (:target ctx)) (not= kind 'I32)) "&mut " "")
               text "." (ctx-field-name ctx f))))

(def vocabulary
  {:namespace 'emon.lang
   :targets #{:rust :go :java :csharp}
   :tags {'I32 I32 'U32 U32 'I64 I64
          'Bool Bool 'Blob Blob 'Src Src 'Bitmap Bitmap 'BitmapSet BitmapSet 'Sink Sink 'Cells Cells 'Buf Buf
          'Cpt Cpt 'Px Px 'Fx Fx 'Conf Conf 'Text Text}
   :names {}
   ;; ---------------------------------------------------------- the casts
   ;;
   ;; TWO FORMS, AND THEY ARE emon'S RATHER THAN kin'S, because a cast is
   ;; the one arithmetic thing the four targets spell four ways. Everything
   ;; else in `kin.lang` is generic over the tags -- `(* a b)` is `a * b`
   ;; everywhere and the TYPES decide what it means -- but widening has no
   ;; such spelling, so it needs a template per target and a template per
   ;; target is what a vocabulary is for.
   ;;
   ;; WHY A 32-BIT ENGINE NEEDS 64 BITS AT ALL. SPEC 7 carries layout in
   ;; integers because four languages agree exactly on integer arithmetic
   ;; and do not agree on floats, and `I32` has been enough for every
   ;; module so far. It stops being enough where an intermediate is the
   ;; SQUARE of a coordinate: a rasteriser's edge intersection is
   ;; `x0 + (y - y0) * (x1 - x0) / (y1 - y0)`, whose product needs twice
   ;; the bits of its operands however small the answer is. Bounding the
   ;; coordinates instead would bound the page, which is not a trade a
   ;; print engine can make.
   ;;
   ;; NARROWING TRUNCATES AND IS NOT CHECKED. Rust's `as`, Go's
   ;; conversion, Java's and C#'s casts all keep the low bits, and C#'s
   ;; default `unchecked` context is what makes its cast agree with the
   ;; other three rather than throwing. A caller that needs to know
   ;; whether a value fits must ask before narrowing; this says nothing
   ;; about it, which is the only behaviour all four share.
   :forms {'emit-ascii emit-ascii-form
           'defenum defenum-form
           'defctx defctx-form
           'fd fd-form
           'i64 (kl/call {:rust "({0} as i64)"
                          :go "int64({0})"
                          :java "((long) {0})"
                          :csharp "((long) {0})"}
                         {:tag I64})
           ;; ------------------------------------------------ 64-bit bit twiddling
           ;;
           ;; FOUR FORMS for hashing and mixing (SplitMix64-style steps,
           ;; PDF's key derivations): wrapping 64-bit arithmetic and a
           ;; LOGICAL right shift, which `kin.lang` does not provide.
           ;; A vocabulary is extensible by design, so they live here.
           ;;
           ;; WRAPPING IS THE POINT AND NOT AN ACCIDENT. A hash step is
           ;; defined modulo 2^64; three of the four targets wrap silently
           ;; and Rust refuses to, so the arithmetic that agrees everywhere
           ;; is the one written down here. C# is `unchecked` explicitly
           ;; rather than by default, because a csproj may set
           ;; `CheckForOverflowUnderflow` and a hash that threw on one
           ;; consumer's build would be the worst kind of disagreement.
           ;;
           ;; AND THE SHIFT IS LOGICAL. A
           ;; SplitMix step shifts an UNSIGNED 64-bit value; an arithmetic
           ;; shift of a negative one keeps the sign bits and gives a
           ;; different number. Java spells it `>>>`, and the other three
           ;; have to say so by going through their unsigned type.
           'mul64 (kl/call {:rust "({0}).wrapping_mul({1})"
                            :go "({0} * {1})"
                            :java "({0} * {1})"
                            :csharp "unchecked({0} * {1})"}
                           {:tag I64})
           'add64 (kl/call {:rust "({0}).wrapping_add({1})"
                            :go "({0} + {1})"
                            :java "({0} + {1})"
                            :csharp "unchecked({0} + {1})"}
                           {:tag I64})
           'shl64 (kl/call {:rust "((({0}) as u64) << ({1})) as i64"
                            :go "int64(uint64({0}) << uint({1}))"
                            :java "({0} << {1})"
                            :csharp "unchecked((long)((ulong)({0}) << {1}))"}
                           {:tag I64})
           'shr64 (kl/call {:rust "(((({0}) as u64) >> ({1})) as i64)"
                            :go "int64(uint64({0}) >> uint({1}))"
                            :java "({0} >>> {1})"
                            :csharp "((long)((ulong)({0}) >> {1}))"}
                           {:tag I64})
           ;; 32-BIT SHIFTS (P0.4, D045). `kin.lang` ships none, because which
           ;; shift is right depends on the tag's signedness in Rust. These
           ;; are for an `I32` and are DEFINED ONLY FOR A SHIFT COUNT 0..31:
           ;; bits shifted out of the top are lost (no target traps on that,
           ;; Rust's `<<` only panics on a count of 32 or more), and `sar32`
           ;; is the ARITHMETIC shift, which equals the logical one for a
           ;; non-negative value -- the only kind a bit reader holds.
           'shl32 (kl/call {:rust "(({0}) << ({1}))"
                            :go "(({0}) << uint({1}))"
                            :java "(({0}) << ({1}))"
                            :csharp "(({0}) << ({1}))"}
                           {:tag I32})
           'sar32 (kl/call {:rust "(({0}) >> ({1}))"
                            :go "(({0}) >> uint({1}))"
                            :java "(({0}) >> ({1}))"
                            :csharp "(({0}) >> ({1}))"}
                           {:tag I32})
           'i32 (kl/call {:rust "({0} as i32)"
                          :go "int32({0})"
                          :java "((int) {0})"
                          :csharp "((int) {0})"}
                         {:tag I32})}
   ;; AN UNANNOTATED INTEGER LITERAL IS AN `I32`. Said here rather than
   ;; assumed, because `kin.lang` ships no default tag and a source that
   ;; wrote `0` would otherwise have nothing to infer a local's type from.
   :literal-tag (fn [v] (when (integer? v) I32))})
