;; THE OTHER HOSTS OF A GATE (DEVLOG P9.5, D1240): Rust and Go are every gate's reference pair; the Java host (P9.1), the C# host (P9.2), the C binding
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

(defn engines
  "Build the drivers of `:hosts` (a vector of :java :csharp :c :pod), one after another (a build is the machine's whole share of CPU). Answers
  [{:host :label :drv}] for the ones that built; prints a loud SKIP line for each that did not and calls `:on-fail` for a real failure."
  [{:keys [hosts on-fail] :or {on-fail (fn [& _] nil)}}]
  (vec
   (for [h hosts
         :let [{:keys [label env script arm which]} (specs h)
               out (cond
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

(defn run
  "Run driver `drv` with `args`: {:exit :out (string) :err}. A run over `timeout-ms` (default 180000) is killed (our own process) and answers exit -1."
  [drv args & [timeout-ms]]
  (let [p (apply process {:out :string :err :string} (str drv) (map str args))
        r (deref p (or timeout-ms 180000) ::timeout)]
    (if (= r ::timeout)
      (do (destroy-tree p){:exit -1 :out "" :err "TIMEOUT"})
      {:exit (:exit r) :out (:out r) :err (:err r)})))

(defn names [extras] (str/join ", " (map :label extras)))
