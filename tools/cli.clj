#!/usr/bin/env bb
;; The per-platform `emon` command-line tool (P11.4b, D541; D524): build it, lay out the archive, check the archive.
;;
;;   bb cli [--target TRIPLE] [--out DIR]           build host/cli with the static Tesseract and Leptonica of `bb native` and the pinned pdfium
;;                                                  bundled beside it, and write DIR/emon-<version>-<triple>.tar.gz (.zip on Windows) and its .sha256
;;   bb cli --check [--target TRIPLE] [--out DIR] [--strict]
;;                                                  the same, then unpack the archive into a temporary directory and hold it to what D524 says:
;;                                                  `emon version` runs from there and finds the bundled pdfium and models; only system libraries are
;;                                                  dynamic (otool -L / ldd); a born-digital and a scanned sample convert and are compared with the
;;                                                  committed samples/*.html (a difference is REPORTED; --strict fails on one); the refusals (a
;;                                                  missing pdfium, an absent model) are sentences with exit 3. Prints `SKIP cli -- why` when
;;                                                  .cache/native/<triple> (bb native), the pinned pdfium (bb pdfium-fetch) or cargo is not there.
;;
;; DIR defaults to .cache/cli. The archive holds one directory, emon-<version>-<triple>/:
;;     bin/emon (bin/emon.exe, bin/pdfium.dll on Windows)   lib/libpdfium.dylib|.so   share/emon/tessdata/{eng,osd}.traineddata
;;     LICENSE  THIRD_PARTY_NOTICES.md  README.md (docs/cli.md)
;; Native builds work on the HOST only (D540): another target is built by its own runner (.github/workflows/cli.yml).
(require '[babashka.fs :as fs]
         '[babashka.process :refer [shell]]
         '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[clojure.string :as str])
(import '[java.security MessageDigest] '[java.util.zip ZipEntry ZipOutputStream])

(defn zip-dir!
  "Write `archive` holding every file under `stage`, named relative to `root` with `/` separators (babashka.fs/zip named them with the platform's
  separator on Windows, which the unzip then took for absolute paths)."
  [archive stage root]
  (let [root (fs/absolutize root)]
    (with-open [zos (ZipOutputStream. (io/output-stream (str archive)))]
      (doseq [f (sort (map str (filter fs/regular-file? (fs/glob stage "**"))))]
        (.putNextEntry zos (ZipEntry. (str/replace (str (fs/relativize root (fs/absolutize f))) "\\" "/")))
        (io/copy (io/file f) zos)
        (.closeEntry zos)))))

(def args (vec *command-line-args*))
(defn flag? [f] (some #{f} args))
(defn opt [f] (second (drop-while #(not= f %) args)))
(defn die [& xs] (binding [*out* *err*] (println (str "FAIL " (apply str xs)))) (System/exit 1))

(def native-pins (edn/read-string (slurp "tools/native/native.edn")))
(def pdfium-pin (edn/read-string (slurp "tools/pdfium/pdfium.edn")))
(def version (:version (edn/read-string (slurp "version.edn"))))

(defn host-triple []
  (let [os (System/getProperty "os.name") arch (System/getProperty "os.arch")
        a (if (#{"aarch64" "arm64"} arch) :arm64 :x64)]
    (cond (str/includes? os "Mac") (if (= a :arm64) "aarch64-apple-darwin" "x86_64-apple-darwin")
          (str/includes? os "Linux") (if (= a :arm64) "aarch64-unknown-linux-gnu" "x86_64-unknown-linux-gnu")
          (str/includes? os "Windows") "x86_64-pc-windows-msvc"
          :else (die "unknown host " os " " arch))))

(def triple (or (opt "--target") (host-triple)))
(def tcfg (or (get-in native-pins [:triples triple])
              (die "no such target " triple " (tools/native/native.edn): " (str/join ", " (sort (keys (:triples native-pins)))))))
(def windows? (= :windows (:os tcfg)))
(def out-dir (str (fs/absolutize (or (opt "--out") ".cache/cli"))))
(def dirname (str "emon-" version "-" triple))
(def archive-name (str dirname (if windows? ".zip" ".tar.gz")))
(def archive (str out-dir "/" archive-name))

;; ---- what the archive is made of -----------------------------------------------------------------------------------------------------------
(def prefix (str (fs/absolutize (str ".cache/native/" triple))))
(def pdfium-platform (:pdfium tcfg))
(def pdfium-version (:version pdfium-pin))
(def pdfium-dir (str (fs/absolutize (str ".cache/pdfium/" (str/replace pdfium-version "/" "-") "/" pdfium-platform))))
(def pdfium-src (str pdfium-dir "/" (get-in pdfium-pin [:platforms pdfium-platform :lib])))
(def pdfium-name (last (str/split pdfium-src #"/")))

(defn skip! [why] (println (str "SKIP cli -- " why)) (System/exit 0))

(defn missing-inputs []
  (cond
    (not= triple (host-triple)) (str triple " is not this host (" (host-triple) "): native builds work on the host only; .github/workflows/cli.yml builds the others")
    (not (fs/exists? (str prefix "/link.rustflags"))) (str "no " prefix " (run `bb native`)")
    (not (fs/exists? (str prefix "/share/tessdata/eng.traineddata"))) (str prefix " has no tessdata (run `bb native`)")
    (not (fs/exists? pdfium-src)) (str "no pinned pdfium at " pdfium-src " (run `bb pdfium-fetch --platform " pdfium-platform "`)")
    (not (fs/which "cargo")) "no cargo"
    :else nil))

(defn sha256-file [f]
  (let [md (MessageDigest/getInstance "SHA-256") buf (byte-array 65536)]
    (with-open [in (clojure.java.io/input-stream (clojure.java.io/file (str f)))]
      (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur)))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn mb [bytes] (format "%.1f MB" (/ bytes 1048576.0)))

;; ---- build and package ---------------------------------------------------------------------------------------------------------------------
(defn build! []
  (println "building host/cli for" triple "(static Tesseract and Leptonica from" prefix ")")
  (let [r (shell {:out :string :err :inherit :continue true
                  :extra-env {"EMON_CLI" "1" "EMON_NATIVE" prefix "EMON_PDFIUM_VERSION" pdfium-version "CARGO_BUILD_JOBS" "4"}}
                 "sh" "kin/scripts/emon-rs-build")]
    (when-not (zero? (:exit r)) (die "kin/scripts/emon-rs-build (EMON_CLI=1) failed"))
    (let [bin (str/trim (last (str/split-lines (:out r))))]
      (when-not (fs/exists? bin) (die "the build printed " bin ", which is not there"))
      (str (fs/absolutize bin)))))

(defn package! [bin]
  (let [stage (str out-dir "/" dirname)
        exe (if windows? "emon.exe" "emon")]
    (fs/create-dirs out-dir)
    (fs/delete-tree stage)
    (fs/delete-if-exists archive)
    (fs/delete-if-exists (str archive ".sha256"))
    (fs/create-dirs (str stage "/bin"))
    (fs/copy bin (str stage "/bin/" exe))
    (when-not windows? (fs/set-posix-file-permissions (str stage "/bin/" exe) "rwxr-xr-x"))
    ;; pdfium: beside the binary on Windows (the loader looks there), in lib/ elsewhere
    (if windows?
      (fs/copy pdfium-src (str stage "/bin/" pdfium-name))
      (do (fs/create-dirs (str stage "/lib")) (fs/copy pdfium-src (str stage "/lib/" pdfium-name))))
    (fs/create-dirs (str stage "/share/emon/tessdata"))
    (doseq [d (:data native-pins) :let [f (:file d)]]
      (fs/copy (str prefix "/share/tessdata/" f) (str stage "/share/emon/tessdata/" f)))
    (fs/copy "LICENSE" (str stage "/LICENSE"))
    (fs/copy "THIRD_PARTY_NOTICES.md" (str stage "/THIRD_PARTY_NOTICES.md"))
    (fs/copy "docs/cli.md" (str stage "/README.md"))
    (if windows?
      (zip-dir! archive stage out-dir)
      (shell {:dir out-dir :extra-env {"COPYFILE_DISABLE" "1"}} "tar" "-czf" archive dirname))
    (let [h (sha256-file archive)]
      (spit (str archive ".sha256") (str h "  " archive-name "\n"))
      (println (str "  " archive-name "  " (mb (fs/size archive)) "  sha256 " h))
      (println (str "  unpacked: " (mb (reduce + (map fs/size (filter fs/regular-file? (fs/glob stage "**")))))
                    " (binary " (mb (fs/size (str stage "/bin/" exe))) ", pdfium " (mb (fs/size pdfium-src)) ", models "
                    (mb (reduce + (map #(fs/size (str stage "/share/emon/tessdata/" (:file %))) (:data native-pins)))) ")")))
    stage))

;; ---- the check -----------------------------------------------------------------------------------------------------------------------------
(def fails (atom 0))
(defn ok! [& xs] (println "  ok " (apply str xs)))
(defn note! [& xs] (println "  NOTE" (apply str xs)))
(defn fail! [& xs] (swap! fails inc) (println "  FAIL" (apply str xs)))

(def clean-env {"EMON_PDFIUM_LIB" "" "EMON_TESSDATA" "" "TESSDATA_PREFIX" ""})

(defn run [dir exe & xs]
  (apply shell {:dir dir :out :string :err :string :continue true :extra-env clean-env} exe xs))

(def system-lib-re
  (case (:os tcfg)
    :mac #"^(/usr/lib/|/System/Library/)"
    :linux #"(linux-vdso|ld-linux|/ld\.so|^lib(c|m|dl|rt|pthread|gcc_s|stdc\+\+|resolv)\.so)"
    ;; Windows (D1580): the C runtime is linked STATICALLY (/MT, +crt-static), the UCRT with it, so no msvcp140, vcruntime140, vcruntime140_1, ucrtbase or
    ;; api-ms-win-crt-* may appear: a dependency on any of them means a /MD object got in, and the exe would need the VC++ redistributable (or, for the UCRT, a
    ;; Windows older than 10). What remains are Windows' own DLLs, and the api-ms-win-core-* etc. API sets rustc's std imports (they ship with Windows 10+).
    :windows #"(?i)^(kernel32|user32|advapi32|ws2_32|ntdll|bcrypt|bcryptprimitives|userenv|ole32|shell32|gdi32|oleaut32|crypt32|secur32|shlwapi|mswsock|version|imm32|api-ms-win-(?!crt-).*)\.dll$"))

(defn dependencies [exe]
  (case (:os tcfg)
    :mac (->> (str/split-lines (:out (shell {:out :string} "otool" "-L" exe))) rest (map #(first (str/split (str/trim %) #" \("))))
    :linux (->> (str/split-lines (:out (shell {:out :string :continue true} "ldd" exe)))
                (map #(first (str/split (str/trim %) #"\s+=>|\s+\("))))
    :windows (let [r (shell {:out :string :continue true} "dumpbin" "/dependents" exe)]
               (->> (str/split-lines (:out r)) (map str/trim) (filter #(re-matches #"(?i).*\.dll" %))))))

(defn compare-html! [label got want]
  (if (= got want)
    (ok! label ": identical to the committed HTML (" (count want) " bytes)")
    (let [a (str/split-lines got) b (str/split-lines want)
          i (or (first (filter #(not= (nth a % nil) (nth b % nil)) (range (max (count a) (count b))))) 0)
          cut #(let [s (or % "")] (if (> (count s) 160) (str (subs s 0 160) "...") s))]
      (let [report (str label ": DIFFERS from the committed HTML (" (count got) " bytes against " (count want) ", " (count a) " lines against " (count b)
                        "); first difference at line " (inc i) "\n         emon: " (cut (nth a i nil)) "\n         file: " (cut (nth b i nil)))]
        (if (flag? "--strict") (fail! report) (note! report))))))

(defn check! [stage]
  (let [tmp (str (fs/create-temp-dir {:prefix "emon-cli-"}))]
    (try
      (if windows?
        (fs/unzip archive tmp)
        (shell {:dir tmp} "tar" "-xzf" archive))
      (let [root (str tmp "/" dirname)
            exe (str root "/bin/" (if windows? "emon.exe" "emon"))
            sample (fn [n ext] (str (fs/absolutize (str "samples/" n "." ext))))]
        ;; the archive's layout
        (let [want (concat ["bin/emon" "LICENSE" "THIRD_PARTY_NOTICES.md" "README.md" "share/emon/tessdata/eng.traineddata" "share/emon/tessdata/osd.traineddata"]
                           [(if windows? (str "bin/" pdfium-name) (str "lib/" pdfium-name))])
              gone (remove #(fs/exists? (str root "/" (if windows? (str/replace % "bin/emon" "bin/emon.exe") %))) want)]
          (if (seq gone) (fail! "the unpacked archive lacks " (str/join ", " gone)) (ok! "the archive unpacks to " dirname "/ with its binary, pdfium, eng and osd models and the notices")))
        ;; `emon version`, from the unpacked directory, with no EMON_* in the environment
        (let [v (run root exe "version") lines (str/split-lines (:out v))
              tess (:version (first (filter #(= "tesseract" (:id %)) (:sources native-pins))))
              lept (:version (first (filter #(= "leptonica" (:id %)) (:sources native-pins))))]
          (cond
            (not (zero? (:exit v))) (fail! "`emon version` exited " (:exit v) ": " (:err v))
            (not= (str "emon " version) (first lines)) (fail! "`emon version` begins " (pr-str (first lines)) ", want " (pr-str (str "emon " version)))
            :else (do (ok! "`emon version` from the unpacked archive:") (doseq [l lines] (println "         " l))))
          (when (zero? (:exit v))
            (when-not (some #(str/includes? % (str "tesseract " tess)) lines) (fail! "version does not say tesseract " tess))
            (when-not (some #(str/includes? % (str "leptonica-" lept)) lines) (fail! "version does not say leptonica-" lept))
            (when-not (some #(str/includes? % (str "chromium/8076")) lines) (fail! "version does not say the pinned pdfium"))
            (let [lib (first (filter #(str/starts-with? % "pdfium library: ") lines))
                  td (first (filter #(str/starts-with? % "tesseract models: ") lines))]
              (if (and lib (str/includes? lib (fs/file-name tmp)) td (str/includes? td (fs/file-name tmp)) (str/includes? td "eng") (str/includes? td "osd"))
                (ok! "pdfium and the models are found inside the unpacked archive (relative to the executable), not elsewhere")
                (fail! "version does not find the bundled pdfium and models in the archive: " lib " / " td)))))
        ;; `emon licenses`
        (let [l (run root exe "licenses")]
          (if (and (zero? (:exit l)) (= (slurp "THIRD_PARTY_NOTICES.md") (:out l)))
            (ok! "`emon licenses` prints THIRD_PARTY_NOTICES.md (" (count (:out l)) " bytes)")
            (fail! "`emon licenses` is not THIRD_PARTY_NOTICES.md (exit " (:exit l) ")")))
        ;; the dynamic dependencies
        (let [deps (dependencies exe)
              bad (remove #(re-find system-lib-re %) deps)
              named (filter #(re-find #"(?i)pdfium|tesseract|leptonica|png|jpeg|tiff|webp|gif|jbig|openjp|lzma|zstd|archive|curl" %) deps)]
          (println (str "         dynamic dependencies of bin/" (fs/file-name exe) ":"))
          (doseq [d deps] (println "           " d))
          (cond (empty? deps) (fail! "could not read the dynamic dependencies")
                (seq bad) (fail! "not a system library: " (str/join ", " bad))
                (seq named) (fail! "a codec or engine is dynamic: " (str/join ", " named))
                :else (ok! "only system libraries are dynamic (" (count deps) "); Tesseract, Leptonica and the codecs are inside the binary; pdfium is loaded at run time from lib/")))
        ;; the refusals are sentences
        (let [r (run root exe "convert" (sample "invoice" "pdf") "--pdfium" (str tmp "/nowhere"))]
          (if (and (= 3 (:exit r)) (str/includes? (:err r) "pdfium") (empty? (:out r)))
            (ok! "a missing pdfium (--pdfium) is exit 3 and a sentence: " (first (str/split-lines (:err r))))
            (fail! "--pdfium /nowhere: exit " (:exit r) ", stderr " (pr-str (:err r)))))
        (let [r (run root exe "convert" (sample "invoice" "pdf") "--lang" "xyz")]
          (if (and (= 3 (:exit r)) (str/includes? (:err r) "xyz.traineddata"))
            (ok! "an absent model (--lang xyz) is exit 3 and names the file: " (first (str/split-lines (:err r))))
            (fail! "--lang xyz: exit " (:exit r) ", stderr " (pr-str (:err r)))))
        (let [r (run root exe "convert" (sample "invoice" "pdf") "no-such-option=1")]
          (if (= 2 (:exit r)) (ok! "an unknown key=value is a usage error (exit 2)") (fail! "no-such-option=1: exit " (:exit r))))
        ;; conversions: the committed samples were made by the Rust driver with the default options, ocr=always
        (let [r (run root exe "convert" (sample "invoice" "pdf") "--ocr" "never")]
          (if (and (zero? (:exit r)) (str/starts-with? (:out r) "<"))
            (ok! "invoice.pdf with --ocr never: HTML, " (count (:out r)) " bytes")
            (fail! "invoice.pdf --ocr never: exit " (:exit r) " " (:err r))))
        (doseq [[n what] [["invoice" "born-digital"] ["scan-plain" "scanned"]]]
          (let [t0 (System/currentTimeMillis)
                r (run root exe "convert" (sample n "pdf"))
                secs (/ (- (System/currentTimeMillis) t0) 1000.0)]
            (if (zero? (:exit r))
              (do (println (format "         %s (%s) converted in %.1f s" n what secs))
                  (compare-html! (str n ".pdf (" what ")") (:out r) (slurp (sample n "html"))))
              (fail! n ".pdf: exit " (:exit r) " " (:err r)))))
        ;; -o and --assets dir
        (let [o (str tmp "/figs.html")
              r (run root exe "convert" (sample "figures-charts" "pdf") "-o" o "--assets" "dir" "--ocr" "never")]
          (if (and (zero? (:exit r)) (fs/exists? o) (seq (fs/glob (str tmp "/figs.assets") "*.png")))
            (ok! "figures-charts.pdf -o FILE --assets dir wrote the HTML and " (count (fs/glob (str tmp "/figs.assets") "*.png")) " PNG(s) beside it")
            (fail! "--assets dir: exit " (:exit r) " " (:err r)))))
      (finally (fs/delete-tree tmp)))))

;; ---- main ----------------------------------------------------------------------------------------------------------------------------------
(when-let [why (missing-inputs)]
  (if (flag? "--check") (skip! why) (die why)))
(when-not (fs/exists? "docs/cli.md") (die "docs/cli.md is missing (it is the archive's README)"))
(let [stage (package! (build!))]
  (when (flag? "--check")
    (check! stage)
    (if (pos? @fails)
      (do (println (str "FAIL cli: " @fails " check(s)")) (System/exit 1))
      (println (str "  ok  cli: " archive-name " holds the CLI of D524 for " triple)))))
