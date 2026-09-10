#!/usr/bin/env bash
set -euo pipefail

: "${JDTLS_HOME:?JDTLS_HOME must point to a real JDT language server installation}"
test -d "${JDTLS_HOME}"

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${root_dir}"

# The JDT-LS fixture test starts disposable MongoDB and produces payment, order, and video
# generations using the production exporter. It then starts an ephemeral real Query HTTP/MCP server
# against those published generations and follows fixture-returned IDs with the MCP SDK; no Query facts are seeded.
mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am -Pjdtls-it \
  -Dtest=FixtureFullIndexJdtLsIT \
  -Dsurefire.failIfNoSpecifiedTests=false test

# The Mongo profile runs the persisted projection contract; the fixture run above verifies MCP discovery.
mvn --batch-mode --no-transfer-progress -pl semantic-query -am -Pmongo-it \
  -Dtest=ToolProjectionEvolutionIT \
  -Dsurefire.failIfNoSpecifiedTests=false test
