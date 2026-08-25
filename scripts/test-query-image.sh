#!/usr/bin/env bash
set -euo pipefail

image_name="${1:?usage: test-query-image.sh IMAGE}"
container_id="$(docker create "${image_name}")"
trap 'docker rm -f "${container_id}" >/dev/null 2>&1 || true' EXIT
image_contents="$(docker export "${container_id}" | tar -tf -)"
image_environment="$(docker image inspect "${image_name}" --format '{{range .Config.Env}}{{println .}}{{end}}')"

if grep -Eqi 'jdtls|jgit|lsp4j|eclipse\.jdt|data/(repos|jdtls)' <<<"${image_contents}"; then
  echo "Query image contains an Indexer-only artifact or directory" >&2
  exit 1
fi

if grep -Eqi 'JAVA_SEMANTIC_JDTLS|JDTLS_|REPOSITORY_.*(ROOT|WORKSPACE)|DATA_ROOT' <<<"${image_environment}"; then
  echo "Query image exposes an Indexer-only environment variable" >&2
  exit 1
fi
