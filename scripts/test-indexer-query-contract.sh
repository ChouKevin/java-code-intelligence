#!/usr/bin/env bash
set -euo pipefail

: "${JDTLS_HOME:?JDTLS_HOME must point to a real JDT language server installation}"
test -d "${JDTLS_HOME}"

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${root_dir}"

# The JDT-LS fixture test starts disposable MongoDB and produces payment, order, and video
# generations using the production exporter; no Query documents or projection constants are seeded here.
mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am -Pjdtls-it \
  -Dtest=FixtureFullIndexJdtLsIT \
  -Dsurefire.failIfNoSpecifiedTests=false test

# The Query suite verifies the twelve-tool MCP catalog and the persisted projection contract.
mvn --batch-mode --no-transfer-progress -pl semantic-query -am -Pmongo-it \
  -Dtest=ToolProjectionEvolutionIT,QueryMcpToolCatalogConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
