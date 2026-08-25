#!/usr/bin/env bash
set -euo pipefail

image_name="${1:?usage: test-query-image.sh IMAGE}"
container_id="$(docker create "${image_name}")"
scan_dir="$(mktemp -d)"
trap 'docker rm -f "${container_id}" >/dev/null 2>&1 || true; rm -rf "${scan_dir}"' EXIT
docker export "${container_id}" | tar -xf - -C "${scan_dir}"
image_contents="$(find "${scan_dir}" -print)"
image_environment="$(docker image inspect "${image_name}" --format '{{range .Config.Env}}{{println .}}{{end}}')"

if grep -Eqi 'jdtls|jgit|lsp4j|eclipse\.jdt|/data/(repos|jdtls)' <<<"${image_contents}"; then
  echo "Query image contains an Indexer-only artifact or directory" >&2
  exit 1
fi

while IFS= read -r query_jar; do
  jar_contents="$(jar tf "${query_jar}")"
  if grep -Eqi '(^|/)(org/eclipse/jgit|org/eclipse/lsp4j|org/eclipse/jdt|.*jdtls)|BOOT-INF/lib/.*(jgit|lsp4j|eclipse\.jdt)' <<<"${jar_contents}"; then
    echo "Query fat jar contains an Indexer-only class or nested dependency" >&2
    exit 1
  fi
  if grep -Eqi 'BOOT-INF/lib/spring-ai-(model|chat-model|embedding-model|prompt|template|document-reader|parser)|org/springframework/ai/(model|chat|embedding|prompt|template|parser)/' <<<"${jar_contents}"; then
    echo "Query fat jar contains a forbidden Spring AI model or prompt dependency" >&2
    exit 1
  fi
done < <(find "${scan_dir}" -type f -name '*.jar')

if grep -Eqi 'JAVA_SEMANTIC_JDTLS|JDTLS_|REPOSITORY_.*(ROOT|WORKSPACE)|DATA_ROOT|/opt/jdtls|/data/(repos|jdtls)' <<<"${image_environment}"; then
  echo "Query image exposes an Indexer-only environment variable" >&2
  exit 1
fi
