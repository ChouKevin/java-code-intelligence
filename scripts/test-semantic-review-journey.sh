#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
maven="${MAVEN_CMD:-mvn}"

if [[ -z "${JDTLS_HOME:-}" || ! -d "${JDTLS_HOME}" ]]; then
  echo "JDTLS_HOME must name an existing JDT LS directory" >&2
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

"${maven}" --batch-mode --no-transfer-progress -pl semantic-indexer -am -Pjdtls-it \
  -Dtest=SemanticReviewJourneyIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Dsemantic.review.journey.enabled=true \
  -Dsemantic.review.journey.indexer.jar="${indexer_jar}" \
  -Dsemantic.review.journey.query.jar="${query_jar}" test
