;; A FAKE ENGINE DRIVER for `bb fuzz --selftest` (P10.5, D700): the engine arm's twin of fakehost.clj. It behaves as
;; a host's `--out=bundle` driver would, but does what the file's whole content says, so every verdict of
;; `fuzz.engine/failures` is tested, and a harness that cannot fail is not a gate.
;;
;;     bb tools/fuzz/fakedriver.clj A|B [--out=bundle] [--chunk=N --async] FILE
;;
;;   ok        a bundle, exit 0                       hang      sleeps a minute
;;   crash     exits 139                              panic     `error: internal`, exit 1
;;   named     `error: damaged`, exit 4               limit     `error: stream-bytes`, exit 4
;;   rss       holds 300 MB, then ok                  status    A ok, B `error: damaged` (exit 4)
;;   name      A `error: x`, B `error: y` (exit 4)    output    each host's own bundle
;;   async     a different bundle through --async
(require '[clojure.string :as str])

(let [args *command-line-args*
      who (first args)
      file (last args)
      async? (some #{"--async"} args)
      s (slurp file)
      err (fn [n] (binding [*out* *err*] (println (str "error: " n)) (println "the message"))) ]
  (case s
    "hang" (Thread/sleep 60000)
    "crash" (System/exit 139)
    "panic" (do (err "internal") (System/exit 1))
    "named" (do (err "damaged") (System/exit 4))
    "limit" (do (err "stream-bytes") (System/exit 4))
    "rss" (let [a (byte-array 300000000)] (java.util.Arrays/fill a (byte 1)) (println "== json\n{}" (aget a 0)))
    "status" (if (= who "B") (do (err "damaged") (System/exit 4)) (println "== json\n{}"))
    "name" (do (err (if (= who "A") "x" "y")) (System/exit 4))
    "output" (println "== json\n{\"host\":\"" who "\"}")
    "async" (println (if async? "== json\n{\"async\":1}" "== json\n{}"))
    (println "== json\n{}")))
