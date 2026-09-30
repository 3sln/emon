(ns synth.make-html
  "Author the synthetic HTML corpus (DEVLOG P2.8, D041, D063).

      bb tools/synth/make_html.clj [OUTDIR]     write test/corpus/synth/html/*.html (or OUTDIR)

  The COMMITTED HTML is the corpus; this script is how it was authored (bulk rows and filler text are
  generated, deterministically from fixed seeds, by pyrandom: CPython's Mersenne Twister, so the text
  is the text the original Python script produced) and exists so a reviewer can change a document and
  see the diff.  Fonts are system fonts only (Georgia, Helvetica, Courier New, Arial, Geeza Pro, Arial
  Hebrew, Hiragino Sans, Devanagari Sangam MN): nothing is fetched, so a render is reproducible on the
  same OS.  No text is `text-transform`ed: what prints is what the source says.  See html2gold.clj for
  the HTML subset."
  (:require [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pyfmt :refer [fmt]]
            [pyfmt]
            [pyjson :as pj :refer [om]]
            [pyrandom :as rnd]))

(def WORDS
  (str/split
   (str "the of and to in is that for it as with was on be by at this have from or one had "
        "not but what all were when we there can an your which their said if do will each "
        "about how up out them then she many some so these would other into has more her two "
        "like him see time could no make than first been its who now people my made over did "
        "down only way find use may water long little very after words called just where most "
        "know get through back much before go good new write our used me man too any day same "
        "right look think also around another came come work three word must because does part "
        "even place well such here take why help put different away again off went old number "
        "great tell men say small every found still between name should home big give air line "
        "set own under read last never us left end along while might next sound below saw "
        "something thought both few those always looked show large often together asked house "
        "don't world going want school important until form food keep children feet land side "
        "without boy once animal life enough took sometimes four head above kind began almost "
        "live page got earth need far hand high year mother light country father let night "
        "picture being study second soon story since white ever paper hard near sentence better "
        "best across during today however sure knew try told young sun thing whole hear example "
        "heard several change answer room sea against top turned learn point city play toward "
        "five using himself usually money seen didn't car morning body upon family later turn "
        "move face door cut done group true leave color red friend pay quarter revenue budget "
        "report review policy region market growth figure measure results method sample")
   #"\s+"))

(defn sentence
  ([r] (sentence r 8 16))
  ([r lo hi]
   (let [n (rnd/randint r lo hi)
         w (vec (repeatedly n #(rnd/choice r WORDS)))
         s (str/join " " w)
         s (str (str/upper-case (subs s 0 1)) (subs s 1) ".")]
     (-> s (str/replace " i " " I ") (str/replace "don't" "do not") (str/replace "didn't" "did not")))))

(defn para
  ([seed] (para seed 4 8 16))
  ([seed n] (para seed n 8 16))
  ([seed n lo hi]
   (let [r (rnd/make seed)]
     (str/join " " (vec (repeatedly n #(sentence r lo hi)))))))

(defn P
  ([seed] (P seed 4 8 16))
  ([seed n] (P seed n 8 16))
  ([seed n lo hi] (str "<p>" (para seed n lo hi) "</p>")))

(defn hints [h]
  (str "<script type=\"application/emon-gold+json\">" (pj/dumps h) "</script>"))

(def SERIF "Georgia, 'Times New Roman', serif")
(def SANS "Helvetica, Arial, sans-serif")

(def BASE
  (fmt "
html{font:11pt/1.45 %s;color:#111}
body{margin:0}
h1,h2,h3,h4,h5,h6{margin:1em 0 .4em;line-height:1.2;font-weight:normal;hyphens:none}
p{margin:0 0 .7em;orphans:2;widows:2}
.num{display:inline-block;min-width:3.2em}
table{border-collapse:collapse;font:10pt %s}
th,td{padding:4px 8px;text-align:left;vertical-align:top}
td.r,th.r{text-align:right}
figure{margin:1em 0}
figcaption{font:italic 9.5pt %s;margin-top:.3em}
" SERIF SANS SANS))

(defn doc [title body {:keys [css lang page margin atpage dir h base rawpage]
                       :or {css "" lang "en" page "Letter" margin "1in" atpage "" dir "ltr" base BASE rawpage ""}}]
  (let [at (fmt "@page{size:%s;margin:%s;%s}\n%s" page margin atpage rawpage)]
    (fmt "<!doctype html>
<html lang=\"%s\" dir=\"%s\">
<head>
<meta charset=\"utf-8\">
<title>%s</title>
<style>
%s
%s
%s
</style>
%s
</head>
<body>
%s
</body>
</html>
" lang dir title at base css (if h (hints h) "") body)))

(defn hd [level text & [num]]
  (let [n (if (seq num) (fmt "<span class=\"num\">%s</span> " num) "")]
    (fmt "<div class=\"hd l%d\" data-level=\"%d\">%s%s</div>" level level n text)))

(defn li
  ([t] (li t ""))
  ([t inner] (fmt "<li>%s%s</li>" t inner)))

(defn tr
  ([cells] (tr cells "td" nil))
  ([cells tag] (tr cells tag nil))
  ([cells tag cls]
   (str "<tr>"
        (apply str (map-indexed (fn [i c]
                                  (let [a (if (and cls (contains? cls i)) " class=\"r\"" "")]
                                    (fmt "<%s%s>%s</%s>" tag a c tag)))
                                cells))
        "</tr>")))

(defn long-table [prefix n seed repeat tid & {:keys [with-thead cols] :or {with-thead true cols ["Ref" "Item" "Category" "Qty" "Amount"]}}]
  (let [r (rnd/make seed)
        cats ["Hardware" "Software" "Services" "Travel" "Training" "Supplies"]
        sb (StringBuilder.)]
    (.append sb (fmt "<table id=\"%s\" data-multipage=\"1\" data-profile=\"ruled\" data-repeat-head=\"%s\">" tid (if repeat "true" "false")))
    (when with-thead (.append sb (str "<thead>" (tr cols "th" #{3 4}) "</thead>")))
    (.append sb "<tbody>")
    (dotimes [i n]
      (let [ref (fmt "%s%04d" prefix (inc i))
            item (pyfmt/capitalize (str/join " " (vec (repeatedly 3 #(rnd/choice r WORDS)))))
            cat (rnd/choice r cats)
            qty (str (rnd/randint r 1 40))
            a (rnd/randint r 10 9000)
            b (rnd/randint r 0 99)]
        (.append sb (tr [ref item cat qty (fmt "%d.%02d" a b)] "td" #{3 4}))))
    (str sb "</tbody></table>")))

(defn small-table [prefix n seed]
  (str/replace (long-table prefix n seed false "x") " id=\"x\" data-multipage=\"1\"" ""))

(def sections- ["Overview" "Findings" "Recommendations" "Methods" "Annex"])

(defn longdoc [seed nsec paras]
  (apply str (for [i (range nsec)]
               (str (fmt "<h2>%s</h2>" (nth sections- (mod i 5)))
                    (apply str (for [j (range paras)] (P (+ seed (* i 10) j) 5)))))))

(defn fn- [marker text]
  (fmt "<div class=\"fn\" data-note=\"footnote\" data-marker=\"%s\"><sup data-gold=\"skip\">%s</sup> %s</div>" marker marker text))

(defn ref- [m] (fmt "<sup data-fnref=\"%s\">%s</sup>" m m))

(defn pg [inner foot]
  (fmt "<div class=\"pg\"><div>%s</div><div class=\"foot\">%s</div></div>" inner foot))

(defn bar-chart [w h vals labels title-y]
  (let [mx (apply max vals)
        sb (StringBuilder.)]
    (.append sb (fmt "<svg width=\"%d\" height=\"%d\" viewBox=\"0 0 %d %d\" font-family=\"Helvetica\" font-size=\"10\">" w h w h))
    (.append sb (fmt "<line x1=\"40\" y1=\"10\" x2=\"40\" y2=\"%d\" stroke=\"#000\"/><line x1=\"40\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#000\"/>" (- h 30) (- h 30) (- w 10) (- h 30)))
    (doseq [t (range 0 5)]
      (let [y (- (- h 30) (/ (* t (- h 45)) 4.0))]
        (.append sb (fmt "<line x1=\"36\" y1=\"%.1f\" x2=\"40\" y2=\"%.1f\" stroke=\"#000\"/><text x=\"32\" y=\"%.1f\" text-anchor=\"end\">%d</text>" y y (+ y 3) (quot (* t mx) 4)))))
    (let [bw (/ (- w 70) (double (count vals)))]
      (doseq [[i [v l]] (map-indexed vector (map vector vals labels))]
        (let [bh (/ (* (- h 45) v) (double mx))]
          (.append sb (fmt "<rect x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" fill=\"#3b6ea5\"/><text x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">%s</text>"
                           (+ 48 (* i bw)) (- (- h 30) bh) (- bw 14) bh (+ 48 (* i bw) (/ (- bw 14) 2)) (- h 16) l)))))
    (.append sb (fmt "<text x=\"6\" y=\"8\" font-size=\"9\">%s</text></svg>" title-y))
    (str sb)))

(defn line-chart [w h]
  (let [sb (StringBuilder.)]
    (.append sb (fmt "<svg width=\"%d\" height=\"%d\" font-family=\"Helvetica\" font-size=\"10\">" w h))
    (.append sb (fmt "<line x1=\"40\" y1=\"10\" x2=\"40\" y2=\"%d\" stroke=\"#000\"/><line x1=\"40\" y1=\"%d\" x2=\"%d\" y2=\"%d\" stroke=\"#000\"/>" (- h 30) (- h 30) (- w 10) (- h 30)))
    (let [pts (vec (for [i (range 6)] [(+ 40 (/ (* i (- w 60)) 5.0)) (- (- h 30) (nth [20 50 45 90 110 140] i))]))]
      (.append sb (fmt "<polyline fill=\"none\" stroke=\"#c0392b\" stroke-width=\"2\" points=\"%s\"/>"
                       (str/join " " (map (fn [[x y]] (fmt "%.1f,%.1f" x y)) pts))))
      (doseq [[i m] (map-indexed vector ["Jan" "Feb" "Mar" "Apr" "May" "Jun"])]
        (.append sb (fmt "<text x=\"%.1f\" y=\"%d\" text-anchor=\"middle\">%s</text>" (first (nth pts i)) (- h 16) m))))
    (.append sb (fmt "<text x=\"36\" y=\"%d\" text-anchor=\"end\">0</text><text x=\"36\" y=\"20\" text-anchor=\"end\">150</text></svg>" (- h 27)))
    (str sb)))

(defn diagram [w h]
  (let [sb (StringBuilder.)]
    (.append sb (fmt "<svg width=\"%d\" height=\"%d\" font-family=\"Helvetica\" font-size=\"11\">" w h))
    (doseq [[i t] (map-indexed vector ["Client" "Gateway" "Service"])]
      (let [x (+ 10 (* i 140))]
        (.append sb (fmt "<rect x=\"%d\" y=\"30\" width=\"100\" height=\"40\" fill=\"#fff\" stroke=\"#000\"/><text x=\"%d\" y=\"55\" text-anchor=\"middle\">%s</text>" x (+ x 50) t))
        (when (< i 2)
          (.append sb (fmt "<line x1=\"%d\" y1=\"50\" x2=\"%d\" y2=\"50\" stroke=\"#000\"/><polygon points=\"%d,50 %d,46 %d,54\"/>" (+ x 100) (+ x 138) (+ x 140) (+ x 132) (+ x 132))))))
    (str sb "</svg>")))

(defn colbody [seed nsec]
  (apply str (for [i (range nsec)]
               (str (fmt "<h2>%s</h2>" (nth ["Motivation" "Approach" "Results" "Discussion" "Limits" "Conclusion"] (mod i 6)))
                    (P (+ seed (* i 3)) 6 9 15) (P (+ seed (* i 3) 1) 5)))))

(defn statement []
  (let [r (rnd/make 1201)
        desc ["Card purchase" "Direct debit" "Transfer in" "Cash withdrawal" "Standing order" "Interest" "Refund" "Salary"]
        shops ["Tesco" "Shell" "Amazon" "Netflix" "Landlord" "Council" "Gym" "Cafe"]
        bal (volatile! 4210.55)]
    (vec (for [i (range 110)]
           (let [d (fmt "%02d Mar" (inc (quot (* i 30) 110)))
                 kind (rnd/choice r desc)
                 amt (pyfmt/round-half-even (rnd/uniform r 3 420) 2)
                 [db cr] (if (contains? #{"Transfer in" "Interest" "Refund" "Salary"} kind)
                           (let [cr (if (not= kind "Salary") amt 2400.0)]
                             (vswap! bal + cr)
                             ["" (fmt "%.2f" cr)])
                           (do (vswap! bal - amt)
                               [(fmt "%.2f" amt) ""]))
                 shop (rnd/choice r shops)]
             [(fmt "S%05d" (inc i)) d (fmt "%s %s" kind shop) db cr (pyfmt/commas @bal 2)])))))

(def AR-P ["بدأت الشركة أعمالها في مدينة صغيرة على الساحل قبل خمسين عاما، ثم توسعت تدريجيا لتصبح من أكبر المؤسسات في المنطقة."
           "تعتمد الخطة الجديدة على تحسين الخدمات وتقليل التكاليف وزيادة رضا العملاء في جميع الفروع خلال السنوات المقبلة."
           "يبين الجدول التالي عدد الموظفين في كل قسم، كما يوضح النسب المئوية للزيادة السنوية المتوقعة."])
(def HE-P ["החברה הוקמה לפני חמישים שנה בעיר קטנה על החוף והתרחבה בהדרגה לאחד הארגונים הגדולים באזור."
           "התוכנית החדשה מתמקדת בשיפור השירות, הפחתת עלויות והגדלת שביעות הרצון של הלקוחות בכל הסניפים."
           "הדוח כולל נתונים על 1,250 עובדים ועל תקציב של 3.4 מיליון שקלים לשנת 2026, לצד מונחים כמו KPI ו-ROI."])

(def PAGENUM-HINT (om "labels" "arabic" "dropped" [(om "reason" "page-number" "text" "{n}")]))

(defn build-p28
  "The 36 documents of P2.8 as {name html}."
  []
  (let [docs (atom {})
        add (fn [name title body & [opts]] (swap! docs assoc name (doc title body (or opts {}))))
        hb (fn ([seed] (P seed 2)) ([seed n] (P seed n)))
        body (volatile! "")
        b+ (fn [& xs] (vswap! body str (apply str xs)))
        b= (fn [& xs] (vreset! body (apply str xs)))]

    ;; ------------------------------------------------------------ headings
    ;; 1 plain, depth 4
    (b= "<h1>Field Notes on Soil Moisture</h1>" (hb 1))
    (doseq [[i t2] (map-indexed vector ["Background" "Method"])]
      (b+ (fmt "<h2>%s</h2>" t2) (hb (+ 10 i)))
      (doseq [[j t3] (map-indexed vector ["Site selection" "Instruments"])]
        (b+ (fmt "<h3>%s</h3>" t3) (hb (+ 20 (* i 5) j)))
        (doseq [[k t4] (map-indexed vector ["Calibration" "Logging"])]
          (b+ (fmt "<h4>%s</h4>" t4) (hb (+ 40 (* i 10) (* j 3) k) 1)))))
    (add "h-plain" "Field Notes on Soil Moisture" @body
         {:css (fmt "
h1{font:bold 22pt %s} h2{font:bold 16pt %s} h3{font:bold 13pt %s} h4{font:italic bold 11pt %s}
" SANS SANS SANS SANS)})

    ;; 2 numbered decimal, depth 4
    (b= "<h1><span class=\"num\">1</span> Introduction</h1>" (hb 51))
    (doseq [[a t2] (map-indexed (fn [i x] [(inc i) x]) ["Purpose" "Scope" "Definitions"])]
      (b+ (fmt "<h2><span class=\"num\">1.%d</span> %s</h2>" a t2) (hb (+ 60 a)))
      (doseq [[b t3] (map-indexed (fn [i x] [(inc i) x]) ["General" "Exceptions"])]
        (b+ (fmt "<h3><span class=\"num\">1.%d.%d</span> %s</h3>" a b t3) (hb (+ 70 (* a 3) b) 1))
        (when (< a 3)
          (doseq [[c t4] (map-indexed (fn [i x] [(inc i) x]) ["Normative" "Informative"])]
            (b+ (fmt "<h4><span class=\"num\">1.%d.%d.%d</span> %s</h4>" a b c t4) (hb (+ 90 (* a 9) (* b 3) c) 1))))))
    (b+ "<h1><span class=\"num\">2</span> Requirements</h1>" (hb 120))
    (b+ "<h2><span class=\"num\">2.1</span> Functional</h2>" (hb 121))
    (b+ "<h3><span class=\"num\">2.1.1</span> Input</h3>" (hb 122 1))
    (add "h-numbered" "Specification With Decimal Numbering" @body
         {:css (fmt "
h1{font:bold 20pt %s} h2{font:bold 15pt %s} h3{font:bold 12.5pt %s} h4{font:bold 11pt %s}
.num{min-width:4.6em}
" SANS SANS SANS SANS)})

    ;; 3 inverted
    (b= "<h1>ANNUAL REVIEW OF OPERATIONS</h1>" (hb 131 2))
    (doseq [[i t2] (map-indexed vector ["Northern Region" "Southern Region"])]
      (b+ (fmt "<h2>%s</h2>" t2) (hb (+ 140 i) 2))
      (doseq [[j t3] (map-indexed vector ["Staffing" "Facilities"])]
        (b+ (fmt "<h3>%s</h3>" t3) (hb (+ 150 (* i 4) j) 1))
        (b+ (fmt "<h4>%s</h4>" (if (= j 0) "Hiring plan" "Maintenance backlog")) (hb (+ 160 (* i 4) j) 2))))
    (b+ "<h1>FINANCIAL SUMMARY</h1>" (hb 170 1))
    (b+ "<h2>Income and Expenses</h2>" (hb 171 2))
    (b+ "<h3>Income</h3>" (hb 172 1))
    (add "h-inverted" "Annual Review" @body
         {:css (fmt "
h1{font:600 9.5pt %s;letter-spacing:.18em;border-bottom:1px solid #999;padding-bottom:3pt;margin-top:1.6em}
h2{font:bold 21pt %s;color:#123}
h3{font:bold 11.5pt %s;color:#555}
h4{font:bold 14pt %s}
" SANS SERIF SANS SERIF)})

    ;; 4 inverted, mixed numbering schemes
    (b= "<h1><span class=\"num\">I.</span> GOVERNANCE</h1>" (hb 181 1))
    (doseq [[i [a t2]] (map-indexed vector [["A" "Board structure"] ["B" "Committees"]])]
      (b+ (fmt "<h2><span class=\"num\">%s.</span> %s</h2>" a t2) (hb (+ 190 i) 1))
      (doseq [[j t3] (map-indexed (fn [i x] [(inc i) x]) ["Audit" "Risk"])]
        (b+ (fmt "<h3><span class=\"num\">%d.</span> %s</h3>" j t3) (hb (+ 200 (* i 4) j) 1))
        (doseq [[k t4] (map-indexed vector ["Charter" "Membership"])]
          (b+ (fmt "<h4><span class=\"num\">%s)</span> %s</h4>" (nth ["a" "b"] k) t4) (hb (+ 210 (* i 9) (* j 3) k) 1)))))
    (b+ "<h1><span class=\"num\">II.</span> REMUNERATION</h1>" (hb 240 1))
    (b+ "<h2><span class=\"num\">A.</span> Principles</h2>" (hb 241 1))
    (add "h-inverted-numbered" "Governance Report" @body
         {:css (fmt "
h1{font:600 9pt %s;letter-spacing:.15em}
h2{font:bold 17pt %s}
h3{font:bold 12pt %s}
h4{font:italic 11pt %s}
.num{min-width:2.2em}
" SANS SANS SANS SERIF)})

    ;; 5 non-semantic headings
    (b= (hd 1 "Maintenance Manual") (hb 251 2))
    (b+ (hd 2 "Safety") (hb 252 2))
    (b+ (hd 3 "Lockout procedure") (hb 253 1))
    (b+ (hd 4 "Verification") (hb 254 1))
    (b+ (hd 3 "Personal protective equipment") (hb 255 1))
    (b+ (hd 2 "Routine service") (hb 256 2))
    (b+ (hd 3 "Daily checks") (hb 257 1))
    (b+ (hd 1 "Appendix") (hb 258 1))
    (add "h-div-samesize" "Maintenance Manual" @body
         {:css "
.hd{font-size:11pt;margin:1em 0 .3em;page-break-after:avoid}
.l1{font-weight:bold;color:#7a1f1f}
.l2{font-weight:bold}
.l3{font-style:italic;font-weight:bold;margin-left:.4in}
.l4{font-style:italic;margin-left:.8in;color:#444}
"})

    ;; 6 keyword numbering
    (b= "")
    (doseq [c (range 1 3)]
      (b+ (fmt "<h1 data-numbering=\"keyword\"><span class=\"num\">Chapter %d</span> %s</h1>" c (nth ["Formation" "Operation"] (dec c))) (hb (+ 260 c) 1))
      (doseq [a (range 1 3)]
        (b+ (fmt "<h2 data-numbering=\"keyword\"><span class=\"num\">Article %d.%d</span> %s</h2>" c a (nth ["Name" "Purpose" "Members" "Meetings"] (+ (* (dec c) 2) a -1))) (hb (+ 270 (* c 3) a) 2))
        (b+ (fmt "<h3 data-numbering=\"keyword\"><span class=\"num\">Section %d.%d.1</span> Interpretation</h3>" c a) (hb (+ 280 (* c 3) a) 1))))
    (add "h-keyword" "Bylaws" @body
         {:css (fmt "
h1{font:bold 13pt %s;text-align:center;margin-top:1.4em}
h2{font:bold 12pt %s}
h3{font:italic 11pt %s}
.num{min-width:0;margin-right:.6em}
" SERIF SERIF SERIF)})

    ;; ------------------------------------------------------------ lists
    (b= "<h1>Packing Checklist</h1>" (hb 301 1))
    (b+ "<ul>" (li "Tent and groundsheet") (li "Stove, with <b>spare</b> fuel canister")
        (li "Water filter" (str "<ul>" (li "Backup tablets") (li "Bottle" (str "<ul>" (li "One litre") (li "Two litre") "</ul>")) "</ul>"))
        (li "First aid kit") "</ul>")
    (b+ "<h2>Steps</h2>" "<ol>" (li "Lay out the groundsheet.") (li "Stake the corners.")
        (li "Raise the poles" (str "<ol type=\"a\">" (li "Short pole first.") (li "Long pole second.") "</ol>"))
        (li "Attach the fly.") "</ol>")
    (b+ (hb 302 1))
    (add "lists-basic" "Packing Checklist" @body
         {:css "ul,ol{margin:0 0 .8em;padding-left:1.6em} li{margin:.15em 0} ul ul{list-style-type:circle} ul ul ul{list-style-type:square}"})

    (b= "<h1>Reference Lists</h1>")
    (b+ "<h2>Alphabetic</h2><ol type=\"a\">" (li "Apples") (li "Bananas") (li "Cherries") "</ol>")
    (b+ "<h2>Roman</h2><ol type=\"I\">" (li "Preamble") (li "Definitions") (li "Obligations") (li "Term") "</ol>")
    (b+ "<h2>Resumed</h2><ol start=\"5\">" (li "Fifth item with a <a href=\"https://example.org/five\">link</a>.") (li "Sixth item, <i>emphasised</i> and <code>monospaced</code>.") (li "Seventh item") "</ol>")
    (b+ "<h2>Explicit values</h2><ol><li value=\"3\">Third</li><li>Fourth</li><li value=\"10\">Tenth</li></ol>")
    (add "lists-schemes" "Reference Lists" @body {:css "ol,ul{margin:0 0 .8em;padding-left:2em}"})

    (b= "<h1>Terms and Conditions</h1>" (hb 311 2) "<ol>")
    (doseq [i (range 1 61)]
      (b+ (fmt "<li>%s</li>" (para (+ 400 i) 2 6 11))))
    (b+ "</ol>" (hb 312 1))
    (add "lists-long-multipage" "Terms and Conditions" @body
         {:css "ol{padding-left:2.2em} li{margin:.3em 0}"
          :atpage (fmt "@bottom-center{content:counter(page);font:9pt %s}" SANS)
          :h PAGENUM-HINT})

    ;; ------------------------------------------------------------ tables
    (b= "<h1>Regional Sales</h1>" (hb 501 2))
    (b+ "<table data-profile=\"ruled\"><caption>Table 1. Sales by region, thousands of units</caption><thead>" (tr ["Region" "Q1" "Q2" "Q3" "Q4"] "th" #{1 2 3 4}) "</thead><tbody>")
    (doseq [[i reg] (map-indexed vector ["North" "South" "East" "West" "Central"])]
      (b+ (tr (vec (cons reg (for [q (range 4)] (fmt "%d" (+ 100 (mod (+ (* i 37) (* q 53)) 90)))))) "td" #{1 2 3 4})))
    (b+ "</tbody><tfoot>" (tr ["Total" "512" "498" "530" "547"] "th" #{1 2 3 4}) "</tfoot></table>" (hb 502 2))
    (add "table-simple-ruled" "Regional Sales" @body
         {:css (fmt "table{width:100%%;border:1px solid #000} th,td{border:1px solid #000} caption{caption-side:top;text-align:left;font:italic 10pt %s;padding-bottom:4px} thead th{background:#ddd}" SANS)})

    (b= "<h1>Fund Performance</h1>" (hb 511 1))
    (b+ "<table data-profile=\"whitespace\"><thead>" (tr ["Fund" "1 yr" "3 yr" "5 yr" "Fee"] "th" #{1 2 3 4}) "</thead><tbody>")
    (doseq [[i f] (map-indexed vector ["Global Equity" "Bond Index" "Small Cap" "Real Assets" "Cash Plus" "Emerging Markets"])]
      (b+ (tr [f (fmt "%.1f%%" (+ 3 (rem (* i 1.7) 9))) (fmt "%.1f%%" (+ 2 (rem (* i 2.3) 7))) (fmt "%.1f%%" (+ 4 (rem (* i 1.1) 6))) (fmt "%.2f%%" (+ 0.1 (* i 0.07)))] "td" #{1 2 3 4})))
    (b+ "</tbody></table>" (hb 512 2))
    (add "table-whitespace" "Fund Performance" @body {:css "table{width:85%;margin:1em 0} th{font-weight:bold;border-bottom:0}"})

    (b= "<h1>Grouped Headers</h1>" (hb 521 1))
    (b+ "<table data-profile=\"partial\"><thead><tr><th rowspan=\"2\">Product</th><th colspan=\"2\">2024</th><th colspan=\"2\">2025</th></tr>")
    (b+ (tr ["Units" "Revenue" "Units" "Revenue"] "th" #{0 1 2 3}) "</thead><tbody>")
    (doseq [[i p] (map-indexed vector ["Alpha" "Beta" "Gamma" "Delta"])]
      (b+ (tr [p (fmt "%d" (+ 1000 (* i 120))) (fmt "$%d,%03d" (+ 20 i) (mod (* i 311) 1000)) (fmt "%d" (+ 1100 (* i 90))) (fmt "$%d,%03d" (+ 23 i) (mod (* i 197) 1000))] "td" #{1 2 3 4})))
    (b+ "<tr><td colspan=\"3\">Combined total for the two years</td><td></td><td class=\"r\">$190,000</td></tr>")
    (b+ "</tbody></table>" (hb 522 1))
    (add "table-spans-partial" "Grouped Headers" @body
         {:css "table{width:100%;border-top:2px solid #000;border-bottom:2px solid #000} thead th{border-bottom:1px solid #000} thead tr:first-child th{text-align:center}"})

    (b= "<h1>Project Status</h1>" (hb 531 1))
    (b+ "<table data-profile=\"shaded\"><thead>" (tr ["Workstream" "Owner" "Status" "Due"] "th") "</thead><tbody>")
    (doseq [[w o s d] [["Data migration" "Ana" "On track" "2026-03-01"] ["Security review" "Ben" "At risk" "2026-03-15"] ["User training" "Chen" "Done" "2026-02-10"] ["Launch" "Dee" "Not started" "2026-04-01"] ["Retrospective" "Eli" "Not started" "2026-04-20"]]]
      (b+ (tr [w o s d])))
    (b+ "</tbody></table>" (hb 532 1))
    (add "table-shaded" "Project Status" @body
         {:css "table{width:100%} thead th{background:#1f4e79;color:#fff} tbody tr:nth-child(odd) td{background:#e9eff7} tbody tr:nth-child(even) td{background:#fff}"})

    (let [tcss "table{width:100%;font-size:10pt} th,td{border:1px solid #888;padding:5px 8px} thead th{background:#e3e3e3} tr{break-inside:avoid}"
          bottom (fmt "@bottom-center{content:counter(page);font:9pt %s}" SANS)]
      (b= "<h1>Purchase Ledger</h1>" (hb 541 2) (long-table "L" 95 542 true "ledger") (hb 543 2))
      (add "table-multipage-thead" "Purchase Ledger" @body {:css tcss :h PAGENUM-HINT :atpage bottom})
      (b= "<h1>Inventory Extract</h1>" (hb 551 2) (long-table "I" 95 552 false "inv") (hb 553 2))
      (add "table-multipage-nohead-repeat" "Inventory Extract" @body
           {:css (str tcss " thead{display:table-row-group}") :h PAGENUM-HINT :atpage bottom})
      (b= "<h1>Raw Transaction Dump</h1>" (hb 561 1) (long-table "R" 100 562 false "raw" :with-thead false) (hb 563 1))
      (add "table-multipage-no-thead" "Raw Transaction Dump" @body {:css tcss :h PAGENUM-HINT :atpage bottom})

      (b= "<h1>Two Schedules</h1><h2>Schedule A</h2>" (small-table "A" 12 571))
      (b+ "<div style=\"break-after:page\"></div><h2>Schedule B</h2>" (small-table "B" 12 572))
      (add "table-consecutive-distinct" "Two Schedules" @body {:css (str tcss " table{font-size:11pt}")}))

    ;; ------------------------------------------------------------ furniture
    (b= "<h1>Quarterly Operating Report</h1>" (longdoc 601 5 3))
    (add "furn-title-pagenum" "Quarterly Operating Report" @body
         {:css (fmt "h1{font:bold 20pt %s} h2{font:bold 14pt %s}" SANS SANS)
          :atpage (fmt "@top-center{content:\"Quarterly Operating Report\";font:9pt %s;color:#555} @bottom-center{content:\"Page \" counter(page) \" of \" counter(pages);font:9pt %s;color:#555}" SANS SANS)
          :h (om "labels" "arabic"
                 "dropped" [(om "reason" "running-title" "text" "Quarterly Operating Report")
                            (om "reason" "page-number" "text" "Page {n} of {N}")])})

    (b= "<h1>Handbook for New Staff</h1>" (longdoc 611 5 5))
    (add "furn-odd-even" "Handbook for New Staff" @body
         {:css (fmt "h1{font:bold 20pt %s} h2{font:bold 14pt %s}" SANS SANS)
          :rawpage (fmt "@page :left{@top-left{content:\"Handbook for New Staff\";font:9pt %s;color:#555}@bottom-left{content:counter(page);font:9pt %s}}
@page :right{@top-right{content:\"Employee Handbook\";font:9pt %s;color:#555}@bottom-right{content:counter(page);font:9pt %s}}
@page :first{@top-right{content:none}@bottom-right{content:none}}" SANS SANS SANS SANS)
          :h (om "labels" "arabic" "label-pages" "rest"
                 "furniture" [(om "role" "header" "template" "Employee Handbook" "pages" "odd-rest")]
                 "dropped" [(om "reason" "running-title" "text" "Handbook for New Staff" "pages" "even")
                            (om "reason" "page-number" "text" "{n}" "pages" "rest")])})

    (b= "<h1>Motion for Summary Judgment</h1><p><b>Plaintiff Acme Holdings</b> moves for summary judgment on all counts.</p>" (longdoc 621 5 3))
    (add "furn-informative" "Motion for Summary Judgment" @body
         {:css (fmt "body{font-family:%s} h1{font:bold 16pt %s;text-align:center} h2{font:bold 12pt %s}" SERIF SERIF SERIF)
          :margin "1.1in 1in"
          :atpage (fmt "@top-left{content:\"Case 1:21-cv-00123-ABC Document 14 Filed 05/03/21\";font:8.5pt %s}
@top-right{content:\"Page \" counter(page) \" of \" counter(pages);font:8.5pt %s}
@bottom-left{content:\"CONFIDENTIAL - ATTORNEYS' EYES ONLY\";font:8pt %s;color:#800}
@bottom-right{content:\"ACME-\" counter(page, decimal-leading-zero);font:8pt %s}" SANS SANS SANS SANS)
          :h (om "labels" "arabic"
                 "furniture" [(om "role" "header" "template" "Case 1:21-cv-00123-ABC Document 14 Filed 05/03/21" "pages" "all")
                              (om "role" "footer" "template" "CONFIDENTIAL - ATTORNEYS' EYES ONLY" "pages" "all")
                              (om "role" "footer" "template" "ACME-{n:02}" "pages" "all")]
                 "dropped" [(om "reason" "page-number" "text" "Page {n} of {N}")])})

    (b= "<div data-gold=\"skip\" class=\"fx top\">ACME CORPORATION - Internal Use Only</div>"
        "<div data-gold=\"skip\" class=\"fx bot\">Form QA-7 rev. 3</div>"
        "<table data-layout=\"1\" class=\"frame\"><thead><tr><td><div class=\"sp\"></div></td></tr></thead><tbody><tr><td>"
        "<h1>Quality Procedure</h1>" (longdoc 631 5 3)
        "</td></tr></tbody><tfoot><tr><td><div class=\"sp\"></div></td></tr></tfoot></table>")
    (add "furn-fixed-dom" "Quality Procedure" @body
         {:margin "0"
          :css (fmt "
.frame{width:100%%;border-collapse:collapse;font:11pt/1.45 %s} .frame td{padding:0 1in;font:inherit}
.sp{height:1in}
.fx{position:fixed;left:0;right:0;font:8.5pt %s;color:#555;text-align:center}
.top{top:.5in} .bot{bottom:.5in}
h1{font:bold 20pt %s} h2{font:bold 14pt %s}" SERIF SANS SANS SANS)
          :h (om "furniture" [(om "role" "header" "template" "ACME CORPORATION - Internal Use Only" "pages" "all")
                              (om "role" "footer" "template" "Form QA-7 rev. 3" "pages" "all")])})

    ;; ------------------------------------------------------------ footnotes
    (b= (pg (fmt "<h1>Measuring Productivity</h1><p>%s%s Output per hour rose in every sector.%s</p><p>%s%s</p>" (para 701 2) (ref- 1) (ref- 2) (para 702 3) (ref- 3))
            (str (fn- 1 "Source: national statistics office, 2025.") (fn- 2 "Seasonally adjusted.") (fn- 3 "Excludes agriculture."))))
    (b+ (pg (fmt "<h2>Comparisons</h2><p>%s%s</p><p>%s</p>" (para 703 3) (ref- 1) (para 704 3))
            (fn- 1 "Purchasing power parity, current prices.")))
    (add "footnotes-page" "Measuring Productivity" @body
         {:css (fmt ".pg{height:9in;display:flex;flex-direction:column;justify-content:space-between;break-after:page} .pg:last-child{break-after:auto} .foot{border-top:1px solid #000;width:2in;padding-top:4px} .fn{font:8.5pt %s;margin:2px 0} h1{font:bold 20pt %s} h2{font:bold 14pt %s}" SANS SANS SANS)
          :atpage (fmt "@bottom-center{content:counter(page);font:9pt %s}" SANS)
          :h PAGENUM-HINT})

    (b= (fmt "<h1>Essay on Lighthouses</h1><p>%s%s %s%s</p><p>%s%s</p>" (para 711 3) (ref- 1) (para 712 2) (ref- 2) (para 713 3) (ref- 3)))
    (b+ "<h2>Notes</h2>"
        (apply str (for [[i t] [[1 "Stevenson, <i>Lighthouse Design</i>, 1880."] [2 "See the log of the keeper."] [3 "Lamp power in candelas is approximate."]]]
                     (fmt "<div data-note=\"endnote\" data-marker=\"%d\"><p>%s</p></div>" i t))))
    (add "footnotes-endnotes" "Essay on Lighthouses" @body
         {:css (fmt "h1{font:bold 20pt %s} h2{font:bold 14pt %s} [data-note]{font-size:10pt}" SANS SANS)})

    ;; ------------------------------------------------------------ figures
    (b= "<h1>Sales Dashboard</h1>" (hb 801 1))
    (b+ (fmt "<figure data-figure=\"chart\">%s<figcaption>Figure 1. Units sold per quarter</figcaption></figure>" (bar-chart 420 210 [120 180 150 220] ["Q1" "Q2" "Q3" "Q4"] "Units (thousands)")))
    (b+ (hb 802 2))
    (b+ (fmt "<figure data-figure=\"chart\">%s<figcaption>Figure 2: Monthly revenue trend</figcaption></figure>" (line-chart 420 190)))
    (b+ "<h2>Architecture</h2>" (hb 803 1))
    (b+ (fmt "<figure data-figure=\"diagram\">%s<figcaption>Figure 3. Request path</figcaption></figure>" (diagram 400 90)))
    (b+ "<figure data-figure=\"photo\" data-labels=\"none\" aria-label=\"Site photograph\"><div class=\"ph\"></div><figcaption>Figure 4. Site photograph, north face</figcaption></figure>")
    (b+ "<figure data-figure=\"chart\" data-labels=\"none\"><div class=\"blank\"></div></figure>" (hb 804 1))
    (add "figures-charts" "Sales Dashboard" @body
         {:css (fmt "h1{font:bold 20pt %s} h2{font:bold 14pt %s} .ph{width:260px;height:120px;background:linear-gradient(135deg,#6a8caf,#e0c097,#7d5a50)} .blank{width:320px;height:140px;border:1px dashed #999}" SANS SANS)})

    (b= "<div class=\"brand\" data-figure=\"logo\"><svg width=\"44\" height=\"44\"><circle cx=\"22\" cy=\"22\" r=\"20\" fill=\"#0a7\"/><rect x=\"12\" y=\"12\" width=\"20\" height=\"20\" fill=\"#fff\"/></svg><span data-label=\"\">NorthWind</span></div>")
    (b+ "<h1>Press Release</h1>" (hb 811 3))
    (b+ "<figure data-figure=\"screenshot\" aria-label=\"Product screenshot\"><div class=\"shot\"><b data-label=\"\">Dashboard</b></div><figcaption>Figure 1. The new interface</figcaption></figure>" (hb 812 2))
    (add "figures-logo-screenshot" "Press Release" @body
         {:css (fmt ".brand{display:flex;align-items:center;gap:8px;font:bold 14pt %s} .shot{width:320px;height:150px;border:2px solid #333;background:#f2f2f2;padding:6px;font:10pt %s} h1{font:bold 20pt %s}" SANS SANS SANS)})

    ;; ------------------------------------------------------------ watermark & seal
    (b= "<div data-gold=\"skip\" class=\"wm\">DRAFT</div>" "<h1>Draft Policy Statement</h1>" (longdoc 901 5 3))
    (add "watermark-draft" "Draft Policy Statement" @body
         {:css (fmt "h1{font:bold 20pt %s} h2{font:bold 14pt %s} .wm{position:fixed;top:38%%;left:8%%;font:bold 150pt %s;color:rgba(200,0,0,.13);transform:rotate(-30deg);z-index:-1}" SANS SANS SANS)
          :h (om "dropped" [(om "reason" "watermark" "text" "DRAFT" "pages" "all")])})

    (b= "<h1>Certificate of Completion</h1>")
    (b+ "<p>This certifies that <b>Jordan Rivera</b> completed the Advanced Safety Course on 14 March 2026 with distinction.</p>" (hb 911 2))
    (b+ "<div class=\"sealbox\"><svg data-seal=\"seal\" data-name=\"City of Riverton\" data-date=\"2026-03-14\" data-color=\"blue\" data-text=\"CITY OF RIVERTON NOTARY PUBLIC\" width=\"150\" height=\"150\" viewBox=\"0 0 150 150\" font-family=\"Helvetica\"><defs><path id=\"arc\" d=\"M75,75 m-56,0 a56,56 0 1,1 112,0 a56,56 0 1,1 -112,0\"/></defs><circle cx=\"75\" cy=\"75\" r=\"70\" fill=\"none\" stroke=\"#1f3f9f\" stroke-width=\"3\"/><circle cx=\"75\" cy=\"75\" r=\"45\" fill=\"none\" stroke=\"#1f3f9f\"/><text font-size=\"11\" fill=\"#1f3f9f\" letter-spacing=\"1.5\"><textPath href=\"#arc\">CITY OF RIVERTON - NOTARY PUBLIC -</textPath></text><text x=\"75\" y=\"80\" text-anchor=\"middle\" font-size=\"13\" font-weight=\"bold\" fill=\"#1f3f9f\">SEAL</text></svg></div>")
    (b+ "<div data-seal=\"stamp\" data-color=\"red\" class=\"stamp\">PAID<br>2026-03-14</div>")
    (b+ "<p class=\"sig\">Signed: ______________________<br><span class=\"sm\">Registrar</span></p>")
    (add "stamp-seal" "Certificate of Completion" @body
         {:css (fmt "h1{font:bold 20pt %s} .sealbox{margin:10px 0 0 20px} .stamp{display:inline-block;border:3px solid #c0201f;color:#c0201f;font:bold 18pt %s;padding:4px 14px;transform:rotate(-8deg);margin:20px 0 0 40px} .sig{margin-top:40px} .sm{font-size:9pt}" SANS SANS)})

    ;; ------------------------------------------------------------ columns
    (b= "<h1>Adaptive Sampling in Sparse Fields</h1><p class=\"au\">A. Author and B. Author</p>")
    (b+ "<h2>Abstract</h2>" (P 1001 4) "<div class=\"cols\">" (colbody 1010 5) "</div>")
    (add "columns-two" "Adaptive Sampling" @body
         {:css (fmt ".cols{column-count:2;column-gap:.35in} h1{font:bold 20pt %s;text-align:center} .au{text-align:center;font-style:italic} h2{font:bold 12pt %s;break-after:avoid} .cols p{text-align:justify}" SANS SANS)})

    (b= "<h1>The Village Gazette</h1>" "<div class=\"cols\">" (colbody 1100 6) "</div>")
    (add "columns-three-news" "The Village Gazette" @body
         {:css (fmt "body{font-size:9.5pt} .cols{column-count:3;column-gap:.25in;column-rule:1px solid #aaa} h1{font:bold 28pt %s;text-align:center;border-bottom:3px double #000} h2{font:bold 11pt %s;break-after:avoid}" SERIF SANS)
          :margin "0.8in"})

    ;; ------------------------------------------------------------ invoice & statement
    (b= "<div class=\"top\"><div><div class=\"co\">Brightline Supplies Ltd.</div><p>14 Harbour Road<br>Portsmouth PO1 2AB<br>United Kingdom</p></div><div class=\"ib\"><h1>INVOICE</h1><p>Invoice No: INV-2026-0417<br>Date: 2026-03-02<br>Due: 2026-04-01</p></div></div>")
    (b+ "<h2>Bill to</h2><p>Harwell &amp; Sons<br>3 Mill Lane<br>Oxford OX2 6QA</p>")
    (b+ "<table id=\"items\" data-profile=\"ruled\"><thead>" (tr ["Item" "Description" "Qty" "Unit price" "Amount"] "th" #{2 3 4}) "</thead><tbody>")
    (let [tot (volatile! 0)]
      (doseq [[c d q u] [["A-100" "Copper pipe 15mm x 3m" 40 6.5] ["B-220" "Elbow joint 15mm" 120 0.85] ["C-310" "Flux paste 100g" 12 4.2] ["D-005" "PTFE tape" 60 0.45] ["E-900" "Delivery" 1 25.0]]]
        (let [a (* q u)]
          (vswap! tot + a)
          (b+ (tr [c d (str q) (fmt "%.2f" u) (fmt "%.2f" a)] "td" #{2 3 4}))))
      (b+ "</tbody></table>")
      (b+ "<table class=\"tot\" data-profile=\"whitespace\"><tbody>" (tr ["Subtotal" (fmt "%.2f" @tot)] "td" #{1}) (tr ["VAT 20%" (fmt "%.2f" (* @tot 0.2))] "td" #{1}) (tr ["<b>Total due</b>" (fmt "<b>%.2f</b>" (* @tot 1.2))] "td" #{1}) "</tbody></table>"))
    (b+ "<h2>Payment terms</h2><p>Payment within 30 days by bank transfer to sort code 12-34-56, account 00112233. Late payments incur interest at 4% above base rate.</p>")
    (add "invoice" "Invoice INV-2026-0417" @body
         {:page "A4"
          :css (fmt ".top{display:flex;justify-content:space-between} .co{font:bold 16pt %s} h1{font:bold 26pt %s;color:#444;margin:0;text-align:right} .ib{text-align:right} h2{font:bold 11pt %s;margin:1.2em 0 .3em} table#items{width:100%%;margin-top:1em} #items th,#items td{border-bottom:1px solid #999} #items thead th{border-bottom:2px solid #000} .tot{margin-left:auto;margin-top:.6em;width:40%%}" SANS SANS SANS)
          :atpage (fmt "@bottom-center{content:\"Brightline Supplies Ltd. - Registered in England No. 0123456\";font:8pt %s;color:#555}" SANS)
          :h (om "furniture" [(om "role" "footer" "template" "Brightline Supplies Ltd. - Registered in England No. 0123456" "pages" "all")])})

    (let [rows (statement)]
      (b= "<div class=\"bank\">Marlow Savings Bank</div><h1>Statement of Account</h1>")
      (b+ "<table class=\"sum\" data-profile=\"form\"><tbody>" (tr ["Account holder" "J. Rivera"]) (tr ["Account number" "40-22-18 / 00923311"]) (tr ["Statement period" "01 Mar 2026 - 31 Mar 2026"]) (tr ["Opening balance" "4,210.55"]) "</tbody></table>")
      (b+ "<h2>Transactions</h2>")
      (b+ "<table id=\"txn\" data-multipage=\"1\" data-profile=\"ruled\" data-repeat-head=\"true\"><thead>" (tr ["Ref" "Date" "Description" "Debit" "Credit" "Balance"] "th" #{3 4 5}) "</thead><tbody>")
      (doseq [rw rows] (b+ (tr rw "td" #{3 4 5})))
      (b+ "</tbody></table><p class=\"sm\">Marlow Savings Bank is authorised and regulated by the Financial Conduct Authority.</p>")
      (add "bank-statement" "Statement of Account March 2026" @body
           {:page "A4" :margin "0.9in 0.8in"
            :css (fmt "body{font:9.5pt/1.3 %s} .bank{font:bold 15pt %s;color:#0b5} h1{font:bold 18pt %s} h2{font:bold 12pt %s} table{font-size:9pt} #txn{width:100%%} #txn th,#txn td{border-bottom:1px solid #ccc;padding:3px 6px} #txn thead th{background:#eee;border-bottom:2px solid #333} .sum td:first-child{font-weight:bold;width:30%%} .sm{font-size:8pt;color:#555} tr{break-inside:avoid}" SANS SANS SANS SANS)
            :atpage (fmt "@top-left{content:\"Account 40-22-18 / 00923311 - Statement 31 Mar 2026\";font:8pt %s}@top-right{content:\"Page \" counter(page) \" of \" counter(pages);font:8pt %s}@bottom-center{content:\"Marlow Savings Bank - Customer service 0800 000 000\";font:8pt %s;color:#555}" SANS SANS SANS)
            :h (om "labels" "arabic"
                   "furniture" [(om "role" "header" "template" "Account 40-22-18 / 00923311 - Statement 31 Mar 2026" "pages" "all")
                                (om "role" "footer" "template" "Marlow Savings Bank - Customer service 0800 000 000" "pages" "all")]
                   "dropped" [(om "reason" "page-number" "text" "Page {n} of {N}")])}))

    ;; ------------------------------------------------------------ scripts
    (b= (fmt "<h1>التقرير السنوي للشركة</h1><p>%s</p><h2>نظرة عامة</h2><p>%s</p><h2>الأهداف</h2><ul><li>تحسين جودة الخدمة</li><li>خفض التكاليف التشغيلية</li><li>توسيع قاعدة العملاء</li></ul><p>%s</p>" (AR-P 0) (AR-P 1) (AR-P 2)))
    (b+ "<table data-profile=\"ruled\"><thead><tr><th>القسم</th><th>الموظفون</th><th>النمو</th></tr></thead><tbody><tr><td>المبيعات</td><td>120</td><td>5%</td></tr><tr><td>الدعم</td><td>85</td><td>8%</td></tr><tr><td>التطوير</td><td>60</td><td>12%</td></tr></tbody></table>")
    (add "script-arabic" "التقرير السنوي" @body
         {:lang "ar" :dir "rtl"
          :css "html{font-family:'Geeza Pro','Al Bayan',Arial,sans-serif;font-size:13pt;line-height:1.8} h1{font-size:22pt;font-weight:bold} h2{font-size:16pt;font-weight:bold} table{width:100%;font-family:'Geeza Pro',Arial} th,td{border:1px solid #000;text-align:right}"})

    (b= (fmt "<h1>דוח שנתי</h1><p>%s</p><h2>סקירה כללית</h2><p>%s</p><h2>יעדים</h2><ol><li>שיפור איכות השירות</li><li>הפחתת עלויות</li><li>הרחבת בסיס הלקוחות</li></ol><p>%s</p>" (HE-P 0) (HE-P 1) (HE-P 2)))
    (add "script-hebrew" "דוח שנתי" @body
         {:lang "he" :dir "rtl"
          :css "html{font-family:'Arial Hebrew','Arial',sans-serif;font-size:13pt;line-height:1.7} h1{font-size:22pt;font-weight:bold} h2{font-size:16pt;font-weight:bold}"})

    (b= "<h1>年度报告</h1><p>本公司五十年前在沿海的一座小城市开始经营，随后逐步发展，成为本地区最大的机构之一。</p><h2>概述</h2><p>新的计划侧重于改善服务、降低成本，并在未来几年内提高所有分支机构的客户满意度。</p><h2>目标</h2><ol><li>提高服务质量</li><li>降低运营成本</li><li>扩大客户基础</li></ol>")
    (b+ "<h1>年次報告書</h1><p>当社は五十年前に海沿いの小さな町で事業を始め、徐々に拡大して地域最大級の組織の一つとなりました。</p><h2>概要</h2><p>新しい計画は、サービスの向上とコストの削減、そして全支店の顧客満足度の向上に重点を置いています。</p>")
    (b+ "<table data-profile=\"ruled\"><thead><tr><th>部門</th><th>人数</th><th>成長率</th></tr></thead><tbody><tr><td>営業</td><td>120</td><td>5%</td></tr><tr><td>サポート</td><td>85</td><td>8%</td></tr></tbody></table>")
    (add "script-cjk" "年度报告 / 年次報告書" @body
         {:lang "zh"
          :css "html{font-family:'Hiragino Sans GB','Hiragino Sans',sans-serif;font-size:12pt;line-height:1.8} h1{font-size:22pt;font-weight:bold} h2{font-size:15pt;font-weight:bold} table{border:1px solid #000} th,td{border:1px solid #000}"})

    (b= "<h1>वार्षिक रिपोर्ट</h1><p>कंपनी ने पचास साल पहले तट पर एक छोटे शहर में अपना काम शुरू किया और धीरे-धीरे क्षेत्र के सबसे बड़े संगठनों में से एक बन गई।</p><h2>अवलोकन</h2><p>नई योजना सेवाओं को बेहतर बनाने, लागत कम करने और आने वाले वर्षों में सभी शाखाओं में ग्राहकों की संतुष्टि बढ़ाने पर केंद्रित है।</p><h2>लक्ष्य</h2><ul><li>सेवा की गुणवत्ता में सुधार</li><li>परिचालन लागत में कमी</li><li>ग्राहक आधार का विस्तार</li></ul>")
    (add "script-devanagari" "वार्षिक रिपोर्ट" @body
         {:lang "hi"
          :css "html{font-family:'Devanagari Sangam MN','Kohinoor Devanagari',sans-serif;font-size:12.5pt;line-height:1.9} h1{font-size:22pt;font-weight:bold} h2{font-size:16pt;font-weight:bold}"})

    (b= "<h1>Multilingual Glossary</h1><p>One table, four scripts, each row a translation of the same term.</p>")
    (b+ "<table data-profile=\"ruled\"><thead><tr><th>English</th><th lang=\"ar\" dir=\"rtl\">العربية</th><th lang=\"zh\">中文</th><th lang=\"hi\">हिन्दी</th></tr></thead><tbody>")
    (doseq [[e a z h] [["Report" "تقرير" "报告" "रिपोर्ट"] ["Budget" "ميزانية" "预算" "बजट"] ["Customer" "عميل" "客户" "ग्राहक"] ["Quality" "جودة" "质量" "गुणवत्ता"]]]
      (b+ (fmt "<tr><td>%s</td><td lang=\"ar\" dir=\"rtl\">%s</td><td lang=\"zh\">%s</td><td lang=\"hi\">%s</td></tr>" e a z h)))
    (b+ "</tbody></table>")
    (add "script-mixed" "Multilingual Glossary" @body
         {:css (fmt "table{width:100%%;font-family:'Geeza Pro','Hiragino Sans GB','Devanagari Sangam MN',Helvetica,sans-serif} th,td{border:1px solid #000} h1{font:bold 20pt %s}" SANS)})
    @docs))

;; =====================================================================================================
;; P2.13 (D114): initials, alignment and columns.  19 documents.
;;
;; Authoring attributes html2gold reads (it never computes CSS): data-initial / data-lines /
;; data-initial-text, data-align, data-cols / data-span, data-note="sidebar|margin".  A drop cap is sized
;; so that its float is 2 pt shorter than n body lines (so exactly n lines wrap it, and build.clj checks
;; that they do): the glyph's baseline lands on line n's, its cap top near line 1's.
;; =====================================================================================================

(def LONGW ["understanding" "administration" "communication" "organization" "responsibility"
            "environmental" "opportunities" "development" "international" "performance"
            "information" "transportation" "investigation" "representative" "infrastructure"
            "consideration" "implementation" "characteristics" "circumstances" "documentation"])

(defn- mixed-pick [r] (if (zero? (rnd/randint r 0 2)) (rnd/choice r LONGW) (rnd/choice r WORDS)))

(defn- sentence-with [r lo hi pick]
  (let [n (rnd/randint r lo hi)
        s (str/join " " (vec (repeatedly n #(pick r))))
        s (str (str/upper-case (subs s 0 1)) (subs s 1) ".")]
    (-> s (str/replace " i " " I ") (str/replace "don't" "do not") (str/replace "didn't" "did not"))))

(defn para-mixed
  "A paragraph in which a third of the words are long ones (for hyphenation)."
  [seed n lo hi]
  (let [r (rnd/make seed)] (str/join " " (vec (repeatedly n #(sentence-with r lo hi mixed-pick))))))

(defn lead
  "A hand-written first sentence, then n generated ones."
  [sentence seed n lo hi]
  (str sentence " " (para seed n lo hi)))

(defn Pa
  "<p> with a class and extra attributes (a string beginning with a space)."
  [cls text & [extra]]
  (str "<p" (when cls (str " class=\"" cls "\"")) (or extra "") ">" text "</p>"))

(defn dropcap-css
  "Declarations for a float spanning n lines of a `body` pt font on a `lh` pt line: its height n*lh-2,
  its font size such that the baseline is line n's (metrics a, d: ascent and descent in em; `:f` forces
  a size; `:side` is left or right)."
  [n body lh & {:keys [a d pad f side] :or {a 0.917 d 0.219 pad 5 side "left"}}]
  (let [h (- (* n lh) 2.0)
        b (+ (* (dec n) lh) (/ (- lh (* (+ a d) body)) 2.0) (* a body))
        fs (or f (/ (- b (/ h 2.0)) (/ (- a d) 2.0)))]
    (fmt "float:%s;font-size:%.2fpt;line-height:%.2fpt;padding:%s;margin:0" side fs h
         (if (= side "left") (fmt "0 %dpt 0 0" pad) (fmt "0 0 0 %dpt" pad)))))

(def JA
  ["彼は朝早く港の近くの小さな店に入り、温かいお茶を頼んだ。"
   "窓の外では漁師たちが網を直しながら、昨夜の嵐について話していた。"
   "店の主人は古い時計を見上げて、今日は風が変わるだろうと言った。"
   "海の色は刻々と変わり、やがて空との境がわからなくなった。"
   "旅人は地図を広げ、次の町までの道を指でたどっていった。"
   "山のふもとには小さな畑が広がり、黄色い花が風に揺れていた。"
   "子どもたちは川辺に集まり、平らな石を投げて遊んでいた。"
   "夕方になると、遠くの寺の鐘がゆっくりと三度鳴った。"
   "老人は縁側に座り、庭の梅の木をいつまでも眺めていた。"
   "市場には季節の果物が並び、売り声が通りに響いていた。"
   "図書館の奥には、誰も読まなくなった古い地図が眠っている。"
   "春の雨は静かに降り、屋根の上で小さな音を立てていた。"
   "列車は定刻に駅を出て、雪におおわれた谷へと入っていった。"
   "彼女は手紙を書き終えると、ゆっくりとペンを置いて息をついた。"
   "港の灯台は夜ごとに光を回し、遠い船に道を教えている。"
   "新しい橋が完成した日、町じゅうの人々が川のほとりに集まった。"
   "雲の切れ間から朝日が差し込み、濡れた石畳が金色に光った。"
   "彼は古い鞄を肩にかけ、誰にも告げずに村を出ていった。"
   "台所からは煮物のやさしい匂いが流れ、家じゅうに広がった。"
   "湖の向こうの山々は、夏でも頂に白い雪をかぶっている。"
   "先生は黒板に大きく円を描いて、ゆっくりと説明を始めた。"
   "祭りの夜には提灯がともり、通りは人の波でいっぱいになった。"
   "猫は日だまりで丸くなり、午後の時間をのんびりと過ごしていた。"
   "冬の朝の空気は澄み渡り、遠くの山の輪郭がくっきりと見えた。"])

(def HE-WORDS
  (vec (distinct (filter #(re-matches #"[א-ת]+" %)
                         (mapcat #(str/split (str/replace % #"[,.]" "") #"\s+")
                                 (concat HE-P ["הקבוצה קיימה את הפגישה השבועית בבניין הישן ליד הנמל ודנה בתוצאות הרבעון האחרון"
                                               "המנהל ביקש מכל הצוותים להגיש דוחות מסודרים ולהציע דרכים לשפר את העבודה בחודשים הבאים"]))))))

(defn he-para [seed n lo hi]
  (let [r (rnd/make seed)]
    (str/join " " (vec (repeatedly n #(let [k (rnd/randint r lo hi)]
                                        (str (str/join " " (vec (repeatedly k (fn [] (rnd/choice r HE-WORDS))))) ".")))))))

(defn ja-paras
  "Paragraphs of the Japanese pool, n sentences each, from sentence index `from`, optionally led by a bracket."
  [from n]
  (str/join "" (subvec JA from (+ from n))))

(defn initial-tile!
  "The PNG of initial-image: a Georgia W on a bordered, filled square at 300 dpi, made by ImageMagick."
  [out-dir]
  (let [dir (io/file out-dir "img")
        f (io/file dir "initial-W.png")]
    (.mkdirs dir)
    (let [r (p/sh "magick" "-size" "192x192" "xc:#7a1f1f"
                  "-font" "/System/Library/Fonts/Supplemental/Georgia.ttf" "-pointsize" "132"
                  "-fill" "#f4e8c8" "-gravity" "center" "-annotate" "+0+6" "W"
                  "-fill" "none" "-stroke" "#f4e8c8" "-strokewidth" "5" "-draw" "rectangle 9,9 182,182"
                  "-units" "PixelsPerInch" "-density" "300" "-strip" (str f))]
      (when-not (zero? (:exit r)) (throw (ex-info (str "magick failed: " (:err r)) r))))))

(defn build-d114
  "The 19 documents of D114 as {name html}."
  []
  (let [docs (atom {})
        add (fn [name title body & [opts]] (swap! docs assoc name (doc title body (or opts {}))))
        LAYOUT (om "layout" true)
        ;; --- Georgia 11 pt on a 15.95 pt line
        B 11 LH 15.95
        ini (fn [n] (dropcap-css n B LH))]

    ;; ------------------------------------------------------------------------------ initials
    (add "initial-firstletter" "Notes on Openings"
         (str "<h1>Notes on Openings</h1>"
              (Pa "d2" (lead "It was the first warm evening of the year, and the harbour lights were already burning." 5001 7 11 17)
                  " data-initial=\"drop\" data-lines=\"2\"")
              (Pa "d3" (lead "A long time ago, in a valley far from the nearest road, there stood a mill that nobody remembered building." 5002 7 11 17)
                  " data-initial=\"drop\" data-lines=\"3\"")
              (Pa "d4" (lead "I remember the harbour as it was before the new pier, when the boats still came in at dusk and the gulls followed them." 5003 8 11 17)
                  " data-initial=\"drop\" data-lines=\"4\"")
              (Pa "d3" (lead "At the edge of the old town, where the lane narrows between the tannery and the chapel, a single lamp has burned every night since the war." 5004 7 11 17)
                  " data-initial=\"drop\" data-lines=\"3\"")
              (Pa "d2" (lead "“Nothing here is what it seems,” said the clerk, folding the ledger shut and looking past the visitor at the rain." 5005 7 11 17)
                  " data-initial=\"drop\" data-lines=\"2\"")
              (Pa "d3" (str "T<span class=\"sc\">HE BUREAU</span> of Weights and Measures opened its doors at nine, as it had every morning for sixty years. " (para 5006 7 11 17))
                  " data-initial=\"drop\" data-lines=\"3\""))
         {
          :css (str "h1{font:bold 20pt " SANS "} .sc{font-size:.82em;letter-spacing:.05em}"
                    " p.d2::first-letter{" (ini 2) "} p.d3::first-letter{" (ini 3) "} p.d4::first-letter{" (ini 4) "}")})

    (add "initial-raised" "Raised Initials"
         (str "<h1>Raised Initials</h1>"
              (Pa "r" (str "<span class=\"ri\" data-initial=\"raised\">T</span>he morning train was late again, and the platform filled slowly with people who knew better than to complain. " (para 5101 6 11 17)))
              (Pa nil (para 5102 5 11 17))
              (Pa "r" (str "<span class=\"ri\" data-initial=\"raised\">S</span>omewhere beyond the orchard the river turned south, and nobody who lived there had ever followed it to the sea. " (para 5103 6 11 17)))
              (Pa "r" (str "<span class=\"ri\" data-initial=\"raised\">L</span>ater that week the council met to discuss the bridge, the budget and the vexed question of the clock. " (para 5104 6 11 17)))
              (Pa nil (para 5105 5 11 17))
              (Pa "r" (str "<span class=\"ri\" data-initial=\"raised\">M</span>any of the letters were never answered, though the postmaster kept every one of them in a drawer by the stove. " (para 5106 6 11 17))))
         {:css (str "h1{font:bold 20pt " SANS "} p.r{margin-top:1.5em} .ri{font-size:2.7em;line-height:0;font-weight:bold;color:#7a1f1f}")})

    (add "initial-image" "An Image Initial"
         (str "<h1>An Image Initial</h1>"
              (Pa nil (para 5201 5 11 17))
              "<p><img class=\"dc\" src=\"img/initial-W.png\" alt=\"W\" data-initial=\"drop\" data-lines=\"3\">hen the last boat had gone out, the town went quiet in a way that visitors found hard to describe. " (para 5202 7 11 17) "</p>"
              (Pa nil (para 5203 5 11 17)))
         {:css (str "h1{font:bold 20pt " SANS "} img.dc{float:left;width:45.85pt;height:45.85pt;margin:0 6pt 0 0}")})

    (let [svg (fn [size letter path]
                (str "<svg class=\"dc\" data-initial=\"drop\" data-lines=\"" (if (= size 45.85) 3 2) "\" data-initial-text=\"" letter
                     "\" width=\"" size "pt\" height=\"" size "pt\" viewBox=\"0 0 46 46\"><path fill=\"#7a1f1f\" d=\"" path "\"/></svg>"))
          T "M3,3 H43 V12 H27 V43 H19 V12 H3 Z"
          L "M8,3 H18 V34 H42 V43 H8 Z"]
      (add "initial-vector" "Vector Initials"
           (str "<h1>Vector Initials</h1>"
                (Pa nil (para 5301 5 11 17))
                "<p>" (svg 45.85 "T" T) "he lamplighter came at the same hour each evening, and the children learned to tell the time by the order in which the windows woke. " (para 5302 7 11 17) "</p>"
                "<p>" (svg 29.9 "L" L) "ong before the railway arrived the valley had its own calendar, kept in the names of the fields. " (para 5303 7 11 17) "</p>")
           {:css (str "h1{font:bold 20pt " SANS "} svg.dc{float:left;margin:0 6pt 0 0}")}))

    (add "initial-firstword" "First Words"
         (str "<h1>First Words</h1>"
              "<p><span class=\"fw\" data-initial=\"drop\" data-lines=\"2\">IT</span> was a bright cold day in April, and the clocks were striking thirteen. " (para 5401 7 11 17) "</p>"
              "<p><span class=\"fw\" data-initial=\"drop\" data-lines=\"2\">ONCE</span> there lived a miller whose three sons could not agree about anything at all. " (para 5402 7 11 17) "</p>"
              "<p><span class=\"fw\" data-initial=\"drop\" data-lines=\"2\">WHEN</span> the snow came that winter it came all at once, and the road to the mill was gone by morning. " (para 5403 7 11 17) "</p>")
         {:css (str "h1{font:bold 20pt " SANS "} span.fw{float:left;font:bold 30pt/29.9pt Georgia,serif;line-height:29.9pt;margin:0 7pt 0 0;color:#7a1f1f}")})

    (add "initial-columns" "Drop Caps in Columns"
         (str "<h1>Drop Caps in Columns</h1><div class=\"cols\" data-cols=\"2\">"
              (Pa nil (para 5501 3 10 15))
              (Pa "d3" (lead "Everything about the harbour had been measured, and nothing about it was certain." 5502 4 10 15)
                  " data-initial=\"drop\" data-lines=\"3\"")
              (Pa nil (para 5504 3 10 15))
              (Pa "d3 cb" (lead "Nobody argued with the tide, which came in twice a day and left the evidence." 5503 5 10 15)
                  " data-initial=\"drop\" data-lines=\"3\"")
              (Pa nil (para 5505 4 10 15))
              "</div>")
         {:h LAYOUT
          :css (str "h1{font:bold 20pt " SANS "} .cols{column-count:2;column-gap:.4in;height:6in;column-fill:auto} .cb{break-before:column}"
                    " p.d3::first-letter{" (ini 3) "}")})

    (add "initial-rtl" "פתיחות"
         (str "<h1>פתיחות בעברית</h1>"
              (Pa "d3" (he-para 5601 6 9 14) " data-initial=\"drop\" data-lines=\"3\"")
              (Pa nil (he-para 5602 4 9 14))
              (Pa "d3" (he-para 5603 6 9 14) " data-initial=\"drop\" data-lines=\"3\""))
         {:lang "he" :dir "rtl"
          :css (str "html{font-family:'Arial Hebrew','Arial',sans-serif;font-size:13pt;line-height:1.7} h1{font-size:22pt;font-weight:bold}"
                    " p.d3::first-letter{" (dropcap-css 3 13 22.1 :side "right" :a 0.9 :d 0.3) "}")})

    (add "initial-cjk" "港町の一日"
         (str "<h1>港町の一日</h1>"
              (Pa nil (ja-paras 0 8) " data-initial=\"drop\" data-lines=\"3\"")
              (Pa nil (str "「今日は風が強いですね」と店の主人が言った。" (ja-paras 8 7)) " data-initial=\"drop\" data-lines=\"3\"")
              (Pa nil (ja-paras 15 9) " data-initial=\"drop\" data-lines=\"3\""))
         {:lang "ja"
          :css (str "html{font-family:'Hiragino Sans GB','Hiragino Sans',sans-serif;font-size:12pt;line-height:1.8} h1{font-size:22pt;font-weight:bold}"
                    " p::first-letter{" (dropcap-css 3 12 21.6 :f 56 :pad 6) "}")})

    (add "initial-letter-headings" "Glossary of Field Terms"
         (str "<h1>Glossary of Field Terms</h1>"
              "<h2 class=\"dv\">A</h2>"
              "<p><b>Aardvark</b> A burrowing animal that feeds at night on ants and termites. " (para 5701 1 10 14) "</p>"
              "<p><b>Abacus</b> A frame of beads used for counting before printed tables were common. " (para 5702 1 10 14) "</p>"
              "<p><b>Anchorage</b> A place where a vessel may lie safely on its cable. " (para 5703 1 10 14) "</p>"
              "<h2 class=\"dv\">B</h2>"
              "<p><b>Barometer</b> An instrument for reading the weight of the air. " (para 5704 1 10 14) "</p>"
              "<p><b>Basalt</b> A dark volcanic rock that cools into close columns. " (para 5705 1 10 14) "</p>"
              "<p><b>Beacon</b> A fire or lamp set on a height to warn or to guide. " (para 5706 1 10 14) "</p>"
              "<h2 class=\"dv\">C</h2>"
              "<p><b>Cairn</b> A heap of stones raised as a landmark or a grave. " (para 5707 1 10 14) "</p>"
              "<p><b>Compass</b> A needle that turns to the north and so tells a traveller where he is. " (para 5708 1 10 14) "</p>"
              "<p><b>Contour</b> A line on a map joining points of equal height. " (para 5709 1 10 14) "</p>")
         {:h (om "initial" true)
          :css (str "h1{font:bold 20pt " SANS "} h2.dv{font:bold 30pt " SERIF ";margin:.9em 0 .25em;color:#7a1f1f}")})

    ;; ------------------------------------------------------------------------------ alignment
    (let [al (fn [tag kind text] (str "<" tag " class=\"a-" kind "\" data-align=\"" kind "\">" text "</" tag ">"))
          css (str ".a-start{text-align:start} .a-end{text-align:end} .a-center{text-align:center} .a-justify{text-align:justify}"
                   " h1{font:bold 20pt " SANS "} h2{font:bold 13pt " SANS "} figcaption{font:italic 9.5pt " SANS "}")]
      (add "align-paragraphs" "Notes on Alignment"
           (str (al "h1" "center" "Notes on Alignment")
                (al "h2" "start" "Start") (al "p" "start" (para 6001 4 11 17))
                (al "h2" "start" "End") (al "p" "end" (para 6002 4 11 17))
                (al "h2" "center" "Center") (al "p" "center" (para 6003 4 11 17))
                (al "h2" "start" "Justify") (al "p" "justify" (para 6004 5 11 17))
                (al "h2" "center" "Single lines")
                (al "p" "center" "A single line, centred.")
                (al "p" "end" "A single line at the end edge.")
                (al "p" "justify" "A justified paragraph that happens to be one line.")
                "<figure data-figure=\"chart\">" (bar-chart 360 170 [120 180 150 220] ["Q1" "Q2" "Q3" "Q4"] "Units (thousands)")
                "<figcaption class=\"a-center\" data-align=\"center\">Figure 1. Units sold per quarter</figcaption></figure>")
           {:css css}))

    (add "align-justify-hyphen" "Justified and Hyphenated"
         (str "<h1 data-align=\"start\">Justified and Hyphenated</h1><div class=\"n\" data-cols=\"2\">"
              (apply str (for [i (range 4)]
                           (str "<h2 data-align=\"start\">" (nth ["Method" "Findings" "Discussion" "Limits"] i) "</h2>"
                                "<p data-align=\"justify\">" (para-mixed (+ 6100 (* i 2)) 4 9 15) "</p>"
                                "<p data-align=\"justify\">" (para-mixed (+ 6101 (* i 2)) 3 9 15) "</p>")))
              "</div>")
         {:lang "en" :margin "1in 1.6in"
          :css (str "h1{font:bold 18pt " SANS "} h2{font:bold 11pt " SANS ";break-after:avoid;hyphens:none}"
                    " .n{column-count:2;column-gap:.3in} .n p{text-align:justify;hyphens:auto;font-size:10.5pt;margin:0 0 .6em}")})

    (let [rows (let [r (rnd/make 6201)
                     items ["Pipe" "Elbow" "Flux" "Tape" "Valve" "Gasket" "Clamp" "Filter" "Nozzle" "Bracket"]
                     regions ["North" "South" "East" "West"]]
                 (vec (for [i (range 10)]
                        (let [units (rnd/randint r 12 9800)
                              rev (rnd/randint r 1000 480000)
                              cents (rnd/randint r 0 99)
                              g (rnd/randint r 1 3)
                              gw (rnd/randint r -9 19)
                              gf (case g 1 "" 2 (str "." (rnd/randint r 1 9)) (str "." (rnd/randint r 10 99)))]
                          [(nth items i) (rnd/choice r regions) (pyfmt/commas units 0)
                           (pyfmt/commas (+ rev (/ cents 100.0)) 2) (str gw) gf]))))
          cell (fn [tag a cls txt] (str "<" tag " class=\"" cls "\" data-align=\"" a "\">" txt "</" tag ">"))]
      (add "align-table-numeric" "Sales by Region"
           (str "<h1 data-align=\"start\">Sales by Region</h1>"
                "<p data-align=\"start\">Units are whole numbers, revenue is to the cent, and growth is shown to its own precision, with the point lined up.</p>"
                "<table data-profile=\"ruled\"><thead><tr>"
                (apply str (for [h ["Item" "Region" "Units" "Revenue" "Growth %"]] (cell "th" "center" "c" h)))
                "</tr></thead><tbody>"
                (apply str (for [[it rg un rv gw gf] rows]
                             (str "<tr>" (cell "td" "start" "l" it) (cell "td" "start" "l" rg) (cell "td" "end" "r" un) (cell "td" "end" "r" rv)
                                  "<td class=\"dec\" data-align=\"none\"><span class=\"ip\">" gw "</span><span class=\"fp\">" gf "</span></td></tr>")))
                "</tbody></table>")
           {:css (str "h1{font:bold 20pt " SANS "} table{width:100%;font:10.5pt " SANS "} th,td{border-bottom:1px solid #bbb;padding:4px 10px}"
                      " thead th{border-bottom:2px solid #333;background:#eee} td.l{text-align:left} td.r{text-align:right} th.c{text-align:center}"
                      " td.dec{text-align:right;white-space:nowrap} .dec .fp{display:inline-block;width:2.2em;text-align:left}")}))

    (let [al (fn [tag kind text] (str "<" tag " class=\"a-" kind "\" data-align=\"" kind "\">" text "</" tag ">"))]
      (add "align-rtl" "יישור"
           (str (al "h1" "center" "יישור בעברית")
                (al "h2" "start" "התחלה") (al "p" "start" (he-para 6301 4 9 14))
                (al "h2" "start" "סוף") (al "p" "end" (he-para 6302 4 9 14))
                (al "h2" "center" "מרכז") (al "p" "center" (he-para 6303 4 9 14))
                (al "h2" "start" "יישור דו-צדדי") (al "p" "justify" (he-para 6304 5 9 14)))
           {:lang "he" :dir "rtl"
            :css (str ".a-start{text-align:start} .a-end{text-align:end} .a-center{text-align:center} .a-justify{text-align:justify}"
                      " html{font-family:'Arial Hebrew','Arial',sans-serif;font-size:13pt;line-height:1.7} h1{font-size:22pt;font-weight:bold} h2{font-size:15pt;font-weight:bold}")}))

    ;; ------------------------------------------------------------------------------ columns
    (add "columns-continue" "Notes on Continuation"
         (str "<h1>Notes on Continuation</h1>"
              "<div class=\"cols\" data-cols=\"2\">"
              (P 6401 5 11 17)
              (str "<h2>First Findings</h2>")
              (str "<p>" (para 6402 4 11 17) (ref- 1) " " (para 6403 4 11 17) "</p>")
              (P 6404 5 11 17) (P 6405 5 11 17)
              (str "<p>" (para 6406 4 11 17) (ref- 2) " " (para 6407 3 11 17) "</p>")
              (P 6408 5 11 17) (P 6409 5 11 17)
              (str "<h2>Second Findings</h2>")
              (P 6410 5 11 17) (P 6411 5 11 17) (P 6412 5 11 17)
              "<div class=\"notes\" data-span=\"all\">"
              (fn- 1 "Source: the regional survey office, 2025.") (fn- 2 "Seasonally adjusted and rounded.")
              "</div>"
              (P 6413 5 11 17) (P 6414 5 11 17)
              (str "<h2>Conclusions</h2>")
              (str "<p>" (para 6415 4 11 17) (ref- 3) " " (para 6416 3 11 17) "</p>")
              (P 6417 5 11 17) (P 6418 5 11 17)
              "<div class=\"notes\" data-span=\"all\">"
              (fn- 3 "Excludes provisional returns.")
              "</div></div>")
         {:h LAYOUT :lang "en"
          :css (str "h1{font:bold 20pt " SANS ";text-align:center} h2{font:bold 12pt " SANS ";break-after:avoid;hyphens:none;margin:1em 0 .4em}"
                    " .cols{column-count:2;column-gap:.35in} .cols p{text-align:justify;hyphens:auto;margin:0 0 .6em}"
                    " .notes{column-span:all;border-top:1px solid #000;width:2.4in;margin-top:.6em;padding-top:4px}"
                    " .fn{font:8.5pt " SANS ";margin:2px 0}")})

    (add "columns-uneven" "A Field Report with a Sidebar"
         (str "<h1>A Field Report with a Sidebar</h1>"
              "<div class=\"g\" data-cols=\"2\">"
              "<p class=\"b1\">" (para 6501 4 11 17) "</p>"
              "<p class=\"b2\">" (para 6502 4 11 17) "</p>"
              "<aside data-note=\"sidebar\"><h3>Method in brief</h3><p>" (para 6503 4 9 14) "</p><p>" (para 6504 3 9 14) "</p></aside>"
              "<p class=\"b3\">" (para 6505 4 11 17) "</p>"
              "<p class=\"b4\">" (para 6506 4 11 17) "</p>"
              "<p class=\"b5\">" (para 6507 4 11 17) "</p></div>")
         {:h LAYOUT
          :css (str "h1{font:bold 20pt " SANS "} .g{display:grid;grid-template-columns:2fr 1fr;column-gap:.3in;align-items:start}"
                    " .g p{grid-column:1} .b1{grid-row:1} .b2{grid-row:2} .b3{grid-row:3} .b4{grid-row:4} .b5{grid-row:5}"
                    " aside{grid-column:2;grid-row:2 / span 3;border:1px solid #567;background:#eef2f7;padding:8px 10px;font:9.5pt/1.35 " SANS "}"
                    " aside h3{font:bold 11pt " SANS ";margin:0 0 .4em} aside p{margin:0 0 .5em;grid-column:auto}")})

    (add "columns-span-figure" "Spanning Elements"
         (str "<h1>Spanning Elements</h1>"
              "<div class=\"cols\" data-cols=\"2\">"
              (P 6601 5 11 17) (P 6602 5 11 17)
              "<figure class=\"sp\" data-figure=\"chart\" data-span=\"all\">"
              (bar-chart 420 190 [120 180 150 220] ["Q1" "Q2" "Q3" "Q4"] "Units (thousands)")
              "<figcaption>Figure 1. Units sold per quarter</figcaption></figure>"
              (P 6603 5 11 17) (P 6604 5 11 17)
              "<figure data-figure=\"chart\">" (line-chart 280 150) "<figcaption>Figure 2. Monthly revenue trend</figcaption></figure>"
              (P 6605 5 11 17)
              "<table class=\"sp\" data-span=\"all\" data-profile=\"ruled\"><caption>Table 1. Quarterly results</caption><thead>"
              (tr ["Quarter" "Units" "Revenue" "Margin"] "th" #{1 2 3}) "</thead><tbody>"
              (tr ["First" "1,200" "14,400" "12.5"] "td" #{1 2 3}) (tr ["Second" "1,800" "21,600" "13.1"] "td" #{1 2 3})
              (tr ["Third" "1,500" "18,000" "12.8"] "td" #{1 2 3}) (tr ["Fourth" "2,200" "26,400" "14.0"] "td" #{1 2 3})
              "</tbody></table>"
              (P 6606 5 11 17) (P 6607 5 11 17) (P 6608 5 11 17)
              "</div>")
         {:h LAYOUT
          :css (str "h1{font:bold 20pt " SANS "} .cols{column-count:2;column-gap:.35in}"
                    " .cols figure{break-inside:avoid;margin:.6em 0} .sp{column-span:all} table.sp{width:100%;margin:.8em 0}"
                    " table.sp caption{font:italic 9.5pt " SANS ";text-align:left;padding:2px 0}"
                    " table.sp th,table.sp td{border-bottom:1px solid #bbb} table.sp thead th{border-bottom:2px solid #333}")})

    (add "columns-mid-page" "Changing Columns"
         (str "<h1>Changing Columns</h1>"
              (P 6701 3 11 17)
              "<div class=\"c2\" data-cols=\"2\">" (P 6702 4 11 17) (P 6703 4 11 17) (P 6704 4 11 17) "</div>"
              "<h2>A Full-Width Interlude</h2>" (P 6705 3 11 17)
              "<div class=\"c3\" data-cols=\"3\">" (P 6706 3 11 17) (P 6707 3 11 17) (P 6708 3 11 17) (P 6709 3 11 17) "</div>")
         {:h LAYOUT
          :css (str "h1{font:bold 20pt " SANS "} h2{font:bold 13pt " SANS "} .c2{column-count:2;column-gap:.35in;margin-bottom:.8em}"
                    " .c3{column-count:3;column-gap:.25in;font-size:10pt}")})

    (add "columns-margin-notes" "An Essay with Margin Notes"
         (str "<h1>An Essay with Margin Notes</h1>"
              "<div class=\"mn\" data-cols=\"2\">"
              (apply str
                     (for [i (range 5)]
                       (str "<div class=\"it\"><p>" (para (+ 6800 i) 5 11 17) "</p>"
                            "<aside data-note=\"margin\"><p>"
                            (nth ["See the survey of 1925, p. 14." "An earlier draft said the opposite."
                                  "The figure is disputed; compare the tax rolls." "Not in the first edition."
                                  "Cf. the harbour master's log."] i)
                            "</p></aside></div>")))
              "</div>")
         {:h LAYOUT
          :css (str "h1{font:bold 20pt " SANS "} .mn{width:4.3in} .it{position:relative} .it p{margin:0 0 .8em}"
                    " aside{position:absolute;left:4.6in;top:0;width:1.9in;font:italic 8.5pt/1.3 " SANS ";color:#444}"
                    " aside p{margin:0}")})

    (add "columns-newspaper" "The Harbour Courier"
         (str "<h1 class=\"mast\">The Harbour Courier</h1><p class=\"dateline\">Vol. 12 - Saturday, 14 March 2026 - Price 2d</p>"
              "<div class=\"paper\" data-cols=\"5\">"
              "<div class=\"lead\"><h2 class=\"hl\">New Pier To Open After Six Years</h2>"
              (P 6901 4 10 15) (P 6902 3 10 15) (P 6903 3 10 15) (P 6904 2 10 15)
              "<p class=\"jump\">Continued on page 2</p></div>"
              "<div class=\"s s2\"><h2>Gulls Return Early</h2>" (P 6905 3 9 14) (P 6906 2 9 14) "</div>"
              "<div class=\"s s3\"><h2>Bridge Toll Unchanged</h2>" (P 6907 3 9 14) (P 6908 2 9 14) "</div>"
              "<div class=\"s s4\"><h2>Market Prices</h2>" (P 6909 3 9 14) (P 6910 2 9 14) "</div>"
              "<div class=\"s s5\"><h2>Letters to the Editor</h2>" (P 6911 4 9 14) (P 6912 3 9 14) "</div>"
              "<div class=\"s s6\"><h2>Shipping News</h2>" (P 6913 3 9 14) (P 6914 2 9 14) "</div>"
              "</div>")
         {:h LAYOUT :margin "0.6in"
          :css (str "html{font:9pt/1.3 " SERIF "} h1.mast{font:bold 40pt " SERIF ";text-align:center;margin:0;border-bottom:3px double #000}"
                    " .dateline{text-align:center;font:italic 9pt " SERIF ";margin:.2em 0 .8em;border-bottom:1px solid #000;padding-bottom:3px}"
                    " .paper{display:grid;grid-template-columns:repeat(5,1fr);column-gap:.22in;row-gap:.3in;align-items:start}"
                    " .lead{grid-column:1 / span 3;column-count:3;column-gap:.22in;column-rule:1px solid #999}"
                    " .lead h2.hl{column-span:all;font:bold 22pt " SERIF ";margin:0 0 .4em;line-height:1.1}"
                    " .s2{grid-column:4;grid-row:1} .s3{grid-column:5;grid-row:1}"
                    " .s4{grid-column:1;grid-row:2} .s5{grid-column:2 / span 2;grid-row:2} .s6{grid-column:4 / span 2;grid-row:2}"
                    " .s{border-left:1px solid #999;margin-left:-.11in;padding-left:.11in} .s h2{font:bold 11pt " SERIF ";margin:0 0 .3em}"
                    " p{margin:0 0 .5em;text-align:justify;hyphens:auto} .jump{font-style:italic;text-align:right;margin:0}")
          :lang "en"})

    @docs))

(defn build
  "Every document: the 36 of P2.8 and the 19 of P2.13 (D114), as {name html}."
  []
  (merge (build-p28) (build-d114)))

(defn -main [& args]
  (let [out (or (first args) "test/corpus/synth/html")
        docs (build)]
    (.mkdirs (io/file out))
    (doseq [[name html] (sort-by key docs)]
      (spit (io/file out (str name ".html")) html :encoding "UTF-8"))
    (initial-tile! out)
    (println (fmt "wrote %d documents to %s" (count docs) out))))
