;; Runs INSIDE the builder container (see spool.clj): cross-compiles the emon driver for Linux x86_64 with static Tesseract.
;; /build is a copy of the repo's build inputs; /out receives emon-rs, eng.traineddata and build-info.edn.
(require '[babashka.fs :as fs] '[babashka.process :refer [shell]] '[clojure.string :as str])
(def target "x86_64-unknown-linux-gnu")
(def env {"EMON_CROSS_EMULATOR" "/usr/bin/qemu-x86_64-static"
          "QEMU_LD_PREFIX" "/usr/x86_64-linux-gnu"
          "CARGO_TARGET_X86_64_UNKNOWN_LINUX_GNU_LINKER" "x86_64-linux-gnu-gcc"
          "EMON_RS_TARGET" target})
(defn sh [& cmd] (apply shell {:extra-env env :dir "/build"} cmd))
(sh "apt-get" "update" "-qq")
(sh "sh" "-ec" (str "DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends "
                    "cmake nasm make pkg-config xz-utils curl ca-certificates qemu-user-static "
                    "gcc-x86-64-linux-gnu g++-x86-64-linux-gnu libc6-dev-amd64-cross >/dev/null"))
(sh "rustup" "target" "add" target)
(sh "bb" "tools/native/native.clj" "--triple" target "--jobs" (or (System/getenv "JOBS") "4"))
(def prefix (str "/build/.cache/native/" target))
(sh "sh" "-ec" (str "EMON_RS_LIVE=1 EMON_NATIVE=" prefix " kin/scripts/emon-rs-build"))
(fs/create-dirs "/out")
(fs/copy (str "/build/.cache/emon-rs-native/target/" target "/release/emon-rs") "/out/emon-rs" {:replace-existing true})
(fs/copy (str prefix "/share/tessdata/eng.traineddata") "/out/eng.traineddata" {:replace-existing true})
(spit "/out/build-info.edn"
      (pr-str {:rustc (str/trim (:out (shell {:out :string} "rustc" "-V")))
               :gcc (str/trim (:out (shell {:out :string} "x86_64-linux-gnu-gcc" "-dumpfullversion")))
               :cmake (first (str/split-lines (:out (shell {:out :string} "cmake" "--version"))))
               :native-manifest (slurp (str prefix "/manifest.edn"))}))
(println "driver built")
