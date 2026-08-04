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
trap 'rm -rf "${SMOKE_ROOT}"' EXIT

INITIALIZE_REQUEST='{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"processId":null,"rootUri":null,"capabilities":{}}}'
printf 'Content-Length: %s\r\n\r\n%s' "${#INITIALIZE_REQUEST}" "${INITIALIZE_REQUEST}" > "${REQUEST_FILE}"

set +e
timeout "${SMOKE_TIMEOUT_SECONDS}s" bash -o pipefail -c '
    { cat "$1"; sleep "$2"; } | docker run -i --rm --entrypoint bash "$3" -ceu '\''
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
    '\''
' bash "${REQUEST_FILE}" "${SMOKE_TIMEOUT_SECONDS}" "${IMAGE}" > "${STDOUT_FILE}" 2> "${STDERR_FILE}"
STATUS=$?
set -e

if [ "${STATUS}" -eq 15 ]; then
  echo "JDT LS image smoke failed: process exited with status 15" >&2
  exit 1
fi

if grep -Eq 'AccessDeniedException|Invalid Configuration Location' "${STDERR_FILE}"; then
  echo "JDT LS image smoke failed: JDT LS rejected its runtime configuration" >&2
  exit 1
fi

if [ "${STATUS}" -ne 124 ]; then
  echo "JDT LS image smoke failed: language server stopped before bounded input wait (status ${STATUS})" >&2
  exit 1
fi

if ! grep -Eq '"id"[[:space:]]*:[[:space:]]*1.*"result"|"result".*"id"[[:space:]]*:[[:space:]]*1' "${STDOUT_FILE}"; then
  echo "JDT LS image smoke failed: initialize response was not received" >&2
  exit 1
fi

echo "JDT LS image smoke passed"
