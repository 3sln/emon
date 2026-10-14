# Contributing to emon

Thank you for reading this before changing something. emon is unusual in one way that decides how you work on it: **the
logic is written once, in [kin](https://github.com/3sln/kin), and generated into Rust, Go, Java and C#.** The generated code is
committed (`gen/`), the hosts around it are small and hand-written (`host/`), and the same inputs must give the same bytes on
every host. [SPEC.md](SPEC.md) says what emon is; [DEVLOG.md](DEVLOG.md) says what is built, in what order, and why.

## The kin-first rule

Behaviour belongs in a `.kin` source (`kin/*.kin`), never in a host. The line is [SPEC section 7](SPEC.md): kin code never names
a library type; where an ecosystem already has an answer (image codecs, OCR, drawing a PDF page, hashes, threads), emon wraps it
in a small port, tags it in kin, and each host implements the port as it likes. So:

- A fix to what emon *reads or says* (a table that is wrong, a heading level, a reading order) is a change to a `.kin` file.
  Run `bb emit` and commit the regenerated `gen/` in the same commit; `bb drift` fails when `gen/` is not what the sources say.
- A change to a *port* or to a host's API (`host/rust`, `host/go`, `host/java`, `host/csharp`, `host/js`, `host/c`, `host/pod`)
  is hand-written. It must keep the four hosts alike: `bb hosts` and `bb embedders` check the shape.
- The kin language library is a separate project, checked out as `../kin`. A problem in kin is not fixed from here: write it up
  under "Found in kin, not ours to fix here" in the DEVLOG, and the fix goes to kin's repository.

## What you need

`bb` (babashka), `sh` and `git`; a checkout of kin as `../kin`; `python3` for a few third-party reference checks. Each host's
toolchain is used when it is there and **skipped, loudly,** when it is not (`EMON_SKIP_JAVA=1`, `EMON_SKIP_CSHARP=1`, `EMON_SKIP_C=1`,
`EMON_SKIP_POD=1` and `EMON_SKIP_WASM=1` leave one out on purpose):

| For | You need |
|---|---|
| Rust (the reference host, the C library, the pod, the CLI) | a stable `rustc`/`cargo`; a pinned nightly with the wasm32 target for the browser module (`kin/scripts/emon-wasm-build`) |
| Go | `go` 1.21 or newer, and `gofmt` |
| Java | a JDK 22 or newer (`/opt/homebrew/opt/openjdk`, or `EMON_JDK`) |
| C# | a .NET SDK (the gates use `net10.0`; the package targets `net8.0`) |
| JavaScript and the demo | Node 22 or newer, and Chrome for the browser checks |
| C | a C compiler |
| pdfium and Tesseract (live recorders and `bb samples`, never the gates) | `bb pdfium-fetch`, and Tesseract and Leptonica from your package manager; `bb native` builds the static ones the CLI bundles |

The sample corpus (`corpus/`, 12 GB) is not in git and the gates do not need it (SPEC 15.2 says how to share it).

## The tasks

`bb tasks` lists them all with their documentation. The ones you will use:

| Task | What it does |
|---|---|
| `bb check` | generate, compile and run every target; one answer |
| `bb emit` | write every source's modules into `gen/` |
| `bb drift` | `gen/` is what the sources say |
| `bb unit [go\|rust\|java\|csharp]` | the laws, as native tests on every target |
| `bb slice`, `bb identity`, `bb repeat` | conversions on every host: same bytes, in memory and from an asynchronous source |
| `bb accuracy` | the scoreboard ratchet (`test/scoreboard.edn`); it only moves up |
| `bb hosts`, `bb embedders` | the host trees agree; the public surfaces match |
| `bb api-diff` | the public API is the one `api/surface.edn` froze |
| `bb release-audit` | licences, the third-party inventory and `THIRD_PARTY_NOTICES.md` |
| `bb version`, `bb docs` | the version in every manifest; the generated documentation |
| `bb package [--check]` | build every registry artifact; `--check` installs each into a clean project and runs the quickstarts |
| `bb gates [GATE...]` | every gate, or the ones named; fails if a gate exits 0 and prints no score |
| `bb ci core`, `bb ci plan --base origin/main` | the fast core every pull request runs; the gates a change calls for |

## Gates

A **gate** is a task that exits 0 and prints at least one `  ok ` line (the claim it made); `kin/scripts/gates` lists them. Not
every task is a gate (recorders, reports and the release tools are not). A pull request runs:

1. the **fast core** (`bb ci core`, a couple of minutes): `check`, `drift`, `hosts`, `lang-test`, `api-diff`, `release-audit`,
   `version --check`, `docs --check`, the release tools' self-tests and `ci coverage`;
2. the gates **the change calls for**, from `ci/gate-map.edn`: a path-to-gates table in which every tracked path must match an
   entry (`[]` is an answer: a document needs no gate beyond the core), as `--fast` variants where a gate has one (a labelled,
   deterministic subset: Rust and Go, a fixed sample of documents).

The full gates run on release, on demand and weekly. A new file that no entry of `ci/gate-map.edn` maps fails `bb ci coverage`:
add the entry in the same change (`bb ci map-coverage` says which path; `bb ci plan --base origin/main` shows the plan).

## The decision log

[DEVLOG.md](DEVLOG.md) is the tracker, and it is append-only:

- Work is an item of a phase (`P11.6`), with a status key (`[ ]` not started, `[~]` in progress, `[x]` done, `[!]` blocked,
  `[?]` an open question). An item is done when its check is green **and** its entry says what was learnt.
- A decision you take gets the next `D###` at the end of the Decisions section: **Decision**, **Why**, **Alternatives**, and a
  `- [ ] **Signed off** (maintainer)` line. Decisions do not block work, but they must be written down, and the maintainer signs
  them off. Never renumber or edit another decision; a change of mind is a new decision that cites the old one.
- Anything you find that is wrong and not yours to fix goes in "Found in kin, not ours to fix here" (kin) or the parking lot.

## The API freeze

From 0.1.0 the `:public` entries of `api/surface.edn` are the API. `bb api-diff` fails on a removed or changed entry and, unless you
pass `--allow-additions`, on an added one. A deliberate change is `bb api-diff --write` in the same commit, so the diff of that file
is the review, with a note in the release. A patch release may not remove, change or add public API.

## The documentation

`README.md`, `docs/` and this file are checked, not just written. `bb docs` rewrites the generated parts (the option and limit
tables from `api/surface.edn` and `kin/config.kin`, the Model's tables from `schema/emon-1.json`, each host's API index) and
`bb docs --check` fails when they differ from what is committed. Every code block in them is either a **snippet** (a file of
`docs/snippets/`, which `bb package --check` compiles and runs against the built artifact) or marked
`<!-- unverified: why -->`. If you add an option, a limit or a Model property you will be told here what to describe; run `bb docs`
and commit the result.

## Licence

emon is MIT ([LICENSE](LICENSE)). By contributing you agree to license your work under it. A new dependency or a vendored file needs
an entry in `release/third-party.edn` with an allowed licence (`bb release-audit` explains what is allowed); jbig2dec and any GPL-family
component are never allowed.
