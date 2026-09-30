(ns emon.project
  "This project, described to kin.

  kin is a library: it has no command-line tool and no config file, so the
  thing that says `these are my vocabularies and these are my targets` is
  ordinary Clojure. Three lines are the whole of it.

  `kin.lang` IS NAMED ONCE and answers for four languages. It declares two
  maps under that one namespace -- three targets in its own and Go in the one
  beside it -- and `load-vocabulary` splices both. A source requiring
  `kin.lang` gets Rust, Java and C# from the first and Go from the second
  without its `ns` form saying anything about either."
  (:require [kin.project :as kp]
            [kin.vfs :as vfs]
            [emon.targets :as targets]))

(def project
  (delay (kp/load-project {:vocabularies '[emon.lang emon.test kin.lang]
                           :targets targets/targets
                           :target-order [:rust :go :java :csharp]
                           ;; `:sources` IS WHAT MAKES THE DRIFT GATE POSSIBLE.
                           ;; `kp/destinations` answers every `[target path]`
                           ;; the project would write, and that list is not
                           ;; knowable any other way once a destination is a
                           ;; function rather than a table -- so a gate that
                           ;; re-emits everything and compares needs the
                           ;; project to be able to say what `everything` is.
                           :sources {:vfs (vfs/disk-vfs "kin")
                                     :match "*.kin"}
                           ;; THE HOST TREES, scanned. What comes out is an
                           ;; ordinary vocabulary -- a source requires
                           ;; `emon.port` by name, the require scope resolves
                           ;; through it, and nothing downstream can tell an
                           ;; annotation was involved.
                           :host @targets/scans})))
