#!/usr/bin/env bash
#
# The mechanical half of a release. The judgment half (checking CI, watching the
# workflow, reviewing the release notes) is the release skill in
# .agents/skills/release/SKILL.md, which calls this script.
#
#   scripts/release.sh prepare X.Y.Z   cut the changelog, set the README and pom versions,
#                                      commit "release X.Y.Z" and tag vX.Y.Z (nothing is pushed)
#   scripts/release.sh check X.Y.Z     verify HEAD is a prepared X.Y.Z release; the release
#                                      workflow runs this before it touches Maven Central
#   scripts/release.sh notes X.Y.Z     print the CHANGELOG section for X.Y.Z (release notes body)
#   scripts/release.sh next X.Y.Z      set the poms to X.Y.Z-SNAPSHOT and commit
#
# prepare and next require a clean working tree on main or on a maintenance branch (N.M.x).
set -euo pipefail

REPO_URL="https://github.com/josipmusa/idempotency4j"

usage() {
    sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//'
    exit 2
}

fail() {
    echo "release.sh: $*" >&2
    exit 1
}

require_version() {
    [[ "$1" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "version must be X.Y.Z, got '$1'"
}

require_clean_tree_on_release_branch() {
    [[ -z "$(git status --porcelain)" ]] || fail "working tree is not clean"
    local branch
    branch="$(git branch --show-current)"
    [[ "$branch" == "main" || "$branch" =~ ^[0-9]+\.[0-9]+\.x$ ]] \
        || fail "must run on main or a maintenance branch (N.M.x), currently on '$branch'"
}

pom_version() {
    ./mvnw -q help:evaluate -Dexpression=project.version -DforceStdout --batch-mode
}

set_pom_version() {
    ./mvnw -q versions:set -DnewVersion="$1" -DgenerateBackupPoms=false --batch-mode
}

prepare() {
    local version="$1" tag="v$1" today
    require_version "$version"
    require_clean_tree_on_release_branch
    today="$(date -u +%F)"

    git rev-parse -q --verify "refs/tags/$tag" >/dev/null && fail "tag $tag already exists locally"
    [[ -z "$(git ls-remote --tags origin "refs/tags/$tag")" ]] || fail "tag $tag already exists on origin"

    local current
    current="$(pom_version)"
    [[ "$current" == *-SNAPSHOT ]] || fail "pom version is '$current', expected a -SNAPSHOT (already prepared?)"

    grep -q '^## \[Unreleased\]$' CHANGELOG.md || fail "CHANGELOG.md has no '## [Unreleased]' section"
    grep -q '^\[Unreleased\]: ' CHANGELOG.md || fail "CHANGELOG.md has no '[Unreleased]:' compare link"
    local unreleased_body
    unreleased_body="$(awk '/^## \[Unreleased\]$/ { on = 1; next } /^## \[/ { on = 0 } on && NF' CHANGELOG.md)"
    [[ -n "$unreleased_body" ]] || fail "the Unreleased section of CHANGELOG.md is empty"
    ! grep -q "^## \[$version\]" CHANGELOG.md || fail "CHANGELOG.md already has a section for $version"

    # Rename Unreleased to the release and reopen an empty Unreleased above it.
    perl -pi -e "s/^## \\[Unreleased\\]\$/## [Unreleased]\n\n## [$version] - $today/" CHANGELOG.md
    perl -pi -e "s{^\\[Unreleased\\]: .*\$}{[Unreleased]: $REPO_URL/compare/$tag...HEAD\n[$version]: $REPO_URL/releases/tag/$tag}" CHANGELOG.md

    perl -pi -e "s{<version>\\d+\\.\\d+\\.\\d+</version>}{<version>$version</version>}g" README.md
    grep -q "<version>$version</version>" README.md || fail "README.md has no dependency snippets to update"

    set_pom_version "$version"

    git add -A
    git commit -q -m "release $version"
    git tag -a "$tag" -m "release $version"
    check "$version"

    cat <<EOF
Prepared $version on $(git branch --show-current):

  $(git log -1 --format='%h %s')
  tag $tag

Review with:   git show --stat HEAD
Publish with:  git push origin $(git branch --show-current) $tag
Then:          scripts/release.sh next <next version>
EOF
}

# Everything the release workflow relies on, checked before it deploys: the tag, the poms,
# the changelog and the README all name the same version, and the commit is on a release
# branch. Works on a detached checkout in CI (remote branches) as well as locally.
check() {
    local version="$1" tag="v$1" failed=0
    require_version "$version"

    problem() {
        echo "release.sh: $*" >&2
        failed=1
    }

    local current
    current="$(pom_version)"
    [[ "$current" == "$version" ]] || problem "pom version is '$current', expected '$version'"

    grep -q "^## \[$version\] - [0-9]\{4\}-[0-9]\{2\}-[0-9]\{2\}$" CHANGELOG.md \
        || problem "CHANGELOG.md has no dated '## [$version]' section"
    grep -q "^\[$version\]: " CHANGELOG.md || problem "CHANGELOG.md has no '[$version]:' link"

    local readme_versions
    readme_versions="$(grep -oE '<version>[0-9]+\.[0-9]+\.[0-9]+</version>' README.md | sort -u | tr '\n' ' ')"
    [[ "$readme_versions" == "<version>$version</version> " ]] \
        || problem "README.md dependency snippets say '${readme_versions% }', expected '<version>$version</version>'"

    git branch -a --contains HEAD --format='%(refname:short)' \
        | grep -qE '^(origin/)?(main|[0-9]+\.[0-9]+\.x)$' \
        || problem "HEAD is not on main or a maintenance branch (N.M.x)"

    if git rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
        [[ "$(git rev-parse "$tag^{commit}")" == "$(git rev-parse HEAD)" ]] \
            || problem "tag $tag does not point at HEAD"
    fi

    [[ $failed -eq 0 ]] || fail "HEAD is not a prepared $version release"
    echo "HEAD is a prepared $version release"
}

# The CHANGELOG section for a version, without its heading: the body of the GitHub release.
notes() {
    local version="$1"
    require_version "$version"
    grep -q "^## \[$version\]" CHANGELOG.md || fail "CHANGELOG.md has no section for $version"
    awk -v heading="## [$version]" '
        index($0, heading) == 1 { on = 1; next }
        /^## \[/ { on = 0 }
        on
    ' CHANGELOG.md | sed -e '/./,$!d' -e :a -e '/^\n*$/{$d;N;ba' -e '}'
}

next() {
    local version="$1"
    require_version "$version"
    require_clean_tree_on_release_branch

    local current
    current="$(pom_version)"
    [[ "$current" != *-SNAPSHOT ]] || fail "pom version is already a snapshot ('$current')"

    set_pom_version "$version-SNAPSHOT"
    git add -A
    git commit -q -m "start $version development"
    echo "Poms set to $version-SNAPSHOT: $(git log -1 --format='%h %s')"
    echo "Publish with:  git push origin $(git branch --show-current)"
}

[[ $# -eq 2 ]] || usage
cd "$(git rev-parse --show-toplevel)"
case "$1" in
    prepare) prepare "$2" ;;
    check) check "$2" ;;
    notes) notes "$2" ;;
    next) next "$2" ;;
    *) usage ;;
esac
