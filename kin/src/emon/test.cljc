(ns emon.test
  "emon's UNIT vocabulary: a law, written once, run as a native test on every
  target (DEVLOG P0.5, D044). Written fresh for emon; it follows nome's
  shape because the shape is forced -- a frame per framework, and assertions
  that go through the host so a law reads the same on all four.

  FOUR TARGETS. Go has `go test`, Rust `cargo test`, C# xunit. Java has a JDK
  here and no JUnit, so its arm is a ~30-line reflection runner in
  `kin/scripts/unit` over public static `test*` methods, and the frame is
  just such a method (D044). A law that throws is a failed law on every arm.

  THE TEST NAME IS THE kin NAME. `(deftest test-a-pixel ...)` becomes
  `TestAPixel` in Go and C# and `test_a_pixel` in Rust; Java gets
  `testAPixel`. Go finds a test by the `Test` prefix and the Java runner by
  `test`, so the source spelling `test-` satisfies both.

  ASSERTIONS GO THROUGH THE HOST (`host/*/testport.*`), not each framework's
  macro: `assert_eq!`, `t.Errorf` and `Assert.Equal` take their arguments in
  three orders and report in three shapes. One function per target, and a
  law says the same thing everywhere. The failure message carries the law's
  own sentence, and for a row corpus the ROW INDEX."
  (:require [kin]
            [kin.lang :as kl]
            [emon.lang :as el]))

;; GO'S TEST PARAMETER IS `emonT`, NOT `t`: the assertions reach the
;; `*testing.T` by name (kin has no lambda to close over it), and a law that
;; binds its own `t` -- the natural name for a text sink -- must not shadow it.

(def Str
  "A string LITERAL, which only a law needs: it carries the law's own
  sentence, a hex spelling of some bytes, or a corpus file's name. The
  production vocabulary has no string tag on purpose (D028)."
  {:name 'Str
   :types {:rust "&str" :go "string" :java "String" :csharp "string"}
   :zero {:go "\"\""}})

(defn- frame
  "The lines each framework wants around a test body: opening lines and a
  close. A vector of lines, since each is emitted at the current indent."
  [target n]
  (case target
    :go [[(str "func " n "(emonT *testing.T) {")] "}"]
    :rust [["#[test]" (str "fn " n "() {")] "}"]
    :csharp [["[Xunit.Fact]" (str "public static void " n "() {")] "}"]
    :java [[(str "public static void " n "() {")] "}"]
    (throw (ex-info (str "emon.test: no test frame for " target)
                    {:target target}))))

(def ^:private deftest
  {:declare
   (fn [ctx form]
     (kin/define-form! ctx {:scope :public} (second form) {}))
   :generate
   (fn [ctx form]
     (let [[_ nm0 & body] form
           target (:target ctx)
           ;; A test is ALWAYS exported: Go finds one by the capital `Test`
           ;; (visibility is in the name there) and xunit and the Java
           ;; runner need `public`.
           nm (vary-meta nm0 assoc :pub true)
           n (kl/target-name ctx nm)
           [open close] (frame target n)]
       (when (= :go target)
         (kl/need! ctx "testing")
         (kl/need! ctx "emon/port"))
       (doseq [line open]
         (kin/emit! ctx (kin/indent-of ctx) line "\n"))
       (kin/scoped
        ctx {:key :fn :value nm :indent 1}
        (fn [inner] (doseq [f body] (kin/statement! inner f))))
       (kin/emit! ctx (kin/indent-of ctx) close "\n")))})

(def vocabulary
  {:namespace 'emon.test
   :targets #{:rust :go :java :csharp}
   :tags {'Str Str}
   :names {}
   :forms
   {'deftest deftest
    ;; `assert-eq got want "law"` -- I32s.
    'assert-eq (kl/call {:go "port.AssertEq(emonT, {0}, {1}, {2})"
                         :rust "crate::testport::assert_eq({0}, {1}, {2})"
                         :java "emon.Testport.assertEq({0}, {1}, {2})"
                         :csharp "emon.Testport.AssertEq({0}, {1}, {2})"}
                        {:tag el/I32})
    ;; The same in a row loop: `assert-eq-at got want "law" row`.
    'assert-eq-at (kl/call {:go "port.AssertEqAt(emonT, {0}, {1}, {2}, {3})"
                            :rust "crate::testport::assert_eq_at({0}, {1}, {2}, {3})"
                            :java "emon.Testport.assertEqAt({0}, {1}, {2}, {3})"
                            :csharp "emon.Testport.AssertEqAt({0}, {1}, {2}, {3})"}
                           {:tag el/I32})
    'assert-eq64 (kl/call {:go "port.AssertEq64(emonT, {0}, {1}, {2})"
                           :rust "crate::testport::assert_eq64({0}, {1}, {2})"
                           :java "emon.Testport.assertEq64({0}, {1}, {2})"
                           :csharp "emon.Testport.AssertEq64({0}, {1}, {2})"}
                          {:tag el/I32})
    ;; A `Bool`, which is what kin's comparisons produce (`assert-eq` takes
    ;; an `I32`, and `kin.lang`'s `if` is a statement, not an expression).
    'assert-that (kl/call {:go "port.AssertThat(emonT, {0}, {1})"
                           :rust "crate::testport::assert_that({0}, {1})"
                           :java "emon.Testport.assertThat({0}, {1})"
                           :csharp "emon.Testport.AssertThat({0}, {1})"}
                          {:tag el/I32})
    'assert-that-at (kl/call {:go "port.AssertThatAt(emonT, {0}, {1}, {2})"
                              :rust "crate::testport::assert_that_at({0}, {1}, {2})"
                              :java "emon.Testport.assertThatAt({0}, {1}, {2})"
                              :csharp "emon.Testport.AssertThatAt({0}, {1}, {2})"}
                             {:tag el/I32})
    ;; A `Text` sink's contents against a literal: `assert-text t "want" "law"`.
    'assert-text (kl/call {:go "port.AssertText(emonT, {0}, {1}, {2})"
                           :rust "crate::testport::assert_text({0}, {1}, {2})"
                           :java "emon.Testport.assertText({0}, {1}, {2})"
                           :csharp "emon.Testport.AssertText({0}, {1}, {2})"}
                          {:tag el/I32})
    ;; A `Text` sink against a span of a `Blob` (a golden file, a corpus row),
    ;; as UTF-8 bytes: `assert-text-span t blob from to "law" row`.
    'assert-text-span (kl/call {:go "port.AssertTextSpan(emonT, {0}, {1}, {2}, {3}, {4}, {5})"
                                :rust "crate::testport::assert_text_span({0}, {1}, {2}, {3}, {4}, {5})"
                                :java "emon.Testport.assertTextSpan({0}, {1}, {2}, {3}, {4}, {5})"
                                :csharp "emon.Testport.AssertTextSpan({0}, {1}, {2}, {3}, {4}, {5})"}
                               {:tag el/I32})
    ;; A `Cells` of byte values against a hex string: `assert-cells-hex c "00ff" "law"`.
    'assert-cells-hex (kl/call {:go "port.AssertCellsHex(emonT, {0}, {1}, {2})"
                                :rust "crate::testport::assert_cells_hex({0}, {1}, {2})"
                                :java "emon.Testport.assertCellsHex({0}, {1}, {2})"
                                :csharp "emon.Testport.AssertCellsHex({0}, {1}, {2})"}
                               {:tag el/I32})
    ;; INPUTS. Rust's `Blob` is `&[u8]` and these produce an owned `Vec`, so
    ;; the borrow is in the template; a temporary borrowed as an argument
    ;; lives to the end of the statement, so these are NESTED in a call and
    ;; not bound by `let`. (`new-cells` and `new-text` are constructors, so
    ;; Rust extends them and they MAY be bound.)
    'text-blob (kl/call {:go "port.TextBlob({0})"
                         :rust "(&crate::testport::text_blob({0})[..])"
                         :java "emon.Testport.textBlob({0})"
                         :csharp "emon.Testport.TextBlob({0})"}
                        {:tag el/Blob})
    'hex-blob (kl/call {:go "port.HexBlob({0})"
                        :rust "(&crate::testport::hex_blob({0})[..])"
                        :java "emon.Testport.hexBlob({0})"
                        :csharp "emon.Testport.HexBlob({0})"}
                       {:tag el/Blob})
    ;; A corpus file under `EMON_TEST_DIR` (the repository's `test/`), whole.
    'test-file (kl/call {:go "port.TestFile({0})"
                         :rust "(&crate::testport::test_file({0})[..])"
                         :java "emon.Testport.testFile({0})"
                         :csharp "emon.Testport.TestFile({0})"}
                        {:tag el/Blob})
    ;; The same, NAMED BY A SPAN OF A BLOB (a corpus row says which file):
    ;; `(test-file-span names from to)`. Only tests read files (D058).
    'test-file-span (kl/call {:go "port.TestFileSpan({0}, {1}, {2})"
                              :rust "(&crate::testport::test_file_span({0}, {1}, {2})[..])"
                              :java "emon.Testport.testFileSpan({0}, {1}, {2})"
                              :csharp "emon.Testport.TestFileSpan({0}, {1}, {2})"}
                             {:tag el/Blob})
    'cells-blob (kl/call {:go "port.CellsBlob({0})"
                          :rust "(&crate::testport::cells_blob({0})[..])"
                          :java "emon.Testport.cellsBlob({0})"
                          :csharp "emon.Testport.CellsBlob({0})"}
                         {:tag el/Blob})
    'new-cells (kl/call {:go "port.NewCells()"
                         :rust "(&mut crate::port::Cells::new())"
                         :java "emon.Port.newCells()"
                         :csharp "emon.Port.NewCells()"}
                        {:tag el/Cells :arity 0})
    'new-text (kl/call {:go "port.NewText()"
                        :rust "(&mut crate::port::new_text())"
                        :java "emon.Testport.newText()"
                        :csharp "emon.Testport.NewText()"}
                       {:tag el/Text :arity 0})}
   ;; A STRING LITERAL IS A `Str`; without the tag nothing could infer the
   ;; type of an argument that is a sentence.
   :literal-tag (fn [v] (when (string? v) Str))})
