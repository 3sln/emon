;; SigV4 against AWS's published vectors, and the .env parser.   bb corpus-test
;; Not a gate (DEVLOG D077): it is pure, but the round trip beside it needs MinIO.
(ns corpus-test
  (:require [clojure.test :refer [deftest is run-tests]]
            [corpus :as c]))

(def empty-hash (c/sha256-hex (byte-array 0)))

(defn auth [req] (c/authorization (merge {:region "us-east-1" :service "service" :payload-hash empty-hash
                                           :access-key "AKIDEXAMPLE" :secret-key "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"
                                           :amz-date "20150830T123600Z"} req)))

(defn sig [a] (second (re-find #"Signature=([0-9a-f]+)" a)))

;; aws-sig-v4-test-suite: get-vanilla
(deftest get-vanilla
  (is (= "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-date, Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"
         (auth {:method "GET" :path "/" :query [] :headers {"host" "example.amazonaws.com" "x-amz-date" "20150830T123600Z"}}))))

;; aws-sig-v4-test-suite: get-vanilla-query-order-key-case (query sorting)
(deftest get-vanilla-query-order
  (is (= "b97d918cfa904a5beff61c982a1b6f458b799221646efd99d3219ec94cdf2500"
         (sig (auth {:method "GET" :path "/" :query [["Param2" "value2"] ["Param1" "value1"]]
                     :headers {"host" "example.amazonaws.com" "x-amz-date" "20150830T123600Z"}})))))

;; AWS S3 docs, "GET Object" with a Range header
(deftest s3-get-object
  (is (= "f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"
         (sig (auth {:method "GET" :path "/test.txt" :query [] :service "s3" :amz-date "20130524T000000Z"
                     :access-key "AKIAIOSFODNN7EXAMPLE" :secret-key "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
                     :headers {"host" "examplebucket.s3.amazonaws.com" "range" "bytes=0-9"
                               "x-amz-content-sha256" empty-hash "x-amz-date" "20130524T000000Z"}})))))

(deftest canonical-encoding
  (is (= "a%20b/c~d%2Be" (c/encode-path "a b/c~d+e")))
  (is (= "k=a%20b&k=a%2Fb" (c/canonical-query [["k" "a/b"] ["k" "a b"]]))))

(deftest env-parser
  (is (= {"A" "1" "B" "two words" "C" "x" "D" "y=z"}
         (c/parse-env "# c\nA=1\nexport B=\"two words\"\n\nC='x'\nD=y=z # note\n"))))

(let [{:keys [fail error]} (run-tests 'corpus-test)]
  (System/exit (if (zero? (+ fail error)) 0 1)))
