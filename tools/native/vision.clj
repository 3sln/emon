#!/usr/bin/env bb
;; The Apple Vision shim, compiled for an Apple triple.   bb tools/native/vision.clj --triple T   (K8, D900)
;;
;; host/vision/emon_vision.m is the whole of the Objective-C of `libemon_ocr_vision`: it hands Vision a bitmap and returns plain C rows (host/vision/emon_vision.h). This builds it for one of
;; the four Apple triples (aarch64-apple-darwin, x86_64-apple-darwin, aarch64-apple-ios, aarch64-apple-ios-sim) with the Xcode on this machine, the deployment targets of
;; tools/native/native.edn, into `.cache/vision/<T>/`:
;;     libemon_vision_shim.a    the shim's object code
;;     link.rustflags           the rustc arguments that link it with the system frameworks (Vision, Foundation, CoreGraphics, ImageIO) and `-l objc`
;; and prints that directory. The Rust adapter (host/rust/src/vision.rs, feature `vision`) declares the shim's functions itself and takes these flags; `bb package` (macOS) and
;; `bb mobile` (iOS) run this first. Nothing here touches the network, and nothing but system frameworks is linked: no Swift, no third-party code.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.edn :as edn] '[clojure.string :as str])

(def root (str (fs/canonicalize ".")))
(def args (vec *command-line-args*))
(defn opt [f] (second (drop-while #(not= f %) args)))
(def pins (edn/read-string (slurp (str root "/tools/native/native.edn"))))
(defn die [& xs] (binding [*out* *err*] (println (str "FAIL " (apply str xs)))) (System/exit 1))

(def triple (or (opt "--triple") (die "usage: bb tools/native/vision.clj --triple T (aarch64-apple-darwin, x86_64-apple-darwin, aarch64-apple-ios, aarch64-apple-ios-sim)")))
(def cfg (or (get-in pins [:triples triple]) (die "no such triple in tools/native/native.edn: " triple)))
(when-not (#{:mac :ios} (:os cfg)) (die triple " is not an Apple triple"))
(def ios? (= :ios (:os cfg)))
(def sdk (if ios? (:sdk cfg) "macosx"))
(def target-flag
  (cond (not ios?) (str "-mmacosx-version-min=" (:osx-deployment cfg))
        (= "iphoneos" sdk) (str "-miphoneos-version-min=" (:ios-deployment cfg))
        :else (str "-mios-simulator-version-min=" (:ios-deployment cfg))))
(def out (str (or (opt "--out") (str root "/.cache/vision")) "/" triple))
(def src (str root "/host/vision/emon_vision.m"))

(defn sh [& cmd]
  (let [r (apply p/shell {:out :string :err :string :continue true} (map str cmd))]
    (when-not (zero? (:exit r)) (die (str/join " " (take 3 cmd)) " failed (exit " (:exit r) ")\n" (:out r) (:err r)))
    (:out r)))

(fs/create-dirs out)
(let [obj (str out "/emon_vision.o") lib (str out "/libemon_vision_shim.a")
      newest (apply max (map #(fs/file-time->millis (fs/last-modified-time %)) [src (str root "/host/vision/emon_vision.h")]))]
  (when-not (and (fs/exists? lib) (> (fs/file-time->millis (fs/last-modified-time lib)) newest))
    (sh "xcrun" "--sdk" sdk "clang" "-arch" (:arch cfg) target-flag "-fobjc-arc" "-O2" "-g0" "-Wall" "-Wextra" "-Werror" "-c" src "-o" obj)
    (fs/delete-if-exists lib)
    (sh "xcrun" "--sdk" sdk "ar" "rcs" lib obj))
  ;; LINK ARGUMENTS, not `-l`: they apply to the final link only, so a RUSTFLAGS build of a whole crate graph (the CLI) does not bundle a copy of the shim into every dependency
  (spit (str out "/link.rustflags")
        (str/join " " (concat ["-C" (str "link-arg=" lib)]
                              (mapcat (fn [f] ["-C" "link-arg=-framework" "-C" (str "link-arg=" f)]) ["Vision" "Foundation" "CoreGraphics" "ImageIO"])
                              ["-C" "link-arg=-lobjc"]))))
(println out)
