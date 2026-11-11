# Releasing emon

The maintainer's runbook (DEVLOG D524, D547, D550). The release flow needs **no credential beyond the workflows' own `GITHUB_TOKEN` and the registries' own
secrets**. Five workflows do the work and a person decides:

```
 every push to main          you run                you read, then publish             (no approval step, D882)
 ----------------------      -----------------      ------------------------------     ------------------------
 verify.yml                  bump.yml               draft.yml                          publish.yml
  fast core + the gates       commit "Release        a DRAFT release vX.Y.Z             verify -> publish
  the change since the        vX.Y.Z" to main,       targeting that commit              assets | crates | npm | nuget | maven | go
  last verified commit        then calls verify.yml  (no tag yet)                       summary (names any partial publish)
  calls for, in full;         and draft.yml for it
  status emon/verified;
  release archives built
  only when affected
```

* **`verify.yml`** (push to `main`, weekly, `workflow_dispatch`, `workflow_call`) verifies `main` and posts the commit status **`emon/verified`**. That status, and
  nothing else, is what the release trusts.
* **`bump.yml`** (`workflow_dispatch`) bumps the version and commits `Release vX.Y.Z` straight to `main`.
* **`draft.yml`** (push to `main` touching `version.edn`, `workflow_call`, `workflow_dispatch`) keeps a DRAFT release for the version in `version.edn`, pointed at a
  commit of `main` by its full sha. The tag `vX.Y.Z` does not exist yet: GitHub makes it at that commit when you publish the draft.
* **`publish.yml`** (starts when you publish the draft) re-verifies everything itself, then attaches the archives and publishes to every registry.
* `native.yml` (the five CLI targets) is called by verification; `pages.yml` deploys the website on push to `main`; `pr.yml` checks pull requests (`pr / verdict`).

Nothing is published until you publish the draft: that one deliberate click is the decision (D882 removed the `release` environment and its approval).

**Validation scope.** Local self-tests and dry runs check release-tool contracts.
The experimental PP add-on has a local macOS arm64 smoke check; its other four
native builds and smokes are wired to hosted CI and have not been run by OP2c.
Local checks do not replace `emon/verified` and `emon/archives` on the final release commit.

## Experimental PP-OCR assets

The five native jobs additionally build `emon-ppocr-<version>-<triple>.tar.gz`
(`.zip` on Windows) with `bb ppocr-addon --fetch --check --out .cache/cli`.
Both separate archives share the existing upload root so downloaded native
artifacts stay flat and the collector finds the CLI files. The standalone
builder's default output remains `dist/ppocr`. Each job unpacks its
archive and converts the committed receipt fixture against the committed
reference. These are separate opt-in downloads: no default archive carries ORT
or PP graphs. macOS arm64 is locally qualified; the other four builds and
smokes run in CI only and must pass before attaching assets. `emon/archives`
covers all five alongside the existing CLI, C and pod archives.

`bb package --host ppocr --check` uses cached pinned inputs from `bb native`
and `bb ppocr-addon --fetch --prepare`. `bb package --host js-ppocr --check`
builds the separate optional npm tarball (install `host/js` dev dependencies
first). Registry publishing builds this package explicitly; the ordinary npm
SDK excludes PP bridge files and retains its ordinary wasm. No required
dependency is added to the Go, Java or C# artifacts.

For a local dry run, collect all platform archives with `bb release-ops archive
--dir DIR --out OUT`; it requires 23 assets (18 archives and five CLI sidecars),
and writes `SHA256SUMS` including every add-on. Then run `bb release-ops attach
--tag v0.2.0 --dir OUT --dry-run`. A missing cross-platform artifact is an
error, not a locally verified release. `bb release-ops --self-test` exercises
this complete set with synthetic bytes; that is not a native smoke check.
Run `bb publish npm --dry-run` only after building ordinary JS, pdfium wasm
and optional JS tarballs. It needs no secret: in Actions npm authenticates by Trusted
Publishing (OIDC); locally a dry run reaches npm without credentials. For local,
credential-free tarball inspection use `npm publish TAR --dry-run
--ignore-scripts --userconfig=/dev/null`. Neither command publishes anything.
If the development version still names an existing release, `release-verify`
correctly refuses a tag pointing at another commit, even in dry mode. Perform
the next version bump after the coordinator's commit; never retarget a published tag.
See [Experimental: PP-OCR engine](ppocr.md) for setup, measurements and limits.

## 1. How `main` is verified

* **Pushes run only when you enable them.** The repository is private and Actions minutes are billed, so a push-triggered `verify.yml` run does nothing (its `plan` job is
  skipped, every other job with it, and no `emon/verified` status is posted) unless the repository variable `EMON_VERIFY_ON_PUSH` is `true` (Settings > Secrets and variables >
  Actions > Variables, or `gh variable set EMON_VERIFY_ON_PUSH --body true`). Set it when there are credits or the repository is public; delete it or set it to anything else to
  stop. The weekly schedule, `workflow_dispatch` (`gh workflow run verify.yml`) and `workflow_call` (`bump.yml`) are not affected and always run. Until the variable is set, run
  `gh workflow run verify.yml` yourself to verify a commit; `publish.yml` still needs the green `emon/verified` on the tagged commit, however it got there.
* **Debounce.** `concurrency: verify-main` with no cancelling: a running run finishes, and of the pushes that arrive meanwhile only the newest waits (GitHub drops the older
  pending ones). Nothing is lost: the next run's base is the newest ancestor that *is* verified, so it covers the skipped commits.
* **Base.** `bb ci verify-plan` walks the first-parent history of the commit (at most 60 back) and takes the newest ancestor whose newest `emon/verified` status is a green one
  (`gh api repos/3sln/emon/commits/SHA/statuses`). No such commit, or GitHub not answering: **every gate runs**.
* **Scope.** `bb ci plan` between the base and the commit, the gate map `ci/gate-map.edn` that pull requests use, then the fast core and the planned shards as **full** gates
  (not the `--fast` variants of `pr.yml`). A diff that changes nothing but version strings (`version.edn` and the manifests `bb version` rewrites) plans **no gate**: that is the
  release bump. `bb ci plan --base HEAD~1` shows what a commit would schedule.
* **The status.** `emon/verified` on the commit: `success` with `verified: N gates since <sha>` only when the plan, the fast core and every job were green; otherwise `failure`.
* **The archives.** The release archives (CLI x5 and experimental PP add-on x5 from `native.yml`, C library x4 and pod x4 from `bb package`, and `SHA256SUMS`) are built in the same run **only when affected**:
  a path they are built from changed (`:archives` in the gate map), no valid archives come with the base, or the **version** changed (the archives carry it in their names and
  in the binaries, so the bump commit rebuilds them although it plans no gate). They are the workflow artifact `release-archives`, kept 90 days. Either way the commit gets
  `emon/archives` (`archives X.Y.Z: run ID (built at SHA)`), naming the run whose artifact holds the valid archives. Archives older than 80 days are not inherited.
* **Safety net.** Weekly (Monday 04:17 UTC) and on `gh workflow run verify.yml -f mode=full`: every gate, archives rebuilt, status posted.
  `gh workflow run verify.yml -f sha=COMMIT` verifies a given commit of `main` (a commit that is not on `main` is refused).

## 2. What has to exist, and in what order

| # | When | What | Where |
|---|------|------|-------|
| 1 | **now** (it takes days) | Verify the Maven namespace `com.3sln` | section 3 |
| 2 | now | Reserve the names `emon` (crates.io), `@3sln/emon` (npm), `Emon` (NuGet) | section 3 |
| 3 | before the first publish | NuGet Trusted Publishing: the policy on nuget.org and the organization variable `NUGET_USER` | section 4 |
| 4 | before the first publish | Pages source, tag rules, the bypass note for `main` | section 4 |
| 5 | dry runs | `bb` dry runs, then the workflow dry runs | section 5 |
| 6 | the release | The first release | section 6 |

Prerequisites: GitHub Actions credits (D524: the gates have run locally until now; a full run takes about two hours on a runner, `repeat` alone about 50 minutes); the
repository is public by the time of publishing (npm provenance, GitHub Pages on a free plan and the Go proxy need it, P11.11); `gh` logged in as @raystubbs.

## 3. Reservations (P11.0), start with Maven

1. **Maven Central namespace `com.3sln`.** At <https://central.sonatype.com> sign in, *Publishing > Namespaces > Add Namespace*, enter `com.3sln`. Central
   shows a verification key; add it as a **DNS TXT record on `3sln.com`** (host `@`, value = the key), then press *Verify Namespace*. DNS and review can take
   a day or more. Nothing else on the Maven side works before this shows *Verified*.
2. **crates.io**: sign in with GitHub; the name `emon` is claimed by the first publish (there is no reservation without publishing). Check it is free:
   `cargo search emon --limit 3`. If it is taken, stop and tell the coordinator: the crate name is in `release/pkg/rust/Cargo.toml` and the version tool.
3. **npm**: create the organization `3sln` (<https://www.npmjs.com/org/create>) so `@3sln` exists and is yours; the packages are claimed by their first publish. There are two: `@3sln/emon` and `@3sln/pdfium-wasm` (pdfium's WebAssembly build, D553); `bb publish npm` publishes both, the second first, with the same token.
4. **NuGet**: sign in at nuget.org; optionally reserve the ID prefix `Emon` (*Account > Reserve ID prefix*, free for open-source). The package `Emon` is claimed by its first push.

## 4. Settings: secrets, Pages, rules

**Secrets.** These exist already, as repository secrets: `CARGO_REGISTRY_TOKEN`, `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `MAVEN_GPG_PRIVATE_KEY`. The publish
jobs read them at repository level; there is no deployment environment (D882).
Optional: `MAVEN_GPG_PASSPHRASE` (leave it unset for a key without a passphrase). There is **no NuGet secret** and **no npm secret** (below: both use Trusted Publishing). The corpus secrets of SPEC 15.3 (`EMON_S3_*`) stay
repository secrets: `verify.yml` needs them without any approval.

| Secret | How to get it |
|---|---|
| `CARGO_REGISTRY_TOKEN` | crates.io > Account Settings > API Tokens > New Token. Scopes `publish-new` and `publish-update`. (For the first publish the token cannot be restricted to a crate that does not exist yet; replace it with a crate-scoped one afterwards.) |
| `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | central.sonatype.com > your account menu > *View Account* > *Generate User Token*. It shows a **username/password pair**; use those two, not your login. |
| `MAVEN_GPG_PRIVATE_KEY` | `gpg --armor --export-secret-keys KEYID` (the whole `-----BEGIN PGP PRIVATE KEY BLOCK-----` text; a base64 of it is accepted too). **Publish the public half** so Central can verify: `gpg --keyserver keyserver.ubuntu.com --send-keys KEYID` and upload to <https://keys.openpgp.org> (and confirm the email). |
| `MAVEN_GPG_PASSPHRASE` | optional: the key's passphrase. |

**npm: Trusted Publishing, no secret.** On npmjs.com, for each package (`@3sln/emon`, `@3sln/pdfium-wasm`, `@3sln/emon-ppocr`): the package's
*Settings* > *Trusted Publisher* > *GitHub Actions*, with organization/user `3sln`, repository `emon`, workflow `publish.yml`, environment left **blank**.
The `npm` job grants `id-token: write` and runs npm 11.21.2 on Node 24; it publishes with provenance and no stored token. A Trusted Publisher can only be
configured on a package that exists, so a package's **first** version (here `@3sln/emon-ppocr`) must be published once by hand
(`npm publish dist/js-ppocr/3sln-emon-ppocr-V.tgz --access public`, logged in as an owner of `@3sln`); every later version publishes from the workflow.
After switching, delete the old `NPM_TOKEN` repository secret.

**NuGet: Trusted Publishing, no stored key.** `publish.yml`'s `nuget` job (with `id-token: write`) runs `NuGet/login`, which exchanges the
job's OIDC token for a short-lived API key (about an hour) and hands it to `bb publish nuget` as `NUGET_API_KEY`. Nothing long-lived exists. Set up once:

1. nuget.org > your profile > *Trusted Publishing* > create a policy: repository owner `3sln`, repository `emon`, workflow file `publish.yml`, environment left **blank**.
2. An **organization** Actions variable `NUGET_USER` (Organization settings > Secrets and variables > Actions > Variables) holding your nuget.org **profile name** (not your
   email). **An organization variable must grant repository access to `emon`** (*Repository access*: all repositories, or selected, with `emon` in the list); otherwise
   `vars.NUGET_USER` is empty. Alternatively create a repository Actions variable `NUGET_USER`. The job checks this before building, including on dry runs, and explains how to fix access.

The login runs only for a real publish (the `release` event). A manual dry run mints no key and `bb publish nuget --dry-run` needs none.

Nothing else is needed: the Go module, the draft, the statuses and the assets use the workflow's own token, and Pages uses GitHub's OIDC.

**Environments** (Settings > Environments):

* **`github-pages`** (GitHub created it when Pages was first deployed, or create it). Allow the `main` branch; release tags need no access.

**Pages.** D630 supersedes D547/D550: publish does not call Pages. Pushes to `main` deploy the current site independently, preventing an older release from reverting it and avoiding tag environment restrictions. The site documents the release version in its source. Settings > Pages > *Source*: **GitHub Actions**. The custom domain `emon.3sln.com` is set (a DNS CNAME to `3sln.github.io`; tick *Enforce HTTPS* once the
certificate is issued). A deployment from Actions keeps the domain; no `CNAME` file is needed. The root is the website;
the browser demo lives under `/demo/`. `pages.yml` installs both npm lockfiles, fetches the pinned pdfium WebAssembly build, and runs
`bb site --out _site --check` before uploading the artifact. This checks the translated pages, documentation and samples as well as converting
documents in headless Chrome. A deployment uses the verified release commit; a manual run can publish a selected ref.

**`main` and the bump.** `bump.yml` pushes `Release vX.Y.Z` directly to `main` with `GITHUB_TOKEN`. If `main` gets a branch protection rule or ruleset (required pull requests,
required checks), that push is refused unless **`github-actions` is a bypass actor** (Settings > Rules > Rulesets > the ruleset > *Bypass list* > add *GitHub Actions*; for the
classic rule, *Allow specified actors to bypass required pull requests*). Likewise a tag ruleset on `go/v*` needs `github-actions` as a bypass actor (`publish.yml`'s Go job pushes
that tag), and one on `v*` must allow whoever publishes the draft. Requiring `pr / verdict` on pull requests is fine and recommended.

## 5. Dry runs, in this order

Each step must be clean before the next. Nothing here publishes.

**Local (no GitHub needed):**

```
bb ci core                          # the fast core, including the self-tests of the release tools and of the planner
bb ci plan --base HEAD~1            # what the last commit would schedule
bb version --check v0.1.0           # every manifest says 0.1.0
bb release-notes --since <first-commit>   # read the notes the draft would get
bb release-verify --tag v0.1.0 --dry-run  # reports "no tag v0.1.0 exists" until the draft is published; the version check is real
bb package                          # builds every artifact into dist/ (the package gate does the same and installs each)
```

**On GitHub, one at a time** (these have never run; expect small fixes, especially on the Linux and Windows legs):

1. `gh workflow run native.yml -f ref=main` : the five-target native and CLI build (P11.4b, P11.5). Fix what the four unbuilt targets find.
2. `gh workflow run verify.yml -f mode=full` : every gate, the archives, the statuses (P11.8). Afterwards the tip of `main` shows a green `emon/verified` and `emon/archives`
   (Commits > the commit > the check mark > Details; or `gh api repos/3sln/emon/commits/$(git rev-parse origin/main)/statuses`).
3. Set `EMON_VERIFY_ON_PUSH` to `true` (section 1), then push something small (a documentation change) to `main`: the run should find that commit as its base, plan nothing, and post `verified: 0 gates since ...`, with the
   archives inherited (the log of `plan` says so).
4. Have the secrets and the NuGet setup of section 4 in place, then `gh workflow run publish.yml -f tag=v0.1.0 -f ref=main` : **a manual run is always a dry run.** It runs the version check, reports that
   the tag, release or status does not exist yet (SKIP), finds the archives of `main`'s tip and checks the artifact is still there, then in each registry job prints which
   credentials are set, asks the registry whether the version is already there, and runs `cargo publish --dry-run` / `npm publish --dry-run`. It attaches nothing and deploys no
   website. Read its summary.
5. `gh workflow run pages.yml -f ref=main` : deploys the website from `main` (this is a real deployment, and a rerun replaces it).

## 6. Releasing

### The first release (0.1.0)

`version.edn` already says `0.1.0`, so there is nothing to bump: skip `bump.yml`.

1. **Get `main` verified.** The commit to release is the tip of `main` (or any commit of `main`). If no ancestor is verified yet, the first push to `main` after this flow lands runs
   everything (only once `EMON_VERIFY_ON_PUSH` is `true`, section 1); or run it by hand: `gh workflow run verify.yml -f mode=full` (about two and a half hours; it builds the archives). It is done when the commit shows a green
   `emon/verified` **and** `emon/archives`. If it fails, fix the cause on `main`; the next push verifies it again.
2. **Draft it.** `gh workflow run draft.yml` (add `-f sha=COMMIT` for another commit of `main`). It creates the DRAFT release `v0.1.0` targeting that commit's sha, with notes from
   `bb release-notes`. If `main` moves on and you want the new tip released instead, run it again: it updates the target and **replaces the notes** (edit them last). The commit must
   be verified: wait for its `emon/verified`.
3. **Review the draft** (Releases > Drafts): edit the notes (they are generated from DEVLOG decisions and commits; the first release lists a capped sample); check the target is the
   commit you mean and shows the green `emon/verified`. The archives are not on the draft: `publish.yml` attaches them from the verified run.
4. **Publish the release** (Edit draft > *Publish release*). GitHub creates the tag `v0.1.0` at the draft's target and `publish.yml` starts: `verify` runs, and then `assets` and every
   registry job run at once; there is no approval step (D882), so publishing the draft is the last chance to stop. (To stop a run already started, cancel it in Actions
   before the registry jobs finish; see "A release that must change after it was published" below.)
5. Watch the **summary** job. All green: install each package from its registry in a scratch directory and convert a sample (`cargo add emon`, `npm i @3sln/emon`,
   `dotnet add package Emon`, a Maven dependency on `com.3sln:emon`, `go get github.com/3sln/emon/go@v0.1.0`). Maven Central shows the artifact within about
   30 minutes of `PUBLISHED`; NuGet within minutes; the Go proxy on first request; the website at <https://emon.3sln.com/> and the demo at <https://emon.3sln.com/demo/>.

### Every later release

1. `gh workflow run bump.yml -f part=minor` (or `patch`, `major`, or `-f version=X.Y.Z`) with `main` as the branch. It checks the version is new, commits `Release vX.Y.Z` to `main`, and
   calls `verify.yml` and `draft.yml` for that commit. The bump plans no gate; it rebuilds the archives (they carry the version), so expect the CLI build time, not the gate time.
2. When `emon/verified` is green on that commit, review and publish the draft as in steps 3 to 5 above.

If a person merges a version bump through a pull request instead, the push to `main` runs `verify.yml` and (it touches `version.edn`) `draft.yml` by itself.

**macOS signing (TODO, D541).** The macOS CLI archives are unsigned and un-notarised; users run `xattr -dr com.apple.quarantine` (docs/cli.md). When an Apple
developer identity exists, add the `codesign` and `notarytool` steps to `native.yml` after `bb cli --check` (secrets `APPLE_CERT_P12`, `APPLE_CERT_PASSWORD`,
`APPLE_ID`, `APPLE_TEAM_ID`, `APPLE_APP_PASSWORD`). A disabled step marks the place in `.github/workflows/native.yml`.

## 7. Recovering

### `publish.yml` refused to start publishing ("NOT verified")

`verify` prints which of the four checks failed:

* the manifests differ from the tag (somebody edited the tree after the draft, or the tag is wrong);
* the tag no longer points at the checked-out commit (it was moved: investigate before anything else);
* the release targets a branch or another commit than the tag (the draft was retargeted by hand after `draft.yml` set it; set the target back, or rerun `draft.yml`);
* `emon/verified` is missing, pending or red on that commit (`verify.yml` has not finished, failed, or was never run for it: run `gh workflow run verify.yml -f sha=COMMIT`).

Unpublish the release (back to draft) only while no registry has the version; then fix the cause and publish again. A published release's tag stays: see the next section.

### `assets` could not find the archives

"no verified commit ... has valid archives": the commit was verified without archives (before this flow), the archives are older than 80 days (artifacts expire after 90), or the
version differs. Run `gh workflow run verify.yml -f mode=full -f sha=COMMIT` for the tag's commit (it rebuilds the archives and posts `emon/archives`), then re-run the
failed `assets` job.

### A partial publish

`publish.yml` runs the registries as separate jobs; one failing never undoes or blocks the others, and the **summary** job lists every result and fails if any
registry did not succeed. Every registry step first asks whether the version is already there and skips it, so **re-running is always safe**.

1. Actions > the failed run > read the summary, then the failed job's log.
2. Fix the *cause that is not code*: an expired or wrongly scoped token (replace the secret), a registry outage (wait), the Central Portal rejecting the bundle
   (its message is in the log: usually the namespace not verified, a missing signature or an unpublished public key).
3. **Actions > the run > Re-run failed jobs.** The jobs that succeeded are not rerun; the failed ones publish what is missing; the summary is recomputed.
   (Re-running works for a limited time after the run. After that, or if you changed a secret that the job read before: edit the release, *Set as draft*, then
   *Publish release* again; that starts a fresh run, whose checks and skips are the same. `publish.yml` cannot be started by hand for real, only as a dry run.)
4. By registry:
   - **crates.io**: a version can be *yanked* (`cargo yank --version X emon`), never deleted or replaced.
   - **npm**: `npm deprecate @3sln/emon@X "reason"` (and `@3sln/pdfium-wasm@X`); `npm unpublish` works only within 72 hours and only if nothing depends on it. A version is never reusable.
   - **NuGet**: *unlist* the version on nuget.org; the ID and version cannot be reused.
   - **Maven Central**: nothing can be deleted or changed once `PUBLISHED`. A deployment that is `FAILED` or still `VALIDATED` can be dropped in the Portal
     (*Publishing > Deployments*); a `PUBLISHED` one is permanent. If the job timed out while the Portal was still validating, look at the deployment in the Portal
     first, and only rerun when it is gone or failed (a second upload of the same version while the first validates is rejected).
   - **Go**: the tag `go/vX` exists once pushed and the proxy keeps the module forever. A bad Go version is superseded by the next one (add `retract` to the module's go.mod then).
   - **Pages**: run `gh workflow run pages.yml --ref main -f ref=main`; the website follows `main`, independently of publication.
5. If some registries have the version and the fix needs **code**: the published ones are permanent. Fix on `main`, `bump.yml` the next patch version and release that to all
   registries; leave the half-published version's release marked *pre-release* with a note.

### A release that must change after it was published (no registry has the version)

Only while **no registry** has the version (every publish job skipped or rejected): delete the release and the tag
(`gh release delete vX --yes --cleanup-tag`), fix on `main`, wait for `emon/verified`, and run `draft.yml` again. `draft.yml` refuses a version whose release is published and a tag that
exists at another commit, so a mistake cannot slip through; and `publish.yml` checks that the tag still points at the commit that was verified and that the release still targets
it, so a moved tag cannot publish.

## 8. Reference

* Workflows: `.github/workflows/verify.yml`, `bump.yml`, `draft.yml`, `publish.yml`; reused: `native.yml`, `pages.yml` (`workflow_call`); `pr.yml` checks pull requests.
* Statuses on a commit of `main`: `emon/verified` (`verified: N gates since <sha>`, target: the run) and `emon/archives` (`archives X.Y.Z: run ID (built at SHA)`).
* Artifact: `release-archives` of the run `emon/archives` names: the 18 files (5 CLI archives with `.sha256`, 4 C library archives, 4 pod archives) and `SHA256SUMS`; 90 days.
* Tools (all `bb`, each with `--self-test`, which the fast core runs): `release-notes`, `release-verify`, `release-ops` (next, draft, archive, verdict, fetch-archives, attach,
  summary), `publish` (crates, npm, nuget, maven, go), and `bb ci` (plan, verify-plan, shards, core, map-coverage). Sources in `tools/release/` and `tools/ci.clj`.
* Locally: `bb release-notes --since TAG`, `bb release-verify --tag T --dry-run`, `bb release-ops next --bump patch`, `bb ci plan --base REF`, `bb ci verify-plan --head SHA`
  (needs `gh`), `bb publish crates --dry-run` (checks `CARGO_REGISTRY_TOKEN` is in your environment, and runs `cargo publish --dry-run` over `dist/` after `bb package --host rust`).
* The Go module is `github.com/3sln/emon/go`, tag `go/vX.Y.Z`, committed as `go/` on a tag-only commit by the publish job (D542); the repository's own tag is `vX.Y.Z`.

The Go tag-only commit keeps the verified release commit as its sole parent and changes only `go/` and `.github/workflows/`; the workflows tree is copied from the freshly fetched origin default branch. An isolated index and `commit-tree` leave the checkout untouched. Existing tags with that parent and changes confined to those paths (including legacy tags changing only `go/`) are skipped. For 0.1.0 the coordinator pushed `go/v0.1.0` manually from a local checkout; its parent is `2e87e026`, and reruns recognise it.
