;; THE OTHER HOSTS OF A GATE (DEVLOG P9.5, D494): Rust and Go are every gate's reference pair; the Java host (P9.1), the C# host (P9.2), the C binding
;; and the babashka pod (P9.4) join a gate as ARMS, each built by its own script and each LEFT OUT LOUDLY when its toolchain is missing:
;;
;;     (require '[extra-hosts :as xh])
;;     (def extras (xh/engines {:hosts [:java :csharp :c] :on-fail bad}))   ; [{:host :java :label "Java" :drv "/abs/emon-java"} ...]
;;
;; A build script that exits 3 prints `SKIP name -- why` on stderr: this prints it (and which arm is left out) and goes on. `EMON_SKIP_JAVA`,
;; `EMON_SKIP_CSHARP`, `EMON_SKIP_C` and `EMON_SKIP_POD` leave one out on purpose. A build that fails for any other reason is a FAILURE (`:on-fail` is
;; called with the text; the arm is then not there). The drivers speak the Rust driver's flags (`--out=`, `--replay=`, `--record=`, `--ocr=`,
;; `--no-retarget`, ...), so a gate that drives `emon-rs` drives them with the same arguments.
(ns extra-hosts
  (:require [babashka.fs :as fs]
            [babashka.process :refer [process destroy-tree]]
            [clojure.string :as str]))

(def specs
  {:go {:label "Go" :env "EMON_SKIP_GO" :script "kin/scripts/emon-go-build" :arm "the Go arm" :which "go"}
   :java{:label "Java" :env "EMON_SKIP_JAVA" :script "kin/scripts/emon-java-build" :arm "the Java arm"}
   :csharp {:label "C#" :env "EMON_SKIP_CSHARP" :script "kin/scripts/emon-cs-build" :arm "the C# arm"}
   :c {:label "C" :env "EMON_SKIP_C" :script "kin/scripts/emon-c-build" :arm "the C arm"}
   :pod {:label "pod" :env "EMON_SKIP_POD" :script "kin/scripts/emon-pod-build" :arm "the pod arm"}})

;; THE FAST VARIANT (P11.7, D1430): `--fast` (or EMON_FAST=1) runs a gate's DETERMINISTIC SUBSET, and for every gate that takes the other hosts as arms that
;; subset is Rust and Go only. The Java, C# and C hosts and the pod are left out LOUDLY unless EMON_FAST_HOSTS names them (a comma list: the pull request plan
;; sets it to the hosts the change touches). The full gate (no flag) runs every arm the toolchain allows, as always.
(def fast? (boolean (or (System/getenv "EMON_FAST") (some #{"--fast"} *command-line-args*))))
(defn fast-hosts [] (set (remove str/blank? (str/split (str (System/getenv "EMON_FAST_HOSTS")) #","))))
(defn fast-skip?
  "Is host `h` (a keyword, or its name) left out of this run because it is a --fast one that does not name it? Rust and Go are never left out."
  [h]
  (and fast? (not (contains? #{:rust :go} (keyword h))) (not (contains? (fast-hosts) (name h)))))
(defn fast-note
  "The label a --fast gate prints first, so its `ok` lines are never taken for the full gate's."
  [gate what]
  (when fast?
    (println (str "  FAST " gate ": a deterministic SUBSET, not the full gate -- " what
                  (when (seq (fast-hosts)) (str "; also the arms of " (str/join ", " (sort (fast-hosts)))))))))

(defn engines
  "Build the drivers of `:hosts` (a vector of :java :csharp :c :pod), one after another (a build is the machine's whole share of CPU). Answers
  [{:host :label :drv}] for the ones that built; prints a loud SKIP line for each that did not and calls `:on-fail` for a real failure."
  [{:keys [hosts on-fail] :or {on-fail (fn [& _] nil)}}]
  (vec
   (for [h hosts
         :let [{:keys [label env script arm which]} (specs h)
               out (cond
                     (fast-skip? h)
                     (do (println (str "  SKIP " (name h) " -- --fast runs Rust and Go only (" arm " is left out; EMON_FAST_HOSTS=" (name h) " adds it)")) nil)
                     (System/getenv env)
                     (do (println (str "  SKIP " (name h) " -- " env " is set (" arm " is left out)")) nil)
                     (and which (not (fs/which which)))
                     (do (println (str "  SKIP " (name h) " -- " which " is not on the PATH (" arm " is left out)")) nil)
                     :else
                     (let [r @(process {:out :string :err :string} script)]
                       (case (:exit r)
                         0 {:host h :label label :drv (str (fs/absolutize (str/trim (:out r))))}
                         3 (do (println (str "  " (str/trim (:err r)) " (" arm " is left out)")) nil)
                         (do (on-fail "the " label " driver did not build:\n" (:err r)) nil))))]
         :when out]
     out)))

(defn plan-drivers
  "The REPLAY drivers of a plan gate (`render` or `align`) for the Java and the C# host, built by kin/scripts/emon-plandrv-build over the host's own library
  tree: [{:host :label :drv}], with the same loud SKIPs and the same `:on-fail` as `engines`. (The C binding and the pod have no plan unit to drive: they
  convert documents; Rust and Go have their drivers in test/<gate>/{rust,go}.)"
  [{:keys [tool hosts on-fail] :or {hosts [:java :csharp] on-fail (fn [& _] nil)}}]
  (vec
   (for [h hosts
         :let [{:keys [label env arm]} (specs h)
               out (if (or (System/getenv env) (fast-skip? h))
                     (do (println (str "  SKIP " (name h) " -- " (if (System/getenv env) (str env " is set") "--fast runs Rust and Go only") " (" arm " is left out)")) nil)
                     (let [r @(process {:out :string :err :string} "kin/scripts/emon-plandrv-build" (name h) (name tool))]
                       (case (:exit r)
                         0 {:host h :label label :drv (str (fs/absolutize (str/trim (:out r))))}
                         3 (do (println (str "  " (str/trim (:err r)) " (" arm " is left out)")) nil)
                         (do (on-fail "the " label " " (name tool) " driver did not build:\n" (:err r)) nil))))]
         :when out]
     out)))

(defn run
  "Run driver `drv` with `args`: {:exit :out (string) :err}. A run over `timeout-ms` (default 180000) is killed (our own process) and answers exit -1."
  [drv args & [timeout-ms]]
  (let [p (apply process {:out :string :err :string} (str drv) (map str args))
        r (deref p (or timeout-ms 180000) ::timeout)]
    (if (= r ::timeout)
      (do (destroy-tree p){:exit -1 :out "" :err "TIMEOUT"})
      {:exit (:exit r) :out (:out r) :err (:err r)})))

(defn names [extras] (str/join ", " (map :label extras)))
