#!/usr/bin/env bash
set -euo pipefail

: "${JDTLS_HOME:?JDTLS_HOME must point to a real JDT language server installation}"
test -d "${JDTLS_HOME}"

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${root_dir}"

# The JDT-LS fixture test starts disposable MongoDB and produces payment, order, and video
# generations using the production exporter; no Query documents or projection constants are seeded here.
mvn --batch-mode --no-transfer-progress -pl semantic-indexer -am -Pjdtls-it \
  -Dtest=FixtureFullIndexJdtLsIT -Dsurefire.failIfNoSpecifiedTests=false test

# Fail immediately when the independently declared acceptance set drifts from the runtime catalog.
acceptance_tools="$(jq -r '.tools[]' acceptance/tool-cases.json | sort -u)"
runtime_tools="$(sed -n 's/.*ToolProjectionRequirement\.[a-z]*("\([^"]*\)".*/\1/p' \
  semantic-query/src/main/java/com/java/semantic/mcp/ToolProjectionCatalog.java | sort -u)"
if [[ "${acceptance_tools}" != "${runtime_tools}" ]]; then
  echo "acceptance/tool-cases.json and the runtime MCP catalog differ" >&2
  diff -u <(printf '%s\n' "${runtime_tools}") <(printf '%s\n' "${acceptance_tools}") || true
  exit 1
fi

# This contract suite verifies every registered case's declared projection requirement and typed result boundary.
mvn --batch-mode --no-transfer-progress -pl semantic-query -am -Pmongo-it \
  -Dtest=ToolProjectionEvolutionIT,ToolProjectionCatalogTest,QueryMcpToolCatalogConfigurationTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
