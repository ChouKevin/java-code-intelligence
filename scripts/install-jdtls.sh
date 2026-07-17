#!/usr/bin/env bash
set -euo pipefail

TARGET="${1:?usage: install-jdtls.sh <target-dir>}"

VERSION="1.50.0"
ARCHIVE="jdt-language-server-1.50.0-202509041425.tar.gz"
BASE_URL="https://download.eclipse.org/jdtls/milestones/${VERSION}"
SHA256="3292c5c33888f95ab0ff718e777ee94ff5496b8635a23a8844b876ee090ebdea"

installation_is_valid() {
  [ -d "${TARGET}/plugins" ] || return 1
  [ -d "${TARGET}/config_linux" ] || return 1
  local launchers
  launchers="$(find "${TARGET}/plugins" -name 'org.eclipse.equinox.launcher_*.jar' | wc -l)"
  [ "${launchers}" -eq 1 ]
}

if [ -f "${TARGET}/.installed-${VERSION}" ] && installation_is_valid; then
  echo "jdtls ${VERSION} already installed at ${TARGET}"
  exit 0
fi

TMP="$(mktemp -d)"
trap 'rm -rf "${TMP}"' EXIT

echo "downloading ${ARCHIVE}"
curl -fsSL "${BASE_URL}/${ARCHIVE}" -o "${TMP}/${ARCHIVE}"

echo "${SHA256}  ${TMP}/${ARCHIVE}" | sha256sum -c -

rm -rf "${TARGET}"
mkdir -p "${TARGET}"
tar -xzf "${TMP}/${ARCHIVE}" -C "${TARGET}"

LAUNCHERS="$(find "${TARGET}/plugins" -name 'org.eclipse.equinox.launcher_*.jar' | wc -l)"
if [ "${LAUNCHERS}" -ne 1 ]; then
  echo "expected exactly 1 equinox launcher, found ${LAUNCHERS}" >&2
  exit 1
fi
[ -d "${TARGET}/config_linux" ] || { echo "config_linux missing" >&2; exit 1; }

touch "${TARGET}/.installed-${VERSION}"
echo "jdtls ${VERSION} installed at ${TARGET}"
