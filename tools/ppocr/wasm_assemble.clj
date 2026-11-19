#!/usr/bin/env bb
;; Mechanical suspension points only: keep the qualified native policy bytes frozen.
(require '[babashka.fs :as fs] '[clojure.string :as str] '[babashka.process :refer [process]])
(defn answer-body [source]
  (let [start (str/index-of source "        let mut out = Cells::new();")
        ;; Native validation uses the full request bitmap. Browser answers pass
        ;; through Session's request-relative validator after this crop builder.
        end (when start (str/index-of source "        let mut validation = Cells::new();" start))
        lock "        let mut lexicon = self.lexicon.lock().map_err(|_| fail(\"PP lexicon lock poisoned\"))?;\n"]
    (assert (and start end) "native PP answer builder anchors")
    (let [native (subs source start end)
          body (-> native
                   (str/replace "req.id()" "id")
                   (str/replace "req.field(crate::emon::plan::oc_flags())" "flags")
                   (str/replace "image.width" "width")
                   (str/replace "image.height" "height")
                   (str/replace lock "")
                   (str/replace "lexicon.valid_english_word(&word.text)" "dictionary(&word.text).await"))
          restored (-> body
                       (str/replace "(&mut out, id," "(&mut out, req.id(),")
                       (str/replace "oil_end_source(&mut out, at, flags)" "oil_end_source(&mut out, at, req.field(crate::emon::plan::oc_flags()))")
                       (str/replace "width as f64" "image.width as f64")
                       (str/replace "height as f64" "image.height as f64")
                       (str/replace "dictionary(&word.text).await" "lexicon.valid_english_word(&word.text)")
                       (str/replace "        for word in &reading.words {" (str lock "        for word in &reading.words {")))]
      (assert (= native restored) "wasm OIL answer must differ only in argument bindings and dictionary suspension")
      (assert (not (re-find #"\b(self|req|image)\." body)) "wasm answer has no unbound native host state")
      body)))

(when (= "--check-answer" (first *command-line-args*))
  (answer-body (slurp "host/rust/src/ppocr.rs"))
  (println "  ok   wasm OIL answer is the native answer with only argument bindings and dictionary suspension")
  (System/exit 0))
(def root (first *command-line-args*))
(assert root "usage: wasm_assemble.clj CRATE")
(assert (zero? (:exit @(process ["bb" "tools/ppocr/wasm_link.clj"] {:inherit true :shutdown nil}))) "wasm dictionary linker")
(let [p (str root "/src/engine.rs")
      s (slurp p)
      from "if (c.oengine == 1 || c.oengine == 2) && !cfg!(ppocr) && !crate::PPOCR_NATIVE_HOST"]
  (assert (str/includes? s from) "optional wasm engine selector anchor")
  (spit p (str/replace s from (str from " && !cfg!(ppocr_web)"))))
(doseq [name ["ppocr_image" "ppocr_ctc"]]
  (fs/copy (str "host/rust/src/" name ".rs") (str root "/src/" name ".rs") {:replace-existing true}))
(fs/copy "tools/ppocr/en-dictionary.txt" (str root "/src/ppocr_dictionary.txt") {:replace-existing true})
(let [source (slurp "host/rust/src/ppocr_pipeline.rs")
      source (str/replace source "pub fn read(" "pub async fn read(")
      source (str/replace source ")?;\n        if shape" ").await?;\n        if shape")
      source (str/replace source "self.classifier.run(input, &[1, 3, 48, w as i64])?" "self.classifier.run(input, &[1, 3, 48, w as i64]).await?")
      source (str/replace source "self.recognizer.run(input, &[1, 3, 48, w as i64])?" "self.recognizer.run(input, &[1, 3, 48, w as i64]).await?")]
  (assert (= 3 (count (re-seq #"\.await" source))) "three inference suspension points")
  (assert (= (slurp "host/rust/src/ppocr_pipeline.rs")
             (-> source (str/replace ".await" "") (str/replace "pub async fn read(" "pub fn read(")))
          "wasm pipeline must differ only at suspension points")
  (spit (str root "/src/ppocr_pipeline.rs") source))
(fs/copy "host/wasm/src/ppocr.rs" (str root "/src/ppocr_runtime.rs") {:replace-existing true})
(spit (str root "/src/lib.rs") "\nmod ppocr_image;\nmod ppocr_ctc;\nmod ppocr_pipeline;\nmod ppocr_runtime;\n" :append true)
;; Use precisely the reference's answer construction, including unknown geometry
;; fields. The lexicon call is the only additional suspension point.
(let [body (answer-body (slurp "host/rust/src/ppocr.rs"))]
  (spit (str root "/src/ppocr_answer.rs")
        (str "async fn answer(reading: crate::ppocr_pipeline::Reading, id: i32, dx: i32, dy: i32, width: usize, height: usize, flags: i32) -> Result<Vec<i32>, String> {\n"
             "use crate::port::{Cells, cells_len, cells_at};\n"
             body "Ok((0..cells_len(&out)).map(|i| cells_at(&out, i)).collect())\n}\n")))
