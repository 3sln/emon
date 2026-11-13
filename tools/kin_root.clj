#!/usr/bin/env bb
;; Resolve kin from the opt-in local config or from the SHA pinned in deps.edn.
(require '[babashka.fs :as fs])
(def root (fs/absolutize "."))
(def pin (get-in (clojure.edn/read-string (slurp "deps.edn")) [:deps 'io.github.3sln/kin :git/sha]))
(def cfg (when (fs/exists? "local.edn") (clojure.edn/read-string (slurp "local.edn"))))
(def local (:kin-dir cfg))
(def candidates [(str (System/getProperty "user.home") "/.gitlibs/libs/io.github.3sln/kin/" pin)
                 (str (System/getProperty "user.home") "/.gitlibs/libs/io.github.3sln/kin/sha-" pin)])
(def path (if local (fs/absolutize (str root "/" local))
              (some #(when (fs/directory? %) %) candidates)))
(when-not (and path (fs/directory? path))
  (binding [*out* *err*]
    (println (if local (str "local.edn :kin-dir does not exist: " path)
                 (str "Pinned kin " pin " is not in gitlibs; run `clojure -P` to fetch it."))))
  (System/exit 1))
(println (str path))
