(ns process-env-test
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [process-env :as env]))

(defn -main [& _]
  (let [parent {"SystemRoot" "C:/Windows" "windir" "C:/Windows"
                "Path" "C:/Windows/System32" "TEMP" "C:/Temp" "TMP" "C:/Temp"
                "USERPROFILE" "C:/Users/runner" "HOME" "C:/Users/runner"
                "PATHEXT" ".EXE;.CMD" "INCLUDE" "C:/SDK/include"}
        windows-env #(:env (env/options true parent %))
        new-path "C:/Tools;C:/Windows/System32"
        override {"PATH" new-path "EMON_OFFLINE" "1"}]
    (assert (= parent (windows-env nil)) "Windows child without overrides keeps the parent environment")
    (assert (= (assoc parent "Path" new-path "EMON_OFFLINE" "1") (windows-env override))
            "Windows child adds overrides, updates Path once, and keeps the remaining variables")
    (assert (= {:extra-env override} (env/options false parent override))
            "Unix child inherits the parent environment with case-sensitive overrides")
    (let [keys-to-check (conj (vec (keys parent)) "PATH" "EMON_WINENV_TEST")
          expected (assoc (into {} (map (juxt identity #(System/getenv %))) keys-to-check)
                          "EMON_WINENV_TEST" "child")
          form (str "(prn (into {} (map (juxt identity #(System/getenv %))) " (pr-str keys-to-check) "))")
          child (p/shell (merge {:out :string :shutdown nil} (env/options {"EMON_WINENV_TEST" "child"}))
                         "bb" "-e" form)]
      (assert (= expected (edn/read-string (:out child))) "Child process inherits system variables and sees the override")))
  (println "process environment self-test ok"))
