;; IMAGE-INPUT MUTATORS for `bb fuzz` (DEVLOG P10.5, D700): PNG chunks, JPEG segments and TIFF directories,
;; made to say what a hostile file says. Each takes `(r bs)` and answers [bytes description].
;;
;;   PNG    IHDR fields (size, depth, colour type, interlace, method bytes), chunks dropped, doubled, moved, with a
;;          wrong length or a wrong CRC, new ancillary chunks (pHYs, tRNS, PLTE, eXIf, iCCP, zTXt, APNG's acTL
;;          fcTL fdAT, an unknown critical one), IDAT split into one-byte pieces, emptied, inflated and changed
;;   JPEG   SOFn precision, size, component count and sampling factors; DQT and DHT counts; DRI; SOS selectors and
;;          spectral bounds; JFIF densities; EXIF orientation; Adobe transform; segment lengths; unknown markers;
;;          a hundred scans; entropy data cut or flipped
;;   TIFF   byte order and magic, the IFD offset, entry counts, next-IFD loops, tags (type, count, value), the values
;;          of ImageWidth ImageLength BitsPerSample Compression Photometric StripOffsets StripByteCounts
;;          Predictor TileWidth Orientation ... and offsets past the end of the file
(ns fuzz.imgmut
  (:require [clojure.string :as str]
            [fuzz.mutate :as m]
            [fuzz.pdfobj :as po]))

(def ^:private below m/below)
(def ^:private pick m/pick)
(def ^:private between m/between)

(defn- u8 ^long [^bytes b i] (bit-and 255 (aget b (int i))))
(defn- be16 [^bytes b i] (+ (bit-shift-left (u8 b i) 8) (u8 b (inc i))))
(defn- be32 [^bytes b i] (+ (bit-shift-left (be16 b i) 16) (be16 b (+ i 2))))
(defn- le16 [^bytes b i] (+ (u8 b i) (bit-shift-left (u8 b (inc i)) 8)))
(defn- le32 [^bytes b i] (+ (le16 b i) (bit-shift-left (le16 b (+ i 2)) 16)))

(defn- be-bytes ^bytes [n w] (byte-array (for [k (range (dec w) -1 -1)] (unchecked-byte (bit-shift-right n (* 8 k))))))
(defn- le-bytes ^bytes [n w] (byte-array (for [k (range w)] (unchecked-byte (bit-shift-right n (* 8 k))))))

(defn- put ^bytes [^bytes bs at ^bytes repl]
  (if (<= (+ at (alength repl)) (alength bs)) (m/splice bs at (+ at (alength repl)) repl) bs))

(defn- big [r] (pick r [0 1 2 3 7 8 15 16 17 255 256 4095 65535 65536 16777216 2147483647 2147483648 4294967295]))

;; ------------------------------------------------------------------------- PNG

(def png-sig (byte-array (map unchecked-byte [137 80 78 71 13 10 26 10])))

(defn crc32 [^bytes bs]
  (let [c (java.util.zip.CRC32.)] (.update c bs) (.getValue c)))

(defn png-parse
  "[{:type :data}] or nil."
  [^bytes bs]
  (when (and (> (alength bs) 8) (java.util.Arrays/equals (m/sub bs 0 8) png-sig))
    (loop [i 8 acc []]
      (if (or (> (+ i 12) (alength bs)) (>= (count acc) 4000))
        acc
        (let [len (be32 bs i) end (+ i 8 len)]
          (if (> (+ end 4) (alength bs))
            acc
            (recur (+ end 4) (conj acc {:type (po/->s (m/sub bs (+ i 4) (+ i 8))) :data (m/sub bs (+ i 8) end)}))))))))

(defn png-emit ^bytes [chunks]
  (apply po/cat-bytes png-sig
         (for [{:keys [type data crc len]} chunks]
           (let [body (po/cat-bytes type data)]
             (po/cat-bytes (be-bytes (or len (alength ^bytes data)) 4) body (be-bytes (or crc (crc32 body)) 4))))))

(defn- chunk [type data] {:type type :data (if (string? data) (po/->b data) data)})

(defn- ihdr-edit [r ^bytes d]
  (let [d (if (< (alength d) 13) (po/cat-bytes d (byte-array (- 13 (alength d)))) d)]
    (case (int (below r 7))
      0 (put d 0 (be-bytes (big r) 4))
      1 (put d 4 (be-bytes (big r) 4))
      2 (put d 8 (byte-array [(unchecked-byte (pick r [0 1 2 3 4 5 7 8 15 16 17 32 255]))]))
      3 (put d 9 (byte-array [(unchecked-byte (pick r [0 1 2 3 4 5 6 7 8 255]))]))
      4 (put d 10 (byte-array [(unchecked-byte (pick r [1 2 255]))]))
      5 (put d 11 (byte-array [(unchecked-byte (pick r [1 2 255]))]))
      6 (put d 12 (byte-array [(unchecked-byte (pick r [2 3 255]))])))))

(defn- exif-blob [r]
  (let [orient (pick r [0 1 2 3 4 5 6 7 8 9 255 65535])]
    (case (int (below r 4))
      0 (po/cat-bytes "MM" (be-bytes 42 2) (be-bytes 8 4) (be-bytes 1 2) (be-bytes 274 2) (be-bytes 3 2) (be-bytes 1 4) (be-bytes orient 2) (byte-array 2) (be-bytes 0 4))
      1 (po/cat-bytes "II" (le-bytes 42 2) (le-bytes 8 4) (le-bytes 1 2) (le-bytes 274 2) (le-bytes 3 2) (le-bytes 1 4) (le-bytes orient 2) (byte-array 2) (le-bytes 8 4))
      2 (po/cat-bytes "II" (le-bytes 42 2) (le-bytes 8 4) (le-bytes 65535 2) (byte-array 40))
      3 (po/cat-bytes "MM" (be-bytes 42 2) (be-bytes 4294967295 4) (byte-array 20)))))

(defn- new-ancillary [r]
  (case (int (below r 14))
    0 (chunk "pHYs" (po/cat-bytes (be-bytes (big r) 4) (be-bytes (big r) 4) (byte-array [(unchecked-byte (pick r [0 1 2 255]))])))
    1 (chunk "tRNS" (byte-array (pick r [0 1 2 3 6 255 300])))
    2 (chunk "PLTE" (byte-array (pick r [0 1 2 3 4 255 256 767 768 771 1000])))
    3 (chunk "gAMA" (be-bytes (big r) 4))
    4 (chunk "cHRM" (byte-array (pick r [0 8 31 32 33])))
    5 (chunk "sRGB" (byte-array [(unchecked-byte (pick r [0 1 4 255]))]))
    6 (chunk "iCCP" (po/cat-bytes "name" (byte-array [0 0]) (byte-array (pick r [0 10 100]))))
    7 (chunk "eXIf" (exif-blob r))
    8 (chunk "tEXt" (po/cat-bytes "Key" (byte-array [0]) (apply str (repeat (pick r [0 10 5000]) "v"))))
    9 (chunk "zTXt" (po/cat-bytes "Key" (byte-array [0 0]) (byte-array (pick r [0 3 100]))))
    10 (chunk "acTL" (po/cat-bytes (be-bytes (big r) 4) (be-bytes (big r) 4)))
    11 (chunk "fcTL" (po/cat-bytes (be-bytes (big r) 4) (byte-array 25)))
    12 (chunk "ZZZZ" (byte-array (pick r [0 4 100])))
    13 (chunk (pick r ["bKGD" "sBIT" "hIST" "sPLT" "tIME" "iTXt" "oFFs" "sCAL" "pCAL" "fdAT" "IHDR" "IEND" "IDAT"]) (byte-array (pick r [0 1 3 7 13 100])))))

(defn png-mutate [r ^bytes bs]
  (when-let [cs (png-parse bs)]
    (let [n (count cs) idat (vec (keep-indexed (fn [i c] (when (= "IDAT" (:type c)) i)) cs))]
      (case (int (below r 15))
        0 (let [i (first (keep-indexed (fn [i c] (when (= "IHDR" (:type c)) i)) cs))]
            (when i [(png-emit (update-in cs [i :data] #(ihdr-edit r %))) "png IHDR field"]))
        1 (when (> n 1) (let [i (below r n)] [(png-emit (into (subvec cs 0 i) (subvec cs (inc i)))) (str "png chunk " (:type (nth cs i)) " dropped")]))
        2 (let [i (below r n)] [(png-emit (into (conj (subvec cs 0 i) (nth cs i)) (subvec cs i))) (str "png chunk " (:type (nth cs i)) " doubled")])
        3 (let [i (below r n) j (below r n)] [(png-emit (assoc cs i (nth cs j) j (nth cs i))) (str "png chunks " i " and " j " swapped")])
        4 (let [i (below r n)] [(png-emit (assoc-in cs [i :len] (pick r [0 1 4294967295 2147483647 (inc (alength ^bytes (:data (nth cs i)))) (max 0 (dec (alength ^bytes (:data (nth cs i)))))]))) (str "png chunk " i " length")])
        5 (let [i (below r n)] [(png-emit (assoc-in cs [i :crc] (pick r [0 1 4294967295]))) (str "png chunk " i " crc")])
        6 (let [i (below r (inc n)) c (new-ancillary r)] [(png-emit (into (conj (subvec cs 0 i) c) (subvec cs i))) (str "png insert " (:type c) "@" i)])
        7 (when (seq idat)
            (let [i (pick r idat) d ^bytes (:data (nth cs i))]
              [(png-emit (into (subvec cs 0 i) (concat (for [k (range (alength d))] (chunk "IDAT" (m/sub d k (inc k)))) (subvec cs (inc i))))) "png IDAT split to bytes"]))
        8 (when (seq idat)
            (let [i (pick r idat)] [(png-emit (assoc-in cs [i :data] (byte-array 0))) "png IDAT emptied"]))
        9 (when (seq idat)
            (let [i (pick r idat) d (po/inflate (:data (nth cs i)) 8000000)]
              (when d
                (let [out (aclone ^bytes d)]
                  (dotimes [_ (between r 1 6)] (when (pos? (alength out)) (aset-byte out (below r (alength out)) (unchecked-byte (below r 256)))))
                  [(png-emit (assoc-in cs [i :data] (po/deflate (case (int (below r 3)) 0 out 1 (m/sub out 0 (below r (inc (alength out)))) 2 (po/cat-bytes out (byte-array (below r 200))))))) "png IDAT inflated, changed, deflated"]))))
        10 (when (seq idat)
             (let [i (pick r idat) d ^bytes (:data (nth cs i))]
               [(png-emit (assoc-in cs [i :data] (m/sub d 0 (below r (inc (alength d)))))) "png IDAT cut"]))
        11 [(po/cat-bytes (png-emit cs) (byte-array (below r 300))) "png trailing bytes"]
        12 (let [out (aclone bs)] (aset-byte out (below r 8) (unchecked-byte (below r 256))) [out "png signature"])
        13 (let [i (below r n)] [(png-emit (subvec cs 0 i)) (str "png cut before chunk " i)])
        14 (let [i (first (keep-indexed (fn [i c] (when (= "IEND" (:type c)) i)) cs))]
             (when i [(png-emit (into (subvec cs i) (subvec cs 0 i))) "png IEND first"]))))))

;; ------------------------------------------------------------------------ JPEG

(defn jpeg-parse
  "{:segs [{:m marker :data bytes}] :tail bytes} or nil. A segment's :data is what follows its length field
   (an SOS also carries its entropy data in :scan)."
  [^bytes bs]
  (when (and (> (alength bs) 4) (= 255 (u8 bs 0)) (= 216 (u8 bs 1)))
    (let [n (alength bs)]
      (loop [i 2 segs []]
        (cond
          (>= (count segs) 3000) {:segs segs :tail (byte-array 0)}
          (or (>= (+ i 1) n) (not= 255 (u8 bs i))) {:segs segs :tail (m/sub bs (min i n) n)}
          :else
          (let [mk (u8 bs (inc i))]
            (cond
              (= mk 255) (recur (inc i) segs)
              (or (= mk 216) (= mk 1) (<= 208 mk 215) (= mk 217)) (recur (+ i 2) (conj segs {:m mk :data (byte-array 0) :nolen true}))
              (> (+ i 4) n) {:segs segs :tail (m/sub bs i n)}
              :else
              (let [len (be16 bs (+ i 2)) end (min n (+ i 2 (max 2 len)))
                    data (m/sub bs (+ i 4) end)]
                (if (= mk 218)
                  (let [stop (loop [j end]
                               (cond (>= (inc j) n) n
                                     (and (= 255 (u8 bs j)) (let [x (u8 bs (inc j))] (not (or (= x 0) (<= 208 x 215))))) j
                                     :else (recur (inc j))))]
                    (recur stop (conj segs {:m mk :data data :scan (m/sub bs end stop)})))
                  (recur end (conj segs {:m mk :data data})))))))))))

(defn jpeg-emit ^bytes [{:keys [segs tail]}]
  (apply po/cat-bytes (byte-array [255 216])
         (concat
          (for [{:keys [m data scan nolen len]} segs]
            (if nolen
              (byte-array [255 (unchecked-byte m)])
              (po/cat-bytes (byte-array [255 (unchecked-byte m)]) (be-bytes (or len (+ 2 (alength ^bytes data))) 2) data (or scan (byte-array 0)))))
          [tail])))

(defn- seg-idx [jp pred] (vec (keep-indexed (fn [i s] (when (pred s) i)) (:segs jp))))

(defn- patch [jp i at ^bytes repl]
  (update-in jp [:segs i :data] #(let [d ^bytes %] (if (<= (+ at (alength repl)) (alength d)) (put d at repl) d))))

(defn jpeg-mutate [r ^bytes bs]
  (when-let [jp (jpeg-parse bs)]
    (let [segs (:segs jp) n (count segs)
          sof (seg-idx jp #(<= 192 (:m %) 207 ) )
          sof (filterv #(not (#{196 200 204} (:m (nth segs %)))) sof)
          dqt (seg-idx jp #(= 219 (:m %))) dht (seg-idx jp #(= 196 (:m %)))
          sos (seg-idx jp #(= 218 (:m %))) app (seg-idx jp #(<= 224 (:m %) 239))]
      (case (int (below r 18))
        0 (when (seq sof) (let [i (pick r sof)] [(jpeg-emit (patch jp i 0 (byte-array [(unchecked-byte (pick r [0 1 2 4 7 9 12 16 255]))]))) "jpeg SOF precision"]))
        1 (when (seq sof) (let [i (pick r sof) v (pick r [0 1 2 255 256 4096 32767 32768 65535])]
                            [(jpeg-emit (patch jp i (pick r [1 3]) (be-bytes v 2))) (str "jpeg SOF size " v)]))
        2 (when (seq sof) (let [i (pick r sof) v (pick r [0 1 2 3 4 5 6 255])]
                            [(jpeg-emit (patch jp i 5 (byte-array [(unchecked-byte v)]))) (str "jpeg SOF components " v)]))
        3 (when (seq sof) (let [i (pick r sof) k (below r 4) v (pick r [0 1 2 3 4 5 8 15 16 17 255 0x11 0x22 0x12 0x21 0x41 0x14 0x44])]
                            [(jpeg-emit (patch jp i (+ 7 (* 3 k)) (byte-array [(unchecked-byte v)]))) (str "jpeg SOF sampling " v)]))
        4 (when (seq sof) (let [i (pick r sof) mk (pick r [192 193 194 195 197 198 199 201 202 203 205 206 207])]
                            [(jpeg-emit (assoc-in jp [:segs i :m] mk)) (str "jpeg SOF marker " mk)]))
        5 (when (seq dqt) (let [i (pick r dqt) d ^bytes (:data (nth segs i))]
                            (when (pos? (alength d))
                              [(jpeg-emit (patch jp i (below r (alength d)) (byte-array [(unchecked-byte (pick r [0 1 255 128]))]))) "jpeg DQT value"])))
        6 (when (seq dht) (let [i (pick r dht) d ^bytes (:data (nth segs i))]
                            (when (> (alength d) 17)
                              [(jpeg-emit (patch jp i (between r 1 16) (byte-array [(unchecked-byte (pick r [0 1 2 100 255]))]))) "jpeg DHT count"])))
        7 [(jpeg-emit (update jp :segs #(let [i (below r (inc (count %)))] (into (conj (subvec % 0 i) {:m 221 :data (be-bytes (pick r [0 1 2 3 255 65535]) 2)}) (subvec % i))))) "jpeg DRI inserted"]
        8 (when (seq sos) (let [i (pick r sos) d ^bytes (:data (nth segs i))]
                            (when (pos? (alength d))
                              [(jpeg-emit (patch jp i (below r (alength d)) (byte-array [(unchecked-byte (pick r [0 1 2 3 4 63 64 255]))]))) "jpeg SOS header"])))
        9 (when (seq app) (let [i (pick r app) d ^bytes (:data (nth segs i))]
                            (when (pos? (alength d))
                              [(jpeg-emit (patch jp i (below r (alength d)) (byte-array [(unchecked-byte (below r 256))]))) "jpeg APPn byte"])))
        10 (let [mk (pick r [224 225 226 238 254 240 253 1 2 100 192])]
             [(jpeg-emit (update jp :segs #(into [(first %)] (cons {:m mk :data (case (int (below r 4))
                                                                              0 (po/cat-bytes "JFIF" (byte-array [0 1 2 (pick r [0 1 2 255]) 255 255 255 255 0 0]))
                                                                              1 (po/cat-bytes "Exif" (byte-array 2) (exif-blob r))
                                                                              2 (po/cat-bytes "Adobe" (byte-array [0 100 0 0 0 0 (pick r [0 1 2 255])]))
                                                                              3 (byte-array (below r 200)))} (rest %)))))
              (str "jpeg insert segment " mk)])
        11 (let [i (below r n)] [(jpeg-emit (assoc-in jp [:segs i :len] (pick r [0 1 2 3 65535 (below r 400)]))) (str "jpeg segment " i " length")])
        12 (when (> n 2) (let [i (below r n)] [(jpeg-emit (update jp :segs #(into (subvec % 0 i) (subvec % (inc i))))) (str "jpeg segment " (:m (nth segs i)) " dropped")]))
        13 (let [i (below r n)] [(jpeg-emit (update jp :segs #(into (conj (subvec % 0 i) (nth % i)) (subvec % i)))) (str "jpeg segment " (:m (nth segs i)) " doubled")])
        14 (when (seq sos) (let [i (pick r sos) k (pick r [5 100 400])]
                             [(jpeg-emit (update jp :segs #(into (subvec % 0 i) (concat (repeat k (nth % i)) (subvec % i))))) (str "jpeg scan x" k)]))
        15 (when (seq sos) (let [i (pick r sos) sc ^bytes (:scan (nth segs i))]
                             [(jpeg-emit (assoc-in jp [:segs i :scan] (case (int (below r 3)) 0 (m/sub sc 0 (below r (inc (alength sc)))) 1 (byte-array (alength sc) (unchecked-byte 255)) 2 (po/cat-bytes sc (byte-array [255 208 255 217 255 0]) sc)))) "jpeg entropy data"]))
        16 [(jpeg-emit (update jp :segs #(subvec % 0 (below r (inc (count %)))))) "jpeg segments cut"]
        17 [(jpeg-emit (assoc jp :tail (byte-array (below r 500)))) "jpeg tail"]))))


;; ------------------------------------------------------------------------ TIFF

(def tiff-tags [254 255 256 257 258 259 262 263 266 269 273 274 277 278 279 282 283 284 292 293 296 305 317 320 322 323 324 325 338 339 347 34665 34675])

(defn tiff-layout
  "{:be :ifds [{:at :count :entries [{:at :tag :type :cnt :val-at}]}]} or nil: the chain of directories, at most 16."
  [^bytes bs]
  (let [n (alength bs)]
    (when (and (> n 8) (or (and (= 73 (u8 bs 0)) (= 73 (u8 bs 1))) (and (= 77 (u8 bs 0)) (= 77 (u8 bs 1)))))
      (let [be (= 77 (u8 bs 0)) r16 (if be be16 le16) r32 (if be be32 le32)]
        (loop [at (r32 bs 4) acc [] seen #{}]
          (if (or (< at 8) (> (+ at 2) n) (seen at) (>= (count acc) 16))
            {:be be :ifds acc}
            (let [c (r16 bs at)
                  ents (vec (for [k (range (min c 200)) :let [e (+ at 2 (* 12 k))] :while (<= (+ e 12) n)]
                              {:at e :tag (r16 bs e) :type (r16 bs (+ e 2)) :cnt (r32 bs (+ e 4)) :val-at (+ e 8)}))
                  nx (+ at 2 (* 12 c))
                  nxt (if (<= (+ nx 4) n) (r32 bs nx) 0)]
              (recur nxt (conj acc {:at at :count c :entries ents :next-at nx}) (conj seen at)))))))))

(defn tiff-mutate [r ^bytes bs]
  (when-let [{:keys [be ifds]} (tiff-layout bs)]
    (let [n (alength bs) w16 (if be #(be-bytes % 2) #(le-bytes % 2)) w32 (if be #(be-bytes % 4) #(le-bytes % 4))
          offv (fn [] (pick r [0 1 4 8 (dec n) n (inc n) (+ n 4096) 2147483647 4294967295 (below r (inc n))]))
          ents (vec (mapcat :entries ifds))
          ent-of (fn [tag] (first (filter #(= tag (:tag %)) ents)))]
      (case (int (below r 14))
        0 (let [out (aclone bs)] (case (int (below r 5))
                                   0 (do (aset-byte out 0 (aget bs 1)) [out "tiff byte order half"])
                                   1 (do (aset-byte out 1 (aget bs 0)) [out "tiff byte order"])
                                   2 [(put bs 2 (w16 (pick r [0 41 43 0x2a00 255 65535]))) "tiff magic"]
                                   3 [(put bs 4 (w32 (offv))) "tiff IFD0 offset"]
                                   4 [(put bs (below r 8) (byte-array [(unchecked-byte (below r 256))])) "tiff header byte"]))
        1 (let [d (pick r ifds)] [(put bs (:at d) (w16 (pick r [0 1 2 (inc (:count d)) (max 0 (dec (:count d))) 255 4096 65535]))) "tiff entry count"])
        2 (let [d (pick r ifds)] [(put bs (:next-at d) (w32 (pick r [0 8 (:at d) (:at (first ifds)) (offv)]))) "tiff next-IFD offset"])
        3 (when (seq ents) (let [e (pick r ents)] [(put bs (:at e) (w16 (pick r tiff-tags))) (str "tiff tag " (:tag e) " renamed")]))
        4 (when (seq ents) (let [e (pick r ents)] [(put bs (+ 2 (:at e)) (w16 (pick r [0 1 2 3 4 5 6 7 8 9 10 11 12 13 14 16 255 65535]))) (str "tiff tag " (:tag e) " type")]))
        5 (when (seq ents) (let [e (pick r ents)] [(put bs (+ 4 (:at e)) (w32 (pick r [0 1 2 3 4 (inc (:cnt e)) (max 0 (dec (:cnt e))) 65535 16777216 2147483647 4294967295]))) (str "tiff tag " (:tag e) " count")]))
        6 (when (seq ents) (let [e (pick r ents)] [(put bs (:val-at e) (w32 (offv))) (str "tiff tag " (:tag e) " value")]))
        7 (let [[tag vals] (pick r [[256 [0 1 65535 2147483647 4294967295]] [257 [0 1 65535 2147483647 4294967295]]
                                    [258 [0 1 2 4 7 8 9 16 17 32 64 255 65535]] [259 [0 1 2 3 4 5 6 7 8 32766 32773 32946 99 65535]]
                                    [262 [0 1 2 3 4 5 6 8 9 32803 34892 255 65535]] [277 [0 1 2 3 4 5 8 255 65535]]
                                    [278 [0 1 2 65535 2147483647 4294967295]] [284 [0 1 2 3 255]] [317 [0 1 2 3 4 5 255]]
                                    [322 [0 1 15 16 17 65535 2147483647]] [323 [0 1 15 16 17 65535 2147483647]] [274 [0 1 2 3 4 5 6 7 8 9 255 65535]]
                                    [338 [0 1 2 3 255]] [339 [0 1 2 3 4 255]] [296 [0 1 2 3 4 255]] [266 [0 1 2 3 255]] [292 [0 1 2 3 4 255]] [293 [0 1 2 3 4 255]]])]
            (when-let [e (ent-of tag)]
              (let [v (pick r vals)]
                [(put bs (:val-at e) (if (<= (:type e) 3) (if (= (:type e) 3) (po/cat-bytes (w16 v) (byte-array 2)) (w32 v)) (w32 v))) (str "tiff " tag " = " v)])))
        8 (let [e (or (ent-of 273) (ent-of 324))]
            (when e [(put bs (:val-at e) (w32 (offv))) "tiff strip/tile offsets"]))
        9 (let [e (or (ent-of 279) (ent-of 325))]
            (when e [(put bs (:val-at e) (w32 (pick r [0 1 2 (dec n) n 65535 2147483647 4294967295]))) "tiff strip/tile byte counts"]))
        10 (let [e (or (ent-of 273) (ent-of 279) (ent-of 324) (ent-of 325))]
             (when (and e (> (:cnt e) 2))
               (let [off ((if be be32 le32) bs (:val-at e)) at (+ off (* 4 (below r (min (:cnt e) 8))))]
                 (when (<= (+ at 4) n) [(put bs at (w32 (offv))) "tiff strip array element"]))))
        11 (let [e (or (ent-of 282) (ent-of 283))]
             (when e (let [off ((if be be32 le32) bs (:val-at e))]
                       (when (<= (+ off 8) n) [(put bs off (po/cat-bytes (w32 (pick r [0 1 72 2147483647])) (w32 (pick r [0 1 2 4294967295])))) "tiff resolution rational"]))))
        12 [(m/sub bs 0 (+ 8 (below r (max 1 (- n 8))))) "tiff cut"]
        13 (let [d (pick r ifds) k (pick r [1 5 40])
                 extra (apply po/cat-bytes (repeat k (po/cat-bytes (w16 (pick r tiff-tags)) (w16 (pick r [1 2 3 4 5 6 7 8 9 10 11 12 13])) (w32 (pick r [1 2 100 4294967295])) (w32 (offv)))))]
             [(m/splice bs (+ (:at d) 2 (* 12 (:count d))) (+ (:at d) 2 (* 12 (:count d))) extra) "tiff junk entries spliced in"])))))

;; ------------------------------------------------------------------------ both

(defn kind-of [^bytes bs]
  (let [n (alength bs)]
    (cond (and (>= n 4) (= 137 (u8 bs 0)) (= 80 (u8 bs 1))) :png
          (and (>= n 3) (= 255 (u8 bs 0)) (= 216 (u8 bs 1))) :jpeg
          (and (>= n 4) (or (and (= 73 (u8 bs 0)) (= 73 (u8 bs 1))) (and (= 77 (u8 bs 0)) (= 77 (u8 bs 1))))) :tiff)))

(defn structured
  "One structure-aware mutation of an image of a known format, or nil."
  [r ^bytes bs]
  (case (kind-of bs)
    :png (png-mutate r bs)
    :jpeg (jpeg-mutate r bs)
    :tiff (tiff-mutate r bs)
    nil))
