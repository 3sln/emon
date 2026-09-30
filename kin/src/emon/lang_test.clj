(ns emon.lang-test
  "Generation-time refusals of emon's own forms (P0.3, P0.6): what must NOT
  generate. The positive cases run in `bb check`, on four compilers."
  (:require [clojure.test :refer [deftest is run-tests]]
            [clojure.string :as str]
            [kin.project :as kp]
            [emon.project :as p]))

(defn- src [& body]
  (str "(ns emon.negprobe (:require [emon.lang :refer [I32 Text emit-ascii defenum]]"
       " [kin.lang :refer [defn return]]))\n"
       (str/join "\n" body)))

(defn- refusal
  "The message generation fails with, or nil if it generated."
  [text]
  (try (kp/generate @p/project text "kin/negprobe.kin") nil
       (catch Exception e (ex-message e))))

(defn- every-target-says? [text & needles]
  (let [m (refusal text)]
    (and m (every? #(str/includes? m %) needles))))

(deftest emit-ascii-refuses-non-ascii
  (is (every-target-says?
       (src "(defn ^:pub ^I32 f [^:mut ^Text t] (emit-ascii t \"café\") (return 0))")
       "emit-ascii" "emon.negprobe" "not printable ASCII" "U+00E9" "index 3")))

(deftest emit-ascii-refuses-control-and-del
  (is (every-target-says?
       (src "(defn ^:pub ^I32 f [^:mut ^Text t] (emit-ascii t \"a\\nb\") (return 0))")
       "emit-ascii" "U+000A"))
  (is (every-target-says?
       (src "(defn ^:pub ^I32 f [^:mut ^Text t] (emit-ascii t \"a\u007fb\") (return 0))")
       "U+007F")))

(deftest emit-ascii-wants-a-string-literal
  (is (every-target-says?
       (src "(defn ^:pub ^I32 f [^:mut ^Text t ^I32 x] (emit-ascii t x) (return 0))")
       "emit-ascii" "string literal")))

(deftest emit-ascii-accepts-printable-ascii
  (is (nil? (refusal
             (src "(defn ^:pub ^I32 f [^:mut ^Text t] (emit-ascii t \"{\\\"a\\\\b\\\": ~\") (return 0))")))))

(defn- enum [& rows]
  (src (str "(defenum ^:pub nk [" (str/join " " rows) "])")))

(deftest defenum-refuses-duplicates-by-name
  (is (every-target-says? (enum "[nk-a 1 \"a\"]" "[nk-b 1 \"b\"]")
                          "defenum" "emon.negprobe" "duplicate code 1" "nk-a" "nk-b"))
  (is (every-target-says? (enum "[nk-a 1 \"a\"]" "[nk-b 2 \"a\"]")
                          "duplicate spelling \"a\""))
  (is (every-target-says? (enum "[nk-a 1 \"a\"]" "[nk-a 2 \"b\"]")
                          "duplicate symbol nk-a")))

(deftest defenum-refuses-bad-rows
  (is (every-target-says? (enum "[nk-a 1 \"café\"]") "not printable ASCII"))
  (is (every-target-says? (enum "[nk-a 1 \"a\\\"b\"]") "quote or backslash"))
  (is (every-target-says? (enum "[nk-a \"1\" \"a\"]") "not [symbol integer-code"))
  (is (every-target-says? (enum "[nk-emit-name 1 \"a\"]") "collides")))

(deftest defenum-accepts-a-sound-table
  (is (nil? (refusal (enum "[nk-a 1 \"a\"]" "[nk-b 5 \"b\"]")))))

(let [{:keys [fail error]} (run-tests 'emon.lang-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
