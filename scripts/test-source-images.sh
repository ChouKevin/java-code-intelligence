#!/usr/bin/env bash
set -euo pipefail

# Operates exclusively on a fresh temporary directory and disposable containers.
indexer_image="${INDEXER_IMAGE:-java-source-indexer:phase1}"
query_image="${QUERY_IMAGE:-java-source-query:phase1}"
for program in docker git curl jq jar javac; do
  command -v "${program}" >/dev/null || { echo "missing prerequisite: ${program}" >&2; exit 1; }
done

root="$(mktemp -d)"
indexer_container=""
query_container=""
check_container=""
bootstrapped=0
successful=0
cleanup() {
  for container in "${query_container}" "${indexer_container}" "${check_container}"; do
    if [ -n "${container}" ]; then docker rm -f "${container}" >/dev/null 2>&1 || true; fi
  done
  # Preserve the original request identity and fresh storage on an unknown
  # acceptance outcome; never silently submit a second request.
  if [ "${successful}" != 1 ] && [ -f "${root}/request-id" ]; then
    echo "Smoke did not complete; request identity retained at ${root}/request-id" >&2
    return
  fi
  # Only the newly created storage is service-owned; never traverse service data.
  if [ "${bootstrapped}" = 1 ]; then
    docker run --rm --user 0 --mount "type=bind,src=${root}/storage,dst=/smoke" \
      --entrypoint sh "${indexer_image}" -c 'rm -rf /smoke/source-admin /smoke/source-published' || true
  fi
  rm -rf "${root}"
}
trap cleanup EXIT
repository_root="$(git rev-parse --show-toplevel)"
mkdir -p "${root}/peer-classes"
javac -d "${root}/peer-classes" \
  "${repository_root}/semantic-query/src/test/java/com/java/semantic/query/source/SourceReadLockPeer.java"

fail() { echo "source image smoke failed: $*" >&2; exit 1; }
mkdir -p "${root}/fixture" "${root}/storage/source-published"
git -C "${root}/fixture" init -q -b main
git -C "${root}/fixture" config user.name 'Source Smoke'
git -C "${root}/fixture" config user.email 'source-smoke@example.invalid'
printf 'package smoke;\nclass Example { String marker = "source-image-needle"; }\n' > "${root}/fixture/Example.java"
git -C "${root}/fixture" add Example.java
git -C "${root}/fixture" commit -qm 'published source fixture'
revision="$(git -C "${root}/fixture" rev-parse HEAD)"
find "${root}/fixture" -type d -exec chmod 0755 {} +
find "${root}/fixture" -type f -exec chmod 0644 {} +

# Only this bootstrap runs as root: an operator must provision the same ownership
# on the new host mount. Both application processes remain different non-root UIDs.
docker run --rm --user 0 --mount "type=bind,src=${root}/storage,dst=/data" \
  --entrypoint sh "${indexer_image}" -ceu '
    chown 10001:10001 /data /data/source-published
    chmod 0700 /data
    chmod 0755 /data/source-published
  '
bootstrapped=1

# Inspect executable image users and the actual packaged Spring Boot dependencies.
[ "$(docker image inspect "${indexer_image}" --format '{{.Config.User}}')" = '10001:10001' ] || fail 'Indexer must run as writer UID 10001'
[ "$(docker image inspect "${query_image}" --format '{{.Config.User}}')" = '10002:10002' ] || fail 'Query must run as reader UID 10002'
for image in "${indexer_image}" "${query_image}"; do
  case "${image}" in "${indexer_image}") jar_name=semantic-indexer;; *) jar_name=semantic-query;; esac
  check_container="$(docker create "${image}")"
  docker cp "${check_container}:/app/${jar_name}.jar" "${root}/${jar_name}.jar"
  docker rm "${check_container}" >/dev/null
  check_container=""
  jar tf "${root}/${jar_name}.jar" > "${root}/${jar_name}.entries"
  if grep -Eiq 'BOOT-INF/lib/[^/]*(mongo|jdt|lsp4j|lombok)|BOOT-INF/classes/.*(mongo|jdtls|lsp4j)' "${root}/${jar_name}.entries"; then
    fail "${jar_name} contains Mongo/JDT/LSP dependency or implementation"
  fi
done
if grep -Eiq 'BOOT-INF/lib/[^/]*jgit|BOOT-INF/classes/com/java/semantic/indexer/' "${root}/semantic-query.entries"; then
  fail 'Query contains JGit or Indexer implementation'
fi
if docker image inspect "${query_image}" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -Eiq '^(GIT_|SEMANTIC_INDEXER_|SEMANTIC_SOURCE_ADMIN_ROOT|SEMANTIC_MONGODB_|JDTLS_)'; then
  fail 'Query image embeds private environment'
fi

docker run --rm --entrypoint sh "${query_image}" -ceu \
  'test -x /usr/bin/rg && /usr/bin/rg --version >/dev/null; ! command -v git >/dev/null; test ! -e /data/source-admin; test ! -e /opt/jdtls'
docker run --rm --entrypoint sh "${indexer_image}" -ceu \
  'test ! -e /opt/jdtls; test ! -e /data/repos; test ! -e /data/jdtls'

indexer_container="$(docker run -d -p 127.0.0.1::8080 \
  --mount "type=bind,src=${root}/storage,dst=/data" \
  --mount "type=bind,src=${root}/fixture,dst=/fixture,readonly" \
  -e SEMANTIC_INDEXER_ADMIN_TOKEN=indexer-smoke-only \
  -e SEMANTIC_SOURCE_RETENTION_INTERVAL=1s \
  -e 'SPRING_APPLICATION_JSON={"semantic":{"repositories":{"fixture":{"url":"file:///fixture","default-branch":"main","display-name":"Smoke Fixture"}}}}' \
  "${indexer_image}")"
indexer_url="http://$(docker port "${indexer_container}" 8080/tcp)"
wait_http() {
  local url="$1" container="$2" status
  for ((attempt=0; attempt<60; attempt++)); do
    status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 2 "${url}" 2>/dev/null || true)"
    if [ "${status}" != '000' ] && [ -n "${status}" ]; then return; fi
    if [ "$(docker inspect "${container}" --format '{{.State.Running}}')" != true ]; then
      docker logs "${container}" >&2
      fail "container exited before HTTP readiness"
    fi
    sleep 1
  done
  docker logs "${container}" >&2
  fail "HTTP readiness timeout: ${url}"
}
wait_http "${indexer_url}/index/repositories/fixture/jobs?requestId=readiness" "${indexer_container}"

# Linux kernel UUIDs are canonical lowercase; write once before HTTP admission.
IFS= read -r request_id < /proc/sys/kernel/random/uuid
printf '%s\n' "${request_id}" > "${root}/request-id"
sync -f "${root}/request-id"
status="$(curl -sS -o "${root}/job.json" -w '%{http_code}' --max-time 10 \
  -H 'X-Api-Token: indexer-smoke-only' -H 'Content-Type: application/json' \
  -d "{\"requestId\":\"${request_id}\"}" "${indexer_url}/index/repositories/fixture/source")"
[ "${status}" = 202 ] || fail "Indexer prepare returned HTTP ${status}: $(cat "${root}/job.json")"
for ((attempt=0; attempt<60; attempt++)); do
  status="$(curl -sS -o "${root}/job.json" -w '%{http_code}' --max-time 5 \
    -H 'X-Api-Token: indexer-smoke-only' \
    "${indexer_url}/index/repositories/fixture/jobs?requestId=${request_id}")"
  [ "${status}" = 200 ] || fail "get_job returned HTTP ${status}: $(cat "${root}/job.json")"
  phase="$(jq -r '.phase' "${root}/job.json")"
  if [ "${phase}" = COMPLETE ]; then break; fi
  [ "${phase}" != FAILED ] || fail "prepare failed: $(cat "${root}/job.json")"
  sleep 1
done
[ "${phase}" = COMPLETE ] || fail 'prepare job did not complete'
[ "$(jq -r '.resolvedRevision' "${root}/job.json")" = "${revision}" ] || fail 'resolved Git SHA mismatch'

query_container="$(docker run -d -p 127.0.0.1::8080 \
  --mount "type=bind,src=${root}/storage/source-published,dst=/published-evidence,readonly" \
  -e SEMANTIC_QUERY_API_TOKEN=query-smoke-only \
  -e SEMANTIC_SOURCE_PUBLISHED_ROOT=/published-evidence \
  -e SEMANTIC_QUERY_RG_EXECUTABLE=/tmp/source-smoke-rg \
  -e 'SPRING_APPLICATION_JSON={"semantic":{"query":{"source":{"allowed-repositories":["fixture"]}}}}' \
  --entrypoint sh "${query_image}" -ceu \
  'cp /usr/bin/rg /tmp/source-smoke-rg; exec java -jar /app/semantic-query.jar')"
query_url="http://$(docker port "${query_container}" 8080/tcp)"
wait_http "${query_url}/api/v1/repositories" "${query_container}"
docker inspect "${query_container}" --format '{{json .Mounts}}' | jq -e \
  'length == 1 and .[0].Destination == "/published-evidence" and .[0].RW == false' >/dev/null \
  || fail 'Query has a private mount or a writable published mount'
docker inspect "${indexer_container}" --format '{{json .Mounts}}' | jq -e \
  'length == 2 and any(.[]; .Destination == "/data" and .RW == true)
   and any(.[]; .Destination == "/fixture" and .RW == false)' >/dev/null \
  || fail 'Indexer staging/published or Git fixture mount is incorrect'


post_query() {
  local path="$1" body="$2" status
  status="$(curl -sS -o "${root}/query.json" -w '%{http_code}' --max-time 10 \
    -H 'X-Api-Token: query-smoke-only' -H 'Content-Type: application/json' \
    -d "${body}" "${query_url}/api/v1/${path}")"
  [ "${status}" = 200 ] || fail "Query ${path} returned HTTP ${status}: $(cat "${root}/query.json")"
}
post_query context '{"repositoryId":"fixture"}'
jq -e --arg sha "${revision}" '.sourceStatus == "READY" and .context.repositoryId == "fixture" and .context.revision == $sha' "${root}/query.json" >/dev/null || fail 'Query did not admit published SHA'
post_query search-text "{\"context\":{\"repositoryId\":\"fixture\",\"revision\":\"${revision}\"},\"query\":\"source-image-needle\"}"
jq -e '.matches | any(.path == "Example.java" and .line == 2)' "${root}/query.json" >/dev/null || fail 'real ripgrep search did not find fixture source'
post_query source "{\"context\":{\"repositoryId\":\"fixture\",\"revision\":\"${revision}\"},\"path\":\"Example.java\"}"
jq -e '.content | contains("source-image-needle")' "${root}/query.json" >/dev/null || fail 'Query did not read published source'
status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
  -H 'X-Api-Token: query-smoke-only' -H 'Content-Type: application/json' \
  -d '{"requestId":"must-not-prepare"}' "${query_url}/index/repositories/fixture/source")"
[ "${status}" = 404 ] || fail "Query exposes Indexer mutation route (HTTP ${status})"


# Probe only a throwaway filename. A root process or a shared UID would hide this bug.
if docker exec "${query_container}" sh -c 'touch /published-evidence/query-must-not-write' >/dev/null 2>&1; then
  fail 'Query can mutate published bind mount'
fi
docker exec "${query_container}" sh -ceu '
  test "$(id -u)" = 10002
  test ! -e /data/source-admin
  test ! -e /fixture
  test ! -e /run/secrets/indexer-admin-token
  test -z "${GIT_TOKEN:-}${GIT_USERNAME:-}${SEMANTIC_INDEXER_ADMIN_TOKEN:-}"
  test ! -e /opt/jdtls
  ! command -v git >/dev/null
'
if docker exec -e SEMANTIC_QUERY_RG_EXECUTABLE=/missing-nondefault-rg "${query_container}" \
    java -jar /app/semantic-query.jar --server.port=0 >"${root}/invalid-rg.log" 2>&1; then
  fail 'Query ignored the configured nondefault ripgrep executable'
fi
docker exec "${indexer_container}" sh -ceu 'test "$(id -u)" = 10001; test -d /data/source-admin; test -d /data/source-published'
# This peer proves OS/UID/read-only-mount locking, not an in-flight HTTP request.
# Native facade tests separately exercise full-operation lease ownership.
printf 'package smoke;\nclass Example { String marker = "source-image-needle-B"; }\n' > "${root}/fixture/Example.java"
git -C "${root}/fixture" add Example.java
git -C "${root}/fixture" commit -qm 'replacement source fixture B'
revision_b="$(git -C "${root}/fixture" rev-parse HEAD)"
IFS= read -r request_b < /proc/sys/kernel/random/uuid
printf '%s\n' "${request_b}" > "${root}/request-id-b"
sync -f "${root}/request-id-b"
status="$(curl -sS -o "${root}/job-b.json" -w '%{http_code}' --max-time 10 \
  -H 'X-Api-Token: indexer-smoke-only' -H 'Content-Type: application/json' \
  -d "{\"requestId\":\"${request_b}\",\"revision\":\"${revision_b}\"}" "${indexer_url}/index/repositories/fixture/source")"
[ "${status}" = 202 ] || fail "B preparation admission returned HTTP ${status}"
for ((attempt=0; attempt<60; attempt++)); do
  status="$(curl -sS -o "${root}/job-b.json" -w '%{http_code}' --max-time 5 \
    -H 'X-Api-Token: indexer-smoke-only' \
    "${indexer_url}/index/repositories/fixture/jobs?requestId=${request_b}")"
  [ "${status}" = 200 ] || fail "B original request lookup returned HTTP ${status}"
  phase="$(jq -r '.phase' "${root}/job-b.json")"
  if [ "${phase}" = COMPLETE ]; then break; fi
  [ "${phase}" != FAILED ] || fail 'B preparation failed'
  sleep 1
done
[ "${phase}" = COMPLETE ] || fail 'B preparation did not complete'
[ "$(jq -r '.resolvedRevision' "${root}/job-b.json")" = "${revision_b}" ] || fail 'B resolved SHA mismatch'
post_query source "{\"context\":{\"repositoryId\":\"fixture\",\"revision\":\"${revision}\"},\"path\":\"Example.java\"}"
jq -e '.content | contains("source-image-needle-B") | not' "${root}/query.json" >/dev/null || fail 'A changed after B publication'
docker stop "${indexer_container}" >/dev/null
# Only disposable private metadata is aged. No published manifest/tree bytes are changed.
docker run --rm --user 10001:10001 --mount "type=bind,src=${root}/storage,dst=/data" \
  --entrypoint sh "${indexer_image}" -ceu 'cat /data/source-admin/repositories/fixture/retention.json' \
  > "${root}/retention-before.json"
aged_at="$(date -u -d '31 days ago' '+%Y-%m-%dT%H:%M:%SZ')"
jq --arg sha "${revision}" --arg age "${aged_at}" '.retiredAt[$sha] = $age' \
  "${root}/retention-before.json" > "${root}/retention-aged.json"
chmod 0644 "${root}/retention-aged.json"
docker run --rm --user 10001:10001 --mount "type=bind,src=${root}/storage,dst=/data" \
  --mount "type=bind,src=${root}/retention-aged.json,dst=/retention-aged.json,readonly" \
  --entrypoint sh "${indexer_image}" -ceu '
    cp /retention-aged.json /data/source-admin/repositories/fixture/retention-aged.tmp
    chmod 0600 /data/source-admin/repositories/fixture/retention-aged.tmp
    sync
    mv /data/source-admin/repositories/fixture/retention-aged.tmp /data/source-admin/repositories/fixture/retention.json
    sync
  '
docker exec "${query_container}" mkdir -p /tmp/source-lock-peer
docker cp "${root}/peer-classes/." "${query_container}:/tmp/source-lock-peer"
docker exec -d --user 10002:10002 "${query_container}" java -cp /tmp/source-lock-peer \
  com.java.semantic.query.source.SourceReadLockPeer shared /published-evidence/fixture/read.lock \
  /tmp/source-gc-peer-ready /tmp/source-gc-peer-release 60000
peer_ready=0
for ((attempt=0; attempt<10; attempt++)); do
  if docker exec "${query_container}" test -f /tmp/source-gc-peer-ready; then peer_ready=1; break; fi
  sleep 1
done
[ "${peer_ready}" = 1 ] || fail 'reader UID peer could not acquire shared lock on read-only mount'
docker start "${indexer_container}" >/dev/null
wait_http "${indexer_url}/index/repositories/fixture/jobs?requestId=${request_b}" "${indexer_container}"
busy=0
for ((attempt=0; attempt<30; attempt++)); do
  docker logs "${indexer_container}" > "${root}/gc.log" 2>&1
  if grep -Eq 'event=source_gc_skip .*repositoryId=fixture reason=READ_IN_PROGRESS' "${root}/gc.log"; then busy=1; break; fi
  sleep 1
done
[ "${busy}" = 1 ] || fail 'startup GC did not report read-in-progress protection'
post_query source "{\"context\":{\"repositoryId\":\"fixture\",\"revision\":\"${revision}\"},\"path\":\"Example.java\"}"
jq -e '.content | contains("source-image-needle")' "${root}/query.json" >/dev/null || fail 'busy A became unreadable'
docker exec --user 10002:10002 "${query_container}" touch /tmp/source-gc-peer-release
deleted=0
for ((attempt=0; attempt<30; attempt++)); do
  status="$(curl -sS -o "${root}/expired.json" -w '%{http_code}' --max-time 5 \
    -H 'X-Api-Token: query-smoke-only' -H 'Content-Type: application/json' \
    -d "{\"repositoryId\":\"fixture\",\"revision\":\"${revision}\"}" "${query_url}/api/v1/context")"
  if [ "${status}" = 404 ] && jq -e '.code == "REVISION_NOT_PREPARED"' "${root}/expired.json" >/dev/null \
      && docker exec "${indexer_container}" test ! -e "/data/source-published/fixture/revisions/${revision}" \
      && docker exec "${indexer_container}" test ! -e /data/source-admin/repositories/fixture/pending-delete.json; then
    deleted=1; break
  fi
  sleep 1
done
[ "${deleted}" = 1 ] || fail 'scheduled retry did not withdraw expired A after peer release'
docker logs "${indexer_container}" > "${root}/gc.log" 2>&1
grep -Eq "event=source_gc_deleted .*repositoryId=fixture revision=${revision} .*result=deleted" "${root}/gc.log" \
  || fail 'A physical deletion completion event missing'
post_query search-text "{\"context\":{\"repositoryId\":\"fixture\",\"revision\":\"${revision_b}\"},\"query\":\"source-image-needle-B\"}"
jq -e '.matches | any(.path == "Example.java" and .line == 2)' "${root}/query.json" >/dev/null || fail 'retained B search failed'
post_query source "{\"context\":{\"repositoryId\":\"fixture\",\"revision\":\"${revision_b}\"},\"path\":\"Example.java\"}"
jq -e '.content | contains("source-image-needle-B")' "${root}/query.json" >/dev/null || fail 'retained B read failed'
successful=1
echo 'Source images: real Git A/B, read-only UID locking, busy skip, scheduled cleanup, retained B and isolation passed'
