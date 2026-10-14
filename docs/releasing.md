# Releasing emon

The maintainer's runbook (DEVLOG D524, D1500). Two workflows do the work and a person decides:

```
 prepare-release.yml                                   you                       publish.yml
 ------------------------------------------------     ------------------------   ---------------------------------------------
 PROPOSE  bump -> PR "release: vX.Y.Z"                 review + merge the PR
 FINALIZE (starts when that PR merges)
    tag vX.Y.Z  ->  DRAFT release with notes
    full gates + native + CLI + C + pod archives
    attach archives, post status emon/full-gates                                  
                                                       read the draft, fix notes,
                                                       flip it to PUBLISHED   ---> verify -> approve (environment `release`)
                                                                                    crates | npm | nuget | maven | go | pages
                                                                                    summary (names any partial publish)
```

Nothing is published until you flip the draft. The publish workflow then re-checks everything itself (the version in every manifest, that the tag was not moved,
that `emon/full-gates` is green on that exact commit) and waits for your approval in the `release` environment before any registry sees a byte.

**Status of this document: none of it has run.** The workflows, the tools' self-tests and the dry runs below were written and checked offline; the first real
run of each will find something. Do the dry runs in the order below, and treat the first release as the last dry run.

## 0. What has to exist, and in what order

| # | When | What | Where |
|---|------|------|-------|
| 1 | **now** (it takes days) | Verify the Maven namespace `com.3sln` | section 2 |
| 2 | now | Reserve the names `emon` (crates.io), `@3sln/emon` (npm), `Emon` (NuGet) | section 2 |
| 3 | before the first workflow run | The release credential: `RELEASE_TOKEN` (PAT) **or** `RELEASE_APP_ID` + `RELEASE_APP_KEY` (GitHub App) | section 3 |
| 4 | before the first workflow run | Environments `release` and `github-pages`; Pages source; branch and tag rules | section 4 |
| 5 | before the dry runs | The registry secrets, in the `release` environment | section 5 |
| 6 | dry runs | `bb` dry runs, then the workflow dry runs | section 6 |
| 7 | the release | The first release | section 7 |

## 1. Prerequisites

- GitHub Actions credits (D524: the gates have run locally until now). The full gates take about two hours on a runner (`repeat` alone about 50 minutes).
- The repository is public by the time of publishing: npm provenance, GitHub Pages on a free plan and the Go proxy all need it (P11.11).
- `gh` logged in as @raystubbs for the local commands below.

## 2. Reservations (P11.0), start with Maven

1. **Maven Central namespace `com.3sln`.** At <https://central.sonatype.com> sign in, *Publishing > Namespaces > Add Namespace*, enter `com.3sln`. Central
   shows a verification key; add it as a **DNS TXT record on `3sln.com`** (host `@`, value = the key), then press *Verify Namespace*. DNS and review can take
   a day or more. Nothing else on the Maven side works before this shows *Verified*.
2. **crates.io**: sign in with GitHub; the name `emon` is claimed by the first publish (there is no reservation without publishing). Check it is free:
   `cargo search emon --limit 3`. If it is taken, stop and tell the coordinator: the crate name is in `release/pkg/rust/Cargo.toml` and the version tool.
3. **npm**: create the organization `3sln` (<https://www.npmjs.com/org/create>) so `@3sln` exists and is yours; the package is claimed by its first publish.
4. **NuGet**: sign in at nuget.org; optionally reserve the ID prefix `Emon` (*Account > Reserve ID prefix*, free for open-source). The package `Emon` is claimed by its first push.

## 3. The release credential (`RELEASE_TOKEN`, or a GitHub App)

A push or pull request made with the workflow's own `GITHUB_TOKEN` does **not** start other workflows, so the release PR would never get its `pr / verdict`
check. prepare-release.yml therefore uses a token of its own (the composite action `.github/actions/release-token` picks one). Give it **one** of:

**Option A, a fine-grained personal access token (simplest).**
GitHub > Settings > Developer settings > Personal access tokens > Fine-grained tokens > *Generate new token*. Resource owner `3sln`; repository access: only
`3sln/emon`; repository permissions: **Contents: Read and write**, **Pull requests: Read and write** (Metadata: read is automatic). Expiry: the maximum you
are comfortable with, and put the date in your calendar. Then in the repo: *Settings > Secrets and variables > Actions > New repository secret* `RELEASE_TOKEN`.

**Option B, a GitHub App (no personal credential, tokens last an hour).**
*Settings (3sln organization) > Developer settings > GitHub Apps > New GitHub App*: no webhook; repository permissions **Contents: Read and write**, **Pull
requests: Read and write**; only on this account. Create it, *Generate a private key* (a `.pem`), *Install App* on `3sln/emon`. In the repo: *Variables* tab >
`RELEASE_APP_ID` (the App ID number); *Secrets* tab > `RELEASE_APP_KEY` (the whole `.pem` text). If both options are set, the App wins.

Needed when: before the first run of `prepare-release.yml` that proposes or tags. (The workflow fails at its first step, naming this section, if neither exists.)

## 4. Environments, Pages, branch and tag rules

Settings > Environments:

- **`release`** (create it). *Required reviewers*: add **@raystubbs**. **Untick "Prevent self-review"**, otherwise a sole maintainer cannot approve their own
  release. *Deployment branches and tags*: *Selected branches and tags*, add the branch rule `main` and the **tag rule `v*`** (a `release` event runs on the tag
  ref; without the tag rule the publish jobs are refused). The registry secrets of section 5 go in this environment, so only an approved publish job can read them.
- **`github-pages`** (GitHub created it when Pages was first deployed, or create it). Its default allows only the `main` branch: add the **tag rule `v*`**, because
  publish.yml deploys the demo from the release tag's commit.

Settings > Pages > *Source*: **GitHub Actions**.

Settings > Branches (or Rules): protect `main`, require the check `pr / verdict`. The release PR is an ordinary PR and must pass it.
If you use tag rulesets, allow creation of `v*` (by the release credential) and `go/v*` (by `github-actions`, which publish.yml's Go job uses to push the Go tag): add
them as bypass actors, or the tag pushes are refused.

## 5. Registry secrets (in the `release` environment, last)

Settings > Environments > `release` > *Environment secrets*. Create them just before the dry runs; the dry run of publish.yml fails on any that is missing, by name.

| Secret | How to get it |
|---|---|
| `CARGO_REGISTRY_TOKEN` | crates.io > Account Settings > API Tokens > New Token. Scopes `publish-new` and `publish-update`. (For the first publish the token cannot be restricted to a crate that does not exist yet; replace it with a crate-scoped one afterwards.) |
| `NPM_TOKEN` | npmjs.com > Access Tokens > Generate New Token > *Granular access token*: permission *Read and write*, scope: packages under `@3sln`, "bypass two-factor" ON (it is for automation). |
| `NUGET_API_KEY` | nuget.org > API Keys > Create: *Push new packages and package versions*, glob pattern `Emon`, expiry as you like. |
| `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | central.sonatype.com > your account menu > *View Account* > *Generate User Token*. It shows a **username/password pair**; use those two, not your login. |
| `MAVEN_GPG_PRIVATE_KEY` | Make a signing key if you have none: `gpg --full-generate-key` (RSA 4096, no expiry or a long one). Export: `gpg --armor --export-secret-keys KEYID` and paste the whole `-----BEGIN PGP PRIVATE KEY BLOCK-----` text (a base64 of it is accepted too). **Publish the public half** so Central can verify: `gpg --keyserver keyserver.ubuntu.com --send-keys KEYID` and upload to <https://keys.openpgp.org> (and confirm the email). |
| `MAVEN_GPG_PASSPHRASE` | optional: the key's passphrase; leave it unset for a key without one. |

Nothing else is needed: the Go module uses the workflow's own token (the tag push), and Pages uses GitHub's OIDC.

## 6. Dry runs, in this order

Each step must be clean before the next. Nothing here publishes.

**Local (no GitHub needed):**

```
bb ci core                          # the fast core, including the self-tests of the four release tools
bb version --check v0.1.0           # every manifest says 0.1.0
bb release-notes --since <first-commit>   # read the notes it would draft
bb release-verify --tag v0.1.0 --dry-run  # reports "no tag v0.1.0 exists" until the tag does; the version check is real
bb package                          # builds every artifact into dist/ (the package gate does the same and installs each)
```

**On GitHub, one at a time** (these have never run; expect small fixes, especially on the Linux and Windows legs):

1. `gh workflow run native.yml -f ref=main` : the five-target native and CLI build (P11.4b, P11.5). Fix what the four unbuilt targets find.
2. `gh workflow run gates.yml -f ref=main` : the full gates (P11.8).
3. `gh workflow run prepare-release.yml -f version=0.1.1 -f dry_run=true` : PROPOSE validates and prints the plan, pushes nothing.
4. Create the secrets of section 5, then `gh workflow run publish.yml -f tag=v0.1.0 -f ref=main` : **a manual run is always a dry run.** It runs the version
   check, reports that the tag or status does not exist yet (SKIP), then in each registry job prints which credentials are set, asks the registry whether
   the version is already there, and runs `cargo publish --dry-run` / `npm publish --dry-run`. It deploys no demo. Read its summary.
5. `gh workflow run pages.yml -f ref=main` : deploys the demo from `main` (this one is real, but it is the demo page, and a rerun replaces it).

## 7. The first release (0.1.0)

`version.edn` already says `0.1.0`, so there is nothing to bump: skip PROPOSE.

1. Confirm `main` is the commit you want released and `bb ci core` is green on it. The merge agent and any other branch should be quiet.
2. `gh workflow run prepare-release.yml -f finalize=true` (add `-f sha=COMMIT` to tag something other than the tip of `main`). FINALIZE runs:
   tag `v0.1.0` (annotated) > draft release > gates + native + packages > attach > status. Expect about 2.5 hours. If a part fails, fix the cause and run the
   same command again: the tag and the draft are kept (a draft's edited notes survive), the failed parts rerun, the status is posted again at the end.
   If the fix needs a change to the code, the tag is wrong: go to "A release that must change after it was tagged" below.
3. For any later release: `gh workflow run prepare-release.yml -f bump=minor` (or `patch`, `major`, or `-f version=X.Y.Z`). Review and merge the PR it opens (it
   starts FINALIZE by itself on merge; `-f finalize=true` is the manual fallback).
4. Read the **draft** (Releases > Drafts): edit the notes (they are generated from DEVLOG decisions and commits; the first release lists a capped sample);
   check the 18 attached files (5 CLI archives with `.sha256`, 4 C library archives, 4 pod archives) and `SHA256SUMS`; check the commit shows the green
   `emon/full-gates` status (Commits > the tagged commit > the check mark).
5. Dry-run the verification the publish job will do, from a clean checkout of the tag:
   `git fetch --tags && git switch --detach v0.1.0 && bb release-verify --tag v0.1.0` (needs `gh` auth).
6. **Publish the release** (Edit draft > *Publish release*). publish.yml starts, `verify` runs, and then every registry job waits for your approval
   (Actions > the run > *Review deployments* > tick `release` > *Approve and deploy*; one click approves all waiting jobs). This is your last chance to stop: *Reject*.
7. Watch the **summary** job. All green: install each package from its registry in a scratch directory and convert a sample (`cargo add emon`, `npm i @3sln/emon`,
   `dotnet add package Emon`, a Maven dependency on `com.3sln:emon`, `go get github.com/3sln/emon/go@v0.1.0`). Maven Central shows the artifact within about
   30 minutes of `PUBLISHED`; NuGet within minutes; the Go proxy on first request.

**macOS signing (TODO, D541).** The macOS CLI archives are unsigned and un-notarised; users run `xattr -dr com.apple.quarantine` (docs/cli.md). When an Apple
developer identity exists, add the `codesign` and `notarytool` steps to `native.yml` after `bb cli --check` (secrets `APPLE_CERT_P12`, `APPLE_CERT_PASSWORD`,
`APPLE_ID`, `APPLE_TEAM_ID`, `APPLE_APP_PASSWORD`). A disabled step marks the place in `.github/workflows/native.yml`.

## 8. Recovering from a partial publish

publish.yml runs the registries as separate jobs; one failing never undoes or blocks the others, and the **summary** job lists every result and fails if any
registry did not succeed. Every registry step first asks whether the version is already there and skips it, so **re-running is always safe**.

1. Actions > the failed run > read the summary, then the failed job's log.
2. Fix the *cause that is not code*: an expired or wrongly scoped token (replace the secret), a registry outage (wait), the Central Portal rejecting the bundle
   (its message is in the log: usually the namespace not verified, a missing signature or an unpublished public key).
3. **Actions > the run > Re-run failed jobs.** The jobs that succeeded are not rerun; the failed ones publish what is missing; the summary is recomputed.
   (Re-running works for a limited time after the run. After that, or if you changed a secret that the job read before: edit the release, *Set as draft*, then
   *Publish release* again; that starts a fresh run, whose checks and skips are the same. publish.yml cannot be started by hand for real, only as a dry run.)
4. By registry:
   - **crates.io**: a version can be *yanked* (`cargo yank --version X emon`), never deleted or replaced.
   - **npm**: `npm deprecate @3sln/emon@X "reason"`; `npm unpublish` works only within 72 hours and only if nothing depends on it. A version is never reusable.
   - **NuGet**: *unlist* the version on nuget.org; the ID and version cannot be reused.
   - **Maven Central**: nothing can be deleted or changed once `PUBLISHED`. A deployment that is `FAILED` or still `VALIDATED` can be dropped in the Portal
     (*Publishing > Deployments*); a `PUBLISHED` one is permanent. If the job timed out while the Portal was still validating, look at the deployment in the Portal
     first, and only rerun when it is gone or failed (a second upload of the same version while the first validates is rejected).
   - **Go**: the tag `go/vX` exists once pushed and the proxy keeps the module forever. A bad Go version is superseded by the next one (add `retract` to the module's go.mod then).
   - **Pages**: rerun the `pages` job or `gh workflow run pages.yml -f ref=vX.Y.Z`; a newer deployment replaces the old.
5. If some registries have the version and the fix needs **code**: the published ones are permanent. Fix on `main`, run PROPOSE for the next patch version
   (`-f bump=patch`) and release that to all registries; leave the half-published version's release marked *pre-release* with a note.

### A release that must change after it was tagged (nothing published yet)

Only while **no registry** has the version (every publish job skipped or the draft was never published): delete the draft release and the tag
(`gh release delete vX --yes; git push origin :refs/tags/vX`), fix on `main`, and FINALIZE again. A tag that was published is never moved: publish.yml checks
that the tag still points at the commit the gates passed on, and refuses (verify fails) if it does not, so a moved tag cannot publish.

### The publish workflow refused to start publishing ("NOT verified")

`verify` prints which of the three checks failed: the manifests differ from the tag (somebody edited the tree after the tag, or the tag is wrong); the tag no longer
points at the checked-out commit (it was moved: investigate before anything else); the `emon/full-gates` status is missing, pending, red, or not the one
prepare-release wrote (`gated <sha> as <tag>`). Unpublish the release (back to draft), fix the cause (usually: rerun FINALIZE so the status is posted), publish again.

## 9. Reference

- Workflows: `.github/workflows/prepare-release.yml`, `publish.yml`; reused: `gates.yml`, `native.yml`, `pages.yml` (`workflow_call`); the credential action
  `.github/actions/release-token`.
- Tools (all `bb`, each with `--self-test`, which the fast core runs): `release-notes`, `release-verify`, `release-ops` (propose, tag, draft, attach, status,
  summary), `publish` (crates, npm, nuget, maven, go). Sources in `tools/release/`.
- Locally: `bb release-notes --since TAG`, `bb release-verify --tag T --dry-run`, `bb release-ops propose --bump patch --dry-run`,
  `bb publish crates --dry-run` (checks `CARGO_REGISTRY_TOKEN` is in your environment, and runs `cargo publish --dry-run` over `dist/` after `bb package --host rust`).
- The Go module is `github.com/3sln/emon/go`, tag `go/vX.Y.Z`, committed as `go/` on a tag-only commit by the publish job (D542); the repository's own tag is `vX.Y.Z`.
- The `emon/full-gates` status description is exactly `gated <full sha> as <tag>`.
- `gates.yml` still also runs when a release is published (D528), which repeats the full gates after the draft's run; remove its `release` trigger if you do not
  want the second run (publish.yml does not depend on it).
