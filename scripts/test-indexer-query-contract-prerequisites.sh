#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONTRACT_SCRIPT="${SCRIPT_DIR}/test-indexer-query-contract.sh"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "${TEST_ROOT}"' EXIT

FAKE_BIN="${TEST_ROOT}/bin"
mkdir -p "${FAKE_BIN}"
MAVEN_PATH="${FAKE_BIN}:${PATH}"
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'set -euo pipefail' \
  'printf "%s\\n" "$*" >> "${MAVEN_LOG}"' > "${FAKE_BIN}/mvn"
chmod +x "${FAKE_BIN}/mvn"

REQUIRED_MESSAGE="JDTLS_HOME must point to a real JDT language server installation directory"

run_rejected_case() {
  local label="$1"
  local mode="$2"
  local configured_path="$3"
  local output="${TEST_ROOT}/${label}.log"
  local maven_log="${TEST_ROOT}/${label}.maven"
  local status

  : > "${maven_log}"
  set +e
  if [ "${mode}" = "unset" ]; then
    env -u JDTLS_HOME MAVEN_LOG="${maven_log}" PATH="${MAVEN_PATH}" "${CONTRACT_SCRIPT}" > "${output}" 2>&1
    status=$?
  else
    JDTLS_HOME="${configured_path}" MAVEN_LOG="${maven_log}" PATH="${MAVEN_PATH}" "${CONTRACT_SCRIPT}" > "${output}" 2>&1
    status=$?
  fi
  set -e

  if [ "${status}" -eq 0 ]; then
    echo "${label} JDTLS_HOME case unexpectedly passed" >&2
    exit 1
  fi
  if ! grep -Fq -- "${REQUIRED_MESSAGE}" "${output}"; then
    echo "${label} case did not report the JDTLS_HOME requirement" >&2
    exit 1
  fi
  if [ -s "${maven_log}" ]; then
    echo "${label} case invoked Maven before rejecting JDTLS_HOME" >&2
    exit 1
  fi
  if [ -n "${configured_path}" ] && grep -Fq -- "${configured_path}" "${output}"; then
    echo "${label} case exposed the configured JDTLS_HOME path" >&2
    exit 1
  fi
}

run_rejected_case "unset" "unset" ""
run_rejected_case "empty" "value" ""

NON_DIRECTORY="${TEST_ROOT}/jdtls-file"
touch "${NON_DIRECTORY}"
run_rejected_case "non-directory" "value" "${NON_DIRECTORY}"

VALID_DIRECTORY="${TEST_ROOT}/valid jdtls home"
VALID_OUTPUT="${TEST_ROOT}/valid.log"
VALID_MAVEN_LOG="${TEST_ROOT}/valid.maven"
mkdir -p "${VALID_DIRECTORY}"
: > "${VALID_MAVEN_LOG}"
JDTLS_HOME="${VALID_DIRECTORY}" MAVEN_LOG="${VALID_MAVEN_LOG}" PATH="${MAVEN_PATH}" \
  "${CONTRACT_SCRIPT}" > "${VALID_OUTPUT}" 2>&1

if [ "$(wc -l < "${VALID_MAVEN_LOG}")" -ne 2 ]; then
  echo "valid JDTLS_HOME case did not forward both Maven invocations" >&2
  exit 1
fi
grep -Fq -- "--batch-mode --no-transfer-progress -pl semantic-indexer -am -Pjdtls-it" "${VALID_MAVEN_LOG}"
grep -Fq -- "-Dtest=FixtureFullIndexJdtLsIT" "${VALID_MAVEN_LOG}"
grep -Fq -- "-pl semantic-query -am -Pmongo-it" "${VALID_MAVEN_LOG}"
grep -Fq -- "-Dtest=ToolProjectionEvolutionIT" "${VALID_MAVEN_LOG}"

echo "test-indexer-query-contract prerequisite tests passed"
