#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
maven="${MAVEN_CMD:-mvn}"
cd "${root_dir}"

# Fresh executable jars are required: the test launches the deployed application boundaries,
# never a combined Indexer/Query test classpath.
"${maven}" --batch-mode --no-transfer-progress -pl semantic-indexer,semantic-query -am -DskipTests package

JDTLS_HOME="${root_dir}/target/git-review-context-missing-jdt" \
  "${maven}" --batch-mode --no-transfer-progress -pl semantic-indexer -am -Pmongo-it \
  -Dtest=GitReviewContextJourneyIT -Dsurefire.failIfNoSpecifiedTests=false -Dgit.review.journey.enabled=true \
  -Dgit.review.journey.indexer.jar="${root_dir}/semantic-indexer/target/semantic-indexer-0.0.1-SNAPSHOT.jar" \
  -Dgit.review.journey.query.jar="${root_dir}/semantic-query/target/semantic-query-0.0.1-SNAPSHOT-exec.jar" verify
