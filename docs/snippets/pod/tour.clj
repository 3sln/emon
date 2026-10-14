(require '[babashka.pods :as pods])

(pods/load-pod "emon-pod") ; found on the PATH; or give the path to the binary

(prn (emon/capabilities)) ; {:version "X.Y.Z", :pdfium true, :tesseract true, :codecs false} from the release archive

;; Stage 0 alone is cheap: format, version, pages, encryption.
(prn (emon/intake "report.pdf")) ; {:format "pdf", :version "1.4", :pages 1, :encrypted false, :bytes 39570} for one sample

;; One text says what to do, limits included (docs/options.md); the third argument says what to return.
(let [r (emon/convert "report.pdf" "ocr=never\nlimit.pages=500"
                      {:model :edn :html "pretty" :assets :info})]
  (println (count (:html r)) "bytes of HTML," (count (:assets r)) "assets"))

;; A failure is an exception whose ex-data has the stable name to route on.
(try
  (emon/convert "report.pdf" "limit.pages=0")
  (catch Exception e
    (prn (ex-data e)))) ; {:name "pages", :code 3, :category "limit"}
