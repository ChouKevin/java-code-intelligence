#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
maven="${MAVEN_CMD:-mvn}"
for command in java git; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "$command is required" >&2
    exit 1
  fi
done
if [[ "$(java -version 2>&1)" != *'version "21.'* && "$(java -version 2>&1)" != *'version "21"'* ]]; then
  echo 'Java 21 is required' >&2
  exit 1
fi
if [[ "$("$maven" --version)" != *'Apache Maven 3.9.'* ]]; then
  echo 'Set MAVEN_CMD to Maven 3.9.x' >&2
  exit 1
fi
export MAVEN_OPTS="${MAVEN_OPTS:--Xms64m -Xmx384m -XX:ActiveProcessorCount=2}"
maven_args=(--batch-mode --no-transfer-progress '-DargLine=-Xms64m -Xmx256m -XX:ActiveProcessorCount=2' -DforkCount=1 -DreuseForks=true)
run_root="$(mktemp -d "${TMPDIR:-/tmp}/document-mcp-journey.XXXXXXXX")"
cleanup() {
  # Never delete underneath a child whose owner failed or was killed before reaping.
  if compgen -G "$run_root/work/*.running" >/dev/null; then
    echo "Owned child termination is unconfirmed; retaining private work root: $run_root/work" >&2
  else
    rm -rf -- "$run_root/work"
  fi
  echo "Document journey artifacts: $run_root/artifacts"
}
trap cleanup EXIT
mkdir -p "$run_root/artifacts" "$run_root/work/runtime"
cd "$root_dir"
"$maven" "${maven_args[@]}" -pl document-query -am -DskipTests clean package
jar="$root_dir/document-query/target/document-query-0.0.1-SNAPSHOT-exec.jar"
if [[ ! -f "$jar" ]]; then
  echo 'Fresh document-query executable jar is required' >&2
  exit 1
fi
cp -- "$jar" "$run_root/work/runtime/document-query.jar"
"$maven" "${maven_args[@]}" -pl document-query -am -Pdeployed-it \
  -Dtest=DocumentMcpJourneyIT,DocumentLocalPublicationJourneyIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Ddocument.journey.root="$run_root" \
  -Ddocument.journey.jar="$run_root/work/runtime/document-query.jar" test
echo 'Native document MCP journeys completed: reader isolation, real local CLI publication, pinned pagination/read, rollback, withdrawal and restart.'
echo 'This is packaged-process proof, not external-agent acceptance; GPT 6 Luna requires a separate recorded dual-MCP run.'
