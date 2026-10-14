# Releasing emon

The maintainer's runbook (DEVLOG D524, D547, D550). The release flow needs **no credential beyond the workflows' own `GITHUB_TOKEN` and the registries' own
secrets**. Five workflows do the work and a person decides:

```
 every push to main          you run                you read, then publish             the maintainer approves
 ----------------------      -----------------      ------------------------------     ---------------------------------
 verify.yml                  bump.yml               draft.yml                          publish.yml
  fast core + the gates       commit "Release        a DRAFT release vX.Y.Z             verify -> approve (environment `release`)
  the change since the        vX.Y.Z" to main,       targeting that commit              assets | crates | npm | nuget | maven | go | pages
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
* **`publish.yml`** (starts when you publish the draft) re-verifies everything itself, waits for your approval, then attaches the archives and publishes to every registry.
* `native.yml` (the five CLI targets) and `pages.yml` (the demo) are called by the others; `pr.yml` checks pull requests (`pr / verdict`).

Nothing is published until you publish the draft **and** approve the `release` environment: two deliberate clicks.

**Status of this document: none of it has run.** The workflows, the tools' self-tests and the dry runs below were written and checked offline; the first real
run of each will find something. Treat the dry runs, and then the first release, as the tests.

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
* **The archives.** The release archives (CLI x5 from `native.yml`, C library x4 and pod x4 from `bb package`, and `SHA256SUMS`) are built in the same run **only when affected**:
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
| 3 | before the first publish | The environment `release` with @raystubbs as required reviewer; the secrets in it | section 4 |
| 4 | before the first publish | `NUGET_API_KEY` (still to come) | section 4 |
| 5 | before the first publish | Pages source, tag rules, the bypass note for `main` | section 4 |
| 6 | dry runs | `bb` dry runs, then the workflow dry runs | section 5 |
| 7 | the release | The first release | section 6 |

Prerequisites: GitHub Actions credits (D524: the gates have run locally until now; a full run takes about two hours on a runner, `repeat` alone about 50 minutes); the
repository is public by the time of publishing (npm provenance, GitHub Pages on a free plan and the Go proxy need it, P11.11); `gh` logged in as @raystubbs.

## 3. Reservations (P11.0), start with Maven

1. **Maven Central namespace `com.3sln`.** At <https://central.sonatype.com> sign in, *Publishing > Namespaces > Add Namespace*, enter `com.3sln`. Central
   shows a verification key; add it as a **DNS TXT record on `3sln.com`** (host `@`, value = the key), then press *Verify Namespace*. DNS and review can take
   a day or more. Nothing else on the Maven side works before this shows *Verified*.
2. **crates.io**: sign in with GitHub; the name `emon` is claimed by the first publish (there is no reservation without publishing). Check it is free:
   `cargo search emon --limit 3`. If it is taken, stop and tell the coordinator: the crate name is in `release/pkg/rust/Cargo.toml` and the version tool.
3. **npm**: create the organization `3sln` (<https://www.npmjs.com/org/create>) so `@3sln` exists and is yours; the package is claimed by its first publish.
4. **NuGet**: sign in at nuget.org; optionally reserve the ID prefix `Emon` (*Account > Reserve ID prefix*, free for open-source). The package `Emon` is claimed by its first push.

## 4. Settings: environment, secrets, Pages, rules

**Secrets.** These exist already, as repository secrets: `CARGO_REGISTRY_TOKEN`, `NPM_TOKEN`, `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `MAVEN_GPG_PRIVATE_KEY`. They work
where they are (a job in an environment can read repository secrets). **Recommended:** create the environment first and move them into it (Settings > Environments >
`release` > *Environment secrets*), so only a publish job that you approved can read them. Still to come: `NUGET_API_KEY`. Optional: `MAVEN_GPG_PASSPHRASE` (leave it unset for a
key without a passphrase). The corpus secrets of SPEC 15.3 (`EMON_S3_*`) stay repository secrets: `verify.yml` needs them without any approval.

| Secret | How to get it |
|---|---|
| `CARGO_REGISTRY_TOKEN` | crates.io > Account Settings > API Tokens > New Token. Scopes `publish-new` and `publish-update`. (For the first publish the token cannot be restricted to a crate that does not exist yet; replace it with a crate-scoped one afterwards.) |
| `NPM_TOKEN` | npmjs.com > Access Tokens > Generate New Token > *Granular access token*: permission *Read and write*, scope: packages under `@3sln`, "bypass two-factor" ON (it is for automation). |
| `NUGET_API_KEY` | nuget.org > API Keys > Create: *Push new packages and package versions*, glob pattern `Emon`, expiry as you like. |
| `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | central.sonatype.com > your account menu > *View Account* > *Generate User Token*. It shows a **username/password pair**; use those two, not your login. |
| `MAVEN_GPG_PRIVATE_KEY` | `gpg --armor --export-secret-keys KEYID` (the whole `-----BEGIN PGP PRIVATE KEY BLOCK-----` text; a base64 of it is accepted too). **Publish the public half** so Central can verify: `gpg --keyserver keyserver.ubuntu.com --send-keys KEYID` and upload to <https://keys.openpgp.org> (and confirm the email). |
| `MAVEN_GPG_PASSPHRASE` | optional: the key's passphrase. |

Nothing else is needed: the Go module, the draft, the statuses and the assets use the workflow's own token, and Pages uses GitHub's OIDC.

**Environments** (Settings > Environments):

* **`release`** (create it). *Required reviewers*: add **@raystubbs**. **Untick "Prevent self-review"**, otherwise a sole maintainer cannot approve their own release.
  *Deployment branches and tags*: *Selected branches and tags*, add the branch rule `main` and the **tag rule `v*`** (a `release` event runs on the tag ref; without the tag
  rule the publish jobs are refused).
* **`github-pages`** (GitHub created it when Pages was first deployed, or create it). Its default allows only the `main` branch: add the **tag rule `v*`**, because
  `publish.yml` deploys the demo from the release tag's commit.

**Pages.** Settings > Pages > *Source*: **GitHub Actions**. The custom domain `emon.3sln.com` is set (a DNS CNAME to `3sln.github.io`; tick *Enforce HTTPS* once the
certificate is issued). A deployment from Actions keeps the domain; no `CNAME` file is needed.

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
4. Create the secrets of section 4, then `gh workflow run publish.yml -f tag=v0.1.0 -f ref=main` : **a manual run is always a dry run.** It runs the version check, reports that
   the tag, release or status does not exist yet (SKIP), finds the archives of `main`'s tip and checks the artifact is still there, then in each registry job prints which
   credentials are set, asks the registry whether the version is already there, and runs `cargo publish --dry-run` / `npm publish --dry-run`. It attaches nothing and deploys no
   demo. Read its summary.
5. `gh workflow run pages.yml -f ref=main` : deploys the demo from `main` (this one is real, but it is the demo page, and a rerun replaces it).

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
   commit you mean and shows the green `emon/verified`. The archives are not on the draft: `publish.yml` attaches them from the verified run, after your approval.
4. **Publish the release** (Edit draft > *Publish release*). GitHub creates the tag `v0.1.0` at the draft's target and `publish.yml` starts: `verify` runs, and then `assets` and every
   registry job wait for your approval (Actions > the run > *Review deployments* > tick `release` > *Approve and deploy*; one click approves all waiting jobs). This is your last
   chance to stop: *Reject*. (Until you approve, the public release page exists without assets. If you reject before any registry has the version, see "A release that must change
   after it was published" below.)
5. Watch the **summary** job. All green: install each package from its registry in a scratch directory and convert a sample (`cargo add emon`, `npm i @3sln/emon`,
   `dotnet add package Emon`, a Maven dependency on `com.3sln:emon`, `go get github.com/3sln/emon/go@v0.1.0`). Maven Central shows the artifact within about
   30 minutes of `PUBLISHED`; NuGet within minutes; the Go proxy on first request; the demo at <https://emon.3sln.com>.

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
   - **npm**: `npm deprecate @3sln/emon@X "reason"`; `npm unpublish` works only within 72 hours and only if nothing depends on it. A version is never reusable.
   - **NuGet**: *unlist* the version on nuget.org; the ID and version cannot be reused.
   - **Maven Central**: nothing can be deleted or changed once `PUBLISHED`. A deployment that is `FAILED` or still `VALIDATED` can be dropped in the Portal
     (*Publishing > Deployments*); a `PUBLISHED` one is permanent. If the job timed out while the Portal was still validating, look at the deployment in the Portal
     first, and only rerun when it is gone or failed (a second upload of the same version while the first validates is rejected).
   - **Go**: the tag `go/vX` exists once pushed and the proxy keeps the module forever. A bad Go version is superseded by the next one (add `retract` to the module's go.mod then).
   - **Pages**: rerun the `pages` job or `gh workflow run pages.yml -f ref=vX.Y.Z`; a newer deployment replaces the old.
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
