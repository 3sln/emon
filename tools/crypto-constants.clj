#!/usr/bin/env bb
;; The constant tables of the Rust host's hand-written hashes (DEVLOG D097).
;; Rust's standard library has no MD5 or SHA, so host/rust/src/port.rs carries
;; them; the SHA constants are DERIVED here, exactly, from their definitions
;; (FIPS 180-4 4.2.2, 5.3.3-5.3.5) so that no table is typed from memory:
;;
;;     bb tools/crypto-constants.clj        prints the Rust arrays
;;
;; The published test vectors (kin/cryptotest.kin) are what proves the result.
(defn iroot [n k]                       ; floor(n ^ (1/k)), exact
  (loop [lo 0N hi (bigint (.shiftLeft (biginteger 1) (inc (quot (.bitLength (biginteger n)) k))))]
    (if (<= hi (inc lo))
      lo
      (let [mid (quot (+ lo hi) 2N)]
        (if (<= (.pow (biginteger mid) k) (biginteger n)) (recur mid hi) (recur lo mid))))))

(def primes
  (take 80 (filter (fn [n] (and (> n 1) (every? #(pos? (mod n %)) (range 2 (inc (long (Math/sqrt n)))))))
                   (range))))

(defn frac-root [p k bits]              ; the first `bits` fractional bits of p^(1/k)
  (let [r (iroot (bigint (.shiftLeft (biginteger p) (* k bits))) k)]
    (mod r (bigint (.shiftLeft (biginteger 1) bits)))))

(defn hex [n width] (format (str "0x%0" (quot width 4) "x") (biginteger n)))

(defn arr [nm ty vals width]
  (println (str "const " nm ": [" ty "; " (count vals) "] = ["))
  (doseq [row (partition-all 4 vals)]
    (println (str "    " (apply str (interpose ", " (map #(hex % width) row))) ",")))
  (println "];"))

(println "// SHA-256 K: cube roots of the first 64 primes, 32 bits")
(arr "SHA256_K" "u32" (map #(frac-root % 3 32) (take 64 primes)) 32)
(println "// SHA-256 H0: square roots of the first 8 primes, 32 bits")
(arr "SHA256_H" "u32" (map #(frac-root % 2 32) (take 8 primes)) 32)
(println "// SHA-512 K: cube roots of the first 80 primes, 64 bits")
(arr "SHA512_K" "u64" (map #(frac-root % 3 64) primes) 64)
(println "// SHA-512 H0: square roots of the first 8 primes, 64 bits")
(arr "SHA512_H" "u64" (map #(frac-root % 2 64) (take 8 primes)) 64)
(println "// SHA-384 H0: square roots of the 9th to 16th primes, 64 bits")
(arr "SHA384_H" "u64" (map #(frac-root % 2 64) (take 8 (drop 8 primes))) 64)
;; MD5 K is floor(2^32 * |sin(i + 1)|); a double's sine is exact enough for the
;; 32 bits kept (the RFC 1321 vectors prove all 64).
(println "// MD5 K: floor(2^32 * |sin(i + 1)|)")
(arr "MD5_K" "u32"
     (for [i (range 1 65)] (long (Math/floor (* 4294967296.0 (Math/abs (Math/sin (double i)))))))
     32)
