;; Push/pull the sample corpus to/from S3-compatible object storage.
;;
;;   bb corpus-push [--dry-run] [category...]
;;   bb corpus-pull [--dry-run] [--force] [category...]
;;
;; Pure babashka: SigV4 is signed here with javax.crypto, HTTP is
;; babashka.http-client. One zip per top-level corpus directory (plus `_root`
;; for loose top-level files), an `index.json` beside them. See DEVLOG D077.
;;
;; Options (besides the EMON_S3_* keys in the gitignored `.env`):
;;   --corpus DIR   corpus location; else $EMON_CORPUS_DIR; else <repo>/corpus
;;   --part-mb N    multipart part size in MiB (default 64; S3 minimum is 5)
;;   $EMON_ENV_FILE alternative .env path;  $EMON_TMP scratch dir for zips
(ns corpus
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as proc]
            [cheshire.core :as json]
            [clojure.string :as str])
  (:import [java.io File InputStream OutputStream FileInputStream FileOutputStream BufferedOutputStream]
           [java.net URLEncoder]
           [java.nio.file Files Path StandardCopyOption LinkOption]
           [java.security MessageDigest]
           [java.time LocalDateTime ZoneOffset Instant]
           [java.time.format DateTimeFormatter]
           [java.util.zip ZipOutputStream ZipEntry ZipFile]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def repo-root (-> (or (System/getProperty "babashka.file") *file*) fs/absolutize fs/parent fs/parent str))

(defn die [& msg] (binding [*out* *err*] (println "corpus:" (apply str msg))) (System/exit 1))
(defn say [& msg] (println (apply str msg)) (flush))

;; ---------------------------------------------------------------- hashing / SigV4

(defn hex [^bytes bs] (apply str (map #(format "%02x" (bit-and % 0xff)) bs)))
(defn sha256-bytes ^bytes [^bytes bs] (.digest (MessageDigest/getInstance "SHA-256") bs))
(defn sha256-hex [^bytes bs] (hex (sha256-bytes bs)))
(defn hmac ^bytes [^bytes k ^String s]
  (let [m (Mac/getInstance "HmacSHA256")]
    (.init m (SecretKeySpec. k "HmacSHA256"))
    (.doFinal m (.getBytes s "UTF-8"))))

(defn uri-encode
  "RFC 3986 percent-encoding as SigV4 wants it: only A-Za-z0-9-_.~ stay."
  [^String s]
  (-> (URLEncoder/encode s "UTF-8") (str/replace "+" "%20") (str/replace "*" "%2A") (str/replace "%7E" "~")))

(defn encode-path [p] (->> (str/split p #"/" -1) (map uri-encode) (str/join "/")))

(defn canonical-query [pairs]
  (->> pairs (map (fn [[k v]] [(uri-encode (str k)) (uri-encode (str v))])) sort
       (map (fn [[k v]] (str k "=" v))) (str/join "&")))

(defn canonical-request
  "`path` must already be encoded; `headers` is a map of lowercase name -> value."
  [{:keys [method path query headers payload-hash]}]
  (let [names (sort (keys headers))]
    (str method "\n" path "\n" (canonical-query query) "\n"
         (apply str (for [n names] (str n ":" (str/replace (str/trim (str (headers n))) #"\s+" " ") "\n"))) "\n"
         (str/join ";" names) "\n" payload-hash)))

(defn signing-key [secret date region service]
  (-> (.getBytes (str "AWS4" secret) "UTF-8") (hmac date) (hmac region) (hmac service) (hmac "aws4_request")))

(defn authorization
  "The `Authorization` header value. `amz-date` is yyyyMMdd'T'HHmmss'Z'."
  [{:keys [headers region service access-key secret-key amz-date] :as req}]
  (let [date  (subs amz-date 0 8)
        scope (str date "/" region "/" service "/aws4_request")
        sts   (str "AWS4-HMAC-SHA256\n" amz-date "\n" scope "\n"
                   (sha256-hex (.getBytes ^String (canonical-request req) "UTF-8")))
        sig   (hex (hmac (signing-key secret-key date region service) sts))]
    (str "AWS4-HMAC-SHA256 Credential=" access-key "/" scope
         ", SignedHeaders=" (str/join ";" (sort (keys headers))) ", Signature=" sig)))

;; ---------------------------------------------------------------- .env / config

(defn parse-env
  "KEY=VALUE lines; `export ` prefix, # comments, single/double quotes allowed."
  [text]
  (into {}
        (for [line (str/split-lines text)
              :let [line (str/trim line)]
              :when (and (seq line) (not (str/starts-with? line "#")))
              :let [line (str/replace line #"^export\s+" "")
                    i (str/index-of line "=")]
              :when (and i (pos? i))
              :let [k (str/trim (subs line 0 i))
                    v (str/trim (subs line (inc i)))
                    v (cond
                        (re-matches #"\"(.*)\"" v) (second (re-matches #"\"(.*)\"" v))
                        (re-matches #"'(.*)'" v) (second (re-matches #"'(.*)'" v))
                        :else (str/trim (str/replace v #"\s+#.*$" "")))]]
          [k v])))

(def required-keys ["EMON_S3_ENDPOINT" "EMON_S3_REGION" "EMON_S3_BUCKET"
                    "EMON_S3_ACCESS_KEY_ID" "EMON_S3_SECRET_ACCESS_KEY"])

(defn load-config []
  (let [f (or (System/getenv "EMON_ENV_FILE") (str (fs/path repo-root ".env")))
        file-env (if (fs/exists? f) (parse-env (slurp f)) {})
        env (merge file-env (into {} (filter (fn [[k _]] (str/starts-with? k "EMON_S3_")) (System/getenv))))
        missing (remove #(seq (get env %)) required-keys)]
    (when (seq missing)
      (die "missing " (str/join ", " missing) " -- set them in " f
           " (see .env.example)" (when-not (fs/exists? f) " [that file does not exist]")))
    (let [ep (str/replace (env "EMON_S3_ENDPOINT") #"/+$" "")]
      (when-not (re-matches #"https?://[^/]+" ep)
        (die "EMON_S3_ENDPOINT must look like https://host[:port] (no path), got: " ep))
      {:endpoint ep
       :region (env "EMON_S3_REGION")
       :bucket (env "EMON_S3_BUCKET")
       :prefix (str/replace (get env "EMON_S3_PREFIX" "") #"^/+|/+$" "")
       :access-key (env "EMON_S3_ACCESS_KEY_ID")
       :secret-key (env "EMON_S3_SECRET_ACCESS_KEY")
       :path-style? (not (contains? #{"false" "0" "no" "off"} (str/lower-case (get env "EMON_S3_PATH_STYLE" "true"))))})))

;; ---------------------------------------------------------------- S3 client

(defn object-key [cfg nm] (if (seq (:prefix cfg)) (str (:prefix cfg) "/" nm) nm))

(defn request-target
  "-> {:scheme :host :path} for one object key, path- or virtual-hosted style."
  [cfg k]
  (let [[_ scheme hostport] (re-matches #"(https?)://([^/]+)" (:endpoint cfg))
        host (if (:path-style? cfg) hostport (str (:bucket cfg) "." hostport))
        path (if (:path-style? cfg) (str "/" (:bucket cfg) (when (seq k) (str "/" k))) (str "/" k))]
    {:scheme scheme :host host :path (encode-path path)}))

(def ^:private amz-fmt (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'"))

(def ^:private http-client (http/client {:version :http1.1}))

(defn s3-request
  "Signed request; the payload is hashed for real (works over plain http too)."
  [cfg method k {:keys [query body as] :or {query []}}]
  (let [{:keys [scheme host path]} (request-target cfg k)
        body (or body (byte-array 0))
        phash (sha256-hex body)
        amz-date (.format amz-fmt (LocalDateTime/now ZoneOffset/UTC))
        headers {"host" host "x-amz-content-sha256" phash "x-amz-date" amz-date}
        auth (authorization {:method (str/upper-case (name method)) :path path :query query :headers headers
                             :payload-hash phash :region (:region cfg) :service "s3"
                             :access-key (:access-key cfg) :secret-key (:secret-key cfg) :amz-date amz-date})
        qs (canonical-query query)
        url (str scheme "://" host path (when (seq qs) (str "?" qs)))]
    (http/request (cond-> {:client http-client :method method :uri url :throw false :timeout 600000
                           :headers (-> headers (dissoc "host") (assoc "authorization" auth))}
                    (pos? (alength ^bytes body)) (assoc :body body)
                    as (assoc :as as)))))

(defn- hdr [resp hname]
  (some (fn [[k v]] (when (= (str/lower-case (clojure.core/name k)) hname) (if (sequential? v) (first v) v))) (:headers resp)))
(defn- ok? [resp] (<= 200 (:status resp) 299))
(defn- fail! [what resp]
  (let [b (:body resp) b (if (string? b) b "")]
    (throw (ex-info (str what " failed: HTTP " (:status resp) " " (subs b 0 (min 300 (count b)))) {:status (:status resp)}))))

(defn with-retries [what n f]
  (loop [i 1]
    (let [r (try {:ok (f)} (catch Exception e {:err e}))]
      (cond (contains? r :ok) (:ok r)
            (>= i n) (throw (:err r))
            :else (do (binding [*out* *err*] (println (str "  retry " i "/" (dec n) " " what ": " (ex-message (:err r)))))
                      (Thread/sleep (long (* 500 (bit-shift-left 1 (dec i)))))
                      (recur (inc i)))))))

(defn get-string
  "Object body as a string, or nil on 404."
  [cfg nm]
  (with-retries (str "GET " nm) 4
    #(let [r (s3-request cfg :get (object-key cfg nm) {})]
       (cond (ok? r) (:body r) (= 404 (:status r)) nil :else (fail! (str "GET " nm) r)))))

(defn put-small [cfg nm ^bytes bs]
  (with-retries (str "PUT " nm) 4
    #(let [r (s3-request cfg :put (object-key cfg nm) {:body bs})]
       (when-not (ok? r) (fail! (str "PUT " nm) r)))))

(defn upload-file
  "Stream `file` to object `nm`: one PUT if it fits a part, else multipart with per-part retries and abort on failure."
  [cfg nm ^File file part-bytes]
  (let [size (.length file)
        k (object-key cfg nm)]
    (if (<= size part-bytes)
      (put-small cfg nm (Files/readAllBytes (.toPath file)))
      (let [r (with-retries (str "initiate " nm) 4
                #(let [r (s3-request cfg :post k {:query [["uploads" ""]]})]
                   (if (ok? r) r (fail! "initiate multipart" r))))
            upload-id (second (re-find #"<UploadId>([^<]+)</UploadId>" (:body r)))]
        (try
          (let [etags (with-open [in (FileInputStream. file)]
                        (loop [n 1 acc []]
                          (let [bs (.readNBytes in (int part-bytes))]
                            (if (zero? (alength bs))
                              acc
                              (let [etag (with-retries (str "part " n " of " nm) 4
                                           #(let [r (s3-request cfg :put k {:query [["partNumber" n] ["uploadId" upload-id]] :body bs})]
                                              (if (ok? r) (hdr r "etag") (fail! (str "part " n) r))))]
                                (say (format "    part %d  %d/%d MiB" n (quot (min size (* n part-bytes)) 1048576) (quot size 1048576)))
                                (recur (inc n) (conj acc [n etag])))))))
                body (str "<CompleteMultipartUpload>"
                          (apply str (for [[n e] etags] (str "<Part><PartNumber>" n "</PartNumber><ETag>" e "</ETag></Part>")))
                          "</CompleteMultipartUpload>")]
            (with-retries (str "complete " nm) 3
              #(let [r (s3-request cfg :post k {:query [["uploadId" upload-id]] :body (.getBytes body "UTF-8")})]
                 ;; S3 may answer 200 with an <Error> body
                 (if (and (ok? r) (not (re-find #"<Error>" (str (:body r))))) r (fail! "complete multipart" r)))))
          (catch Throwable t
            (try (s3-request cfg :delete k {:query [["uploadId" upload-id]]}) (catch Throwable _))
            (throw t)))))))

(defn download-file
  "Stream object to `dest`; returns {:sha256 :size}."
  [cfg nm ^File dest]
  (with-retries (str "GET " nm) 4
    #(let [r (s3-request cfg :get (object-key cfg nm) {:as :stream})]
       (if-not (ok? r)
         (fail! (str "GET " nm) {:status (:status r) :body (try (slurp (:body r)) (catch Exception _ ""))})
         (let [md (MessageDigest/getInstance "SHA-256")]
           (with-open [^InputStream in (:body r) out (BufferedOutputStream. (FileOutputStream. dest) 65536)]
             (let [buf (byte-array 65536)]
               (loop [size 0]
                 (let [n (.read in buf)]
                   (if (neg? n)
                     {:sha256 (hex (.digest md)) :size size}
                     (do (.update md buf 0 n) (.write out buf 0 n) (recur (+ size n)))))))))))))

;; ---------------------------------------------------------------- local corpus

(def root-name "_root")
(def ^:private fixed-time (LocalDateTime/of 1980 1 1 0 0 0))
(def ^:private no-attrs (make-array java.nio.file.attribute.FileAttribute 0))

(defn corpus-dir [opts]
  (str (fs/absolutize (or (:corpus opts) (System/getenv "EMON_CORPUS_DIR") (str (fs/path repo-root "corpus"))))))

(defn categories
  "Archive names present locally: each top-level dir, plus _root if loose files exist.
   Dot entries and `_dl` (the deletable raw-download cache) are not corpus."
  [dir]
  (let [kids (->> (fs/list-dir dir) (map fs/file-name) (remove #(or (str/starts-with? % ".") (= % "_dl"))) sort)
        dirs (filter #(fs/directory? (fs/path dir %)) kids)
        files (filter #(fs/regular-file? (fs/path dir %)) kids)]
    (when (some #{root-name} dirs) (die "a top-level dir named " root-name " collides with the loose-files archive"))
    (vec (concat dirs (when (seq files) [root-name])))))

(defn category-files
  "Sorted [path-inside-archive Path] for regular files of a category (symlinks skipped)."
  [dir cat]
  (let [dir (fs/path dir)
        ok (fn [^Path p] (and (Files/isRegularFile p (into-array LinkOption [LinkOption/NOFOLLOW_LINKS]))
                              (not= ".DS_Store" (str (.getFileName p)))))
        ps (if (= cat root-name)
             (->> (fs/list-dir dir) (remove #(str/starts-with? (fs/file-name %) ".")) (filter ok))
             (->> (fs/glob (fs/path dir cat) "**" {:hidden true}) (filter ok)))]
    (->> ps (map (fn [p] [(str/join "/" (map str (fs/components (fs/relativize (if (= cat root-name) dir (fs/path dir cat)) p)))) p])) (sort-by first) vec)))

(defn fingerprint [files]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (doseq [[rel ^Path p] files]
      (.update md (.getBytes (str rel "\u0000" (Files/size p) "\u0000" (.toMillis (Files/getLastModifiedTime p (into-array LinkOption []))) "\n") "UTF-8")))
    (hex (.digest md))))

(defn tmp-dir []
  (let [d (or (System/getenv "EMON_TMP") (str (fs/path (System/getProperty "java.io.tmpdir") "emon-corpus")))]
    (fs/create-dirs d) d))

(defn file-sha256 [^File f]
  (let [md (MessageDigest/getInstance "SHA-256") buf (byte-array 65536)]
    (with-open [in (FileInputStream. f)]
      (loop [] (let [n (.read in buf)] (when (pos? n) (.update md buf 0 n) (recur)))))
    (hex (.digest md))))

(defn zip-category!
  "Deterministic zip (sorted, fixed mtime, level 6) streamed to `dest`. -> {:sha256 :size :files}"
  [files ^File dest]
  (do
    (with-open [zos (ZipOutputStream. (BufferedOutputStream. (FileOutputStream. dest) 65536))]
      (.setLevel zos 6)
      (doseq [[rel ^Path p] files]
        (let [e (ZipEntry. ^String rel)]
          (.setTimeLocal e fixed-time)
          (.putNextEntry zos e)
          (Files/copy p ^OutputStream zos)
          (.closeEntry zos))))
    {:sha256 (file-sha256 dest) :size (.length dest) :files (count files)}))

(defn extract-zip! [^File zip ^Path dest]
  (Files/createDirectories dest no-attrs)
  (let [dest (.normalize (.toAbsolutePath dest))]
    (with-open [zf (ZipFile. zip)]
      (doseq [^ZipEntry e (enumeration-seq (.entries zf))
              :let [out (.normalize (.resolve dest (.getName e)))]]
        (when-not (.startsWith out dest) (throw (ex-info (str "unsafe zip entry " (.getName e)) {})))
        (if (.isDirectory e)
          (Files/createDirectories out no-attrs)
          (do (Files/createDirectories (.getParent out) no-attrs)
              (with-open [in (.getInputStream zf e)] (Files/copy in out (into-array [StandardCopyOption/REPLACE_EXISTING])))
              (when (str/ends-with? (.getName e) ".sh") (.setExecutable (.toFile out) true))))))))

(defn install-category!
  "Replace corpus/<cat> with the extracted tree: extract aside, then rename."
  [dir cat ^File zip]
  (let [tmp (fs/path dir (str ".tmp-" cat "-" (System/nanoTime)))]
    (try
      (extract-zip! zip tmp)
      (if (= cat root-name)
        (doseq [p (fs/list-dir tmp)]
          (Files/move p (fs/path dir (fs/file-name p)) (into-array [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE])))
        (let [target (fs/path dir cat) old (fs/path dir (str ".old-" cat "-" (System/nanoTime)))]
          (when (fs/exists? target) (Files/move target old (into-array [StandardCopyOption/ATOMIC_MOVE])))
          (Files/move tmp target (into-array [StandardCopyOption/ATOMIC_MOVE]))
          (when (fs/exists? old) (fs/delete-tree old))))
      (finally (when (fs/exists? tmp) (fs/delete-tree tmp))))))

;; state: what this checkout last pushed/pulled, so unchanged archives cost nothing
(defn state-file [dir] (str (fs/path dir ".emon-corpus-state.json")))
(defn read-state [dir] (let [f (state-file dir)] (if (fs/exists? f) (json/parse-string (slurp f)) {})))
(defn write-state! [dir st] (spit (state-file dir) (json/generate-string st {:pretty true})))

(defn git-commit []
  (try (let [sh #(str/trim (:out (proc/shell {:out :string :err :string :dir repo-root} %)))
             c (sh "git rev-parse HEAD")]
         (str c (when (seq (sh "git status --porcelain")) "-dirty")))
       (catch Exception _ "unknown")))

(defn fmt-size [n]
  (cond (>= n 1073741824) (format "%.2f GB" (/ n 1073741824.0))
        (>= n 1048576) (format "%.1f MB" (/ n 1048576.0))
        :else (str n " B")))

;; ---------------------------------------------------------------- push / pull

(defn read-index [cfg]
  (when-let [b (get-string cfg "index.json")] (json/parse-string b)))

(defn push! [{:keys [dry-run part-mb cats] :as opts}]
  (let [cfg (load-config) dir (corpus-dir opts)
        _ (when-not (fs/directory? dir) (die "corpus dir not found: " dir))
        all (categories dir)
        sel (if (seq cats)
              (do (doseq [c cats] (when-not (some #{c} all) (die "no such category: " c " (have " (str/join ", " all) ")"))) (vec cats))
              all)
        remote (read-index cfg)
        rmap (into {} (map (juxt #(get % "name") identity) (get remote "archives")))
        st (read-state dir)
        part-bytes (* 1048576 (or part-mb 64))
        tmp (tmp-dir)]
    (say (if dry-run "DRY RUN -- " "") "push " dir " -> " (:endpoint cfg) "/" (:bucket cfg) "/" (object-key cfg "")
         " (" (count sel) " archive(s))")
    (let [results
          (vec (for [cat sel]
                 (let [files (category-files dir cat)
                       fp (fingerprint files)
                       rsha (get-in rmap [cat "sha256"])
                       prev (get st cat)]
                   (cond
                     (and rsha prev (= fp (get prev "fingerprint")) (= rsha (get prev "sha256")))
                     (do (say "  skip   " cat " (unchanged since last push)") {:cat cat :entry (get rmap cat) :changed? false})

                     dry-run
                     (do (say "  would  zip " cat " (" (count files) " files); upload unless its sha256 matches the remote")
                         {:cat cat :entry (get rmap cat) :changed? false})

                     :else
                     (let [zip (fs/file tmp (str cat ".zip"))]
                       (try
                         (say "  zip    " cat " (" (count files) " files)")
                         (let [{:keys [sha256 size files]} (zip-category! files zip)
                               entry {"name" cat "file" (str cat ".zip") "size" size "sha256" sha256 "files" files}]
                           (if (= sha256 rsha)
                             (do (say "  skip   " cat " (remote already has sha256 " (subs sha256 0 12) ")") {:cat cat :entry entry :changed? false :fp fp})
                             (do (say "  upload " cat ".zip (" (fmt-size size) ")")
                                 (upload-file cfg (str cat ".zip") zip part-bytes)
                                 {:cat cat :entry entry :changed? true :fp fp})))
                         (finally (fs/delete-if-exists zip))))))))
          changed (count (filter :changed? results))]
      (when-not dry-run
        (write-state! dir (reduce (fn [s r] (if (:fp r) (assoc s (:cat r) {"sha256" (get-in r [:entry "sha256"]) "fingerprint" (:fp r)}) s))
                                  st results)))
      (let [local (into {} (keep #(when (:entry %) [(:cat %) (:entry %)]) results))
            merged (vals (if (seq cats) (merge rmap local) local))
            index {"version" 1 "pushed_at" (str (Instant/now)) "git_commit" (git-commit)
                   "archives" (vec (sort-by #(get % "name") merged))}]
        (cond dry-run (say "DRY RUN -- nothing written.")
              (or (pos? changed) (nil? remote)
                  (not= (set (map #(get % "name") (get remote "archives"))) (set (map #(get % "name") merged))))
              (do (put-small cfg "index.json" (.getBytes ^String (json/generate-string index {:pretty true}) "UTF-8"))
                  (say "  wrote  index.json"))
              :else (say "  index.json unchanged"))
        (say "done: " changed " uploaded, " (- (count results) changed) " skipped")))))

(defn pull! [{:keys [dry-run force cats] :as opts}]
  (let [cfg (load-config) dir (corpus-dir opts)
        index (or (read-index cfg) (die "no index.json at " (:endpoint cfg) "/" (:bucket cfg) "/" (object-key cfg "") " -- nothing pushed yet?"))
        archives (get index "archives")
        names (map #(get % "name") archives)
        sel (if (seq cats)
              (do (doseq [c cats] (when-not (some #{c} names) (die "not in remote index: " c " (have " (str/join ", " names) ")"))) cats)
              names)
        st (if (fs/directory? dir) (read-state dir) {})
        tmp (tmp-dir)]
    (say (if dry-run "DRY RUN -- " "") "pull " (:endpoint cfg) "/" (:bucket cfg) " -> " dir
         " (index from " (get index "pushed_at") ", commit " (get index "git_commit") ")")
    (when-not dry-run (fs/create-dirs dir))
    (loop [[cat & more] sel st st n 0]
      (if-not cat
        (say "done: " n " pulled, " (- (count sel) n) " up to date")
        (let [a (first (filter #(= cat (get % "name")) archives))
              sha (get a "sha256")
              present? (or (= cat root-name) (fs/directory? (fs/path dir cat)))
              current? (and (not force) present? (= sha (get-in st [cat "sha256"])))]
          (cond
            current? (do (say "  skip   " cat " (up to date)") (recur more st n))
            dry-run (do (say "  would  download " cat ".zip (" (fmt-size (get a "size")) ", " (get a "files") " files)") (recur more st (inc n)))
            :else
            (let [zip (fs/file tmp (str cat ".zip.part"))]
              (try
                (say "  fetch  " cat ".zip (" (fmt-size (get a "size")) ")")
                (let [{got :sha256} (download-file cfg (get a "file") zip)]
                  (when-not (= got sha) (die "sha256 mismatch for " cat ".zip: index says " sha ", downloaded " got " -- not extracted"))
                  (say "  verify ok; extract " cat)
                  (install-category! dir cat zip))
                (finally (fs/delete-if-exists zip)))
              (let [st (assoc st cat {"sha256" sha})]
                (write-state! dir st)
                (recur more st (inc n))))))))))

;; ---------------------------------------------------------------- CLI

(defn parse-args [args]
  (loop [[a & more :as as] args o {:cats []}]
    (cond (empty? as) o
          (= a "--dry-run") (recur more (assoc o :dry-run true))
          (= a "--force") (recur more (assoc o :force true))
          (= a "--corpus") (recur (rest more) (assoc o :corpus (first more)))
          (= a "--part-mb") (recur (rest more) (assoc o :part-mb (parse-long (first more))))
          (str/starts-with? a "--") (die "unknown option " a)
          :else (recur more (update o :cats conj a)))))

(defn -main [& [cmd & args]]
  (let [opts (parse-args args)]
    (try
      (case cmd
        "push" (push! opts)
        "pull" (pull! opts)
        (die "usage: bb corpus-push|corpus-pull [--dry-run] [--corpus DIR] [category...]"))
      (catch clojure.lang.ExceptionInfo e (die (ex-message e))))))

(when (= (System/getProperty "babashka.file") *file*)
  (apply -main *command-line-args*))
