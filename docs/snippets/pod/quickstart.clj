(require '[babashka.pods :as pods])

(pods/load-pod "emon-pod") ; found on the PATH; or give the path to the binary
(spit "report.html" (:html (emon/convert "report.pdf")))
