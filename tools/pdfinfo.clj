;; poppler's `pdfinfo` as an oracle for a PDF's page geometry (babashka).
;;
;;     (load-file "tools/pdfinfo.clj")      ; from the repository root
;;     (emon.pdfinfo/pdfinfo root "corpus/x.pdf" {:password "pw"})
;;       => {:pages 3 :digest 1234567} or nil when poppler cannot open it
;;
;; THE DIGEST is emon's (kin/pdfdoctest.kin `dt-digest-pts`): a 23-bit rolling
;; hash, 131 * h + v modulo 8388593, over every page's MediaBox and CropBox in
;; WHOLE POINTS (round half up: pdfinfo prints two binary-rounded decimals) and
;; its rotation. One number that says every page's geometry, computed here
;; from poppler's text and never from emon. Used by kin/scripts/pdf-compare and
;; kin/scripts/pdf-p3-fixtures.
(ns emon.pdfinfo
  (:require [babashka.process :as p]))

(def MOD 8388593)

(defn pts [s]
  (let [cp (.longValue (.setScale (.multiply (bigdec s) 100M) 0 java.math.RoundingMode/HALF_UP))]
    (Math/floorDiv (+ cp 50) 100)))

(defn pdfinfo
  "Page count and geometry digest of `path` (relative to `root`, or absolute),
  or nil. Options: :password, given as both the user and the owner password."
  ([root path] (pdfinfo root path {}))
  ([root path {:keys [password]}]
   (let [r (apply p/shell {:out :string :err :string :continue true :dir root}
                  (concat ["pdfinfo" "-f" "1" "-l" "100000" "-box"]
                          (when password ["-upw" password "-opw" password])
                          [path]))
         out (:out r)
         m (re-find #"(?m)^Pages:\s+(\d+)" out)]
     (when m
       (let [n (parse-long (second m))]
         (loop [pg 1 h 0]
           (if (> pg n)
             {:pages n :digest h}
             (let [mm (re-find (re-pattern (str "(?m)^Page\\s+" pg " MediaBox:\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)")) out)
                   cc (re-find (re-pattern (str "(?m)^Page\\s+" pg " CropBox:\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)")) out)
                   rr (re-find (re-pattern (str "(?m)^Page\\s+" pg " rot:\\s+(\\d+)")) out)]
               (when (and mm cc rr)
                 (let [v (mapv pts (concat (rest mm) (rest cc)))
                       h (reduce (fn [h [j x]]
                                   (let [h (mod (+ (* h 131) (mod x MOD)) MOD)]
                                     (if (== j 7) (mod (+ (* h 131) (parse-long (second rr))) MOD) h)))
                                 h (map-indexed vector v))]
                   (recur (inc pg) h)))))))))))
