---
name: release
description: Cut and publish a release of idempotency4j. Use when asked to release a version ("release 0.4.0"). Runs the pre-release checks, prepares and tags the release with scripts/release.sh, pushes on confirmation, watches the Release workflow, and starts the next development version.
disable-model-invocation: true
---

# Releasing idempotency4j

Releases are tag-driven. `scripts/release.sh prepare` makes one commit that sets the release
version everywhere and tags it; pushing the tag runs `.github/workflows/release.yml`, which
checks that commit, verifies, publishes to Maven Central and creates the GitHub release with
the CHANGELOG section as its body. `scripts/release.sh next` then reopens development. The
script does the mechanics. This skill is the checklist and the judgment around it.

The argument is the version to release, `X.Y.Z` without a `v`. Work through the steps in
order and stop at the confirmation gate.

## 1. Pre-release checks

Do all of these before changing anything. Report what you found; a failed check ends the
release until the user says otherwise.

- The working tree is clean and on `main` (or on a `N.M.x` maintenance branch for a patch
  release, see below), and up to date with `origin`.
- CI is green for the current `HEAD`: `gh run list --branch main --limit 3`.
- `CHANGELOG.md` has entries under `## [Unreleased]`, and they read as a complete account of
  the release: every user-visible change since the last tag is there and nothing is stale.
  Compare against `git log v<previous>..HEAD --oneline` and against the merged PRs. Fix the
  changelog in a normal PR first if it is not right.
- The version is correct for the content: a breaking change or a new baseline (Java, Spring
  Boot) is a minor bump while the project is pre-1.0, a fix-only release is a patch.
- No open PR should ship in this release. List them with `gh pr list` and ask if unsure.
- The release build passes locally, so the workflow will not fail after the tag exists:

  ```bash
  ./mvnw verify -Prelease -DskipTests -Dgpg.skip=true
  ```

  Docker is required for a full `./mvnw verify`; CI already ran it for `HEAD`, so the local
  run may skip tests.

## 2. Prepare

```bash
scripts/release.sh prepare X.Y.Z
```

This renames `## [Unreleased]` to the release with today's date and reopens an empty
Unreleased section, updates the link footer, sets the README dependency snippets and every
pom to `X.Y.Z`, commits `release X.Y.Z` and creates the annotated tag `vX.Y.Z`. Nothing is
pushed. The script refuses a dirty tree, a non-release branch, an empty Unreleased section,
a pom that is not a snapshot, or a tag that already exists, and finishes by running
`scripts/release.sh check X.Y.Z`, the same check the workflow runs.

Show the user `git show --stat HEAD` and the changelog diff.

## 3. Confirmation gate

Stop here and ask the user to confirm the push. Pushing the tag publishes to Maven Central,
which cannot be undone. Do not push without an explicit yes in this conversation.

The push needs repository admin credentials: `main` requires pull requests and tag creation
is blocked for everyone else, and the admin role bypasses both. Make sure `git` and `gh` are
authenticated as the repository owner before continuing.

```bash
git push origin main vX.Y.Z
```

Push the branch and the tag together so the release commit is on `main` when the workflow
checks for it.

## 4. Watch the workflow

```bash
gh run list --workflow release.yml --limit 1
gh run watch <run-id> --exit-status
```

The run checks the commit, verifies, deploys and waits until Central reports the deployment
as published, then creates the GitHub release. Expect it to take a while; the wait for
publication alone can be several minutes. If it fails:

- **At the check step**: the commit is not a prepared release. Read the message; the fix is
  another commit on `main`, then delete and recreate the tag. Nothing has been published.
- **At verify**: nothing has been published. Fix on `main`, then delete and recreate the tag.
- **At deploy**: look at the deployment on https://central.sonatype.com/publishing/deployments
  before anything else. A validation failure leaves nothing published and the tag can be
  redone; a deployment that shows as published cannot be repeated for the same version, so
  the fix is then only the GitHub release, created by hand with `gh release create`.
- **At the release step**: Central is done. Create the release by hand with
  `scripts/release.sh notes X.Y.Z` as the body.

## 5. Confirm the result

- The GitHub release exists and its body starts with the changelog section:
  `gh release view vX.Y.Z`.
- The artifacts resolve, for example
  https://repo1.maven.org/maven2/io/github/josipmusa/idempotency-core/X.Y.Z/. Central's
  mirror can lag the workflow by a few minutes; the deployment page is authoritative.

## 6. Start the next development version

```bash
scripts/release.sh next X.Y+1.0
git push origin main
```

This sets every pom to the next `-SNAPSHOT` and commits `start X.Y+1.0 development`. The
snapshot job in CI publishes it on that push. Skip this on a maintenance branch when no
further patch is planned.

Then report: the release URL, the Central status, and anything left open.

## Patch releases from a maintenance branch

To release `0.3.1` while `main` is already on `0.4.0-SNAPSHOT`:

```bash
git checkout -b 0.3.x v0.3.0
git push -u origin 0.3.x
# cherry-pick the fixes, add them under Unreleased in CHANGELOG.md, then
scripts/release.sh prepare 0.3.1
git push origin 0.3.x v0.3.1
```

The script and the workflow accept `N.M.x` branches. Afterwards, cherry-pick the changelog
section into `main` under the right heading and update the README snippets there by hand;
the README on `main` should always show the latest release.

## Conventions the script assumes

- Commit messages `release X.Y.Z` and `start X.Y.Z development`; tag message `release X.Y.Z`.
- `CHANGELOG.md` follows Keep a Changelog with a permanent `## [Unreleased]` section and a
  `[Unreleased]: .../compare/v<last>...HEAD` link in the footer.
- The pom on `main` always carries the next planned version as a `-SNAPSHOT`; the only
  non-snapshot commit on `main` is a release commit.
