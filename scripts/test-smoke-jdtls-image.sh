#!/usr/bin/env bash
set -euo pipefail

SCRIPT_PATH="$(readlink -f "${BASH_SOURCE[0]}")"

if [ "${FAKE_DOCKER_MODE:-}" = "initialize-then-exit" ]; then
  case "${1:-}" in
    run)
      printf '%s\n' 'true 0' > "${FAKE_DOCKER_STATE}"
      printf '%s\n' '{"jsonrpc":"2.0","id":1,"result":{}}'
      sleep 0.2
      printf '%s\n' 'false 7' > "${FAKE_DOCKER_STATE}"
      exit 7
      ;;
    container)
      if [ "${2:-}" != "inspect" ] || [ ! -f "${FAKE_DOCKER_STATE}" ]; then
        exit 1
      fi
      touch "${FAKE_DOCKER_INSPECTED}"
      cat "${FAKE_DOCKER_STATE}"
      exit 0
      ;;
    rm)
      rm -f "${FAKE_DOCKER_STATE}"
      touch "${FAKE_DOCKER_CLEANUP}"
      exit 0
      ;;
    *)
      exit 1
      ;;
  esac
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SMOKE_SCRIPT="${SCRIPT_DIR}/smoke-jdtls-image.sh"
TEST_ROOT="$(mktemp -d)"
trap 'rm -rf "${TEST_ROOT}"' EXIT

FAKE_BIN="${TEST_ROOT}/bin"
FAKE_DOCKER_STATE="${TEST_ROOT}/container-state"
FAKE_DOCKER_CLEANUP="${TEST_ROOT}/container-cleanup"
FAKE_DOCKER_INSPECTED="${TEST_ROOT}/container-inspected"
mkdir -p "${FAKE_BIN}"
ln -s "${SCRIPT_PATH}" "${FAKE_BIN}/docker"

set +e
OUTPUT="$(
  PATH="${FAKE_BIN}:${PATH}" \
    FAKE_DOCKER_MODE=initialize-then-exit \
    FAKE_DOCKER_STATE="${FAKE_DOCKER_STATE}" \
    FAKE_DOCKER_CLEANUP="${FAKE_DOCKER_CLEANUP}" \
    FAKE_DOCKER_INSPECTED="${FAKE_DOCKER_INSPECTED}" \
    JDTLS_IMAGE_SMOKE_TIMEOUT_SECONDS=2 \
    "${SMOKE_SCRIPT}" fake-image 2>&1
)"
STATUS=$?
set -e

if [ "${STATUS}" -eq 0 ]; then
  echo "initialize response followed by exit 7 must fail the image smoke" >&2
  exit 1
fi

if [ ! -f "${FAKE_DOCKER_INSPECTED}" ]; then
  echo "smoke must inspect the container after initialize response" >&2
  exit 1
fi

case "${OUTPUT}" in
  *"language server stopped after initialize response (status 7)"*) ;;
  *)
    echo "smoke did not report the initialize-then-exit status" >&2
    exit 1
    ;;
esac

if [ ! -f "${FAKE_DOCKER_CLEANUP}" ]; then
  echo "failed smoke must remove the named container" >&2
  exit 1
fi

if [ -e "${FAKE_DOCKER_STATE}" ]; then
  echo "failed smoke left a fake container behind" >&2
  exit 1
fi

echo "smoke-jdtls-image lifecycle regression test passed"
