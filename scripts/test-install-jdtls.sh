#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALLER="${SCRIPT_DIR}/install-jdtls.sh"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "${TEST_ROOT}"' EXIT

FAKE_BIN="${TEST_ROOT}/bin"
PARTIAL_TARGET="${TEST_ROOT}/partial"
VALID_TARGET="${TEST_ROOT}/valid"
mkdir -p "${FAKE_BIN}" "${PARTIAL_TARGET}" "${VALID_TARGET}/plugins" "${VALID_TARGET}/config_linux"
cp "$(type -P false)" "${FAKE_BIN}/curl"

touch "${PARTIAL_TARGET}/.installed-1.50.0"
set +e
PARTIAL_OUTPUT="$(PATH="${FAKE_BIN}:/usr/bin:/bin" "${INSTALLER}" "${PARTIAL_TARGET}" 2>&1)"
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

touch "${VALID_TARGET}/.installed-1.50.0"
touch "${VALID_TARGET}/plugins/org.eclipse.equinox.launcher_test.jar"
touch "${VALID_TARGET}/config_linux/config.ini"
VALID_OUTPUT="$(PATH="${FAKE_BIN}:/usr/bin:/bin" "${INSTALLER}" "${VALID_TARGET}" 2>&1)"
case "${VALID_OUTPUT}" in
  *"jdtls 1.50.0 already installed"*) ;;
  *)
    echo "valid installation did not use the marker fast path" >&2
    exit 1
    ;;
esac

echo "install-jdtls marker validation tests passed"
