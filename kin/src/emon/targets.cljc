(ns emon.targets
  "The four languages emon generates, described to kin.

  THREE OF THE FOUR COME FROM KIN UNCHANGED. `kin.target` ships `rust`,
  `java`, `csharp` and `go`, each carrying that language's reserved words, its
  namers and the shape of a module file -- and each deliberately omitting
  `:vfs` and `:path`, because where a namespace's code goes is the project's
  answer and not the language's. So what this file adds is exactly that: a
  place, and a path within it."
  (:require [clojure.string :as str]
            [kin]
            [kin.host]
            [kin.lang :as lang]
            [kin.target :as target]
            [kin.vfs :as vfs]))

(defn- segments [ns-name] (str/split (str ns-name) #"\."))
(defn- last-seg [ns-name] (last (segments ns-name)))

(defn- pascal [s] (str/join (mapv str/capitalize (str/split (str s) #"-"))))

(def go-module
  "The module path in `gen/go/go.mod`. A Go import is absolute from the module
  root, so this is the one thing about Go's layout that kin cannot derive from
  a namespace -- and it is configuration, which is why it is here."
  "emon")

;; WHERE THE GENERATED CODE GOES, and it is computed rather than listed: give
;; a namespace and each target answers a file, with no table anywhere. The
;; subtree is parallel to the hand-written one -- `gen/` beside `host/` -- so
;; a file kin owns and a file a person owns are never in the same directory
;; and never in doubt.

;; -------------------------------------------------------------- host linkage
;;
;; A HOST FUNCTION DECLARES ITS OWN LINKAGE, beside the code it describes:
;;
;;     // @kin:link:ns: emon.port
;;     // @kin:link:form:utf8-len: {:template "emon.Port.utf8Len({0})" :arity 1}
;;     public static int utf8Len(String s) { ... }
;;
;; kin reads the marker and stops. That the payload names a template and an
;; arity is emon's invention, not kin's -- kin does not know what `:template`
;; means, does not validate it and does not count it. What kin does with the
;; answer is compare the targets against EACH OTHER: a form three of them
;; declare and the fourth does not is the failure this exists to catch, and it
;; is the one a hand-written table hides until something calls it.
;;
;; `:throws` IS NOT A SPELLING, which is why it rides beside the template and
;; not in it. A host function that can fail needs its call hoisted and its
;; error checked in Rust and Go -- statements, not text -- so the fact reaches
;; kin as part of the ANSWER and kin generates the propagation. It is also
;; the fact most worth comparing across the four trees: a port made fallible
;; in two languages and not in the other two compiles everywhere, because
;; each target is internally consistent, and means something different in
;; half of it.

(defn- linker
  "Turn one annotation's payload into a form, for `target`.

  `:import` IS GO'S ALONE. Rust reaches a sibling module by path and Java and
  C# take a fully qualified name, so for those three the template is the whole
  answer. Go must name the package in a header, so its payload carries one and
  the link fn registers it with `need!` -- the same mechanism a form uses to
  ask for `strconv`, and the reason it works without kin knowing what an
  import is."
  [target]
  (fn [data _vfs _path]
    (let [call (lang/call {target (:template data)}
                          {:throws (:throws data)})]
      {:arity (:arity data)
       ;; ANSWERED ONLY WHEN THE ANNOTATION SAYS SO. `nil` is kin's "not
       ;; stated", so a port that says nothing about failure does not
       ;; disagree with one that says no -- it declines to answer, which is
       ;; what a port nobody has thought about yet is doing.
       :throws (:throws data)
       :link-fn (if-let [imp (:import data)]
                  (fn [ctx form] (lang/need! ctx imp) (call ctx form))
                  call)})))

;; `porttest` ENDS IN `test` AND IS NOT A UNIT TEST: it is the probe P0.2 names
;; for the ports (DEVLOG), compiled by `bb check` in the ordinary build. The
;; Tests/ rule is nome's suffix rule; this is its one named exception (D035).
(def ^:private probe-modules #{"porttest"})

(def targets
  {:rust (assoc target/rust
                :link (linker :rust)
                :data-emitters {'kin.lang/flat-array lang/flat-array}
                :vfs (vfs/disk-vfs "gen/rust")
                ;; `emon.layout.flex` -> `emon/layout/flex.rs`, which is where
                ;; Rust insists a module lives. The parent needs `pub mod
                ;; flex;` to reach it, and kin REPORTS that line rather than
                ;; writing it: writing into a file kin does not own is the
                ;; splice problem under another name.
                :path (fn [ns-name] (str (str/join "/" (segments ns-name)) ".rs")))

   :go (assoc target/go
              :link (linker :go)
              ;; WHAT A NAMESPACE COMPILES TO IN GO, and it is the whole of
              ;; P0.4c. Go needs TWO spellings where the other three need one:
              ;; a header wants an import PATH absolute from the module root,
              ;; a reference wants the PACKAGE NAME alone.
              ;;
              ;; kin already has room for both and I had not looked. A unit is
              ;; a MAP: `unit-import` reads `:qualified` for the header, and
              ;; `default-cross-unit` reads `unit-label` -- the `:name` -- and
              ;; writes `name.Thing`, which is exactly Go's spelling. So there
              ;; is no kin change here and never needed to be one; the target
              ;; describes its own language, as `:reserved` and `:local-name`
              ;; already do.
              :unit (fn [ns-name]
                      (let [segs (segments ns-name)]
                        {:ns ns-name
                         :name (last segs)
                         :qualified (str/join "/" (cons go-module (rest segs)))}))
              :data-emitters {'kin.lang/flat-array lang/flat-array}
              :vfs (vfs/disk-vfs "gen/go")
              ;; A GO PACKAGE IS A DIRECTORY, and its files may be named
              ;; anything -- so the last segment is both the directory and
              ;; the file, which is the convention every Go module follows.
              ;;
              ;; THE FIRST SEGMENT IS DROPPED, because it is the module and a
              ;; module does not repeat itself: `emon.hello` lives at
              ;; `hello/hello.go` and is imported as `emon/hello`, not
              ;; `emon/emon/hello`.
              :path (fn [ns-name]
                      (let [segs (segments ns-name)]
                        (str (str/join "/" (rest segs)) "/"
                             (last segs) ".go"))))

   :java (assoc target/java
                :link (linker :java)
                :data-emitters {'kin.lang/flat-array lang/flat-array}
                :vfs (vfs/disk-vfs "gen/java")
                ;; The package path, which is where Java insists a class
                ;; lives: `emon.layout.flex` -> `emon/layout/Flex.java`.
                :path (fn [ns-name]
                        (str (str/join "/" (butlast (segments ns-name))) "/"
                             (pascal (last-seg ns-name)) ".java")))

   :csharp (assoc target/csharp
                  :link (linker :csharp)
                  :data-emitters {'kin.lang/flat-array lang/flat-array}
                  :vfs (vfs/disk-vfs "gen/csharp")
                  ;; A TEST MODULE GOES IN ITS OWN DIRECTORY, AND ONLY IN C#.
                  ;;
                  ;; The asymmetry is the languages' and not the project's. A
                  ;; generated Go test needs `testing`, which is in the
                  ;; standard library; a Rust one needs `#[test]`, which is
                  ;; built into the compiler. Both therefore compile in an
                  ;; ORDINARY build and every consumer that copies the tree can
                  ;; keep copying the whole of it. A C# one needs
                  ;; `[Xunit.Fact]`, which is a NuGet package -- so a file that
                  ;; sat beside the modules broke `bb limits`, whose csproj has
                  ;; no test reference and no reason to grow one.
                  ;;
                  ;; EXCLUDED BY PATH AND NOT BY NAME: `gen/csharp/Emon/*.cs`
                  ;; is what every consumer already globs, so moving the file
                  ;; out of that directory fixes all of them at once and
                  ;; without any of them learning a naming rule. A rule spelled
                  ;; in six places is the thing D561 and D566 are both about.
                  :path (fn [ns-name]
                          (let [leaf (last-seg ns-name)]
                            (if (and (str/ends-with? (str leaf) "test")
                                     (not (probe-modules (str leaf))))
                              (str "Tests/" (pascal leaf) ".cs")
                              (str (str/join "/" (mapv pascal (butlast (segments ns-name)))) "/"
                                   (pascal leaf) ".cs")))))})


(def scans
  "The host trees, scanned and interpreted.

  Read through a vfs like everything else -- kin performs no I/O of its own,
  ever -- and one scan per target, because each language's tree declares its
  own half and `check-agreement` compares them."
  (delay
    (kin.host/interpret
     targets
     ;; ONE DIRECTORY EACH, not a tree. `kin.vfs/disk-vfs` lists one level and
     ;; says so -- "a recursive listing is a thing to add when something needs
     ;; it rather than a thing to guess at" -- so a scan is rooted where the
     ;; files are. That holds while each language has one port file and will
     ;; not once `host/rust` has a module per port; see ROADMAP.
     [(kin.host/scan {:vfs (vfs/disk-vfs "host/rust/src") :match "*.rs"
                      :target :rust :comment "//"})
      (kin.host/scan {:vfs (vfs/disk-vfs "host/go/port") :match "*.go"
                      :target :go :comment "//"})
      (kin.host/scan {:vfs (vfs/disk-vfs "host/java/emon") :match "*.java"
                      :target :java :comment "//"})
      (kin.host/scan {:vfs (vfs/disk-vfs "host/csharp/Emon") :match "*.cs"
                      :target :csharp :comment "//"})])))
