#!/usr/bin/env bash
set -euo pipefail

image_name="${1:?usage: smoke-jdtls-image.sh IMAGE}"

docker run --rm --entrypoint sh "${image_name}" -ceu '
  test -d /opt/jdtls/plugins
  test -d /opt/jdtls/config_linux
  test "$(find /opt/jdtls/plugins -name "org.eclipse.equinox.launcher_*.jar" | wc -l)" -eq 1
  test -d /data/repos
  test -d /data/jdtls
'
