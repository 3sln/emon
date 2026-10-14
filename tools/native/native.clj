#!/usr/bin/env bb
;; Static Tesseract and Leptonica with their codecs, built from pinned tarballs.   bb native   (DEVLOG P11.5, D540; D524, D526)
;;
;;   bb native [--triple T] [--jobs N] [--force]   fetch (sha256-checked), extract and build the host's triple into .cache/native/<triple>/
;;   bb native --audit [--triple T]                 inspect the result: static libraries only, no unlisted library, no jbig2dec, JBIG-KIT, OpenJPEG
;;                                                  or other excluded symbol (nm), no excluded name in a .pc file, a CMake cache or a link line
;;   bb native --licenses [--check]                 write (or verify) the licence texts of tools/native/native.edn into release/licenses/, from the
;;                                                  source tarballs and the pinned pdfium-binaries archive (nothing else is downloaded)
;;   bb native --check                              native.edn is well formed, every triple is in native.yml and has a pdfium pin
;;   bb native --self-test                          plants each violation in a temporary prefix and requires the audit to name it
;;   bb native --fetch                              only download and verify the sources
;;
;; NOT a gate (it downloads and compiles for minutes); `bb release-audit` and `bb ci coverage` are the gates that hold its inputs.
;; Native builds work on the HOST only; the other triples are built by .github/workflows/native.yml on their own runners.
;;
;;   .cache/native/dl/              the verified tarballs            .cache/native/src/<name>-<ver>/   the extracted sources
;;   .cache/native/build/<triple>/  one CMake build tree and one log per component
;;   .cache/native/<triple>/        the install prefix: include/ lib/*.a share/tessdata/ manifest.edn link.rustflags .built
(require '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.pprint]
         '[clojure.string :as str])
(import '[java.security MessageDigest])

(def pins-file "tools/native/native.edn")
(def pins (edn/read-string (slurp pins-file)))
(def cache ".cache/native")
(def args (vec *command-line-args*))
(defn flag? [f] (some #{f} args))
(defn opt [f] (second (drop-while #(not= f %) args)))

(defn die [& xs] (binding [*out* *err*] (println (str "FAIL " (apply str xs)))) (System/exit 1))

;; ------------------------------------------------------------------------------------------------ host and paths
(defn host-triple []
  (let [os (System/getProperty "os.name") arch (System/getProperty "os.arch")
        a (if (#{"aarch64" "arm64"} arch) :arm64 :x64)]
    (cond (str/includes? os "Mac") (if (= a :arm64) "aarch64-apple-darwin" "x86_64-apple-darwin")
          (str/includes? os "Linux") (if (= a :arm64) "aarch64-unknown-linux-gnu" "x86_64-unknown-linux-gnu")
          (str/includes? os "Windows") "x86_64-pc-windows-msvc"
          :else (die "unknown host " os " " arch))))

(def triple (or (opt "--triple") (host-triple)))
(defn triple-cfg [t] (or (get-in pins [:triples t]) (die "no such triple " t " in " pins-file ": " (str/join ", " (sort (keys (:triples pins)))))))
(defn abs [p] (str (fs/absolutize p)))
(defn prefix-of [t] (abs (str cache "/" t)))
(defn build-root [t] (abs (str cache "/build/" t)))

;; ------------------------------------------------------------------------------------------------ hashing and fetching
(defn sha256-file [f]
  (let [md (MessageDigest/getInstance "SHA-256") buf (byte-array 65536)]
    (with-open [in (io/input-stream (io/file (str f)))]
      (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn fetch! [{:keys [url sha256]} dest]
  (when-not (and (fs/exists? dest) (= sha256 (sha256-file dest)))
    (fs/create-dirs (fs/parent dest))
    (println "downloading" url)
    (p/shell "curl" "-fsSL" "--retry" "3" "-o" (str dest) url)
    (let [got (sha256-file dest)]
      (when-not (= got sha256)
        (fs/delete dest)
        (die url ": sha256 " got " is not the pinned " sha256))))
  dest)

(defn tarball [s] (fetch! s (abs (str cache "/dl/" (:archive s)))))
(defn datafile [d] (fetch! d (abs (str cache "/dl/" (:file d)))))

(defn extract! [s]
  (let [tgz (tarball s)
        dir (abs (str cache "/src/" (:dir s)))
        mark (str dir "/.sha256")]
    (when-not (and (fs/exists? mark) (= (:sha256 s) (str/trim (slurp mark))))
      (fs/delete-tree dir)
      (fs/create-dirs (str cache "/src"))
      ;; GNU tar (Git for Windows) reads `D:\...` as host:file; --force-local says it is a file
      ;; and the backslashes of its paths are escapes to it: forward slashes only
      (let [win? (= :windows (:os (triple-cfg (host-triple)))) fwd #(str/replace (str %) "\\" "/")]
        (apply p/shell "tar" (concat (when win? ["--force-local"]) ["-xf" (fwd tgz) "-C" (fwd (abs (str cache "/src")))])))
      (when-not (fs/exists? dir) (die (:archive s) " did not extract to " (:dir s)))
      (spit mark (str (:sha256 s) "\n")))
    dir))

;; ------------------------------------------------------------------------------------------------ the pin hash
(defn pin-hash []
  (let [md (MessageDigest/getInstance "SHA-256")]
    (doseq [f [pins-file "tools/native/giflib.CMakeLists.txt" "tools/native/tesseract-prefind.cmake" "tools/native/native.clj"]]
      (.update md (.getBytes (slurp f) "UTF-8")))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

;; ------------------------------------------------------------------------------------------------ building
(defn cmake-args [t src bdir prefix s]
  (let [cfg (triple-cfg t)
        mac? (= :mac (:os cfg))
        sub #(str/replace % "@PREFIX@" prefix)]
    (vec (concat
          ["cmake" "-S" src "-B" bdir "-G" (:generator cfg)
           (str "-DCMAKE_INSTALL_PREFIX=" prefix)
           (str "-DCMAKE_PREFIX_PATH=" prefix)]
          (when (seq (:ignore-prefix cfg)) [(str "-DCMAKE_IGNORE_PREFIX_PATH=" (str/join ";" (:ignore-prefix cfg)))])
          (when mac? [(str "-DCMAKE_OSX_DEPLOYMENT_TARGET=" (:osx-deployment cfg)) (str "-DCMAKE_OSX_ARCHITECTURES=" (:arch cfg))])
          (when (= :windows (:os cfg)) ["-DCMAKE_MSVC_RUNTIME_LIBRARY=MultiThreadedDLL"])
          ;; the assembler is a program, found on the PATH before the prefix filters above
          (when (and (= "libjpeg-turbo" (:id s)) (fs/which "nasm")) [(str "-DCMAKE_ASM_NASM_COMPILER=" (fs/which "nasm"))])
          (when-let [pi (:project-include s)] [(str "-DCMAKE_PROJECT_INCLUDE=" (abs pi))])
          (map sub (:common-cmake pins))
          (map sub (:cmake cfg))
          (map sub (:cmake s))))))

(defn run-logged! [t what log env & cmd]
  (let [t0 (System/currentTimeMillis)
        r (apply p/shell {:out (io/file log) :err :out :continue true :extra-env env} cmd)]
    (when-not (zero? (:exit r))
      (println (str/join "\n" (take-last 40 (str/split-lines (slurp log)))))
      (die what " failed (" (str/join " " (take 3 cmd)) "...); log " log))
    (/ (- (System/currentTimeMillis) t0) 1000.0)))

(defn build-env [t prefix]
  ;; pkg-config sees the prefix and nothing else; Apple's libtool writes no timestamps into archives; :env of a triple is added (CMake puts $CXXFLAGS before its own defaults)
  (merge {"PKG_CONFIG_LIBDIR" (str prefix "/lib/pkgconfig") "PKG_CONFIG_PATH" "" "ZERO_AR_DATE" "1"} (:env (triple-cfg t))))

(defn remove-shared! [prefix]
  (doseq [f (concat (fs/glob (str prefix "/lib") "*.{dylib,so,so.*,dll}")
                    (fs/glob (str prefix "/bin") "*.dll"))]
    (fs/delete f))
  ;; zlib's Windows import library for its DLL is zlib.lib; the static one is zlibstatic.lib
  (when (fs/exists? (str prefix "/lib/zlib.lib")) (fs/delete (str prefix "/lib/zlib.lib"))))

(defn build-one! [t s jobs]
  (let [prefix (prefix-of t) bdir (str (build-root t) "/" (:id s)) log (str (build-root t) "/" (:id s) ".log")
        src (extract! s) env (build-env t prefix)]
    (fs/create-dirs (build-root t))
    (fs/delete-tree bdir)
    (when-let [cl (:cmakelists s)] (fs/copy cl (str src "/CMakeLists.txt") {:replace-existing true}))
    (print (format "  %-14s %-8s configure " (:id s) (:version s))) (flush)
    (let [a (apply run-logged! t (str (:id s) " configure") log env (cmake-args t src bdir prefix s))
          b (do (print (format "%5.1fs  build " a)) (flush)
                (apply run-logged! t (str (:id s) " build") (str log ".build") env ["cmake" "--build" bdir "--config" "Release" "--parallel" (str jobs)]))
          c (do (print (format "%5.1fs  install " b)) (flush)
                (run-logged! t (str (:id s) " install") (str log ".install") env "cmake" "--install" bdir "--config" "Release"))]
      (println (format "%5.1fs" c))
      (remove-shared! prefix))))

;; ------------------------------------------------------------------------------------------------ the libraries of a prefix
(defn lib-stem
  "The component a static library belongs to: `libpng16.a` -> png, `libjpeg-static.lib` -> jpeg, `leptonica-1.87.0.lib` -> leptonica."
  [file]
  (-> (str file)
      (str/replace #"\.(a|lib)$" "")
      (str/replace #"[-_]static$" "")
      (str/replace #"^lib(?=.)" "")
      (str/replace #"[-_.]?[0-9][0-9.]*$" "")
      (str/replace #"^zlibstatic$" "z")
      (str/replace #"^zlib$" "z")))

(defn static-libs [prefix]
  (sort (map fs/file-name (fs/glob (str prefix "/lib") "*.{a,lib}"))))

(defn link-name [file] (-> file (str/replace #"\.(a|lib)$" "") (str/replace #"^lib(?=.)" "")))

(defn write-manifest! [t prefix secs]
  (let [cfg (triple-cfg t)
        files (static-libs prefix)
        by-stem (into {} (map (juxt lib-stem identity)) files)
        order (:link-order pins)
        missing (remove by-stem order)]
    (when (seq missing) (die "no static library for " (str/join ", " missing) " in " prefix "/lib"))
    (let [names (mapv #(link-name (by-stem %)) order)
          flags (str/join " " (concat [(str "-L native=" prefix "/lib")]
                                      (map #(str "-l static=" %) names)
                                      (map #(str "-l " %) (:cxx-runtime cfg))))]
      (spit (str prefix "/link.rustflags") (str flags "\n"))
      (fs/create-dirs (str prefix "/share/tessdata"))
      (doseq [d (:data pins)] (fs/copy (datafile d) (str prefix "/share/tessdata/" (:file d)) {:replace-existing true}))
      (spit (str prefix "/manifest.edn")
            (with-out-str
              (clojure.pprint/pprint
               {:triple t :pin-hash (pin-hash) :build-seconds (Math/round (double secs))
                :versions (into {} (map (juxt :id :version)) (:sources pins))
                :link-order names :cxx-runtime (:cxx-runtime cfg)
                :libs (vec (for [f files] {:file f :bytes (fs/size (str prefix "/lib/" f)) :sha256 (sha256-file (str prefix "/lib/" f))}))
                :tessdata (vec (for [d (:data pins)] {:file (:file d) :sha256 (:sha256 d)}))}))))))

;; ------------------------------------------------------------------------------------------------ the audit
(defn- nm-tool []
  (some (fn [c] (when (fs/which (first c)) c))
        [["nm" "-A"] ["llvm-nm" "-A"] ["dumpbin" "/symbols" "/nologo"]]))

(defn- tokens [line] (remove str/blank? (str/split line #"\s+")))
(defn- lib-token? [tok] (or (re-find #"^-l." tok) (re-find #"\.(a|lib|so|dylib|dll)(\.[0-9]+)*$" tok)))
(defn- lib-token-name [tok] (if (str/starts-with? tok "-l") (subs tok 2) (fs/file-name (str/replace tok "\"" ""))))

(defn audit
  "Findings [:fail|:ok|:note msg] for the install prefix and build tree of `t`."
  [t prefix bdir]
  (let [out (atom []) fail! #(swap! out conj [:fail (apply str %&)]) ok! #(swap! out conj [:ok (apply str %&)])
        note! #(swap! out conj [:note (apply str %&)])
        nfail #(count (filter (comp #{:fail} first) @out))
        deny-libs (re-pattern (get-in pins [:deny :libs]))
        deny-syms (re-pattern (get-in pins [:deny :symbols]))
        deny-paths (re-pattern (get-in pins [:deny :paths]))
        allowed (set (concat (mapcat :libs (:sources pins))))]
    (if-not (fs/exists? (str prefix "/lib"))
      (fail! prefix "/lib does not exist: nothing is built for " t)
      (do
        ;; 1. static only, and only the listed libraries
        (let [b (nfail)
              shared (concat (fs/glob (str prefix "/lib") "*.{dylib,so,so.*,dll,tbd}") (fs/glob (str prefix "/bin") "*.dll"))
              all-files (remove fs/directory? (fs/glob (str prefix "/lib") "*"))]
          (doseq [f shared] (fail! (fs/relativize prefix f) ": a shared library in the prefix; everything must be static"))
          (doseq [f all-files
                  :let [n (fs/file-name f)]
                  :when (re-find #"\.(a|lib)$" n)]
            (when-not (allowed (lib-stem n))
              (fail! "lib/" n ": a static library no pinned component installs (" (lib-stem n) "); nothing is taken from the machine"))
            (when (re-find deny-libs n) (fail! "lib/" n ": an excluded library")))
          (when (= b (nfail)) (ok! (str "lib/ holds only the listed static libraries (" (str/join " " (static-libs prefix)) ")"))))
        ;; 2. every listed component is there
        (let [b (nfail) have (set (map lib-stem (static-libs prefix)))]
          (doseq [l allowed] (when-not (have l) (fail! "no static library for " l " in " prefix "/lib")))
          (when (= b (nfail)) (ok! (str "all " (count allowed) " libraries of the pins are present"))))
        ;; 3. symbols
        (let [b (nfail) tool (nm-tool)]
          (if-not tool
            (fail! "no nm, llvm-nm or dumpbin on the PATH: the symbols cannot be inspected")
            (let [nsyms (atom 0)]
              (doseq [f (static-libs prefix)
                      :let [r (apply p/shell {:out :string :err :string :continue true} (concat tool [(str prefix "/lib/" f)]))
                            lines (str/split-lines (str (:out r)))]]
                (when-not (zero? (:exit r)) (fail! (first tool) " failed on lib/" f ": " (str/trim (str (:err r)))))
                (swap! nsyms + (count lines))
                (when-let [hit (first (filter #(re-find deny-syms %) lines))]
                  (fail! "lib/" f ": an excluded symbol (" (str/trim hit) ")")))
              (when (= b (nfail)) (ok! (str "no jbig2dec, JBIG-KIT, OpenJPEG, lzma, zstd, lerc, deflate, archive or curl symbol in " (count (static-libs prefix))
                                            " libraries (" @nsyms " " (first tool) " lines)"))))))
        ;; 4. the pkg-config files
        (let [b (nfail)]
          (doseq [f (fs/glob (str prefix "/lib/pkgconfig") "*.pc")
                  line (str/split-lines (slurp (str f)))
                  :when (re-find #"^(Libs|Libs\.private|Requires|Requires\.private|Cflags)\s*:" line)
                  tok (tokens (second (str/split line #":" 2)))
                  :when (or (and (lib-token? tok) (re-find deny-libs (lib-token-name tok))) (re-find deny-libs tok))]
            (fail! (fs/relativize prefix f) ": names an excluded library: " (str/trim line)))
          (when (= b (nfail)) (ok! "no excluded library in the .pc files")))
        ;; 5. the build trees: CMake caches and link lines
        (let [b (nfail) n (atom 0)]
          (when (fs/exists? bdir)
            (doseq [f (fs/glob bdir "**/CMakeCache.txt")
                    line (str/split-lines (slurp (str f)))
                    :when (and (re-find #":(FILEPATH|PATH|STRING)=" line) (not (re-find #"-NOTFOUND" line)))]
              (swap! n inc)
              ;; a tool (pkg-config, a compiler, ar) may live in Homebrew; a library, header or package directory may not
              (when (and (re-find deny-paths line)
                         (not (re-find #"^[^:=]*(EXECUTABLE|PROGRAM|_COMPILER|_AR:|RANLIB|_NM:|_LINKER|_COMMAND|_TOOL|OBJDUMP|_STRIP)" line)))
                (fail! (fs/relativize bdir f) ": a path of the machine's own libraries: " (str/trim line)))
              (let [v (second (str/split line #"=" 2))]
                (when (and v (re-find #"\.(a|lib|dylib|so|tbd)$" v) (allowed (lib-stem (fs/file-name v))) (not (str/starts-with? v prefix)))
                  (fail! (fs/relativize bdir f) ": a pinned component found OUTSIDE the prefix: " (str/trim line)))
                (when (and v (re-find deny-libs (fs/file-name (str/replace (str v) #"[;\"]" " "))) (re-find #"\.(a|lib|so|dylib)$" (str v)))
                  (fail! (fs/relativize bdir f) ": an excluded library in the cache: " (str/trim line)))))
            (doseq [f (concat (fs/glob bdir "**/link.txt") (fs/glob bdir "**/build.ninja"))
                    line (str/split-lines (slurp (str f)))
                    tok (tokens line)
                    :when (lib-token? tok)]
              (swap! n inc)
              (when (re-find deny-libs (lib-token-name tok)) (fail! (fs/relativize bdir f) ": a link line names an excluded library: " tok))
              (when (re-find deny-paths tok) (fail! (fs/relativize bdir f) ": a link line names the machine's libraries: " tok))))
          (if (fs/exists? bdir)
            (when (= b (nfail)) (ok! (str "no excluded library and no system path in the CMake caches and link lines of " bdir " (" @n " entries)")))
            (note! (str bdir " is absent (a restored cache): caches and link lines not inspected"))))
        ;; 6. Leptonica's configuration said what was linked
        (let [b (nfail) f (str prefix "/lib/pkgconfig/lept.pc")]
          (when (fs/exists? f)
            (let [s (slurp f)]
              (doseq [w ["jp2k" "openjp2" "jbig"] :when (re-find (re-pattern (str "(?i)" w)) s)] (fail! "lept.pc names " w))))
          (when (= b (nfail)) (ok! "lept.pc names no JPEG 2000 or JBIG library")))))
    @out))

(defn report! [findings]
  (doseq [[k m] findings] (println (case k :ok "  ok  " :note "  note" :fail "  FAIL") m))
  (zero? (count (filter (comp #{:fail} first) findings))))

;; ------------------------------------------------------------------------------------------------ licence texts
(defn licences! [check?]
  (let [bad (atom 0)
        put! (fn [out content]
               (let [dst (str "release/licenses/" out)]
                 (if check?
                   (if (and (fs/exists? dst) (= content (slurp dst)))
                     (println "  ok  " out)
                     (do (swap! bad inc) (println "  FAIL" out "differs from its source (or is missing); bb native --licenses")))
                   (do (spit dst content) (println "  wrote" out)))))
        srcs (into {} (map (juxt :id identity)) (:sources pins))]
    (doseq [{:keys [out source file]} (:licence-texts pins)]
      (let [dir (extract! (get srcs source))]
        (put! out (slurp (str dir "/" file)))))
    (let [{:keys [platform texts]} (:pdfium-licences pins)
          ver (:version (edn/read-string (slurp "tools/pdfium/pdfium.edn")))
          d (str ".cache/pdfium/" (str/replace ver "/" "-") "/" platform)]
      (when-not (fs/exists? (str d "/.verified")) (p/shell "bb" "tools/pdfium/fetch.clj" "--platform" platform))
      (doseq [{:keys [out file]} texts] (put! out (slurp (str d "/" file)))))
    (when (pos? @bad) (System/exit 1))))

;; ------------------------------------------------------------------------------------------------ --check
(defn check! []
  (let [bad (atom 0) f! (fn [& xs] (swap! bad inc) (println "  FAIL" (apply str xs)))
        pdf (edn/read-string (slurp "tools/pdfium/pdfium.edn"))
        wf (when (fs/exists? ".github/workflows/native.yml") (slurp ".github/workflows/native.yml"))]
    (doseq [s (:sources pins)]
      (when-not (re-matches #"[0-9a-f]{64}" (str (:sha256 s))) (f! (:id s) ": sha256 is not 64 hex digits"))
      (when-not (re-find #"^https://" (:url s)) (f! (:id s) ": url is not https"))
      (when-not (str/includes? (:archive s) (:version s)) (f! (:id s) ": archive name lacks the version")))
    (doseq [d (:data pins)] (when-not (re-matches #"[0-9a-f]{64}" (str (:sha256 d))) (f! (:id d) ": sha256 is not 64 hex digits")))
    (doseq [[t c] (:triples pins)]
      (when-not (get-in pdf [:platforms (:pdfium c)]) (f! t ": pdfium platform " (:pdfium c) " is not pinned in tools/pdfium/pdfium.edn"))
      (when-not wf (f! ".github/workflows/native.yml is missing"))
      (when wf
        (when-not (str/includes? wf t) (f! "native.yml does not name the triple " t))
        (when-not (str/includes? wf (:runner c)) (f! "native.yml does not name the runner " (:runner c) " (" t ")"))))
    (when-not (get-in pdf [:platforms "wasm" :sha256]) (f! "pdfium.edn has no wasm pin"))
    (let [stems (set (mapcat :libs (:sources pins)))]
      (doseq [l (:link-order pins)] (when-not (stems l) (f! "link-order names " l ", which no source installs"))))
    (doseq [{:keys [out]} (:licence-texts pins)] (when-not (fs/exists? (str "release/licenses/" out)) (f! "release/licenses/" out " is not vendored (bb native --licenses)")))
    (doseq [{:keys [out]} (:texts (:pdfium-licences pins))] (when-not (fs/exists? (str "release/licenses/" out)) (f! "release/licenses/" out " is not vendored (bb native --licenses)")))
    (if (zero? @bad)
      (println "  ok   native.edn:" (count (:sources pins)) "sources and" (count (:data pins)) "data files pinned with sha256," (count (:triples pins))
               "triples each with a runner in native.yml and a pdfium pin, wasm pinned, every licence text vendored")
      (System/exit 1))))

;; ------------------------------------------------------------------------------------------------ --self-test
(defn self-test! []
  (let [cc (or (fs/which "cc") (fs/which "clang") (fs/which "gcc")) ar (fs/which "ar")]
    (if-not (and cc ar (not= :windows (:os (triple-cfg (host-triple)))))
      (println "  SKIP --self-test needs cc and ar (it builds the planted libraries)")
      (let [tmp (str (fs/create-temp-dir {:prefix "native-selftest"}))
            fails (atom 0)
            mk-lib (fn [prefix name sym]
                     (let [c (str tmp "/" name ".c") o (str tmp "/" name ".o") a (str prefix "/lib/lib" name ".a")]
                       (fs/create-dirs (str prefix "/lib"))
                       (spit c (str "int " sym "(void) { return 1; }\n"))
                       (p/shell (str cc) "-c" c "-o" o)
                       (fs/delete-if-exists a)
                       (p/shell (str ar) "rcs" a o)))
            fresh (fn [tag]
                    (let [p (str tmp "/" tag)]
                      (fs/create-dirs (str p "/lib/pkgconfig"))
                      (doseq [s (:sources pins) l (:libs s)] (mk-lib p l (str "ok_" (str/replace l "-" "_") "_fn")))
                      p))
            run (fn [p] (audit (host-triple) p (str p "/build")))
            expect (fn [what p pattern]
                     (let [fs* (run p) hit (some (fn [[k m]] (and (= k :fail) (re-find pattern m))) fs*)]
                       (if hit (println "  ok   planted:" what) (do (swap! fails inc) (println "  FAIL planted but not named:" what)))))
            clean (fresh "clean")]
        (if (every? (comp #{:ok :note} first) (run clean))
          (println "  ok   a prefix of only the listed static libraries passes")
          (do (swap! fails inc) (println "  FAIL the clean prefix does not pass:") (report! (run clean))))
        (let [p (fresh "jbig2dec-symbol")] (mk-lib p "z" "jbig2_ctx_new") (expect "a jbig2dec symbol (jbig2_ctx_new) in libz.a" p #"excluded symbol"))
        (let [p (fresh "jbigkit-symbol")] (mk-lib p "tiff" "jbg_enc_init") (expect "a JBIG-KIT symbol (jbg_enc_init) in libtiff.a" p #"excluded symbol"))
        (let [p (fresh "openjpeg-symbol")] (mk-lib p "leptonica" "opj_decode") (expect "an OpenJPEG symbol (opj_decode) in libleptonica.a" p #"excluded symbol"))
        (let [p (fresh "lib-jbig2dec")] (mk-lib p "jbig2dec" "x_fn") (expect "libjbig2dec.a in the prefix" p #"jbig2dec"))
        (let [p (fresh "lib-openjp2")] (mk-lib p "openjp2" "x_fn") (expect "libopenjp2.a in the prefix" p #"openjp2"))
        (let [p (fresh "lib-unlisted")] (mk-lib p "lzma" "x_fn") (expect "libxz-style libzma.a, a library no pin installs" p #"no pinned component"))
        (let [p (fresh "shared")] (spit (str p "/lib/libpng16.dylib") "x") (expect "a shared libpng16.dylib" p #"shared library"))
        (let [p (fresh "missing")] (fs/delete (str p "/lib/libgif.a")) (expect "libgif.a missing" p #"no static library for gif"))
        (let [p (fresh "pc")] (spit (str p "/lib/pkgconfig/lept.pc") "Libs: -L/x -lleptonica\nLibs.private: -ljbig2dec -lm\n") (expect "-ljbig2dec in lept.pc" p #"\.pc: names an excluded"))
        (let [p (fresh "pc-jp2k")] (spit (str p "/lib/pkgconfig/lept.pc") "Requires.private: libopenjp2\n") (expect "libopenjp2 in lept.pc" p #"excluded library|JPEG 2000"))
        (let [p (fresh "cache")]
          (fs/create-dirs (str p "/build/leptonica"))
          (spit (str p "/build/leptonica/CMakeCache.txt") "PNG_LIBRARY_RELEASE:FILEPATH=/opt/homebrew/lib/libpng.dylib\n")
          (expect "a Homebrew libpng in a CMakeCache" p #"machine's own libraries"))
        (let [p (fresh "cache-outside")]
          (fs/create-dirs (str p "/build/leptonica"))
          (spit (str p "/build/leptonica/CMakeCache.txt") "PNG_LIBRARY_RELEASE:FILEPATH=/elsewhere/libpng16.a\n")
          (expect "a libpng16.a found outside the prefix" p #"OUTSIDE the prefix"))
        (let [p (fresh "cache-lib")]
          (fs/create-dirs (str p "/build/tiff"))
          (spit (str p "/build/tiff/CMakeCache.txt") "JBIG_LIBRARY:FILEPATH=/somewhere/libjbig.a\n")
          (expect "libjbig.a in a CMakeCache" p #"excluded library in the cache"))
        (let [p (fresh "linktxt")]
          (fs/create-dirs (str p "/build/tesseract"))
          (spit (str p "/build/tesseract/link.txt") "c++ -o tesseract a.o -ljbig2dec -lpng16\n")
          (expect "-ljbig2dec in a link.txt" p #"link line names an excluded"))
        (let [p (fresh "linkpath")]
          (fs/create-dirs (str p "/build/tesseract"))
          (spit (str p "/build/tesseract/link.txt") "c++ -o tesseract a.o /opt/homebrew/lib/libwebp.a\n")
          (expect "/opt/homebrew/lib/libwebp.a in a link.txt" p #"machine's libraries"))
        (fs/delete-tree tmp)
        (if (zero? @fails) (println "  ok   --self-test: 16 planted violations, each named; the clean prefix passes") (System/exit 1))))))

;; ------------------------------------------------------------------------------------------------ --smoke
(defn smoke! []
  (let [t triple prefix (prefix-of t) dir (str (build-root t) "/smoke")
        man (edn/read-string (slurp (str prefix "/manifest.edn")))
        cc (or (fs/which "cc") (fs/which "clang") (fs/which "gcc")) cxx (or (fs/which "c++") (fs/which "clang++") (fs/which "g++"))
        dyn (if (= :mac (:os (triple-cfg t))) ["otool" "-L"] ["ldd"])]
    (fs/create-dirs dir)
    (spit (str dir "/smoke.c")
          (str "#include <stdio.h>\nextern const char* TessVersion(void);\nextern char* getLeptonicaVersion(void);\n"
               "int main(void) { printf(\"tesseract %s | %s\\n\", TessVersion(), getLeptonicaVersion()); return 0; }\n"))
    (p/shell (str cc) "-c" (str dir "/smoke.c") "-o" (str dir "/smoke.o"))
    (apply p/shell (str cxx) "-o" (str dir "/smoke") (str dir "/smoke.o")
           (concat (map #(str prefix "/lib/lib" % ".a") (:link-order man)) (map #(str "-l" %) (remove #{"c++"} (:cxx-runtime man)))))
    (println "  ran:" (str/trim (:out (p/shell {:out :string} (str dir "/smoke")))))
    (let [deps (:out (apply p/shell {:out :string} (concat dyn [(str dir "/smoke")])))
          bad (filter #(re-find #"(?i)tesseract|lept|png|jpe?g|tiff|webp|gif|libz[.]" %) (str/split-lines deps))]
      (println "  dynamic dependencies of the linked program:")
      (doseq [l (str/split-lines deps)] (println "   " (str/trim l)))
      (if (seq bad)
        (die "a codec, Tesseract or Leptonica is linked dynamically: " (str/join "; " bad))
        (println "  ok   Tesseract, Leptonica and every codec are inside the program (static); only system libraries are loaded")))))

;; ------------------------------------------------------------------------------------------------ the build
(defn build! []
  (let [t triple cfg (triple-cfg t)
        _ (when-not (= t (host-triple))
            (die t " cannot be built on this host (" (host-triple) "): native builds work on the host only; "
                 ".github/workflows/native.yml builds it on " (:runner cfg)))
        prefix (prefix-of t)
        jobs (min 4 (Integer/parseInt (or (opt "--jobs") "4")))
        t0 (System/currentTimeMillis)]
    (doseq [tool ["cmake" "tar" "curl"]] (when-not (fs/which tool) (die tool " is not on the PATH")))
    (if (and (not (flag? "--force")) (fs/exists? (str prefix "/.built")) (= (pin-hash) (str/trim (slurp (str prefix "/.built")))))
      (println "up to date:" prefix "(the pins, native.clj and giflib.CMakeLists.txt are unchanged; --force rebuilds)")
      (do
        (println "native" t "-> " prefix " (" (count (:sources pins)) "sources, -j" jobs ")")
        (doseq [s (:sources pins)] (extract! s))
        (doseq [d (:data pins)] (datafile d))
        (fs/delete-tree prefix)
        (fs/create-dirs prefix)
        (doseq [s (:sources pins)] (build-one! t s jobs))
        (let [secs (/ (- (System/currentTimeMillis) t0) 1000.0)]
          (write-manifest! t prefix secs)
          (println (format "built in %.0f s; auditing" secs))
          (when-not (report! (audit t prefix (build-root t)))
            (fs/delete (str prefix "/manifest.edn"))
            (die "the audit failed; " prefix " is not to be used"))
          (spit (str prefix "/.built") (str (pin-hash) "\n")))))))

(cond
  (flag? "--self-test") (self-test!)
  (flag? "--licenses") (licences! (flag? "--check"))
  (flag? "--check") (check!)
  (flag? "--fetch") (do (doseq [s (:sources pins)] (extract! s)) (doseq [d (:data pins)] (datafile d)) (println "ok: every source is downloaded and matches its pin"))
  (flag? "--pin-hash") (println (pin-hash))
  (flag? "--smoke") (smoke!)
  (flag? "--audit") (let [t triple]
                      (println "audit" t (prefix-of t))
                      (when-not (report! (audit t (prefix-of t) (build-root t))) (System/exit 1)))
  :else (build!))
