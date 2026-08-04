#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALLER="${SCRIPT_DIR}/install-jdtls.sh"
RELEASE="${SCRIPT_DIR}/jdtls-release.env"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "${TEST_ROOT}"' EXIT

FAKE_BIN="${TEST_ROOT}/bin"
PARTIAL_TARGET="${TEST_ROOT}/partial"
VALID_TARGET="${TEST_ROOT}/valid"
EMPTY_METADATA_TARGET="${TEST_ROOT}/empty-metadata"
TEST_SCRIPTS="${TEST_ROOT}/scripts"
mkdir -p "${FAKE_BIN}" "${PARTIAL_TARGET}" "${VALID_TARGET}/plugins" "${VALID_TARGET}/config_linux" "${TEST_SCRIPTS}"
cp "$(type -P false)" "${FAKE_BIN}/curl"

cp "${INSTALLER}" "${TEST_SCRIPTS}/install-jdtls.sh"
cp "${RELEASE}" "${TEST_SCRIPTS}/jdtls-release.env"
TEST_INSTALLER="${TEST_SCRIPTS}/install-jdtls.sh"

# shellcheck source=jdtls-release.env
source "${RELEASE}"

sed -i 's/^JDTLS_VERSION=.*/JDTLS_VERSION=/' "${TEST_SCRIPTS}/jdtls-release.env"
set +e
EMPTY_METADATA_OUTPUT="$("${TEST_INSTALLER}" "${EMPTY_METADATA_TARGET}" 2>&1)"
EMPTY_METADATA_STATUS=$?
set -e
if [ "${EMPTY_METADATA_STATUS}" -eq 0 ]; then
  echo "empty release metadata must fail" >&2
  exit 1
fi
case "${EMPTY_METADATA_OUTPUT}" in
  *"JDTLS_VERSION is required"*) ;;
  *)
    echo "empty release metadata did not report the missing field" >&2
    exit 1
    ;;
esac

cp "${RELEASE}" "${TEST_SCRIPTS}/jdtls-release.env"

touch "${PARTIAL_TARGET}/.installed-${JDTLS_VERSION}"
set +e
PARTIAL_OUTPUT="$(PATH="${FAKE_BIN}:/usr/bin:/bin" "${TEST_INSTALLER}" "${PARTIAL_TARGET}" 2>&1)"
PARTIAL_STATUS=$?
set -e
if [ "${PARTIAL_STATUS}" -eq 0 ]; then
  echo "partial marker state must trigger reinstallation" >&2
  exit 1
fi
case "${PARTIAL_OUTPUT}" in
  *"already installed"*)
    echo "partial marker state was incorrectly accepted" >&2
    exit 1
    ;;
esac

touch "${VALID_TARGET}/.installed-${JDTLS_VERSION}"
touch "${VALID_TARGET}/plugins/org.eclipse.equinox.launcher_test.jar"
touch "${VALID_TARGET}/config_linux/config.ini"
VALID_OUTPUT="$(PATH="${FAKE_BIN}:/usr/bin:/bin" "${TEST_INSTALLER}" "${VALID_TARGET}" 2>&1)"
case "${VALID_OUTPUT}" in
  *"jdtls ${JDTLS_VERSION} already installed"*) ;;
  *)
    echo "valid installation did not use the marker fast path" >&2
    exit 1
    ;;
esac

echo "install-jdtls marker validation tests passed"
