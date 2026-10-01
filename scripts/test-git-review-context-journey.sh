#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
maven="${MAVEN_CMD:-mvn}"
cd "${root_dir}"
: "${JDTLS_HOME:?Set JDTLS_HOME to a real JDT LS installation for review endpoint preparation}"
test -d "${JDTLS_HOME}"
command -v java >/dev/null
command -v docker >/dev/null
docker info >/dev/null

# Fresh executable jars are required: the test launches the deployed application boundaries,
# never a combined Indexer/Query test classpath.
# This controlled local-fixture host journey explicitly uses LOCAL_TRUSTED JDT LS.
# It does not certify production LINUX_UID isolation; the image journey owns that evidence.
# Mongo is a fresh Testcontainers instance, and both applications use independent JVMs.
"${maven}" --batch-mode --no-transfer-progress -pl semantic-indexer,semantic-query -am -DskipTests package

"${maven}" --batch-mode --no-transfer-progress -pl semantic-indexer -am -Pmongo-it \
  -Dtest=GitReviewContextJourneyIT -Dsurefire.failIfNoSpecifiedTests=false -Dgit.review.journey.enabled=true \
  -Dgit.review.journey.indexer.jar="${root_dir}/semantic-indexer/target/semantic-indexer-0.0.1-SNAPSHOT.jar" \
  -Dgit.review.journey.query.jar="${root_dir}/semantic-query/target/semantic-query-0.0.1-SNAPSHOT-exec.jar" verify
