#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -ne 1 ]; then
  echo "usage: $0 IMAGE" >&2
  exit 2
fi

IMAGE="$1"
SMOKE_TIMEOUT_SECONDS="${JDTLS_IMAGE_SMOKE_TIMEOUT_SECONDS:-30}"
SMOKE_ROOT="$(mktemp -d)"
STDOUT_FILE="${SMOKE_ROOT}/stdout"
STDERR_FILE="${SMOKE_ROOT}/stderr"
REQUEST_FILE="${SMOKE_ROOT}/initialize-request"
INPUT_FIFO="${SMOKE_ROOT}/stdin"
CONTAINER_NAME="jdtls-image-smoke-${SMOKE_ROOT##*/}"
DOCKER_PID=""
DOCKER_EXIT_STATUS=""
INPUT_PID=""

cleanup() {
  local status=$?
  trap - EXIT

  if [ -n "${INPUT_PID}" ]; then
    kill "${INPUT_PID}" >/dev/null 2>&1 || true
    wait "${INPUT_PID}" >/dev/null 2>&1 || true
  fi

  docker rm -f "${CONTAINER_NAME}" >/dev/null 2>&1 || true

  if [ -n "${DOCKER_PID}" ]; then
    kill "${DOCKER_PID}" >/dev/null 2>&1 || true
    wait "${DOCKER_PID}" >/dev/null 2>&1 || true
  fi

  rm -rf "${SMOKE_ROOT}"
  exit "${status}"
}

container_status() {
  docker container inspect --format '{{.State.Running}} {{.State.ExitCode}}' "${CONTAINER_NAME}"
}

docker_process_stopped() {
  if kill -0 "${DOCKER_PID}" >/dev/null 2>&1; then
    return 1
  fi

  set +e
  wait "${DOCKER_PID}"
  DOCKER_EXIT_STATUS=$?
  set -e
  return 0
}

fail_for_invalid_runtime_configuration() {
  if grep -Eq 'AccessDeniedException|Invalid Configuration Location' "${STDERR_FILE}"; then
    echo "JDT LS image smoke failed: JDT LS rejected its runtime configuration" >&2
    exit 1
  fi
}

verify_processes_after_initialize() {
  if ! CONTAINER_STATUS="$(container_status 2>/dev/null)"; then
    echo "JDT LS image smoke failed: Docker container could not be inspected after initialize response" >&2
    exit 1
  fi

  read -r CONTAINER_RUNNING CONTAINER_EXIT_STATUS <<< "${CONTAINER_STATUS}"
  if [ "${CONTAINER_RUNNING}" != "true" ]; then
    echo "JDT LS image smoke failed: language server stopped after initialize response (status ${CONTAINER_EXIT_STATUS})" >&2
    exit 1
  fi

  if docker_process_stopped; then
    echo "JDT LS image smoke failed: Docker process stopped after initialize response (status ${DOCKER_EXIT_STATUS})" >&2
    exit 1
  fi
}

trap cleanup EXIT

INITIALIZE_REQUEST='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":null,"capabilities":{}}}'
printf 'Content-Length: %s\r\n\r\n%s' "${#INITIALIZE_REQUEST}" "${INITIALIZE_REQUEST}" > "${REQUEST_FILE}"
mkfifo "${INPUT_FIFO}"

docker run -i --name "${CONTAINER_NAME}" --entrypoint bash "${IMAGE}" -ceu '
    if [ "$(id -un)" != "semantic" ]; then
        exit 1
    fi
    launcher="$(find /opt/jdtls/plugins -maxdepth 1 -type f -name "org.eclipse.equinox.launcher_*.jar" -print -quit)"
    if [ -z "${launcher}" ]; then
        exit 1
    fi
    workspace="$(mktemp -d /data/jdtls/image-smoke.XXXXXX)"
    exec java \
        -Declipse.application=org.eclipse.jdt.ls.core.id1 \
        -Dosgi.bundles.defaultStartLevel=4 \
        -Declipse.product=org.eclipse.jdt.ls.core.product \
        -Dlog.level=ALL \
        -Xmx512m \
        --add-modules=ALL-SYSTEM \
        --add-opens java.base/java.util=ALL-UNNAMED \
        --add-opens java.base/java.lang=ALL-UNNAMED \
        -jar "${launcher}" \
        -configuration /opt/jdtls/config_linux \
        -data "${workspace}"
' < "${INPUT_FIFO}" > "${STDOUT_FILE}" 2> "${STDERR_FILE}" &
DOCKER_PID=$!

{ cat "${REQUEST_FILE}"; sleep "${SMOKE_TIMEOUT_SECONDS}"; } > "${INPUT_FIFO}" &
INPUT_PID=$!

DEADLINE=$((SECONDS + SMOKE_TIMEOUT_SECONDS))
while [ "${SECONDS}" -lt "${DEADLINE}" ]; do
  if grep -Eq '"id"[[:space:]]*:[[:space:]]*1.*"result"|"result".*"id"[[:space:]]*:[[:space:]]*1' "${STDOUT_FILE}"; then
    break
  fi

  if CONTAINER_STATUS="$(container_status 2>/dev/null)"; then
    read -r CONTAINER_RUNNING CONTAINER_EXIT_STATUS <<< "${CONTAINER_STATUS}"
    if [ "${CONTAINER_RUNNING}" != "true" ]; then
      fail_for_invalid_runtime_configuration
      echo "JDT LS image smoke failed: language server stopped before initialize response (status ${CONTAINER_EXIT_STATUS})" >&2
      exit 1
    fi
  elif docker_process_stopped; then
    fail_for_invalid_runtime_configuration
    echo "JDT LS image smoke failed: Docker process stopped before initialize response (status ${DOCKER_EXIT_STATUS})" >&2
    exit 1
  fi

  sleep 0.1
done

fail_for_invalid_runtime_configuration

if ! grep -Eq '"id"[[:space:]]*:[[:space:]]*1.*"result"|"result".*"id"[[:space:]]*:[[:space:]]*1' "${STDOUT_FILE}"; then
  echo "JDT LS image smoke failed: initialize response was not received before timeout" >&2
  exit 1
fi

LIVENESS_DEADLINE=$((SECONDS + 1))
if [ "${LIVENESS_DEADLINE}" -gt "${DEADLINE}" ]; then
  LIVENESS_DEADLINE="${DEADLINE}"
fi

while :; do
  verify_processes_after_initialize

  if [ "${SECONDS}" -ge "${LIVENESS_DEADLINE}" ]; then
    break
  fi

  sleep 0.1
done

fail_for_invalid_runtime_configuration

echo "JDT LS image smoke passed"
