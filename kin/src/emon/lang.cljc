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
  "16.16 fixed point held in an `I64` (PDF reals, matrices; SPEC 6.3)."
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

(def vocabulary
  {:namespace 'emon.lang
   :targets #{:rust :go :java :csharp}
   :tags {'I32 I32 'U32 U32 'I64 I64
          'Bool Bool 'Blob Blob 'Sink Sink 'Cells Cells
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
