(ns synth.assets
  "The two pictures the fusion documents refer to (DEVLOG P6.6, D209):

      bb -cp tools:tools/lib -m synth.assets      write test/corpus/synth/html/img/{wm-sample.png,bg-texture.jpg}

    wm-sample.png   the word SAMPLE, grey, turned -25 degrees, at 20 % opacity (the alpha channel carries it): the
                    picture `wm-image-behind` puts behind its text, so the PDF has a watermark with NO text-layer
                    witness;
    bg-texture.jpg  a light paper-like texture, the size of a Letter page at 96 dpi, which `bg-texture` puts under its
                    text.
  ImageMagick only, seeded (`-seed`): the same version gives the same bytes (MANIFEST.json records its version).
  Tooling, never run by a gate."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def DIR "test/corpus/synth/html/img")

(defn- magick! [& args]
  (let [r (apply p/sh "magick" args)]
    (when-not (zero? (:exit r)) (throw (ex-info (str "magick failed: " (str/join " " args) (:err r)) r)))))

(defn -main [& _]
  (.mkdirs (io/file DIR))
  (magick! "-background" "none" "-fill" "rgb(110,110,110)" "-font" "/System/Library/Fonts/Supplemental/Arial Bold.ttf" "-pointsize" "170" "label:SAMPLE"
           "-background" "none" "-rotate" "-25" "-alpha" "set" "-channel" "A" "-evaluate" "multiply" "0.2" "+channel"
           "-strip" (str "PNG32:" DIR "/wm-sample.png"))
  (magick! "-size" "816x1056" "xc:gray94" "-seed" "7" "-attenuate" "0.5" "+noise" "Gaussian" "-blur" "0x1.4"
           "-colorspace" "Gray" "-strip" "-quality" "55" (str DIR "/bg-texture.jpg"))
  (println "wrote" DIR))

(when (= *file* (System/getProperty "babashka.file")) (-main))
