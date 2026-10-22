(ns calibration.fit-test
  (:require [calibration.calibration :as fit]
            [clojure.test :refer [deftest is run-tests]]))

(deftest laplace-smoothing-must-not-break-isotonic-order
  ;; A large, nearly perfect group followed by one perfect observation.
  ;; Smoothing AFTER PAV used to turn 0.95,1.0 into 0.941,0.667.
  (let [rows (concat (repeat 95 {:conf 500 :y 1000})
                     (repeat 5 {:conf 500 :y 0})
                     [{:conf 900 :y 1000}])
        steps (#'fit/fit-points rows)]
    (is (apply <= (map second steps)))))

(let [{:keys [fail error]} (run-tests)]
  (when (pos? (+ fail error)) (System/exit 1)))
