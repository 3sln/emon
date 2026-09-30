(ns pyrandom
  "CPython's random.Random (MT19937), seeded by an int, for the tooling ports: the same seed gives the
  same stream as Python 3 for random(), getrandbits(k<=32), randint, randrange(n), choice, uniform.
  A generator is a mutable map {:mt long-array :i atom}; use the fns below."
  )

(def ^:private N 624)
(def ^:private M 397)
(def ^:private mask 0xffffffff)

(defn- u32 [x] (bit-and x mask))

(defn- init-genrand [^longs mt s]
  (aset mt 0 (u32 s))
  (doseq [i (range 1 N)]
    (let [p (aget mt (dec i))]
      (aset mt i (u32 (+ (* 1812433253 (bit-xor p (unsigned-bit-shift-right p 30))) i))))))

(defn- init-by-array [^longs mt key]
  (init-genrand mt 19650218)
  (let [kl (count key)
        key (vec key)]
    (loop [k (max N kl) i 1 j 0]
      (if (pos? k)
        (let [p (aget mt (dec i))
              v (u32 (+ (bit-xor (aget mt i) (u32 (* (bit-xor p (unsigned-bit-shift-right p 30)) 1664525)))
                        (nth key j) j))
              _ (aset mt i v)
              i (inc i) j (inc j)
              [i] (if (>= i N) (do (aset mt 0 (aget mt (dec N))) [1]) [i])
              j (if (>= j kl) 0 j)]
          (recur (dec k) i j))
        (loop [k (dec N) i i]
          (if (pos? k)
            (let [p (aget mt (dec i))
                  v (u32 (- (bit-xor (aget mt i) (u32 (* (bit-xor p (unsigned-bit-shift-right p 30)) 1566083941))) i))
                  _ (aset mt i v)
                  i (inc i)
                  i (if (>= i N) (do (aset mt 0 (aget mt (dec N))) 1) i)]
              (recur (dec k) i))
            (aset mt 0 0x80000000)))))))

(defn make
  "random.Random(seed) for an int seed below 2^32 in magnitude (all the tooling uses; multi-word seeds are NOT verified)."
  [seed]
  (let [mt (long-array N)
        a (biginteger (Math/abs (long seed)))
        key (if (zero? a) [0]
                (loop [x a out []]
                  (if (zero? (.signum x)) out
                      (recur (.shiftRight x 32) (conj out (.longValue (.and x (biginteger mask))))))))]
    (init-by-array mt key)
    {:mt mt :i (atom N)}))

(defn- gen32 [{:keys [^longs mt i]}]
  (when (>= @i N)
    (doseq [kk (range N)]
      (let [y (bit-or (bit-and (aget mt kk) 0x80000000) (bit-and (aget mt (mod (inc kk) N)) 0x7fffffff))
            v (bit-xor (aget mt (mod (+ kk M) N)) (unsigned-bit-shift-right y 1)
                       (if (odd? y) 0x9908b0df 0))]
        (aset mt kk (u32 v))))
    (reset! i 0))
  (let [y (aget mt @i)
        _ (swap! i inc)
        y (bit-xor y (unsigned-bit-shift-right y 11))
        y (bit-xor y (bit-and (u32 (bit-shift-left y 7)) 0x9d2c5680))
        y (bit-xor y (bit-and (u32 (bit-shift-left y 15)) 0xefc60000))
        y (bit-xor y (unsigned-bit-shift-right y 18))]
    (u32 y)))

(defn random-double [r]
  (let [a (unsigned-bit-shift-right (gen32 r) 5)
        b (unsigned-bit-shift-right (gen32 r) 6)]
    (/ (+ (* a 67108864.0) b) 9007199254740992.0)))

(defn getrandbits [r k]
  (unsigned-bit-shift-right (gen32 r) (- 32 k)))

(defn randbelow [r n]
  (if (zero? n) 0
      (let [k (- 64 (Long/numberOfLeadingZeros n))]
        (loop [x (getrandbits r k)]
          (if (>= x n) (recur (getrandbits r k)) x)))))

(defn randint [r lo hi] (+ lo (randbelow r (- (inc hi) lo))))
(defn choice [r coll] (nth coll (randbelow r (count coll))))
(defn uniform [r a b] (+ a (* (- b a) (random-double r))))
