#!/usr/bin/env python3
"""Author the synthetic HTML corpus (DEVLOG P2.8, D041, D063).

    tools/synth/make_html.py            write test/corpus/synth/html/*.html

The COMMITTED HTML is the corpus; this script is how it was authored (bulk
rows and filler text are generated, deterministically from fixed seeds) and
exists so a reviewer can change a document and see the diff.  Fonts are
system fonts only (Georgia, Helvetica, Courier New, Arial, Geeza Pro, Arial
Hebrew, Hiragino Sans, Devanagari Sangam MN): nothing is fetched, so a
render is reproducible on the same OS.  No text is `text-transform`ed: what
prints is what the source says.  See html2gold.py for the HTML subset.
"""
import json
import os
import random

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "test", "corpus", "synth", "html")

WORDS = ("the of and to in is that for it as with was on be by at this have from or one had "
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
         "report review policy region market growth figure measure results method sample").split()


def sentence(r, lo=8, hi=16):
    n = r.randint(lo, hi)
    w = [r.choice(WORDS) for _ in range(n)]
    s = " ".join(w)
    s = s[0].upper() + s[1:] + "."
    return s.replace(" i ", " I ").replace("don't", "do not").replace("didn't", "did not")


def para(seed, n=4, lo=8, hi=16):
    r = random.Random(seed)
    return " ".join(sentence(r, lo, hi) for _ in range(n))


def P(seed, n=4, **kw):
    return "<p>%s</p>" % para(seed, n, **kw)


def hints(**h):
    return '<script type="application/emon-gold+json">%s</script>' % json.dumps(h, ensure_ascii=False)


SERIF = "Georgia, 'Times New Roman', serif"
SANS = "Helvetica, Arial, sans-serif"

BASE = """
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
""" % (SERIF, SANS, SANS)


def doc(title, body, css="", lang="en", page="Letter", margin="1in", atpage="", dir="ltr",
        h=None, base=BASE, rawpage=""):
    at = "@page{size:%s;margin:%s;%s}\n%s" % (page, margin, atpage, rawpage)
    return """<!doctype html>
<html lang="%s" dir="%s">
<head>
<meta charset="utf-8">
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
""" % (lang, dir, title, at, base, css, hints(**h) if h else "", body)


DOCS = {}


def add(name, *a, **k):
    DOCS[name] = doc(*a, **k)


# ------------------------------------------------------------------ headings

def hb(seed, n=2):
    return P(seed, n)


# 1 plain, depth 4
body = "<h1>Field Notes on Soil Moisture</h1>" + hb(1)
for i, t2 in enumerate(["Background", "Method"]):
    body += "<h2>%s</h2>" % t2 + hb(10 + i)
    for j, t3 in enumerate(["Site selection", "Instruments"]):
        body += "<h3>%s</h3>" % t3 + hb(20 + i * 5 + j)
        for k, t4 in enumerate(["Calibration", "Logging"]):
            body += "<h4>%s</h4>" % t4 + hb(40 + i * 10 + j * 3 + k, 1)
add("h-plain", "Field Notes on Soil Moisture", body, css="""
h1{font:bold 22pt %s} h2{font:bold 16pt %s} h3{font:bold 13pt %s} h4{font:italic bold 11pt %s}
""" % (SANS, SANS, SANS, SANS))

# 2 numbered decimal, depth 4
body = '<h1><span class="num">1</span> Introduction</h1>' + hb(51)
n2 = 0
for a, t2 in enumerate(["Purpose", "Scope", "Definitions"], 1):
    body += '<h2><span class="num">1.%d</span> %s</h2>' % (a, t2) + hb(60 + a)
    for b, t3 in enumerate(["General", "Exceptions"], 1):
        body += '<h3><span class="num">1.%d.%d</span> %s</h3>' % (a, b, t3) + hb(70 + a * 3 + b, 1)
        if a < 3:
            for c, t4 in enumerate(["Normative", "Informative"], 1):
                body += '<h4><span class="num">1.%d.%d.%d</span> %s</h4>' % (a, b, c, t4) + hb(90 + a * 9 + b * 3 + c, 1)
body += '<h1><span class="num">2</span> Requirements</h1>' + hb(120)
body += '<h2><span class="num">2.1</span> Functional</h2>' + hb(121)
body += '<h3><span class="num">2.1.1</span> Input</h3>' + hb(122, 1)
add("h-numbered", "Specification With Decimal Numbering", body, css="""
h1{font:bold 20pt %s} h2{font:bold 15pt %s} h3{font:bold 12.5pt %s} h4{font:bold 11pt %s}
.num{min-width:4.6em}
""" % (SANS, SANS, SANS, SANS))

# 3 inverted: level 1 is SMALLER than level 2; level 3 smaller than level 4
body = "<h1>ANNUAL REVIEW OF OPERATIONS</h1>" + hb(131, 2)
for i, t2 in enumerate(["Northern Region", "Southern Region"]):
    body += "<h2>%s</h2>" % t2 + hb(140 + i, 2)
    for j, t3 in enumerate(["Staffing", "Facilities"]):
        body += "<h3>%s</h3>" % t3 + hb(150 + i * 4 + j, 1)
        body += "<h4>%s</h4>" % ("Hiring plan" if j == 0 else "Maintenance backlog") + hb(160 + i * 4 + j, 2)
body += "<h1>FINANCIAL SUMMARY</h1>" + hb(170, 1)
body += "<h2>Income and Expenses</h2>" + hb(171, 2)
body += "<h3>Income</h3>" + hb(172, 1)
add("h-inverted", "Annual Review", body, css="""
h1{font:600 9.5pt %s;letter-spacing:.18em;border-bottom:1px solid #999;padding-bottom:3pt;margin-top:1.6em}
h2{font:bold 21pt %s;color:#123}
h3{font:bold 11.5pt %s;color:#555}
h4{font:bold 14pt %s}
""" % (SANS, SERIF, SANS, SERIF))

# 4 inverted, with mixed numbering schemes I. / A. / 1. / a)
body = '<h1><span class="num">I.</span> GOVERNANCE</h1>' + hb(181, 1)
for i, (a, t2) in enumerate([("A", "Board structure"), ("B", "Committees")]):
    body += '<h2><span class="num">%s.</span> %s</h2>' % (a, t2) + hb(190 + i, 1)
    for j, t3 in enumerate(["Audit", "Risk"], 1):
        body += '<h3><span class="num">%d.</span> %s</h3>' % (j, t3) + hb(200 + i * 4 + j, 1)
        for k, t4 in enumerate(["Charter", "Membership"]):
            body += '<h4><span class="num">%s)</span> %s</h4>' % ("ab"[k], t4) + hb(210 + i * 9 + j * 3 + k, 1)
body += '<h1><span class="num">II.</span> REMUNERATION</h1>' + hb(240, 1)
body += '<h2><span class="num">A.</span> Principles</h2>' + hb(241, 1)
add("h-inverted-numbered", "Governance Report", body, css="""
h1{font:600 9pt %s;letter-spacing:.15em}
h2{font:bold 17pt %s}
h3{font:bold 12pt %s}
h4{font:italic 11pt %s}
.num{min-width:2.2em}
""" % (SANS, SANS, SANS, SERIF))

# 5 non-semantic headings: no <hN>, all body size; level by weight/case/colour/indent only
def hd(level, text, num=None):
    n = '<span class="num">%s</span> ' % num if num else ""
    return '<div class="hd l%d" data-level="%d">%s%s</div>' % (level, level, n, text)


body = hd(1, "Maintenance Manual") + hb(251, 2)
body += hd(2, "Safety") + hb(252, 2)
body += hd(3, "Lockout procedure") + hb(253, 1)
body += hd(4, "Verification") + hb(254, 1)
body += hd(3, "Personal protective equipment") + hb(255, 1)
body += hd(2, "Routine service") + hb(256, 2)
body += hd(3, "Daily checks") + hb(257, 1)
body += hd(1, "Appendix") + hb(258, 1)
add("h-div-samesize", "Maintenance Manual", body, css="""
.hd{font-size:11pt;margin:1em 0 .3em;page-break-after:avoid}
.l1{font-weight:bold;color:#7a1f1f}
.l2{font-weight:bold}
.l3{font-style:italic;font-weight:bold;margin-left:.4in}
.l4{font-style:italic;margin-left:.8in;color:#444}
""")

# 6 keyword numbering (Chapter / Article / Section)
body = ""
for c in range(1, 3):
    body += '<h1 data-numbering="keyword"><span class="num">Chapter %d</span> %s</h1>' % (c, ["Formation", "Operation"][c - 1]) + hb(260 + c, 1)
    for a in range(1, 3):
        body += '<h2 data-numbering="keyword"><span class="num">Article %d.%d</span> %s</h2>' % (c, a, ["Name", "Purpose", "Members", "Meetings"][(c - 1) * 2 + a - 1]) + hb(270 + c * 3 + a, 2)
        body += '<h3 data-numbering="keyword"><span class="num">Section %d.%d.1</span> Interpretation</h3>' % (c, a) + hb(280 + c * 3 + a, 1)
add("h-keyword", "Bylaws", body, css="""
h1{font:bold 13pt %s;text-align:center;margin-top:1.4em}
h2{font:bold 12pt %s}
h3{font:italic 11pt %s}
.num{min-width:0;margin-right:.6em}
""" % (SERIF, SERIF, SERIF))

# ------------------------------------------------------------------ lists
def li(t, inner=""):
    return "<li>%s%s</li>" % (t, inner)


body = "<h1>Packing Checklist</h1>" + hb(301, 1)
body += "<ul>" + li("Tent and groundsheet") + li("Stove, with <b>spare</b> fuel canister") + li("Water filter", "<ul>" + li("Backup tablets") + li("Bottle", "<ul>" + li("One litre") + li("Two litre") + "</ul>") + "</ul>") + li("First aid kit") + "</ul>"
body += "<h2>Steps</h2>" + "<ol>" + li("Lay out the groundsheet.") + li("Stake the corners.") + li("Raise the poles", "<ol type=\"a\">" + li("Short pole first.") + li("Long pole second.") + "</ol>") + li("Attach the fly.") + "</ol>"
body += hb(302, 1)
add("lists-basic", "Packing Checklist", body, css="ul,ol{margin:0 0 .8em;padding-left:1.6em} li{margin:.15em 0} ul ul{list-style-type:circle} ul ul ul{list-style-type:square}")

body = "<h1>Reference Lists</h1>"
body += "<h2>Alphabetic</h2><ol type=\"a\">" + li("Apples") + li("Bananas") + li("Cherries") + "</ol>"
body += "<h2>Roman</h2><ol type=\"I\">" + li("Preamble") + li("Definitions") + li("Obligations") + li("Term") + "</ol>"
body += "<h2>Resumed</h2><ol start=\"5\">" + li("Fifth item with a <a href=\"https://example.org/five\">link</a>.") + li("Sixth item, <i>emphasised</i> and <code>monospaced</code>.") + li("Seventh item") + "</ol>"
body += "<h2>Explicit values</h2><ol><li value=\"3\">Third</li><li>Fourth</li><li value=\"10\">Tenth</li></ol>"
add("lists-schemes", "Reference Lists", body, css="ol,ul{margin:0 0 .8em;padding-left:2em}")

body = "<h1>Terms and Conditions</h1>" + hb(311, 2) + "<ol>"
for i in range(1, 61):
    body += "<li>%s</li>" % para(400 + i, 2, 6, 11)
body += "</ol>" + hb(312, 1)
add("lists-long-multipage", "Terms and Conditions", body, css="ol{padding-left:2.2em} li{margin:.3em 0}",
    atpage="@bottom-center{content:counter(page);font:9pt %s}" % SANS,
    h={"labels": "arabic", "dropped": [{"reason": "page-number", "text": "{n}"}]})

# ------------------------------------------------------------------ tables
def tr(cells, tag="td", cls=None):
    out = "<tr>"
    for i, c in enumerate(cells):
        a = ""
        if cls and i in cls:
            a = ' class="r"'
        out += "<%s%s>%s</%s>" % (tag, a, c, tag)
    return out + "</tr>"


body = "<h1>Regional Sales</h1>" + hb(501, 2)
body += '<table data-profile="ruled"><caption>Table 1. Sales by region, thousands of units</caption><thead>' + tr(["Region", "Q1", "Q2", "Q3", "Q4"], "th", {1, 2, 3, 4}) + "</thead><tbody>"
for i, reg in enumerate(["North", "South", "East", "West", "Central"]):
    body += tr([reg] + ["%d" % (100 + (i * 37 + q * 53) % 90) for q in range(4)], cls={1, 2, 3, 4})
body += "</tbody><tfoot>" + tr(["Total", "512", "498", "530", "547"], "th", {1, 2, 3, 4}) + "</tfoot></table>" + hb(502, 2)
add("table-simple-ruled", "Regional Sales", body, css="table{width:100%%;border:1px solid #000} th,td{border:1px solid #000} caption{caption-side:top;text-align:left;font:italic 10pt %s;padding-bottom:4px} thead th{background:#ddd}" % SANS)

body = "<h1>Fund Performance</h1>" + hb(511, 1)
body += '<table data-profile="whitespace"><thead>' + tr(["Fund", "1 yr", "3 yr", "5 yr", "Fee"], "th", {1, 2, 3, 4}) + "</thead><tbody>"
for i, f in enumerate(["Global Equity", "Bond Index", "Small Cap", "Real Assets", "Cash Plus", "Emerging Markets"]):
    body += tr([f, "%.1f%%" % (3 + (i * 1.7) % 9), "%.1f%%" % (2 + (i * 2.3) % 7), "%.1f%%" % (4 + (i * 1.1) % 6), "%.2f%%" % (0.1 + i * 0.07)], cls={1, 2, 3, 4})
body += "</tbody></table>" + hb(512, 2)
add("table-whitespace", "Fund Performance", body, css="table{width:85%;margin:1em 0} th{font-weight:bold;border-bottom:0}")

body = "<h1>Grouped Headers</h1>" + hb(521, 1)
body += '<table data-profile="partial"><thead><tr><th rowspan="2">Product</th><th colspan="2">2024</th><th colspan="2">2025</th></tr>'
body += tr(["Units", "Revenue", "Units", "Revenue"], "th", {0, 1, 2, 3}) + "</thead><tbody>"
for i, p in enumerate(["Alpha", "Beta", "Gamma", "Delta"]):
    body += tr([p, "%d" % (1000 + i * 120), "$%d,%03d" % (20 + i, (i * 311) % 1000), "%d" % (1100 + i * 90), "$%d,%03d" % (23 + i, (i * 197) % 1000)], cls={1, 2, 3, 4})
body += '<tr><td colspan="3">Combined total for the two years</td><td></td><td class="r">$190,000</td></tr>'
body += "</tbody></table>" + hb(522, 1)
add("table-spans-partial", "Grouped Headers", body, css="table{width:100%;border-top:2px solid #000;border-bottom:2px solid #000} thead th{border-bottom:1px solid #000} thead tr:first-child th{text-align:center}")

body = "<h1>Project Status</h1>" + hb(531, 1)
body += '<table data-profile="shaded"><thead>' + tr(["Workstream", "Owner", "Status", "Due"], "th") + "</thead><tbody>"
for i, (w, o, s, d) in enumerate([("Data migration", "Ana", "On track", "2026-03-01"), ("Security review", "Ben", "At risk", "2026-03-15"), ("User training", "Chen", "Done", "2026-02-10"), ("Launch", "Dee", "Not started", "2026-04-01"), ("Retrospective", "Eli", "Not started", "2026-04-20")]):
    body += tr([w, o, s, d])
body += "</tbody></table>" + hb(532, 1)
add("table-shaded", "Project Status", body, css="table{width:100%} thead th{background:#1f4e79;color:#fff} tbody tr:nth-child(odd) td{background:#e9eff7} tbody tr:nth-child(even) td{background:#fff}")

# multi-page tables
def long_table(prefix, n, seed, repeat, tid, with_thead=True, cols=("Ref", "Item", "Category", "Qty", "Amount")):
    r = random.Random(seed)
    cats = ["Hardware", "Software", "Services", "Travel", "Training", "Supplies"]
    h = '<table id="%s" data-multipage="1" data-profile="ruled" data-repeat-head="%s">' % (tid, "true" if repeat else "false")
    if with_thead:
        h += "<thead>" + tr(cols, "th", {3, 4}) + "</thead>"
    h += "<tbody>"
    for i in range(n):
        h += tr(["%s%04d" % (prefix, i + 1), " ".join(r.choice(WORDS) for _ in range(3)).capitalize(), r.choice(cats), str(r.randint(1, 40)), "%d.%02d" % (r.randint(10, 9000), r.randint(0, 99))], cls={3, 4})
    return h + "</tbody></table>"


tcss = "table{width:100%;font-size:10pt} th,td{border:1px solid #888;padding:5px 8px} thead th{background:#e3e3e3} tr{break-inside:avoid}"
body = "<h1>Purchase Ledger</h1>" + hb(541, 2) + long_table("L", 95, 542, True, "ledger") + hb(543, 2)
add("table-multipage-thead", "Purchase Ledger", body, css=tcss, h={"labels": "arabic", "dropped": [{"reason": "page-number", "text": "{n}"}]},
    atpage="@bottom-center{content:counter(page);font:9pt %s}" % SANS)
body = "<h1>Inventory Extract</h1>" + hb(551, 2) + long_table("I", 95, 552, False, "inv") + hb(553, 2)
add("table-multipage-nohead-repeat", "Inventory Extract", body, css=tcss + " thead{display:table-row-group}",
    h={"labels": "arabic", "dropped": [{"reason": "page-number", "text": "{n}"}]}, atpage="@bottom-center{content:counter(page);font:9pt %s}" % SANS)
body = "<h1>Raw Transaction Dump</h1>" + hb(561, 1) + long_table("R", 100, 562, False, "raw", with_thead=False) + hb(563, 1)
add("table-multipage-no-thead", "Raw Transaction Dump", body, css=tcss,
    h={"labels": "arabic", "dropped": [{"reason": "page-number", "text": "{n}"}]}, atpage="@bottom-center{content:counter(page);font:9pt %s}" % SANS)

# two distinct tables on consecutive pages: must NOT merge
def small_table(prefix, n, seed):
    return long_table(prefix, n, seed, False, "x") .replace(' id="x" data-multipage="1"', "")


body = "<h1>Two Schedules</h1><h2>Schedule A</h2>" + small_table("A", 12, 571).replace("<table ", '<table ', 1)
body += '<div style="break-after:page"></div><h2>Schedule B</h2>' + small_table("B", 12, 572)
add("table-consecutive-distinct", "Two Schedules", body, css=tcss + " table{font-size:11pt}")

# ------------------------------------------------------------------ furniture
sections_ = ["Overview", "Findings", "Recommendations", "Methods", "Annex"]


def longdoc(seed, nsec=5, paras=3, numbered=False):
    b = ""
    for i in range(nsec):
        b += "<h2>%s</h2>" % sections_[i % 5]
        for j in range(paras):
            b += P(seed + i * 10 + j, 5)
    return b


body = "<h1>Quarterly Operating Report</h1>" + longdoc(601, 5, 3)
add("furn-title-pagenum", "Quarterly Operating Report", body, css="h1{font:bold 20pt %s} h2{font:bold 14pt %s}" % (SANS, SANS),
    atpage="@top-center{content:\"Quarterly Operating Report\";font:9pt %s;color:#555} @bottom-center{content:\"Page \" counter(page) \" of \" counter(pages);font:9pt %s;color:#555}" % (SANS, SANS),
    h={"labels": "arabic", "dropped": [{"reason": "running-title", "text": "Quarterly Operating Report"}, {"reason": "page-number", "text": "Page {n} of {N}"}]})

# first page different; odd/even: title on left (even) pages, "Employee Handbook" on right (odd) pages
body = "<h1>Handbook for New Staff</h1>" + longdoc(611, 5, 5)
add("furn-odd-even", "Handbook for New Staff", body, css="h1{font:bold 20pt %s} h2{font:bold 14pt %s}" % (SANS, SANS),
    rawpage="""@page :left{@top-left{content:"Handbook for New Staff";font:9pt %s;color:#555}@bottom-left{content:counter(page);font:9pt %s}}
@page :right{@top-right{content:"Employee Handbook";font:9pt %s;color:#555}@bottom-right{content:counter(page);font:9pt %s}}
@page :first{@top-right{content:none}@bottom-right{content:none}}""" % (SANS, SANS, SANS, SANS),
    h={"labels": "arabic", "label-pages": "rest",
       "furniture": [{"role": "header", "template": "Employee Handbook", "pages": "odd-rest"}],
       "dropped": [{"reason": "running-title", "text": "Handbook for New Staff", "pages": "even"},
                   {"reason": "page-number", "text": "{n}", "pages": "rest"}]})

# real information in the running header: a court filing
body = "<h1>Motion for Summary Judgment</h1><p><b>Plaintiff Acme Holdings</b> moves for summary judgment on all counts.</p>" + longdoc(621, 5, 3)
add("furn-informative", "Motion for Summary Judgment", body, css="body{font-family:%s} h1{font:bold 16pt %s;text-align:center} h2{font:bold 12pt %s}" % (SERIF, SERIF, SERIF),
    margin="1.1in 1in",
    atpage="""@top-left{content:"Case 1:21-cv-00123-ABC Document 14 Filed 05/03/21";font:8.5pt %s}
@top-right{content:"Page " counter(page) " of " counter(pages);font:8.5pt %s}
@bottom-left{content:"CONFIDENTIAL - ATTORNEYS' EYES ONLY";font:8pt %s;color:#800}
@bottom-right{content:"ACME-" counter(page, decimal-leading-zero);font:8pt %s}""" % (SANS, SANS, SANS, SANS),
    h={"labels": "arabic",
       "furniture": [{"role": "header", "template": "Case 1:21-cv-00123-ABC Document 14 Filed 05/03/21", "pages": "all"},
                     {"role": "footer", "template": "CONFIDENTIAL - ATTORNEYS' EYES ONLY", "pages": "all"},
                     {"role": "footer", "template": "ACME-{n:02}", "pages": "all"}],
       "dropped": [{"reason": "page-number", "text": "Page {n} of {N}"}]})

# running header/footer as position:fixed DOM elements (another producer idiom); the layout table's
# empty thead/tfoot rows reserve the space on every page
body = ('<div data-gold="skip" class="fx top">ACME CORPORATION - Internal Use Only</div>'
        '<div data-gold="skip" class="fx bot">Form QA-7 rev. 3</div>'
        '<table data-layout="1" class="frame"><thead><tr><td><div class="sp"></div></td></tr></thead><tbody><tr><td>'
        + "<h1>Quality Procedure</h1>" + longdoc(631, 5, 3) +
        '</td></tr></tbody><tfoot><tr><td><div class="sp"></div></td></tr></tfoot></table>')
add("furn-fixed-dom", "Quality Procedure", body, margin="0", css="""
.frame{width:100%%;border-collapse:collapse;font:11pt/1.45 %s} .frame td{padding:0 1in;font:inherit}
.sp{height:1in}
.fx{position:fixed;left:0;right:0;font:8.5pt %s;color:#555;text-align:center}
.top{top:.5in} .bot{bottom:.5in}
h1{font:bold 20pt %s} h2{font:bold 14pt %s}""" % (SERIF, SANS, SANS, SANS),
    h={"furniture": [{"role": "header", "template": "ACME CORPORATION - Internal Use Only", "pages": "all"},
                     {"role": "footer", "template": "Form QA-7 rev. 3", "pages": "all"}]})

# ------------------------------------------------------------------ footnotes
def fn(marker, text):
    return '<div class="fn" data-note="footnote" data-marker="%s"><sup data-gold="skip">%s</sup> %s</div>' % (marker, marker, text)


def ref(m):
    return '<sup data-fnref="%s">%s</sup>' % (m, m)


pg = lambda inner, foot: '<div class="pg"><div>%s</div><div class="foot">%s</div></div>' % (inner, foot)
body = pg("<h1>Measuring Productivity</h1><p>%s%s Output per hour rose in every sector.%s</p><p>%s%s</p>" % (para(701, 2), ref(1), ref(2), para(702, 3), ref(3)),
          fn(1, "Source: national statistics office, 2025.") + fn(2, "Seasonally adjusted.") + fn(3, "Excludes agriculture."))
body += pg("<h2>Comparisons</h2><p>%s%s</p><p>%s</p>" % (para(703, 3), ref(1), para(704, 3)),
           fn(1, "Purchasing power parity, current prices."))
add("footnotes-page", "Measuring Productivity", body, css=".pg{height:9in;display:flex;flex-direction:column;justify-content:space-between;break-after:page} .pg:last-child{break-after:auto} .foot{border-top:1px solid #000;width:2in;padding-top:4px} .fn{font:8.5pt %s;margin:2px 0} h1{font:bold 20pt %s} h2{font:bold 14pt %s}" % (SANS, SANS, SANS),
    atpage="@bottom-center{content:counter(page);font:9pt %s}" % SANS,
    h={"labels": "arabic", "dropped": [{"reason": "page-number", "text": "{n}"}]})

body = "<h1>Essay on Lighthouses</h1><p>%s%s %s%s</p><p>%s%s</p>" % (para(711, 3), ref(1), para(712, 2), ref(2), para(713, 3), ref(3))
body += "<h2>Notes</h2>" + "".join('<div data-note="endnote" data-marker="%d"><p>%s</p></div>' % (i, t) for i, t in
                                   [(1, "Stevenson, <i>Lighthouse Design</i>, 1880."), (2, "See the log of the keeper."), (3, "Lamp power in candelas is approximate.")])
add("footnotes-endnotes", "Essay on Lighthouses", body, css="h1{font:bold 20pt %s} h2{font:bold 14pt %s} [data-note]{font-size:10pt}" % (SANS, SANS))

# ------------------------------------------------------------------ figures
def bar_chart(w, h, vals, labels, title_y):
    mx = max(vals)
    s = '<svg width="%d" height="%d" viewBox="0 0 %d %d" font-family="Helvetica" font-size="10">' % (w, h, w, h)
    s += '<line x1="40" y1="10" x2="40" y2="%d" stroke="#000"/><line x1="40" y1="%d" x2="%d" y2="%d" stroke="#000"/>' % (h - 30, h - 30, w - 10, h - 30)
    for t in range(0, 5):
        y = h - 30 - t * (h - 45) / 4
        s += '<line x1="36" y1="%.1f" x2="40" y2="%.1f" stroke="#000"/><text x="32" y="%.1f" text-anchor="end">%d</text>' % (y, y, y + 3, t * mx // 4)
    bw = (w - 70) / len(vals)
    for i, (v, l) in enumerate(zip(vals, labels)):
        bh = (h - 45) * v / mx
        s += '<rect x="%.1f" y="%.1f" width="%.1f" height="%.1f" fill="#3b6ea5"/><text x="%.1f" y="%d" text-anchor="middle">%s</text>' % (48 + i * bw, h - 30 - bh, bw - 14, bh, 48 + i * bw + (bw - 14) / 2, h - 16, l)
    s += '<text x="6" y="8" font-size="9">%s</text></svg>' % title_y
    return s


def line_chart(w, h):
    s = '<svg width="%d" height="%d" font-family="Helvetica" font-size="10">' % (w, h)
    s += '<line x1="40" y1="10" x2="40" y2="%d" stroke="#000"/><line x1="40" y1="%d" x2="%d" y2="%d" stroke="#000"/>' % (h - 30, h - 30, w - 10, h - 30)
    pts = [(40 + i * (w - 60) / 5, h - 30 - [20, 50, 45, 90, 110, 140][i]) for i in range(6)]
    s += '<polyline fill="none" stroke="#c0392b" stroke-width="2" points="%s"/>' % " ".join("%.1f,%.1f" % p for p in pts)
    for i, m in enumerate(["Jan", "Feb", "Mar", "Apr", "May", "Jun"]):
        s += '<text x="%.1f" y="%d" text-anchor="middle">%s</text>' % (pts[i][0], h - 16, m)
    s += '<text x="36" y="%d" text-anchor="end">0</text><text x="36" y="20" text-anchor="end">150</text></svg>' % (h - 27)
    return s


def diagram(w, h):
    s = '<svg width="%d" height="%d" font-family="Helvetica" font-size="11">' % (w, h)
    for i, t in enumerate(["Client", "Gateway", "Service"]):
        x = 10 + i * 140
        s += '<rect x="%d" y="30" width="100" height="40" fill="#fff" stroke="#000"/><text x="%d" y="55" text-anchor="middle">%s</text>' % (x, x + 50, t)
        if i < 2:
            s += '<line x1="%d" y1="50" x2="%d" y2="50" stroke="#000"/><polygon points="%d,50 %d,46 %d,54"/>' % (x + 100, x + 138, x + 140, x + 132, x + 132)
    return s + "</svg>"


body = "<h1>Sales Dashboard</h1>" + hb(801, 1)
body += '<figure data-figure="chart">%s<figcaption>Figure 1. Units sold per quarter</figcaption></figure>' % bar_chart(420, 210, [120, 180, 150, 220], ["Q1", "Q2", "Q3", "Q4"], "Units (thousands)")
body += hb(802, 2)
body += '<figure data-figure="chart">%s<figcaption>Figure 2: Monthly revenue trend</figcaption></figure>' % line_chart(420, 190)
body += "<h2>Architecture</h2>" + hb(803, 1)
body += '<figure data-figure="diagram">%s<figcaption>Figure 3. Request path</figcaption></figure>' % diagram(400, 90)
body += '<figure data-figure="photo" data-labels="none" aria-label="Site photograph"><div class="ph"></div><figcaption>Figure 4. Site photograph, north face</figcaption></figure>'
body += '<figure data-figure="chart" data-labels="none"><div class="blank"></div></figure>' + hb(804, 1)
add("figures-charts", "Sales Dashboard", body, css="h1{font:bold 20pt %s} h2{font:bold 14pt %s} .ph{width:260px;height:120px;background:linear-gradient(135deg,#6a8caf,#e0c097,#7d5a50)} .blank{width:320px;height:140px;border:1px dashed #999}" % (SANS, SANS))

body = '<div class="brand" data-figure="logo"><svg width="44" height="44"><circle cx="22" cy="22" r="20" fill="#0a7"/><rect x="12" y="12" width="20" height="20" fill="#fff"/></svg><span data-label="">NorthWind</span></div>'
body += "<h1>Press Release</h1>" + hb(811, 3)
body += '<figure data-figure="screenshot" aria-label="Product screenshot"><div class="shot"><b data-label="">Dashboard</b></div><figcaption>Figure 1. The new interface</figcaption></figure>' + hb(812, 2)
add("figures-logo-screenshot", "Press Release", body, css=".brand{display:flex;align-items:center;gap:8px;font:bold 14pt %s} .shot{width:320px;height:150px;border:2px solid #333;background:#f2f2f2;padding:6px;font:10pt %s} h1{font:bold 20pt %s}" % (SANS, SANS, SANS))

# ------------------------------------------------------------------ watermark & seal
body = "<h1>Draft Policy Statement</h1>" + longdoc(901, 5, 3)
body = '<div data-gold="skip" class="wm">DRAFT</div>' + body
add("watermark-draft", "Draft Policy Statement", body, css="h1{font:bold 20pt %s} h2{font:bold 14pt %s} .wm{position:fixed;top:38%%;left:8%%;font:bold 150pt %s;color:rgba(200,0,0,.13);transform:rotate(-30deg);z-index:-1}" % (SANS, SANS, SANS),
    h={"dropped": [{"reason": "watermark", "text": "DRAFT", "pages": "all"}]})

body = "<h1>Certificate of Completion</h1>"
body += "<p>This certifies that <b>Jordan Rivera</b> completed the Advanced Safety Course on 14 March 2026 with distinction.</p>" + hb(911, 2)
body += '<div class="sealbox"><svg data-seal="seal" data-name="City of Riverton" data-date="2026-03-14" data-color="blue" data-text="CITY OF RIVERTON NOTARY PUBLIC" width="150" height="150" viewBox="0 0 150 150" font-family="Helvetica"><defs><path id="arc" d="M75,75 m-56,0 a56,56 0 1,1 112,0 a56,56 0 1,1 -112,0"/></defs><circle cx="75" cy="75" r="70" fill="none" stroke="#1f3f9f" stroke-width="3"/><circle cx="75" cy="75" r="45" fill="none" stroke="#1f3f9f"/><text font-size="11" fill="#1f3f9f" letter-spacing="1.5"><textPath href="#arc">CITY OF RIVERTON - NOTARY PUBLIC -</textPath></text><text x="75" y="80" text-anchor="middle" font-size="13" font-weight="bold" fill="#1f3f9f">SEAL</text></svg></div>'
body += '<div data-seal="stamp" data-color="red" class="stamp">PAID<br>2026-03-14</div>'
body += '<p class="sig">Signed: ______________________<br><span class="sm">Registrar</span></p>'
add("stamp-seal", "Certificate of Completion", body, css="h1{font:bold 20pt %s} .sealbox{margin:10px 0 0 20px} .stamp{display:inline-block;border:3px solid #c0201f;color:#c0201f;font:bold 18pt %s;padding:4px 14px;transform:rotate(-8deg);margin:20px 0 0 40px} .sig{margin-top:40px} .sm{font-size:9pt}" % (SANS, SANS))
# the svg carries data-seal: a block-level svg is a seal in the gold converter
# ------------------------------------------------------------------ columns
def colbody(seed, nsec):
    b = ""
    for i in range(nsec):
        b += "<h2>%s</h2>" % ["Motivation", "Approach", "Results", "Discussion", "Limits", "Conclusion"][i % 6]
        b += P(seed + i * 3, 6, lo=9, hi=15) + P(seed + i * 3 + 1, 5)
    return b


body = "<h1>Adaptive Sampling in Sparse Fields</h1><p class=\"au\">A. Author and B. Author</p>"
body += "<h2>Abstract</h2>" + P(1001, 4) + '<div class="cols">' + colbody(1010, 5) + "</div>"
add("columns-two", "Adaptive Sampling", body, css=".cols{column-count:2;column-gap:.35in} h1{font:bold 20pt %s;text-align:center} .au{text-align:center;font-style:italic} h2{font:bold 12pt %s;break-after:avoid} .cols p{text-align:justify}" % (SANS, SANS))

body = "<h1>The Village Gazette</h1>" + '<div class="cols">' + colbody(1100, 6) + "</div>"
add("columns-three-news", "The Village Gazette", body, css="body{font-size:9.5pt} .cols{column-count:3;column-gap:.25in;column-rule:1px solid #aaa} h1{font:bold 28pt %s;text-align:center;border-bottom:3px double #000} h2{font:bold 11pt %s;break-after:avoid}" % (SERIF, SANS), margin="0.8in")

# ------------------------------------------------------------------ invoice & statement
body = '<div class="top"><div><div class="co">Brightline Supplies Ltd.</div><p>14 Harbour Road<br>Portsmouth PO1 2AB<br>United Kingdom</p></div><div class="ib"><h1>INVOICE</h1><p>Invoice No: INV-2026-0417<br>Date: 2026-03-02<br>Due: 2026-04-01</p></div></div>'
body += '<h2>Bill to</h2><p>Harwell &amp; Sons<br>3 Mill Lane<br>Oxford OX2 6QA</p>'
body += '<table id="items" data-profile="ruled"><thead>' + tr(["Item", "Description", "Qty", "Unit price", "Amount"], "th", {2, 3, 4}) + "</thead><tbody>"
its = [("A-100", "Copper pipe 15mm x 3m", 40, 6.5), ("B-220", "Elbow joint 15mm", 120, 0.85), ("C-310", "Flux paste 100g", 12, 4.2), ("D-005", "PTFE tape", 60, 0.45), ("E-900", "Delivery", 1, 25.0)]
tot = 0
for c, d, q, u in its:
    a = q * u
    tot += a
    body += tr([c, d, str(q), "%.2f" % u, "%.2f" % a], cls={2, 3, 4})
body += "</tbody></table>"
body += '<table class="tot" data-profile="whitespace"><tbody>' + tr(["Subtotal", "%.2f" % tot], cls={1}) + tr(["VAT 20%", "%.2f" % (tot * .2)], cls={1}) + tr(["<b>Total due</b>", "<b>%.2f</b>" % (tot * 1.2)], cls={1}) + "</tbody></table>"
body += "<h2>Payment terms</h2><p>Payment within 30 days by bank transfer to sort code 12-34-56, account 00112233. Late payments incur interest at 4% above base rate.</p>"
add("invoice", "Invoice INV-2026-0417", body, page="A4", css=".top{display:flex;justify-content:space-between} .co{font:bold 16pt %s} h1{font:bold 26pt %s;color:#444;margin:0;text-align:right} .ib{text-align:right} h2{font:bold 11pt %s;margin:1.2em 0 .3em} table#items{width:100%%;margin-top:1em} #items th,#items td{border-bottom:1px solid #999} #items thead th{border-bottom:2px solid #000} .tot{margin-left:auto;margin-top:.6em;width:40%%}" % (SANS, SANS, SANS),
    atpage="@bottom-center{content:\"Brightline Supplies Ltd. - Registered in England No. 0123456\";font:8pt %s;color:#555}" % SANS,
    h={"furniture": [{"role": "footer", "template": "Brightline Supplies Ltd. - Registered in England No. 0123456", "pages": "all"}]})

def statement():
    r = random.Random(1201)
    bal = 4210.55
    rows = []
    desc = ["Card purchase", "Direct debit", "Transfer in", "Cash withdrawal", "Standing order", "Interest", "Refund", "Salary"]
    for i in range(110):
        d = "%02d Mar" % (1 + i * 30 // 110)
        kind = r.choice(desc)
        amt = round(r.uniform(3, 420), 2)
        if kind in ("Transfer in", "Interest", "Refund", "Salary"):
            cr, db = amt if kind != "Salary" else 2400.0, ""
            bal += float(cr)
            cr = "%.2f" % float(cr)
        else:
            db, cr = "%.2f" % amt, ""
            bal -= amt
        rows.append(("S%05d" % (i + 1), d, "%s %s" % (kind, r.choice(["Tesco", "Shell", "Amazon", "Netflix", "Landlord", "Council", "Gym", "Cafe"])), db, cr, "{:,.2f}".format(bal)))
    return rows


rows = statement()
body = '<div class="bank">Marlow Savings Bank</div><h1>Statement of Account</h1>'
body += '<table class="sum" data-profile="form"><tbody>' + tr(["Account holder", "J. Rivera"]) + tr(["Account number", "40-22-18 / 00923311"]) + tr(["Statement period", "01 Mar 2026 - 31 Mar 2026"]) + tr(["Opening balance", "4,210.55"]) + "</tbody></table>"
body += "<h2>Transactions</h2>"
body += '<table id="txn" data-multipage="1" data-profile="ruled" data-repeat-head="true"><thead>' + tr(["Ref", "Date", "Description", "Debit", "Credit", "Balance"], "th", {3, 4, 5}) + "</thead><tbody>"
for rw in rows:
    body += tr(list(rw), cls={3, 4, 5})
body += "</tbody></table><p class=\"sm\">Marlow Savings Bank is authorised and regulated by the Financial Conduct Authority.</p>"
add("bank-statement", "Statement of Account March 2026", body, page="A4", margin="0.9in 0.8in",
    css="body{font:9.5pt/1.3 %s} .bank{font:bold 15pt %s;color:#0b5} h1{font:bold 18pt %s} h2{font:bold 12pt %s} table{font-size:9pt} #txn{width:100%%} #txn th,#txn td{border-bottom:1px solid #ccc;padding:3px 6px} #txn thead th{background:#eee;border-bottom:2px solid #333} .sum td:first-child{font-weight:bold;width:30%%} .sm{font-size:8pt;color:#555} tr{break-inside:avoid}" % (SANS, SANS, SANS, SANS),
    atpage="@top-left{content:\"Account 40-22-18 / 00923311 - Statement 31 Mar 2026\";font:8pt %s}@top-right{content:\"Page \" counter(page) \" of \" counter(pages);font:8pt %s}@bottom-center{content:\"Marlow Savings Bank - Customer service 0800 000 000\";font:8pt %s;color:#555}" % (SANS, SANS, SANS),
    h={"labels": "arabic",
       "furniture": [{"role": "header", "template": "Account 40-22-18 / 00923311 - Statement 31 Mar 2026", "pages": "all"},
                     {"role": "footer", "template": "Marlow Savings Bank - Customer service 0800 000 000", "pages": "all"}],
       "dropped": [{"reason": "page-number", "text": "Page {n} of {N}"}]})

# ------------------------------------------------------------------ scripts
AR_P = ["بدأت الشركة أعمالها في مدينة صغيرة على الساحل قبل خمسين عاما، ثم توسعت تدريجيا لتصبح من أكبر المؤسسات في المنطقة.",
        "تعتمد الخطة الجديدة على تحسين الخدمات وتقليل التكاليف وزيادة رضا العملاء في جميع الفروع خلال السنوات المقبلة.",
        "يبين الجدول التالي عدد الموظفين في كل قسم، كما يوضح النسب المئوية للزيادة السنوية المتوقعة."]
body = "<h1>التقرير السنوي للشركة</h1><p>%s</p><h2>نظرة عامة</h2><p>%s</p><h2>الأهداف</h2><ul><li>تحسين جودة الخدمة</li><li>خفض التكاليف التشغيلية</li><li>توسيع قاعدة العملاء</li></ul><p>%s</p>" % (AR_P[0], AR_P[1], AR_P[2])
body += '<table data-profile="ruled"><thead><tr><th>القسم</th><th>الموظفون</th><th>النمو</th></tr></thead><tbody><tr><td>المبيعات</td><td>120</td><td>5%</td></tr><tr><td>الدعم</td><td>85</td><td>8%</td></tr><tr><td>التطوير</td><td>60</td><td>12%</td></tr></tbody></table>'
add("script-arabic", "التقرير السنوي", body, lang="ar", dir="rtl", css="html{font-family:'Geeza Pro','Al Bayan',Arial,sans-serif;font-size:13pt;line-height:1.8} h1{font-size:22pt;font-weight:bold} h2{font-size:16pt;font-weight:bold} table{width:100%;font-family:'Geeza Pro',Arial} th,td{border:1px solid #000;text-align:right}")

HE_P = ["החברה הוקמה לפני חמישים שנה בעיר קטנה על החוף והתרחבה בהדרגה לאחד הארגונים הגדולים באזור.",
        "התוכנית החדשה מתמקדת בשיפור השירות, הפחתת עלויות והגדלת שביעות הרצון של הלקוחות בכל הסניפים.",
        "הדוח כולל נתונים על 1,250 עובדים ועל תקציב של 3.4 מיליון שקלים לשנת 2026, לצד מונחים כמו KPI ו-ROI."]
body = "<h1>דוח שנתי</h1><p>%s</p><h2>סקירה כללית</h2><p>%s</p><h2>יעדים</h2><ol><li>שיפור איכות השירות</li><li>הפחתת עלויות</li><li>הרחבת בסיס הלקוחות</li></ol><p>%s</p>" % (HE_P[0], HE_P[1], HE_P[2])
add("script-hebrew", "דוח שנתי", body, lang="he", dir="rtl", css="html{font-family:'Arial Hebrew','Arial',sans-serif;font-size:13pt;line-height:1.7} h1{font-size:22pt;font-weight:bold} h2{font-size:16pt;font-weight:bold}")

body = "<h1>年度报告</h1><p>本公司五十年前在沿海的一座小城市开始经营，随后逐步发展，成为本地区最大的机构之一。</p><h2>概述</h2><p>新的计划侧重于改善服务、降低成本，并在未来几年内提高所有分支机构的客户满意度。</p><h2>目标</h2><ol><li>提高服务质量</li><li>降低运营成本</li><li>扩大客户基础</li></ol>"
body += "<h1>年次報告書</h1><p>当社は五十年前に海沿いの小さな町で事業を始め、徐々に拡大して地域最大級の組織の一つとなりました。</p><h2>概要</h2><p>新しい計画は、サービスの向上とコストの削減、そして全支店の顧客満足度の向上に重点を置いています。</p>"
body += '<table data-profile="ruled"><thead><tr><th>部門</th><th>人数</th><th>成長率</th></tr></thead><tbody><tr><td>営業</td><td>120</td><td>5%</td></tr><tr><td>サポート</td><td>85</td><td>8%</td></tr></tbody></table>'
add("script-cjk", "年度报告 / 年次報告書", body, lang="zh", css="html{font-family:'Hiragino Sans GB','Hiragino Sans',sans-serif;font-size:12pt;line-height:1.8} h1{font-size:22pt;font-weight:bold} h2{font-size:15pt;font-weight:bold} table{border:1px solid #000} th,td{border:1px solid #000}")

body = "<h1>वार्षिक रिपोर्ट</h1><p>कंपनी ने पचास साल पहले तट पर एक छोटे शहर में अपना काम शुरू किया और धीरे-धीरे क्षेत्र के सबसे बड़े संगठनों में से एक बन गई।</p><h2>अवलोकन</h2><p>नई योजना सेवाओं को बेहतर बनाने, लागत कम करने और आने वाले वर्षों में सभी शाखाओं में ग्राहकों की संतुष्टि बढ़ाने पर केंद्रित है।</p><h2>लक्ष्य</h2><ul><li>सेवा की गुणवत्ता में सुधार</li><li>परिचालन लागत में कमी</li><li>ग्राहक आधार का विस्तार</li></ul>"
add("script-devanagari", "वार्षिक रिपोर्ट", body, lang="hi", css="html{font-family:'Devanagari Sangam MN','Kohinoor Devanagari',sans-serif;font-size:12.5pt;line-height:1.9} h1{font-size:22pt;font-weight:bold} h2{font-size:16pt;font-weight:bold}")

body = "<h1>Multilingual Glossary</h1><p>One table, four scripts, each row a translation of the same term.</p>"
body += '<table data-profile="ruled"><thead><tr><th>English</th><th lang="ar" dir="rtl">العربية</th><th lang="zh">中文</th><th lang="hi">हिन्दी</th></tr></thead><tbody>'
for e, a, z, h in [("Report", "تقرير", "报告", "रिपोर्ट"), ("Budget", "ميزانية", "预算", "बजट"), ("Customer", "عميل", "客户", "ग्राहक"), ("Quality", "جودة", "质量", "गुणवत्ता")]:
    body += '<tr><td>%s</td><td lang="ar" dir="rtl">%s</td><td lang="zh">%s</td><td lang="hi">%s</td></tr>' % (e, a, z, h)
body += "</tbody></table>"
add("script-mixed", "Multilingual Glossary", body, css="table{width:100%%;font-family:'Geeza Pro','Hiragino Sans GB','Devanagari Sangam MN',Helvetica,sans-serif} th,td{border:1px solid #000} h1{font:bold 20pt %s}" % SANS)

if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    for name, html in sorted(DOCS.items()):
        with open(os.path.join(OUT, name + ".html"), "w", encoding="utf-8", newline="\n") as f:
            f.write(html)
    print("wrote %d documents to %s" % (len(DOCS), os.path.relpath(OUT)))
