# Releasing emon

`version.edn` is the single release input (D886). Change it on `main`, and
`release.yml` verifies that exact commit in full, creates `vX.Y.Z` and a published
GitHub release with generated notes and verified archives, then publishes crates,
npm, NuGet, Maven and Go independently. No deployment environment or approval
step is used. Pages deploys independently from `main`.

## Release a version

Edit `version.edn`, or run `bb version bump minor` (also `patch`, `major`, or
`bb version set X.Y.Z`). Commit and push, or merge through a pull request, to
`main`. Only `version.edn` changes for a bump. Watch Actions > release.
A push that does not touch that file cannot start a release. An existing tag at
the same commit skips automatic publication; at another commit it fails loudly.

`release.yml` validates the selected commit belongs to `main` and resolves its
version. It calls `release-run.yml`, whose concurrency group is the resolved
version, with cancellation disabled. That pipeline calls `verify.yml` with
`mode=full`, including native archives, regardless of `EMON_VERIFY_ON_PUSH`.
No tag, release or registry mutation occurs until full verification succeeds
and the archives have been downloaded and checked against SHA256SUMS.
Tag creation and release creation can be retried independently: existing tags
at the same commit and existing releases targeting that commit are reused.
The release is published with the archives; a rerun replaces matching assets.
A summary names partial publication and fails when any selected job fails.

Normal `verify.yml` pushes keep their scoped behavior and the
`EMON_VERIFY_ON_PUSH=true` opt-in. A push changing only `version.edn` is excluded
there because release verifies it. A version bump combined with other changes
can run verification twice; this is accepted to preserve ordinary push coverage.

## Retry or dry-run

Commands below are operational examples (unverified locally; require GitHub Actions).

```sh
# emon-docs: unverified — operator commands, require repository permissions
# Default dispatch is a dry run. Choose the original release ref when main moved.
gh workflow run release.yml --ref main -f version=X.Y.Z
# Publish all missing targets:
gh workflow run release.yml --ref main -f version=X.Y.Z -f dry_run=false
# Retry npm only:
gh workflow run release.yml --ref vX.Y.Z -f version=X.Y.Z -f only=npm -f dry_run=false
```

The `version` input may be blank to read `version.edn` at the chosen ref; a supplied
value must equal that file exactly. `only` is a comma list of
`assets,crates,npm,nuget,maven,go`; blank selects all. Unknown names, spaces and
empty list entries are rejected. Selecting registries without `assets` requires
an existing published GitHub release and tag at that commit. Assets are always
validated, even for a registry retry. Dry runs still verify, build, download and
validate, but create no tag/release and publish nothing. NuGet mints no key.
GitHub's **Re-run failed jobs** also works: the successful prerequisite jobs
remain valid and each registry skips a version it already has.

Never move a released tag. Fix an implementation defect on `main` and release
a new patch. For a credential or registry outage, retain the version and retry
only the missing registries. Successfully published versions stay published.
Prereleases such as `X.Y.Z-rc.N` use GitHub's prerelease flag and npm's `next` tag.

## Build-time versions

All tracked release manifests, the Rust lock's own crate entry, shell manifest
templates and C/pod runtime strings use `0.0.0-dev`. `bb version set|bump` writes
only `version.edn`. `bb version --check TAG` checks that file and the placeholder
inventory and existing dist/stage manifests. Core uses --source-only to ignore
stale build outputs after a bump; package checks validate the current staged fields. All package
hosts stamp staged text, never tracked sources: Cargo manifest and lock, Maven
POM, NuGet project, npm versions and matching peer ranges, and C/pod sources.
Go's version is its `go/vX.Y.Z` tag and module-zip prefix.

Local Rust, CLI, C/pod, wasm and unit build scripts read `version.edn` through
`bb tools/version.clj --stamp-copy` while assembling their copies (`--stamp`
for temporary unit manifests). Stamped copies preserve timestamps when unchanged. CLI version uses
Cargo's package version; the C and pod strings are stamped too. No CI-only
version environment is required. Archive names and Java jar metadata already
read the central file. Tracked documentation keeps stable placeholders; the
site replaces them with the central version when rendering install examples.
`site/package.json` has no release version: its `@3sln/bab` dependencies have
independent upstream versions and must stay pinned, along with their lockfile.

## Registry and repository setup

The repository must be public for npm provenance, Go proxy resolution and free
GitHub Pages. Reserve the npm organization `3sln`; verify Maven namespace
`com.3sln` in Central using its DNS TXT challenge. Package names are `emon`
(crates.io), `@3sln/emon`, `@3sln/pdfium-wasm`, `@3sln/emon-ppocr` (npm), `Emon`
(NuGet), and `com.3sln:emon` (Maven). Go is `github.com/3sln/emon/go`.
Allow GitHub Actions to create `v*` and `go/v*` tags in any tag rulesets. No bot
needs to bypass protection on `main`: bumps use the normal contributor workflow.
Pages source is GitHub Actions, with the `github-pages` environment allowing
`main`; Pages remains independent of release and uses `emon.3sln.com`.

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
*Settings* > *Trusted Publisher* > *GitHub Actions*, with organization/user `3sln`, repository `emon`, workflow `release.yml`, environment left **blank**.
The `npm` job grants `id-token: write` and runs npm 11.21.2 on Node 24; it publishes with provenance and no stored token. A Trusted Publisher can only be
configured on a package that exists, so a package's **first** version (here `@3sln/emon-ppocr`) must be published once by hand
(`npm publish dist/js-ppocr/3sln-emon-ppocr-V.tgz --access public`, logged in as an owner of `@3sln`); every later version publishes from the workflow.
After switching, delete the old `NPM_TOKEN` repository secret.

**NuGet: Trusted Publishing, no stored key.** `release-run.yml`'s `nuget` job (with `id-token: write`) runs `NuGet/login`, which exchanges the
job's OIDC token for a short-lived API key (about an hour) and hands it to `bb publish nuget` as `NUGET_API_KEY`. Nothing long-lived exists. Set up once:

1. nuget.org > your profile > *Trusted Publishing* > create a policy: repository owner `3sln`, repository `emon`, workflow file `release-run.yml`, environment left **blank**.
2. An **organization** Actions variable `NUGET_USER` (Organization settings > Secrets and variables > Actions > Variables) holding your nuget.org **profile name** (not your
   email). **An organization variable must grant repository access to `emon`** (*Repository access*: all repositories, or selected, with `emon` in the list); otherwise
   `vars.NUGET_USER` is empty. Alternatively create a repository Actions variable `NUGET_USER`. The job checks this before building, including on dry runs, and explains how to fix access.

The login runs only when dry_run=false. A manual dry run mints no key and `bb publish nuget --dry-run` needs none.

Migrate the npm Trusted Publisher to `release.yml`: npm validates the calling
workflow's name ([npm documentation](https://docs.npmjs.com/trusted-publishers/)).
Use `release-run.yml` in the NuGet policy: NuGet validates `job_workflow_ref`
([NuGet specification](https://github.com/NuGet/Home/blob/dev/accepted/2024/trusted-publishers-oidc-for-nuget-push.technical.md)),
which identifies the called workflow ([GitHub documentation](https://docs.github.com/en/actions/how-tos/secure-your-work/security-harden-deployments/oidc-with-reusable-workflows)).
Both files belong to `3sln/emon`; keep environment blank (D881/D882). Hosted
OIDC exchanges and the actual registry policies still require CI validation.

## Archives and checks

Verification builds five CLI and five optional PP-OCR archives plus four C and
four pod archives: 18 archives, five CLI sidecars, and SHA256SUMS. The optional
PP artifacts remain separate; no default SDK gains an ORT dependency.
`emon/archives` records the verified run containing `release-archives` (90-day
retention); expired artifacts require rerunning verification. `bb release-ops
fetch-archives` checks completeness and hashes before publication. Native
cross-platform smokes and full corpus gates are hosted CI requirements.

Local contracts: `bb version --self-test`, `bb release-ops --self-test`,
`bb release-verify --self-test`, `bb publish --self-test`, `bb release-audit`,
`bb docs --check`, `bb ci core`, and `bb package --check`. Packaging does not
upload. Never invoke a real `bb publish` locally. `bb release-notes` derives notes
from DEVLOG and commits since the previous tag; `bb release-verify` checks the
central version, templates, tag, release target and verified commit status.
