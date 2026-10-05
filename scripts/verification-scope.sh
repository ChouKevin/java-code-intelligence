#!/usr/bin/env bash
set -euo pipefail

unit=false journey=false images=false
emit() {
  printf 'unit=%s\njourney=%s\nimages=%s\n' "$unit" "$journey" "$images"
}
full() {
  unit=true journey=true images=true
  emit
  exit 0
}

base='' head=''
while [[ $# -gt 0 ]]; do
  case "$1" in
    --full) full ;;
    --base|--head)
      [[ $# -ge 2 && -n "$2" ]] || full
      if [[ "$1" == --base ]]; then base="$2"; else head="$2"; fi
      shift 2
      ;;
    *) full ;;
  esac
done
[[ -n "$base" && -n "$head" ]] || full
# Resolve to commit IDs before diff: refs cannot become options or pathspecs.
base="$(git rev-parse --verify --end-of-options "$base^{commit}" 2>/dev/null)" || full
head="$(git rev-parse --verify --end-of-options "$head^{commit}" 2>/dev/null)" || full
paths="$(mktemp "${TMPDIR:-/tmp}/verification-scope.XXXXXXXX")" || full
trap 'rm -f -- "$paths"' EXIT
# A rename is deliberately represented as delete + add, preserving both scopes.
# Check diff status directly; process substitution can hide a failed git command.
if ! git diff --no-ext-diff --no-textconv --no-renames --name-only -z "$base" "$head" -- > "$paths"; then
  full
fi
while IFS= read -r -d '' path; do
  case "$path" in
    README.md|AGENTS.md|docs/*) ;;
    pom.xml|semantic-model/*|semantic-indexer/pom.xml|semantic-query/pom.xml|.github/*|scripts/verification-scope.sh|scripts/test-verification-scope.sh)
      unit=true journey=true images=true ;;
    semantic-indexer/src/test/java/com/java/semantic/indexer/uat/SourceMcpJourneyIT.java|scripts/test-source-mcp.sh|semantic-indexer/fixtures/uat/video-service/*)
      journey=true ;;
    semantic-indexer/src/main/resources/*|semantic-query/src/main/resources/*|*/src/main/java/*/config/*|*/src/main/java/*/SemanticIndexerApplication.java|*/src/main/java/*/SemanticQueryApplication.java|*/src/main/java/*Security*.java|*/src/main/java/*TokenFilter.java|*/src/main/java/*/SourcePathResolver.java|*/src/main/java/*/LocalSourceRevisionCatalog.java)
      unit=true journey=true images=true ;;
    semantic-indexer/src/main/java/*.java|semantic-query/src/main/java/*.java)
      unit=true journey=true ;;
    semantic-indexer/src/test/*|semantic-query/src/test/*)
      unit=true ;;
    Dockerfile.indexer|Dockerfile.query|.env.example|scripts/test-source-images.sh)
      images=true ;;
    *) unit=true journey=true images=true ;;
  esac
done < "$paths"
emit
