#!/usr/bin/env bash
set -euo pipefail

selector="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/verification-scope.sh"
run_root="$(mktemp -d "${TMPDIR:-/tmp}/verification-scope-test.XXXXXXXX")"
trap 'rm -rf -- "$run_root"' EXIT
export GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
export GIT_AUTHOR_NAME='Scope Test' GIT_AUTHOR_EMAIL='scope@example.test'
export GIT_COMMITTER_NAME="$GIT_AUTHOR_NAME" GIT_COMMITTER_EMAIL="$GIT_AUTHOR_EMAIL"
repo="$run_root/repository"
git init --quiet "$repo"
cd "$repo"
printf 'seed\n' > seed
git add -- seed
git commit --quiet -m baseline
base="$(git rev-parse HEAD)"
scenarios=0

expect() {
  local name="$1" unit="$2" journey="$3" images="$4" actual expected
  shift 4
  expected="$(printf 'unit=%s\njourney=%s\nimages=%s' "$unit" "$journey" "$images")"
  if ! actual="$(bash "$selector" "$@")"; then
    echo "FAIL: $name: selector did not return a usable selection" >&2
    exit 1
  fi
  if [[ "$actual" != "$expected" ]]; then
    printf 'FAIL: %s\nExpected:\n%s\nActual:\n%s\n' "$name" "$expected" "$actual" >&2
    exit 1
  fi
  scenarios=$((scenarios + 1))
  printf 'PASS: %s\n' "$name"
}

change() {
  local name="$1" unit="$2" journey="$3" images="$4" path
  shift 4
  # Restore only this disposable repository's controlled baseline.
  git reset --quiet --hard "$base"
  for path in "$@"; do
    mkdir -p -- "$(dirname -- "$path")"
    printf 'content for %s\n' "$path" > "$path"
  done
  git add --all
  git commit --quiet -m "$name"
  expect "$name" "$unit" "$journey" "$images" --base "$base" --head HEAD
}

change docs-only false false false README.md AGENTS.md docs/operations/testing.md
change root-build true true true pom.xml
change module-build true true true semantic-query/pom.xml
change shared-model true true true semantic-model/src/main/java/Identity.java
change ci-change true true true .github/workflows/ci.yml
change selector-change true true true scripts/verification-scope.sh
change selector-regression-change true true true scripts/test-verification-scope.sh
change indexer-behavior true true false semantic-indexer/src/main/java/com/java/semantic/indexer/job/IndexJobDispatcher.java
change query-behavior true true false semantic-query/src/main/java/com/java/semantic/query/source/BoundedSourceReader.java
change app-resources true true true semantic-query/src/main/resources/application.yml
change app-security true true true semantic-indexer/src/main/java/com/java/semantic/indexer/api/IndexerAdminTokenFilter.java
change app-bootstrap true true true semantic-query/src/main/java/com/java/semantic/query/SemanticQueryApplication.java
change ordinary-tests true false false semantic-indexer/src/test/java/com/java/semantic/indexer/source/LocalSourceFixture.java
change journey-test false true false semantic-indexer/src/test/java/com/java/semantic/indexer/uat/SourceMcpJourneyIT.java
change journey-launcher false true false scripts/test-source-mcp.sh
change video-data false true false semantic-indexer/fixtures/uat/video-service/src/main/java/com/example/video/Video.java
change images false false true Dockerfile.indexer Dockerfile.query .env.example scripts/test-source-images.sh
change mixed true true false semantic-query/src/test/java/SearchTest.java scripts/test-source-mcp.sh
change unknown-path true true true new-surface/unknown.txt
change hostile-doc-name false false false $'docs/space tab\tand\nnewline.md'
change hostile-unknown-name true true true $'-hostile $(touch compromised)\nunknown'
[[ ! -e compromised ]]

git reset --quiet --hard "$base"
mkdir -p docs
printf 'unique rename contents\n' > docs/old.md
git add --all
git commit --quiet -m before-rename
rename_base="$(git rev-parse HEAD)"
git mv -- docs/old.md Dockerfile.query
git commit --quiet -m rename
expect rename-doc-to-image false false true --base "$rename_base" --head HEAD

git mv -- Dockerfile.query docs/new.md
git commit --quiet -m reverse-rename
expect rename-image-to-doc false false true --base HEAD~1 --head HEAD

git rm --quiet -- docs/new.md
git commit --quiet -m delete-doc
expect deleted-doc false false false --base HEAD~1 --head HEAD

git rm --quiet -- seed
git commit --quiet -m delete-unknown
expect deleted-unknown true true true --base HEAD~1 --head HEAD
mkdir -p semantic-query/src/test semantic-indexer/fixtures/uat/video-service
printf 'cross-boundary rename\n' > semantic-query/src/test/Case.java
git add --all
git commit --quiet -m before-test-rename
git mv -- semantic-query/src/test/Case.java semantic-indexer/fixtures/uat/video-service/Case.java
git commit --quiet -m test-to-fixture
expect rename-test-to-journey true true false --base HEAD~1 --head HEAD
git rm --quiet -- semantic-indexer/fixtures/uat/video-service/Case.java
git commit --quiet -m delete-fixture
expect deleted-fixture false true false --base HEAD~1 --head HEAD
expect empty-diff false false false --base HEAD --head HEAD
expect missing-base true true true --head HEAD
expect unavailable-base true true true --base 0000000000000000000000000000000000000000 --head HEAD
expect unavailable-head true true true --base "$base" --head missing-revision
expect explicit-full true true true --full
# Real diff failure: invoke outside a Git repository, with no mocked Git process.
cd "$run_root"
expect failed-diff true true true --base "$base" --head "$base"
printf 'PASS: %s real temporary-Git scenarios\n' "$scenarios"
