#!/usr/bin/env bb
;; Write test/render/layers.pdf, the one-page fixture the render layers are tested on (P6.2, D127).
;;
;;     bb tools/render/mkfixture.clj
;;
;; 240 x 240 points, built by hand so every object is known:
;;     a pale blue square          marked /Artifact /Type /Background          (background layer)
;;     "Body text" in black        plain text                                  (text layer)
;;     a black ruled box           plain path                                  (ink, not text)
;;     "DRAFT" large and red       marked /Artifact /Subtype /Watermark        (watermark layer; text)
;;     "In a form" in dark green   text inside a form XObject (Do), unmarked   (text found by recursion)
;;     a green bar                 path inside that same form XObject
;; The committed PDF is the artifact; this script says how it was made.
(require '[clojure.string :as str])

(def content
  (str "/Artifact <</Type /Background>> BDC 0.85 0.9 1 rg 20 20 200 200 re f EMC\n"
       "BT /F1 18 Tf 0 g 40 190 Td (Body text) Tj ET\n"
       "0 G 2 w 40 150 160 24 re S\n"
       "/Artifact <</Type /Pagination /Subtype /Watermark>> BDC BT /F1 48 Tf 1 0 0 rg 36 80 Td (DRAFT) Tj ET EMC\n"
       "q 1 0 0 1 0 0 cm /Fm1 Do Q\n"))

(def form "BT /F1 14 Tf 0 0.4 0 rg 40 40 Td (In a form) Tj ET\n0 0.6 0 rg 40 30 120 4 re f\n")

(def objs
  [(str "<</Type /Catalog /Pages 2 0 R>>")
   (str "<</Type /Pages /Kids [3 0 R] /Count 1>>")
   (str "<</Type /Page /Parent 2 0 R /MediaBox [0 0 240 240] /Contents 4 0 R "
        "/Resources <</Font <</F1 5 0 R>> /XObject <</Fm1 6 0 R>>>>>>")
   (str "<</Length " (count content) ">>\nstream\n" content "endstream")
   "<</Type /Font /Subtype /Type1 /BaseFont /Helvetica>>"
   (str "<</Type /XObject /Subtype /Form /BBox [0 0 240 240] /Resources <</Font <</F1 5 0 R>>>> /Length "
        (count form) ">>\nstream\n" form "endstream")])

(let [sb (StringBuilder. "%PDF-1.7\n")
      offs (atom [])]
  (doseq [[i o] (map-indexed vector objs)]
    (swap! offs conj (.length sb))
    (.append sb (str (inc i) " 0 obj\n" o "\nendobj\n")))
  (let [xref (.length sb)]
    (.append sb (str "xref\n0 " (inc (count objs)) "\n0000000000 65535 f \n"))
    (doseq [o @offs] (.append sb (format "%010d 00000 n \n" o)))
    (.append sb (str "trailer\n<</Size " (inc (count objs)) " /Root 1 0 R>>\nstartxref\n" xref "\n%%EOF\n")))
  (spit "test/render/layers.pdf" (str sb))
  (println "wrote test/render/layers.pdf" (.length sb) "bytes"))
