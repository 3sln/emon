;; A FAKE HOST for `bb fuzz --selftest` (P2.12, D106): a driver that misbehaves on
;; demand, so the harness's hang, crash, panic, mismatch and async verdicts, its
;; resumption after a killed host and its minimiser are themselves tested. A gate
;; that cannot fail is not a gate.
;;
;;     bb tools/fuzz/fakehost.clj A|B [--chunk=N]       paths on stdin
;;
;; What a file says (its whole content): `hang` sleeps forever; `crash` exits 3;
;; `panic` answers PANIC; `mismatch` answers differently on A and B; `status`
;; answers a different status on B; `async` answers a different async line; and any
;; file containing the byte X panics (the minimiser's target). Everything else is
;; `ok 1`.
(require '[clojure.string :as str])

(let [who (first *command-line-args*)]
  (doseq [path (line-seq (java.io.BufferedReader. *in*))]
    (let [s (slurp path)
          mem (cond
                (= s "hang") (do (Thread/sleep 600000) "")
                (= s "crash") (System/exit 3)
                (= s "panic") "PANIC boom"
                (str/includes? s "X") "PANIC has an X"
                (= s "mismatch") (str "ok " who)
                (= s "status") (if (= who "B") "bad-xref 1" "ok 1")
                :else "ok 1")
          async (if (= s "async") "ok 1 !missed" mem)]
      (println (str path "\t" mem "\t" async))
      (flush))))
