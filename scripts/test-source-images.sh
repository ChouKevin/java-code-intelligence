#!/usr/bin/env bash
set -euo pipefail

# Operates exclusively on a fresh temporary directory and disposable containers.
indexer_image="${INDEXER_IMAGE:-java-source-indexer:phase3}"
query_image="${QUERY_IMAGE:-java-source-query:phase3}"
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
      --entrypoint sh "${indexer_image}" -c 'rm -rf /smoke/source-admin /smoke/source-published /smoke/index-published' || true
  fi
  rm -rf "${root}"
}
trap cleanup EXIT
repository_root="$(git rev-parse --show-toplevel)"
mkdir -p "${root}/peer-classes"
javac -d "${root}/peer-classes" \
  "${repository_root}/semantic-query/src/test/java/com/java/semantic/query/source/SourceReadLockPeer.java"

fail() { echo "source image smoke failed: $*" >&2; exit 1; }
mkdir -p "${root}/fixture" "${root}/storage/source-published" "${root}/storage/index-published"
git -C "${root}/fixture" init -q -b main
git -C "${root}/fixture" config user.name 'Source Smoke'
git -C "${root}/fixture" config user.email 'source-smoke@example.invalid'
printf 'package smoke;\nclass Example { String marker = "source-image-needle"; public static void main(String[] args) {} }\n' > "${root}/fixture/Example.java"
git -C "${root}/fixture" add Example.java
git -C "${root}/fixture" commit -qm 'published source fixture'
revision="$(git -C "${root}/fixture" rev-parse HEAD)"
find "${root}/fixture" -type d -exec chmod 0755 {} +
find "${root}/fixture" -type f -exec chmod 0644 {} +

# Only this bootstrap runs as root: an operator must provision the same ownership
# on the new host mount. Both application processes remain different non-root UIDs.
docker run --rm --user 0 --mount "type=bind,src=${root}/storage,dst=/data" \
  --entrypoint sh "${indexer_image}" -ceu '
    chown 10001:10001 /data /data/source-published /data/index-published
    chmod 0700 /data
    chmod 0755 /data/source-published /data/index-published
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
  if grep -Eiq 'BOOT-INF/lib/[^/]*(mongo|lombok)|BOOT-INF/classes/.*mongo' "${root}/${jar_name}.entries"; then
    fail "${jar_name} contains Mongo/Lombok dependency or implementation"
  fi
done
for module in semantic-indexer semantic-query; do
  grep -Eq 'BOOT-INF/lib/sqlite-jdbc-3\.53\.4\.0\.jar$' "${root}/${module}.entries" \
    || fail "${module} lacks the approved SQLite JDBC runtime"
done
grep -Eq 'BOOT-INF/lib/javaparser-core-3\.28\.2\.jar$' "${root}/semantic-indexer.entries" \
  || fail 'Indexer lacks the approved JavaParser runtime'
if grep -Eq 'BOOT-INF/lib/[^/]*javaparser' "${root}/semantic-query.entries"; then
  fail 'Query contains a parser runtime'
fi
if grep -Eiq 'BOOT-INF/lib/[^/]*jgit|BOOT-INF/classes/com/java/semantic/indexer/' "${root}/semantic-query.entries"; then
  fail 'Query contains JGit or Indexer implementation'
fi
if docker image inspect "${query_image}" --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -Eiq '^(GIT_|SEMANTIC_INDEXER_|SEMANTIC_SOURCE_ADMIN_ROOT|SEMANTIC_MONGODB_)'; then
  fail 'Query image embeds private environment'
fi

docker run --rm --entrypoint sh "${query_image}" -ceu \
  'test -x /usr/bin/rg && /usr/bin/rg --version >/dev/null; ! command -v git >/dev/null; test ! -e /data/source-admin'
docker run --rm --entrypoint sh "${indexer_image}" -ceu \
  'test ! -e /data/repos'

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
  --mount "type=bind,src=${root}/storage/index-published,dst=/index-evidence,readonly" \
  -e SEMANTIC_QUERY_API_TOKEN=query-smoke-only \
  -e SEMANTIC_SOURCE_PUBLISHED_ROOT=/published-evidence \
  -e SEMANTIC_INDEX_PUBLISHED_ROOT=/index-evidence \
  -e SEMANTIC_QUERY_RG_EXECUTABLE=/tmp/source-smoke-rg \
  -e 'SPRING_APPLICATION_JSON={"semantic":{"query":{"source":{"allowed-repositories":["fixture"]}}}}' \
  --entrypoint sh "${query_image}" -ceu \
  'cp /usr/bin/rg /tmp/source-smoke-rg; exec java -jar /app/semantic-query.jar')"
query_url="http://$(docker port "${query_container}" 8080/tcp)"
wait_http "${query_url}/api/v1/repositories" "${query_container}"
docker inspect "${query_container}" --format '{{json .Mounts}}' | jq -e \
  'length == 2 and all(.[]; .RW == false)
   and any(.[]; .Destination == "/published-evidence")
   and any(.[]; .Destination == "/index-evidence")' >/dev/null \
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

# Use the actual HTTP MCP transport, accepting JSON and SSE responses.
mcp_request() {
  local url="$1" token="$2" session="$3" body="$4" status request_id=''
  local -a headers=(-H "X-Api-Token: ${token}" -H 'Content-Type: application/json'
    -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2025-06-18')
  if [ -n "${session}" ]; then headers+=(-H "Mcp-Session-Id: ${session}"); fi
  rm -f "${root}/mcp.json"
  if jq -e 'has("id")' <<< "${body}" >/dev/null; then
    request_id="$(jq -c '.id' <<< "${body}")"
  fi
  status="$(curl -sS --max-time 15 -D "${root}/mcp.headers" -o "${root}/mcp.response" \
    -w '%{http_code}' "${headers[@]}" -d "${body}" "${url}/mcp")"
  case "${status}" in 200|202) ;; *) fail "MCP returned HTTP ${status}";; esac
  if [ -s "${root}/mcp.response" ]; then
    jq -Rs '. as $raw | try fromjson catch ([$raw | split("\n")[] |
      select(startswith("data:")) | ltrimstr("data:") | fromjson] | last)' \
      "${root}/mcp.response" > "${root}/mcp.json"
  elif [ -n "${request_id}" ]; then
    fail "MCP request ${request_id} returned an empty response"
  elif [ "${status}" != 202 ]; then
    fail 'MCP notification returned an empty non-202 response'
  fi
  if [ -n "${request_id}" ]; then
    jq -e --argjson request_id "${request_id}" '
      if type != "object" then false else
        .jsonrpc == "2.0" and has("id") and .id == $request_id and
        ((has("result") and (has("error") | not)) or
         (has("error") and (has("result") | not)))
      end' "${root}/mcp.json" >/dev/null \
      || fail "MCP request ${request_id} lacked a matching JSON-RPC response"
  fi
}

capture_mcp_payload() {
  jq -e '.result.structuredContent' "${root}/mcp.json" > "$1" \
    || fail 'MCP response lacked structured content'
}

assert_http_mcp_parity() {
  local tool="$1" http_result="$2" mcp_result="$3"
  jq -en --slurpfile http_result "${http_result}" --slurpfile mcp_result "${mcp_result}" \
    '$http_result[0] == $mcp_result[0]' >/dev/null \
    || fail "HTTP/MCP ${tool} payloads differ"
}

initialize_query_mcp() {
  mcp_request "${query_url}" query-smoke-only '' \
    '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"image-smoke","version":"3"}}}'
  query_session=''
  while IFS=: read -r name value; do
    if [ "${name,,}" = mcp-session-id ]; then
      value="${value//$'\r'/}"
      query_session="${value# }"
    fi
  done < "${root}/mcp.headers"
  [ -n "${query_session}" ] || fail 'Query did not issue a session ID'
  mcp_request "${query_url}" query-smoke-only "${query_session}" \
    '{"jsonrpc":"2.0","method":"notifications/initialized"}'
}
call_query_mcp() {
  local tool="$1" arguments="$2"
  mcp_request "${query_url}" query-smoke-only "${query_session}" \
    "$(jq -nc --arg name "${tool}" --argjson arguments "${arguments}" \
      '{jsonrpc:"2.0",id:3,method:"tools/call",params:{name:$name,arguments:$arguments}}')"
  jq -e '.result.isError == false and
    .result.structuredContent == (.result.content[0].text | fromjson)' "${root}/mcp.json" >/dev/null \
    || fail "MCP ${tool} failed or disagreed with text content"
}
prepare_index() {
  local sha="$1" label="$2" request status phase=''
  IFS= read -r request < /proc/sys/kernel/random/uuid
  printf '%s\n' "${request}" > "${root}/index-request-${label}"
  sync -f "${root}/index-request-${label}"
  status="$(curl -sS -o "${root}/index-job.json" -w '%{http_code}' --max-time 10 \
    -H 'X-Api-Token: indexer-smoke-only' -H 'Content-Type: application/json' \
    -d "{\"requestId\":\"${request}\",\"revision\":\"${sha}\"}" \
    "${indexer_url}/index/repositories/fixture/semantic-index")"
  [ "${status}" = 202 ] || fail "semantic admission returned HTTP ${status}"
  for ((attempt=0; attempt<60; attempt++)); do
    status="$(curl -sS -o "${root}/index-job.json" -w '%{http_code}' --max-time 5 \
      -H 'X-Api-Token: indexer-smoke-only' \
      "${indexer_url}/index/repositories/fixture/jobs?requestId=${request}")"
    [ "${status}" = 200 ] || fail 'semantic original-request lookup failed'
    phase="$(jq -r '.phase' "${root}/index-job.json")"
    if [ "${phase}" = COMPLETE ]; then break; fi
    [ "${phase}" != FAILED ] || fail "semantic build failed: $(cat "${root}/index-job.json")"
    sleep 1
  done
  [ "${phase}" = COMPLETE ] || fail 'semantic build did not complete'
  jq -e --arg sha "${sha}" --arg request "${request}" \
    '.operation == "prepare_semantic_index" and .revision == $sha and .requestId == $request
     and .publication.manifest.context.revision == $sha
     and .publication.manifest.indexGeneration == .indexGeneration
     and .publication.requestId == $request and .publication.jobId == .jobId
     and (.publication.manifestDigest | length) == 64' "${root}/index-job.json" >/dev/null \
    || fail 'semantic job receipt identity mismatch'
  built_generation="$(jq -r '.indexGeneration' "${root}/index-job.json")"
}
refresh_source() {
  local sha="$1" label="$2" request status phase=''
  IFS= read -r request < /proc/sys/kernel/random/uuid
  printf '%s\n' "${request}" > "${root}/source-refresh-${label}"
  sync -f "${root}/source-refresh-${label}"
  status="$(curl -sS -o "${root}/refresh-job.json" -w '%{http_code}' --max-time 10 \
    -H 'X-Api-Token: indexer-smoke-only' -H 'Content-Type: application/json' \
    -d "{\"requestId\":\"${request}\"}" "${indexer_url}/index/repositories/fixture/source")"
  [ "${status}" = 202 ] || fail "refresh admission returned HTTP ${status}"
  for ((attempt=0; attempt<60; attempt++)); do
    status="$(curl -sS -o "${root}/refresh-job.json" -w '%{http_code}' --max-time 5 \
      -H 'X-Api-Token: indexer-smoke-only' \
      "${indexer_url}/index/repositories/fixture/jobs?requestId=${request}")"
    [ "${status}" = 200 ] || fail 'refresh original-request lookup failed'
    phase="$(jq -r '.phase' "${root}/refresh-job.json")"
    if [ "${phase}" = COMPLETE ]; then break; fi
    [ "${phase}" != FAILED ] || fail 'refresh failed'
    sleep 1
  done
  [ "${phase}" = COMPLETE ] || fail 'refresh did not complete'
  [ "$(jq -r '.resolvedRevision' "${root}/refresh-job.json")" = "${sha}" ] || fail 'refresh SHA mismatch'
}

initialize_query_mcp
mcp_request "${query_url}" query-smoke-only "${query_session}" \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
jq -e '[.result.tools[].name] | sort ==
  (["list_repositories","get_context","list_files","search_text","read_source","search_code","get_outline","list_entry_points"] | sort)' \
  "${root}/mcp.json" >/dev/null || fail 'Query does not advertise exactly eight tools'
mcp_request "${indexer_url}" indexer-smoke-only '' \
  '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"image-smoke","version":"3"}}}'
mcp_request "${indexer_url}" indexer-smoke-only '' \
  '{"jsonrpc":"2.0","method":"notifications/initialized"}'
mcp_request "${indexer_url}" indexer-smoke-only '' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}'
jq -e '[.result.tools[].name] | sort == (["prepare_source","prepare_semantic_index","get_job"] | sort)' \
  "${root}/mcp.json" >/dev/null || fail 'Indexer does not advertise exactly three tools'
status="$(curl -sS -o "${root}/missing-index.json" -w '%{http_code}' --max-time 10 \
  -H 'X-Api-Token: query-smoke-only' -H 'Content-Type: application/json' \
  -d "{\"context\":{\"repositoryId\":\"fixture\",\"revision\":\"${revision}\"},\"query\":\"Example\",\"match\":\"exact\"}" \
  "${query_url}/api/v1/search-code")"
[ "${status}" = 409 ] && jq -e '.code == "INDEX_NOT_PREPARED"' "${root}/missing-index.json" >/dev/null \
  || fail 'missing index did not remain index-local'
context_a="{\"repositoryId\":\"fixture\",\"revision\":\"${revision}\"}"
call_query_mcp list_repositories '{}'
call_query_mcp get_context "{\"repositoryId\":\"fixture\",\"revision\":\"${revision}\"}"
call_query_mcp list_files "{\"context\":${context_a},\"directory\":\"\"}"
call_query_mcp search_text "{\"context\":${context_a},\"query\":\"source-image-needle\"}"
capture_mcp_payload "${root}/mcp-search-text.json"
call_query_mcp read_source "{\"context\":${context_a},\"path\":\"Example.java\",\"view\":\"raw\"}"
capture_mcp_payload "${root}/mcp-read-source.json"
jq -e '[.result.structuredContent.segments[] | select(.kind == "SOURCE") | .content] | join("") | contains("source-image-needle")' \
  "${root}/mcp.json" >/dev/null || fail 'missing index broke source evidence'
prepare_index "${revision}" a
generation_a="${built_generation}"
post_query search-code "{\"context\":${context_a},\"query\":\"Example\",\"match\":\"exact\"}"
jq -e --arg sha "${revision}" --arg gen "${generation_a}" \
  '.context.revision == $sha and .indexGeneration == $gen and .analysisLevel == "SYNTAX"
   and .coverage.complete == true and any(.items[]; .name == "Example" and .path == "Example.java")' \
  "${root}/query.json" >/dev/null || fail 'real SQLite declaration search failed'
cp "${root}/query.json" "${root}/http-search-code.json"
name_start="$(jq -r '.items[] | select(.name == "Example") | .nameRange.startByte' "${root}/query.json")"
name_end="$(jq -r '.items[] | select(.name == "Example") | .nameRange.endByte' "${root}/query.json")"
post_query source "{\"context\":${context_a},\"path\":\"Example.java\",\"view\":\"raw\"}"
name_slice="$(jq -j '[.segments[] | select(.kind == "SOURCE") | .content] | join("")' "${root}/query.json" | dd bs=1 skip="${name_start}" count="$((name_end-name_start))" 2>/dev/null)"
[ "${name_slice}" = Example ] || fail 'index raw-byte name range does not cite returned source'
call_query_mcp search_code "{\"context\":${context_a},\"query\":\"Example\",\"match\":\"exact\"}"
jq -e --arg gen "${generation_a}" '.result.structuredContent.indexGeneration == $gen
  and any(.result.structuredContent.items[]; .name == "Example")' "${root}/mcp.json" >/dev/null \
  || fail 'MCP declaration search did not read the built generation'
capture_mcp_payload "${root}/mcp-search-code.json"
assert_http_mcp_parity 'search_code' "${root}/http-search-code.json" "${root}/mcp-search-code.json"
post_query outline "{\"context\":${context_a},\"path\":\"Example.java\"}"
jq -e 'any(.items[]; .name == "marker" and .kind == "FIELD")' "${root}/query.json" >/dev/null \
  || fail 'real SQLite outline failed'
cp "${root}/query.json" "${root}/http-outline.json"
post_query outline "{\"context\":${context_a},\"path\":\"Example.java\",\"limit\":1}"
jq -e '.page.hasMore == true and (.page.nextCursor | type) == "string"' \
  "${root}/query.json" >/dev/null || fail 'outline did not produce a continuation'
outline_cursor_a="$(jq -r '.page.nextCursor' "${root}/query.json")"
call_query_mcp get_outline "{\"context\":${context_a},\"path\":\"Example.java\"}"
capture_mcp_payload "${root}/mcp-outline.json"
assert_http_mcp_parity 'get_outline' "${root}/http-outline.json" "${root}/mcp-outline.json"
post_query entry-points "{\"context\":${context_a}}"
jq -e '.analysisLevel == "SYNTAX" and any(.items[]; .kind == "MAIN"
  and .declaration.name == "main" and .evidence.kind == "MAIN_SIGNATURE")' "${root}/query.json" >/dev/null \
  || fail 'real syntax entry candidate missing'
cp "${root}/query.json" "${root}/http-entry-points.json"
call_query_mcp list_entry_points "{\"context\":${context_a}}"
capture_mcp_payload "${root}/mcp-entry-points.json"
assert_http_mcp_parity 'list_entry_points' "${root}/http-entry-points.json" "${root}/mcp-entry-points.json"
post_query context '{"repositoryId":"fixture"}'
jq -e --arg sha "${revision}" '.sourceStatus == "READY" and .context.repositoryId == "fixture" and .context.revision == $sha' "${root}/query.json" >/dev/null || fail 'Query did not admit published SHA'
post_query search-text "{\"context\":{\"repositoryId\":\"fixture\",\"revision\":\"${revision}\"},\"query\":\"source-image-needle\"}"
jq -e '.matches | any(.path == "Example.java" and .line == 2)' "${root}/query.json" >/dev/null || fail 'real ripgrep search did not find fixture source'
assert_http_mcp_parity 'search_text' "${root}/query.json" "${root}/mcp-search-text.json"
post_query source "{\"context\":{\"repositoryId\":\"fixture\",\"revision\":\"${revision}\"},\"path\":\"Example.java\",\"view\":\"raw\"}"
jq -e '[.segments[] | select(.kind == "SOURCE") | .content] | join("") | contains("source-image-needle")' "${root}/query.json" >/dev/null || fail 'Query did not read published source'
assert_http_mcp_parity 'read_source' "${root}/query.json" "${root}/mcp-read-source.json"
status="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
  -H 'X-Api-Token: query-smoke-only' -H 'Content-Type: application/json' \
  -d '{"requestId":"must-not-prepare"}' "${query_url}/index/repositories/fixture/source")"
[ "${status}" = 404 ] || fail "Query exposes Indexer mutation route (HTTP ${status})"


# Probe only a throwaway filename. A root process or a shared UID would hide this bug.
if docker exec "${query_container}" sh -c 'touch /published-evidence/query-must-not-write' >/dev/null 2>&1; then
  fail 'Query can mutate published bind mount'
fi
if docker exec "${query_container}" sh -c 'touch /index-evidence/query-must-not-write' >/dev/null 2>&1; then
  fail 'Query can mutate index bind mount'
fi
docker exec "${query_container}" sh -ceu '
  test "$(id -u)" = 10002
  test ! -e /data/source-admin
  test ! -e /fixture
  test ! -e /run/secrets/indexer-admin-token
  test -z "${GIT_TOKEN:-}${GIT_USERNAME:-}${SEMANTIC_INDEXER_ADMIN_TOKEN:-}"
  test -w /tmp/sqlite-native
  test "$(stat -c %a /tmp/sqlite-native)" = 700
  ! command -v git >/dev/null
'
if docker exec -e SEMANTIC_QUERY_RG_EXECUTABLE=/missing-nondefault-rg "${query_container}" \
    java -jar /app/semantic-query.jar --server.port=0 >"${root}/invalid-rg.log" 2>&1; then
  fail 'Query ignored the configured nondefault ripgrep executable'
fi
docker exec "${indexer_container}" sh -ceu 'test "$(id -u)" = 10001; test -d /data/source-admin; test -d /data/source-published; test -d /data/index-published; test -w /tmp/sqlite-native; test "$(stat -c %a /tmp/sqlite-native)" = 700'
# This peer proves OS/UID/read-only-mount locking, not an in-flight HTTP request.
# Native facade tests separately exercise full-operation lease ownership.
printf 'package smoke;\nclass Example { String marker = "source-image-needle-B"; public static void main(String[] args) {} }\n' > "${root}/fixture/Example.java"
git -C "${root}/fixture" add Example.java
git -C "${root}/fixture" commit -qm 'replacement source fixture B'
revision_b="$(git -C "${root}/fixture" rev-parse HEAD)"
IFS= read -r request_b < /proc/sys/kernel/random/uuid
printf '%s\n' "${request_b}" > "${root}/request-id-b"
sync -f "${root}/request-id-b"
status="$(curl -sS -o "${root}/job-b.json" -w '%{http_code}' --max-time 10 \
  -H 'X-Api-Token: indexer-smoke-only' -H 'Content-Type: application/json' \
  -d "{\"requestId\":\"${request_b}\"}" "${indexer_url}/index/repositories/fixture/source")"
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
prepare_index "${revision_b}" b
generation_b="${built_generation}"
# Protect A1 through the rebuild and pinned reads, then the source-GC restart.
# Only the explicit peer release below permits expiry; no production grace period.
docker exec "${query_container}" mkdir -p /tmp/source-lock-peer
docker cp "${root}/peer-classes/." "${query_container}:/tmp/source-lock-peer"
docker exec -d --user 10002:10002 "${query_container}" java -cp /tmp/source-lock-peer \
  com.java.semantic.query.source.SourceReadLockPeer shared /published-evidence/fixture/read.lock \
  /tmp/source-gc-peer-ready /tmp/source-gc-peer-release 900000
peer_ready=0
for ((attempt=0; attempt<10; attempt++)); do
  if docker exec "${query_container}" test -f /tmp/source-gc-peer-ready; then peer_ready=1; break; fi
  sleep 1
done
[ "${peer_ready}" = 1 ] || fail 'reader UID peer could not acquire shared lock on read-only mount'
IFS= read -r rejected_request < /proc/sys/kernel/random/uuid
printf '%s\n' "${rejected_request}" > "${root}/index-request-a-not-current"
sync -f "${root}/index-request-a-not-current"
status="$(curl -sS -o "${root}/noncurrent-index.json" -w '%{http_code}' --max-time 10 \
  -H 'X-Api-Token: indexer-smoke-only' -H 'Content-Type: application/json' \
  -d "{\"requestId\":\"${rejected_request}\",\"revision\":\"${revision}\"}" \
  "${indexer_url}/index/repositories/fixture/semantic-index")"
[ "${status}" = 409 ] && jq -e '.code == "INDEX_NOT_CURRENT"' "${root}/noncurrent-index.json" >/dev/null \
  || fail 'historical A index admission did not reject without promotion'
# A new index requires a real default-branch refresh, not an explicit-SHA promotion.
git -C "${root}/fixture" update-ref refs/heads/main "${revision}"
refresh_source "${revision}" a-before-rebuild
prepare_index "${revision}" a-rebuild
[ "${built_generation}" != "${generation_a}" ] || fail 'A rebuild reused its sealed generation'
generation_a_rebuilt="${built_generation}"
git -C "${root}/fixture" update-ref refs/heads/main "${revision_b}"
refresh_source "${revision_b}" b-after-rebuild
post_query search-code "{\"context\":${context_a},\"query\":\"Example\",\"match\":\"exact\"}"
jq -e --arg gen "${generation_a_rebuilt}" \
  '.indexGeneration == $gen and any(.items[]; .name == "Example")' "${root}/query.json" >/dev/null \
  || fail 'unpinned A search did not select its rebuilt generation'
post_query context '{"repositoryId":"fixture"}'
jq -e --arg sha "${revision_b}" '.context.revision == $sha' "${root}/query.json" >/dev/null \
  || fail 'refresh back to B did not restore source current'
post_query search-code "{\"context\":${context_a},\"query\":\"Example\",\"match\":\"exact\",\"indexGeneration\":\"${generation_a}\"}"
jq -e --arg gen "${generation_a}" '.indexGeneration == $gen and any(.items[]; .name == "Example")' \
  "${root}/query.json" >/dev/null || fail 'prior sealed A generation became unreadable after rebuild'
post_query outline "$(jq -nc --argjson context "${context_a}" --arg cursor "${outline_cursor_a}" \
  '{context:$context,path:"Example.java",limit:1,cursor:$cursor}')"
jq -e --arg gen "${generation_a}" '.indexGeneration == $gen and (.items | length) == 1' \
  "${root}/query.json" >/dev/null || fail 'A cursor silently switched generations after rebuild'
post_query search-code "{\"context\":{\"repositoryId\":\"fixture\",\"revision\":\"${revision_b}\"},\"query\":\"Example\",\"match\":\"exact\"}"
jq -e --arg gen "${generation_b}" '.indexGeneration == $gen' "${root}/query.json" >/dev/null \
  || fail 'B index selection changed during A rebuild'
post_query source "{\"context\":{\"repositoryId\":\"fixture\",\"revision\":\"${revision}\"},\"path\":\"Example.java\"}"
jq -e '[.segments[] | select(.kind == "SOURCE") | .content] | join("") | contains("source-image-needle-B") | not' "${root}/query.json" >/dev/null || fail 'A changed after B publication'
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
docker start "${indexer_container}" >/dev/null
# An omitted host port is allocated at start; a restart can change the published binding.
previous_indexer_url="${indexer_url}"
indexer_url="http://$(docker port "${indexer_container}" 8080/tcp)"
printf 'Source image restart binding: previous=%s current=%s\n' "${previous_indexer_url}" "${indexer_url}"
wait_http "${indexer_url}/index/repositories/fixture/jobs?requestId=${request_b}" "${indexer_container}"
busy=0
for ((attempt=0; attempt<30; attempt++)); do
  docker logs "${indexer_container}" > "${root}/gc.log" 2>&1
  if grep -Eq 'event=source_gc_skip .*repositoryId=fixture reason=READ_IN_PROGRESS' "${root}/gc.log"; then busy=1; break; fi
  sleep 1
done
[ "${busy}" = 1 ] || fail 'startup GC did not report read-in-progress protection'
post_query source "{\"context\":{\"repositoryId\":\"fixture\",\"revision\":\"${revision}\"},\"path\":\"Example.java\"}"
jq -e '[.segments[] | select(.kind == "SOURCE") | .content] | join("") | contains("source-image-needle")' "${root}/query.json" >/dev/null || fail 'busy A became unreadable'
docker exec --user 10002:10002 "${query_container}" touch /tmp/source-gc-peer-release
deleted=0
for ((attempt=0; attempt<30; attempt++)); do
  status="$(curl -sS -o "${root}/expired.json" -w '%{http_code}' --max-time 5 \
    -H 'X-Api-Token: query-smoke-only' -H 'Content-Type: application/json' \
    -d "{\"repositoryId\":\"fixture\",\"revision\":\"${revision}\"}" "${query_url}/api/v1/context")"
  if [ "${status}" = 404 ] && jq -e '.code == "REVISION_NOT_PREPARED"' "${root}/expired.json" >/dev/null \
      && docker exec "${indexer_container}" test ! -e "/data/source-published/fixture/revisions/${revision}" \
      && docker exec "${indexer_container}" test ! -e "/data/index-published/fixture/revisions/${revision}/${generation_a}" \
      && docker exec "${indexer_container}" test ! -e "/data/index-published/fixture/revisions/${revision}/${generation_a_rebuilt}" \
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
jq -e '[.segments[] | select(.kind == "SOURCE") | .content] | join("") | contains("source-image-needle-B")' "${root}/query.json" >/dev/null || fail 'retained B read failed'
printf 'Source image evidence: repositoryId=fixture reclaimedRevision=%s retainedRevision=%s queryUid=10002 publishedMount=read-only busySkip=READ_IN_PROGRESS expiredContext=REVISION_NOT_PREPARED retainedPath=Example.java retainedMatchLine=2\n' \
  "${revision}" "${revision_b}"
grep -E "event=source_gc_deleted .*repositoryId=fixture revision=${revision} .*result=deleted" "${root}/gc.log"
# Fresh process: SQLite extraction is intentionally impossible. Source tools must
# work before and after the index-local failure, on the same read-only evidence.
curl -sS --max-time 10 -X DELETE -o /dev/null \
  -H 'X-Api-Token: query-smoke-only' -H "Mcp-Session-Id: ${query_session}" \
  -H 'MCP-Protocol-Version: 2025-06-18' "${query_url}/mcp"
docker rm -f "${query_container}" >/dev/null
query_container="$(docker run -d -p 127.0.0.1::8080 \
  --mount "type=bind,src=${root}/storage/source-published,dst=/published-evidence,readonly" \
  --mount "type=bind,src=${root}/storage/index-published,dst=/index-evidence,readonly" \
  -e SEMANTIC_QUERY_API_TOKEN=query-smoke-only \
  -e SEMANTIC_SOURCE_PUBLISHED_ROOT=/published-evidence \
  -e SEMANTIC_INDEX_PUBLISHED_ROOT=/index-evidence \
  -e 'SPRING_APPLICATION_JSON={"semantic":{"query":{"source":{"allowed-repositories":["fixture"]}}}}' \
  --entrypoint sh "${query_image}" -ceu '
    native_dir="$(mktemp -d /tmp/sqlite-native/image-smoke-disabled.XXXXXX)"
    chmod 0700 "${native_dir}"
    native_lib_name="sqlitejdbc-image-smoke-absent-${native_dir##*.}"
    export JAVA_TOOL_OPTIONS="-Dorg.sqlite.tmpdir=/proc/sqlite-smoke-unavailable -Dorg.sqlite.lib.name=${native_lib_name} -Dorg.sqlite.lib.path=${native_dir} -Djava.library.path=${native_dir}"
    exec java -jar /app/semantic-query.jar
  ')"
query_url="http://$(docker port "${query_container}" 8080/tcp)"
wait_http "${query_url}/api/v1/repositories" "${query_container}"
initialize_query_mcp
context_b="{\"repositoryId\":\"fixture\",\"revision\":\"${revision_b}\"}"
assert_source_tools() {
  call_query_mcp list_repositories '{}'
  call_query_mcp get_context "{\"repositoryId\":\"fixture\",\"revision\":\"${revision_b}\"}"
  call_query_mcp list_files "{\"context\":${context_b},\"directory\":\"\"}"
  call_query_mcp search_text "{\"context\":${context_b},\"query\":\"source-image-needle-B\"}"
  jq -e 'any(.result.structuredContent.matches[]; .path == "Example.java" and .line == 2)' \
    "${root}/mcp.json" >/dev/null || fail 'native failure broke source search'
  call_query_mcp read_source "{\"context\":${context_b},\"path\":\"Example.java\"}"
  jq -e '[.result.structuredContent.segments[] | select(.kind == "SOURCE") | .content] | join("") | contains("source-image-needle-B")' \
    "${root}/mcp.json" >/dev/null || fail 'native failure broke source bytes'
}
assert_source_tools
for ((attempt=0; attempt<2; attempt++)); do
  status="$(curl -sS -o "${root}/native-failure.json" -w '%{http_code}' --max-time 10 \
    -H 'X-Api-Token: query-smoke-only' -H 'Content-Type: application/json' \
    -d "{\"context\":${context_b},\"query\":\"Example\",\"match\":\"exact\"}" \
    "${query_url}/api/v1/search-code")"
  [ "${status}" = 503 ] && jq -e '.code == "INDEX_UNAVAILABLE"' "${root}/native-failure.json" >/dev/null \
    || fail 'native extraction failure escaped index-local HTTP error mapping'
  mcp_request "${query_url}" query-smoke-only "${query_session}" \
    "$(jq -nc --argjson context "${context_b}" '{jsonrpc:"2.0",id:4,method:"tools/call",
      params:{name:"search_code",arguments:{context:$context,query:"Example",match:"exact"}}}')"
  jq -e '.result.isError == true and .result.structuredContent.code == "INDEX_UNAVAILABLE"
    and .result.structuredContent == (.result.content[0].text | fromjson)' \
    "${root}/mcp.json" >/dev/null || fail 'native extraction MCP/HTTP error parity failed'
done
assert_source_tools
post_query source "{\"context\":${context_b},\"path\":\"Example.java\"}"
jq -e '[.segments[] | select(.kind == "SOURCE") | .content] | join("") | contains("source-image-needle-B")' "${root}/query.json" >/dev/null \
  || fail 'native extraction failure broke HTTP source'
curl -sS --max-time 10 -X DELETE -o /dev/null \
  -H 'X-Api-Token: query-smoke-only' -H "Mcp-Session-Id: ${query_session}" \
  -H 'MCP-Protocol-Version: 2025-06-18' "${query_url}/mcp"
successful=1
echo 'Source/index images: real Git A/B refresh, current-only SQLite rebuild/read and segmented raw-byte citation, eight/three MCP tools, retained historical generations, source-independent native failure, read-only UID locking, GC and isolation passed'
