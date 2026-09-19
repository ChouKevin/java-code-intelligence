#!/usr/bin/env bash
set -euo pipefail

image_name="${1:?usage: smoke-jdtls-image.sh IMAGE}"

docker run --rm --entrypoint sh "${image_name}" -ceu '
  test -d /opt/jdtls/plugins
  test -d /opt/jdtls/config_linux
  test "$(find /opt/jdtls/plugins -name "org.eclipse.equinox.launcher_*.jar" | wc -l)" -eq 1
  test -d /data/repos
  test -f /opt/jdtls/lombok.jar
  echo "01f7b1a015e33e2b62d5f5f37053306357ab1415fd181fcba7794f5d198c1126  /opt/jdtls/lombok.jar" | sha256sum --check
  test -d /data/jdtls
  test "$(getent passwd 10001 | cut -d: -f1)" = analysis
  test -x /usr/bin/setpriv
'

docker run --rm --env SYNTHETIC_PARENT_SECRET=only-for-image-smoke --entrypoint sh "${image_name}" -ceu '
  env -i HOME=/home/analysis USER=analysis \
    /usr/bin/setpriv --reuid=10001 --regid=10001 --clear-groups --no-new-privs --bounding-set=-all \
    sh -ceu '"'"'
      test "$(id -u)" = 10001
      test "$(id -g)" = 10001
      test "$(awk "/^CapEff:/ { print \$2 }" /proc/self/status)" = 0000000000000000
      test -z "${SYNTHETIC_PARENT_SECRET:-}"
    '"'"'
'

docker run --rm --env SYNTHETIC_PARENT_SECRET=only-for-image-smoke --entrypoint sh "${image_name}" -ceu '
  workspace=/data/jdtls/smoke
  mkdir -p "$workspace"
  chown 10001:10001 "$workspace"
  cp -a /opt/jdtls/config_linux "$workspace/configuration"
  chown -R 10001:10001 "$workspace"
  launcher=$(find /opt/jdtls/plugins -name "org.eclipse.equinox.launcher_*.jar" -print -quit)
  initialize="{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"processId\":null,\"rootUri\":\"file:///tmp\",\"capabilities\":{}}}"
  initialized="{\"jsonrpc\":\"2.0\",\"method\":\"initialized\",\"params\":{}}"
  shutdown="{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"shutdown\",\"params\":null}"
  exit_request="{\"jsonrpc\":\"2.0\",\"method\":\"exit\",\"params\":null}"
  send() { printf "Content-Length: %s\\r\\n\\r\\n%s" "${#1}" "$1"; }
  response=$( (
    send "$initialize"
    send "$initialized"
    send "$shutdown"
    send "$exit_request"
  ) | env -i HOME=/home/analysis USER=analysis \
    /usr/bin/setpriv --reuid=10001 --regid=10001 --clear-groups --no-new-privs --bounding-set=-all \
    /opt/java/openjdk/bin/java \
      -Declipse.application=org.eclipse.jdt.ls.core.id1 \
      -Dosgi.bundles.defaultStartLevel=4 \
      -Declipse.product=org.eclipse.jdt.ls.core.product \
      -Dlog.level=ALL \
      -javaagent:/opt/jdtls/lombok.jar \
      --add-modules=ALL-SYSTEM \
      --add-opens java.base/java.util=ALL-UNNAMED \
      --add-opens java.base/java.lang=ALL-UNNAMED \
      -jar "$launcher" -configuration "$workspace/configuration" -data "$workspace")
  printf "%s" "$response" | grep -q "\"id\":1"
  printf "%s" "$response" | grep -q "\"id\":2"
'
