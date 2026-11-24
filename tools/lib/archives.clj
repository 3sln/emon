(ns archives
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io BufferedOutputStream RandomAccessFile]
           [java.nio.file Files]
           [java.time LocalDateTime]
           [java.util.zip GZIPOutputStream ZipEntry ZipFile ZipOutputStream]))

(defn- archive-path [path]
  (str/replace (str path) "\\" "/"))

(defn- entries [root name exclude?]
  (letfn [(walk [path rel]
            (when-not (exclude? (fs/file-name path))
              (cond
                (fs/sym-link? path) [[rel path :link]]
                (fs/directory? path) (cons [(str rel "/") path :dir]
                                           (mapcat #(walk % (str rel "/" (fs/file-name %))) (fs/list-dir path)))
                (fs/regular-file? path) [[rel path :file]]
                :else (throw (ex-info "Unsupported archive entry" {:path (str path)})))))]
    (sort-by first (walk (fs/path root name) (archive-path name)))))

(defn- mode [path kind]
  (if (= kind :link)
    0777
    (try
      (let [permissions (set (map str (fs/posix-file-permissions path)))]
        (reduce (fn [n permission] (+ (* n 2) (if (permissions permission) 1 0))) 0
                (for [who ["OWNER" "GROUP" "OTHERS"] what ["READ" "WRITE" "EXECUTE"]] (str who "_" what))))
      (catch UnsupportedOperationException _
        (if (= kind :dir) 0755 0644)))))

(defn- utf8 [s] (.getBytes (str s) "UTF-8"))

(defn- put-field! [header offset width value]
  (let [data (utf8 value)]
    (when (> (alength data) width)
      (throw (ex-info "Tar header field is too long" {:value value :width width})))
    (System/arraycopy data 0 header offset (alength data))))

(defn- tar-header [name type permissions size link]
  (let [header (byte-array 512)]
    (put-field! header 0 100 name)
    (put-field! header 100 8 (format "%07o" permissions))
    (put-field! header 108 8 "0000000")
    (put-field! header 116 8 "0000000")
    (put-field! header 124 12 (format "%011o" size))
    (put-field! header 136 12 "00000000000")
    (put-field! header 148 8 "        ")
    (put-field! header 156 1 type)
    (put-field! header 157 100 link)
    (put-field! header 257 6 "ustar")
    (put-field! header 263 2 "00")
    (put-field! header 148 7 (format "%06o\u0000" (reduce + (map #(bit-and % 255) header))))
    header))

(defn- pax-record [key value]
  (let [body (str " " key "=" value "\n")
        size (alength (utf8 body))]
    (loop [length (+ 1 size)]
      (let [total (+ size (count (str length)))]
        (if (= total length) (str length body) (recur total))))))

(defn- pad! [out size]
  (.write out (byte-array (mod (- size) 512))))

(defn tar-gz!
  "Write one directory tree with fixed timestamps, sorted paths, POSIX modes and symlinks."
  ([dest root name] (tar-gz! dest root name #{}))
  ([dest root name exclude?]
   (with-open [out (GZIPOutputStream. (io/output-stream (str dest)) 65536)]
     (doseq [[rel path kind] (entries root name exclude?)]
       (let [size (if (= kind :file) (fs/size path) 0)
             link (if (= kind :link) (archive-path (Files/readSymbolicLink path)) "")
             long-path? (> (alength (utf8 rel)) 100)
             long-link? (> (alength (utf8 link)) 100)
             pax (utf8 (str (when long-path? (pax-record "path" rel))
                            (when long-link? (pax-record "linkpath" link))))
             type ({:dir "5" :link "2" :file "0"} kind)]
         (when (pos? (alength pax))
           (.write out (tar-header "PaxHeader" "x" 0644 (alength pax) ""))
           (.write out pax)
           (pad! out (alength pax)))
         (.write out (tar-header (if long-path? "PaxEntry" rel) type (mode path kind) size (if long-link? "" link)))
         (when (= kind :file)
           (io/copy (fs/file path) out)
           (pad! out size))))
     (.write out (byte-array 1024)))
   dest))

(defn- read-u16 [reader]
  (+ (.readUnsignedByte reader) (* 256 (.readUnsignedByte reader))))

(defn- zip-modes! [dest offset modes]
  ;; java.util.zip does not expose the central directory's Unix mode field.
  (with-open [zip (RandomAccessFile. (str dest) "rw")]
    (loop [offset offset, modes (seq modes)]
      (when modes
        (.seek zip offset)
        (when-not (= 0x504b0102 (.readInt zip))
          (throw (ex-info "Invalid ZIP central directory" {:file dest :offset offset})))
        (.seek zip (+ offset 5))
        (.writeByte zip 3) ; Unix creator system
        (.seek zip (+ offset 28))
        (let [variable-size (+ (read-u16 zip) (read-u16 zip) (read-u16 zip))]
          (.seek zip (+ offset 40))
          (.writeByte zip (bit-and 255 (first modes)))
          (.writeByte zip (bit-shift-right (first modes) 8))
          (recur (+ offset 46 variable-size) (next modes)))))))

(defn zip!
  "Write a sorted ZIP with fixed local timestamps and Unix file modes."
  [dest root name]
  (let [files (vec (remove #(= :dir (nth % 2)) (entries root name #{})))
        offset (with-open [out (ZipOutputStream. (BufferedOutputStream. (io/output-stream (str dest)) 65536))]
                 (doseq [[rel path kind] files]
                   (let [entry (ZipEntry. rel)]
                     (.setTimeLocal entry (LocalDateTime/of 1980 1 2 0 0 0))
                     (.putNextEntry out entry)
                     (if (= kind :link)
                       (.write out (utf8 (archive-path (Files/readSymbolicLink path))))
                       (io/copy (fs/file path) out))
                     (.closeEntry out)))
                 (.flush out)
                 (fs/size dest))]
    (zip-modes! dest offset (map (fn [[_ path kind]] (+ (if (= kind :link) 0120000 0100000) (mode path kind))) files))
    dest))

(defn zip-list [file]
  (with-open [zip (ZipFile. (str file))]
    (mapv #(.getName %) (enumeration-seq (.entries zip)))))

(defn zip-text [file entry]
  (with-open [zip (ZipFile. (str file))]
    (with-open [in (.getInputStream zip (.getEntry zip entry))]
      (slurp in))))

(defn tar-read
  "Run a read operation with the archive on stdin so tar never parses drive letters."
  [file option & args]
  (with-open [in (io/input-stream (str file))]
    (:out (apply p/shell {:in in :out :string :shutdown nil} "tar" option "-" (map str args)))))

(defn tar-list [file]
  (str/split-lines (tar-read file "-tzf")))

(defn untar! [file dir]
  (let [option (cond
                 (str/ends-with? (str file) ".xz") "-xJf"
                 (re-find #"\.(gz|tgz|crate)$" (str file)) "-xzf"
                 :else "-xf")]
    (tar-read file option "-C" (archive-path dir))))
