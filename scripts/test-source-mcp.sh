#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
maven="${MAVEN_CMD:-mvn}"
for command in git java; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "$command is required" >&2
    exit 1
  fi
done
if [[ "$(java -version 2>&1)" != *'version "21.'* && "$(java -version 2>&1)" != *'version "21"'* ]]; then
  echo 'Java 21 is required' >&2
  exit 1
fi
if ! git --version >/dev/null; then
  echo 'A working Git executable is required' >&2
  exit 1
fi
rg_executable="${SOURCE_TEST_RG:-/usr/bin/rg}"
if [[ -z "${SOURCE_TEST_RG:-}" && ! -x "$rg_executable" ]]; then
  rg_executable="$(type -P rg || true)"
fi
if [[ ! -f "$rg_executable" || ! -x "$rg_executable" || "$rg_executable" != /* ]]; then
  echo 'Set SOURCE_TEST_RG to an absolute, executable real ripgrep file' >&2
  exit 1
fi
rg_executable="$(readlink -f "$rg_executable")"
if [[ "$("$rg_executable" --version)" != ripgrep\ * ]]; then
  echo 'The configured executable must be real ripgrep' >&2
  exit 1
fi

run_root="$(mktemp -d "${TMPDIR:-/tmp}/source-mcp-journey.XXXXXXXX")"
cleanup() {
  # Only remove this run's disposable service storage, Git fixture and process logs.
  # Git source bodies and potentially sensitive process diagnostics never become retained artifacts.
  rm -rf -- "$run_root/work"
  echo "Source journey artifacts: $run_root/artifacts"
}
trap cleanup EXIT
mkdir -p "$run_root/artifacts"
cd "$root_dir"
"$maven" --batch-mode --no-transfer-progress -DskipTests clean package
indexer_jar="$root_dir/semantic-indexer/target/semantic-indexer-0.0.1-SNAPSHOT.jar"
query_jar="$root_dir/semantic-query/target/semantic-query-0.0.1-SNAPSHOT-exec.jar"
if [[ ! -f "$indexer_jar" || ! -f "$query_jar" ]]; then
  echo 'Fresh, separately named Indexer and Query executable jars are required' >&2
  exit 1
fi
"$maven" --batch-mode --no-transfer-progress -pl semantic-indexer -am -Pdeployed-it \
  -Dtest=SourceMcpJourneyIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Dsource.journey.root="$run_root" -Dsource.journey.indexer.jar="$indexer_jar" \
  -Dsource.journey.query.jar="$query_jar" -Dsource.test.rg="$rg_executable" test
