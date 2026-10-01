#!/usr/bin/env bb
;; The hand-written samples of right-to-left text with numbers and punctuation, for kin/bidi*test.kin (D162).
;;
;;     bb kin/scripts/bidi-samples.clj            write test/bidi/samples.txt
;;     bb kin/scripts/bidi-samples.clj --check    fail when the committed file is not what this writes
;;
;; Each sample is what a reader SEES in a right-to-left paragraph, left to right on the page, written as tokens (a
;; word of Hebrew or Arabic is written in reading order and reversed here, letter by letter; everything else is
;; as it is on the page), and the LOGICAL text, typed by hand. A row is `shown <TAB> logical <TAB> why`, UTF-8. The
;; expected orders come from UAX #9's rules as a person applies them (the rule is named in each row), not from emon.
(require '[clojure.string :as str] '[clojure.java.io :as io])

(defn rev [s] (apply str (reverse s)))
(defn v [& toks] (apply str (map #(if (vector? %) (rev (first %)) %) toks)))

(def samples
  [;; [visual tokens, logical, why]
   [(v ["בגליל"] " " ",2026" " " ["שנת"]) "שנת 2026, בגליל" "a comma after a European number: N1 puts it on the right-to-left side, so it sits at the number's LEFT"]
   [(v ["בשנה"] " " "50%" " " ["של"] " " ["ירידה"]) "ירידה של 50% בשנה" "W5: the percent sign beside a European number is part of it, so 50% reads left to right"]
   [(v ["בעיר"] " " "12/05/2026-" ["ב"] " " ["נולד"]) "נולד ב-12/05/2026 בעיר" "W4: slashes between numbers are numbers; the hyphen before the date is a neutral between R and EN: N1"]
   [(v ["שקלים"] " " "1,250.50" " " ["מחיר"]) "מחיר 1,250.50 שקלים" "W4: a comma or a full stop between two numbers is part of the number"]
   [(v ["הוקמה"] " " "Google" " " ["חברת"]) "חברת Google הוקמה" "a Latin word is its own left-to-right run"]
   [(v "ROI-" ["ו"]) "ו-ROI" "N2: the hyphen between R and L takes the paragraph's direction"]
   [(v ".2026" " " ["בשנת"]) "בשנת 2026." "the full stop that ends the sentence is at the left of the number: N1"]
   [(v ["בלבד"] " " "$100" " " ["עלות"]) "עלות $100 בלבד" "W5: the dollar sign before a European number is part of it"]
   [(v ["מעלות"] " " "5-" " " ["טמפרטורה"]) "טמפרטורה -5 מעלות" "W6: a sign before a number is a neutral: N1 makes it right to left, so it is at the number's right"]
   [(v ["ארבע"] " " "2020-2026" " " ["בין"]) "בין 2020-2026 ארבע" "W4: a hyphen between two numbers is part of the number"]
   [(v ["גדולות"] " " "2027" " " "2026" " " ["שנים"]) "שנים 2026 2027 גדולות" "N1: the space between two numbers is right to left, so two numbers read right to left"]
   [(v ["חדש"] " " "iPhone 15 Pro" " " ["קניתי"]) "קניתי iPhone 15 Pro חדש" "W7: a number after a Latin letter is a letter, so the phrase is one left-to-right run"]
   [(v ["בבוקר"] " " "10:30" " " ["בשעה"]) "בשעה 10:30 בבוקר" "W4: a colon between two numbers is part of the number"]
   [(v ["דוד"] " " ":" ["שם"]) "שם: דוד" "a colon after a word is at the word's left: N1"]
   [(v ["ارتفع"] " " "،2026" " " ["عام"] " " ["في"]) "في عام 2026، ارتفع" "W2: the digits after an Arabic letter are Arabic numbers; the Arabic comma after them is at their left"]
   [(v ["ارتفع"] " " "٢٠٢٦" " " ["عام"] " " ["في"]) "في عام ٢٠٢٦ ارتفع" "Arabic-Indic digits are Arabic numbers: they read left to right"]
   [(v "٪٥٠" " " ["بنسبة"] " " ["ارتفع"]) "ارتفع بنسبة ٥٠٪" "W6: the Arabic percent sign is a terminator that no European number follows: neutral, so at the number's left"]
   [(v ["ابريل"] " " "12/05" " " ["في"]) "في 12/05 ابريل" "the two numbers of a date with a slash: W4 (EN CS EN), so the whole date is one run and reads left to right; as typed it is 12/05"]
   [(v ["חדשה"] " " "(2026)" " " ["חברה"]) "חברה (2026) חדשה" "N0: brackets around a number are right to left (EN counts as R); at an odd level the glyphs are mirrored, so what the page shows at its left is the logical closing bracket"]
   [(v ["חדשה"] " " "(Google)" " " ["חברת"]) "חברת (Google) חדשה" "N0: a Latin word inside, a right-to-left word before: the brackets stay right to left; the word is a left-to-right run"]
   [(v "(" ["שלום"] ")") "(שלום)" "N0 and L4: brackets around right-to-left text are right to left and mirrored"]
   [(v "(" "2026" " " ["שלום"] ")") "(שלום 2026)" "N0, N1: a number after a word inside brackets"]
   [(v ["سعر"] " " "٣٫٥٠" " " ["الكتاب"]) "الكتاب ٣٫٥٠ سعر" "an Arabic decimal separator (U+066B) is an Arabic number itself"]])

(defn render []
  (str "# Hand-written right-to-left samples (kin/scripts/bidi-samples.clj): shown (left to right on the page) <TAB> logical <TAB> why.\n"
       (str/join "\n" (map (fn [[shown logical why]] (str shown "\t" logical "\t" why)) samples)) "\n"))

(let [root (-> *file* io/file .getCanonicalFile .getParentFile .getParentFile .getParent)
      f (io/file root "test/bidi/samples.txt")]
  (if (= (first *command-line-args*) "--check")
    (do (when-not (and (.exists f) (= (slurp f) (render)))
          (println "test/bidi/samples.txt is stale; run bb kin/scripts/bidi-samples.clj") (System/exit 1))
        (println "  ok   test/bidi/samples.txt is what bidi-samples.clj writes"))
    (do (io/make-parents f) (spit f (render)) (println "wrote" (.getPath f)))))
