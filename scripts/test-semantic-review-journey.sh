#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
maven="${MAVEN_CMD:-mvn}"

# Opt-in, destructive only to this test's disposable Mongo container and temporary Git fixture.
# The production Indexer image must be rebuilt from the same checkout as the jars below.
# JDT LS runs inside that image; Query runs independently with a read-only Mongo user.
indexer_image="${SEMANTIC_REVIEW_INDEXER_IMAGE:-semantic-indexer:review-local}"
if ! command -v docker >/dev/null 2>&1; then
  echo "Docker CLI is required for the semantic review journey" >&2
  exit 1
fi
if ! docker info >/dev/null 2>&1; then
  echo "Docker daemon is required for the semantic review journey" >&2
  exit 1
fi
if ! docker image inspect "${indexer_image}" >/dev/null 2>&1; then
  echo "Indexer image '${indexer_image}' is not available locally" >&2
  exit 1
fi

cd "${root_dir}"
"${maven}" --batch-mode --no-transfer-progress -pl semantic-indexer,semantic-query -am -DskipTests package

indexer_jar="${root_dir}/semantic-indexer/target/semantic-indexer-0.0.1-SNAPSHOT.jar"
query_jar="${root_dir}/semantic-query/target/semantic-query-0.0.1-SNAPSHOT-exec.jar"
if [[ ! -f "${indexer_jar}" || ! -f "${query_jar}" ]]; then
  echo "fresh executable Indexer and Query jars are required" >&2
  exit 1
fi

echo "Semantic journey: isolated maintenance/writer/reader identities; native BUILD requestId recovery"
echo "Semantic journey: UNINDEXED -> A -> newer current; pinned READY A/B; guide disabled/valid/invalid"
echo "Semantic journey: semantic HTTP/native MCP parity; real JDT fingerprint reuse; cold Mongo-only Query"

"${maven}" --batch-mode --no-transfer-progress -pl semantic-indexer -am -Pjdtls-it \
  -Dtest=SemanticReviewJourneyIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Dsemantic.review.journey.enabled=true \
  -Dsemantic.review.journey.indexer.jar="${indexer_jar}" \
  -Dsemantic.review.journey.indexer.image="${indexer_image}" \
  -Dsemantic.review.journey.query.jar="${query_jar}" test
