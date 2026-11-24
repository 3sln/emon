;; The maintainer's FIRST manual pub.dev publish of the eight Dart packages (D885, D895, D905), from a release's
;; verified Dart tree. pub.dev only allows automated publishing for packages that already exist, so this runs once:
;;   bb tools/release/pub_first.clj --version 0.3.0-preview.1 [--repo 3sln/emon] [--dir DIR] [--dry-run] [--yes]
;; 1. downloads emon-dart-<version>.tar.gz and SHA256SUMS from the GitHub release and checks the archive's SHA-256;
;; 2. unpacks it to DIR (default ~/emon-dart-<version>);
;; 3. for each package in dependency order: `pub publish --dry-run`, then (after you confirm) `pub publish`
;;    (`flutter pub publish` for emon_ocr_mlkit and emon_flutter, `dart pub publish` otherwise). A package that
;;    pub.dev already has at this version is skipped, so a re-run finishes what an interrupted one left.
;; The first publish opens a browser for your Google login (pub.dev credentials stay with the Dart tool).
;; Needs Dart >= 3.10 / Flutter >= 3.38 and gh.
(require '[babashka.process :as p] '[babashka.fs :as fs] '[babashka.http-client :as http] '[clojure.string :as str])

(defn die [& m] (binding [*out* *err*] (println (apply str "pub_first: " m))) (System/exit 1))
(defn arg [k] (second (drop-while #(not= % k) *command-line-args*)))
(def flag? (set *command-line-args*))
(def version (or (arg "--version") (die "--version is required, e.g. --version 0.3.0-preview.1")))
(def repo (or (arg "--repo") "3sln/emon"))
(def dir (str (fs/expand-home (or (arg "--dir") (str "~/emon-dart-" version)))))
(def packages ["emon_core" "emon_ocr_tesseract" "emon_ocr_ppocr" "emon_ocr_vision" "emon_ocr_winocr" "emon_ocr_mlkit" "emon" "emon_flutter"])
(def flutter-pkgs #{"emon_ocr_mlkit" "emon_flutter"})

(defn sh [opts & cmd] (apply p/shell (merge {:continue true} opts) cmd))
(defn ask [q] (or (flag? "--yes") (do (print (str q " [y/N] ")) (flush) (contains? #{"y" "yes"} (str/lower-case (str/trim (or (read-line) "")))))))

(doseq [t ["gh" "dart" "flutter" "tar" "shasum"]] (when-not (fs/which t) (die t " not found on PATH")))
(let [v (:out (p/shell {:out :string :err :string} "dart" "--version"))
      [_ maj mn] (re-find #"(\d+)\.(\d+)" (str v))]
  (when (or (nil? maj) (< (+ (* 100 (parse-long maj)) (parse-long mn)) 310)) (die "Dart >= 3.10 needed (found: " (str/trim v) "); brew upgrade --cask flutter")))

;; 1. download and verify
(def asset (str "emon-dart-" version ".tar.gz"))
(def dl (str (fs/create-temp-dir {:prefix "emon-pub-"})))
(println "downloading" asset "and SHA256SUMS from" (str repo " v" version))
(when-not (zero? (:exit (sh {} "gh" "release" "download" (str "v" version) "--repo" repo "--dir" dl "--pattern" asset "--pattern" "SHA256SUMS")))
  (die "download failed: does release v" version " exist with " asset "?"))
(let [want (some (fn [l] (let [[h f] (str/split (str/trim l) #"\s+\*?")] (when (= f asset) h))) (str/split-lines (slurp (str dl "/SHA256SUMS"))))
      got (first (str/split (:out (p/shell {:out :string} "shasum" "-a" "256" (str dl "/" asset))) #"\s"))]
  (when-not want (die asset " is not listed in SHA256SUMS"))
  (when-not (= want got) (die "SHA-256 mismatch for " asset ": SHA256SUMS " want ", file " got))
  (println "  verified sha256" (subs got 0 16) "..."))

;; 2. unpack
(when (and (fs/exists? dir) (seq (fs/list-dir dir))) (die dir " exists and is not empty; pass --dir"))
(fs/create-dirs dir)
(sh {} "tar" "-xzf" (str dl "/" asset) "-C" dir)
(def root (let [ds (filter fs/directory? (fs/list-dir dir))] (if (and (= 1 (count ds)) (not (fs/exists? (fs/path dir "emon_core")))) (str (first ds)) dir)))
(doseq [pkg packages] (when-not (fs/exists? (fs/path root pkg "pubspec.yaml")) (die "the tree has no " pkg "/pubspec.yaml under " root)))
(println "  unpacked to" root)

;; 3. publish in order
(defn on-pub? [pkg] (try (let [r (http/get (str "https://pub.dev/api/packages/" pkg) {:throw false})]
                           (and (= 200 (:status r)) (str/includes? (:body r) (str "\"version\":\"" version "\""))))
                         (catch Exception _ false)))
(defn tool [pkg] (if (flutter-pkgs pkg) "flutter" "dart"))
(doseq [pkg packages]
  (println)
  (if (on-pub? pkg)
    (println "==" pkg version "is already on pub.dev; skipped")
    (do (println "==" pkg "(" (tool pkg) "pub publish )")
        (when-not (zero? (:exit (sh {:dir (str (fs/path root pkg))} (tool pkg) "pub" "publish" "--dry-run")))
          (die pkg ": the dry run failed; fix it and re-run (published packages are skipped)"))
        (cond (flag? "--dry-run") (println "  dry run only")
              (ask (str "Publish " pkg " " version " to pub.dev?"))
              (when-not (zero? (:exit (sh {:dir (str (fs/path root pkg))} (tool pkg) "pub" "publish" "--force")))
                (die pkg ": publish failed; re-run to continue (published packages are skipped)"))
              :else (die "stopped before " pkg "; re-run to continue")))))

(println)
(println "Done. Next (docs/releasing.md, \"The maintainer's settings\"): on pub.dev, for each package, Admin > Automated publishing:")
(println "  GitHub Actions, repository" repo ", tag pattern emon-dart-v{{version}}, no environment; transfer to the 3sln.com publisher if needed;")
(println "  then add the deploy key secret PUB_DEPLOY_KEY and set the variable EMON_PUB_ENABLED=true.")
(println "You can delete" dir "now.")
