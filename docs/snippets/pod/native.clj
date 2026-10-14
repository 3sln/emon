(require '[babashka.pods :as pods])

(pods/load-pod "emon-pod") ; found on the PATH; or give the path to the binary

;; pdfium and the English and orientation models travel in the archive (lib/ and share/emon/tessdata beside the pod).
(prn (emon/capabilities)) ; {:version "X.Y.Z", :pdfium true, :tesseract true, :codecs false}

;; :native true brings them in: pdfium draws the pages, Tesseract reads them.
(spit "scan.html" (:html (emon/convert "scan.pdf" nil {:html true :native true})))
