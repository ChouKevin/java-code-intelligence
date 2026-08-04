#!/bin/sh
set -eu

die() {
    printf '%s\n' "$1" >&2
    exit 1
}

SCRIPT_DIR=$(CDPATH= cd "$(dirname "$0")" && pwd)
RELEASE_FILE="${SCRIPT_DIR}/jdtls-release.env"

[ -r "${RELEASE_FILE}" ] || die "JDT LS release metadata is required"
# shellcheck source=jdtls-release.env
. "${RELEASE_FILE}"

[ "$#" -eq 1 ] || die "usage: install-jdtls.sh <target-dir>"
TARGET=$1

[ -n "${JDTLS_VERSION:-}" ] || die "JDTLS_VERSION is required"
[ -n "${JDTLS_ARCHIVE:-}" ] || die "JDTLS_ARCHIVE is required"
[ -n "${JDTLS_URL:-}" ] || die "JDTLS_URL is required"
[ -n "${JDTLS_SHA256:-}" ] || die "JDTLS_SHA256 is required"
[ -n "${TARGET}" ] || die "target directory is required"

case "${TARGET}" in
    /|.|./|..|../|-*) die "target directory is invalid" ;;
esac

installation_is_valid() {
    installation=$1
    [ -d "${installation}/plugins" ] || return 1
    [ -d "${installation}/config_linux" ] || return 1
    set -- "${installation}"/plugins/org.eclipse.equinox.launcher_*.jar
    [ "$#" -eq 1 ] && [ -f "$1" ]
}

if [ -f "${TARGET}/.installed-${JDTLS_VERSION}" ] && installation_is_valid "${TARGET}"; then
    printf '%s\n' "jdtls ${JDTLS_VERSION} already installed at ${TARGET}"
    exit 0
fi

TMP=$(mktemp -d) || die "unable to create temporary directory"
STAGING=
trap 'rm -rf "${TMP}" ${STAGING:+"${STAGING}"}' EXIT HUP INT TERM

ARCHIVE_PATH="${TMP}/${JDTLS_ARCHIVE}"

printf '%s\n' "downloading ${JDTLS_ARCHIVE}"
curl -fsSL "${JDTLS_URL}" -o "${ARCHIVE_PATH}"
printf '%s  %s\n' "${JDTLS_SHA256}" "${ARCHIVE_PATH}" | sha256sum -c -

tar -xzf "${ARCHIVE_PATH}" -C "${TMP}"
installation_is_valid "${TMP}" || die "JDT LS archive has an invalid layout"

TARGET_PARENT=$(dirname "${TARGET}")
TARGET_NAME=$(basename "${TARGET}")
mkdir -p "${TARGET_PARENT}"
STAGING=$(mktemp -d "${TARGET_PARENT}/.${TARGET_NAME}.install.XXXXXX") \
    || die "unable to create destination staging directory"
cp -R "${TMP}/." "${STAGING}"
touch "${STAGING}/.installed-${JDTLS_VERSION}"
installation_is_valid "${STAGING}" || die "installed JDT LS layout is invalid"

rm -rf "${TARGET}"
mv "${STAGING}" "${TARGET}"
STAGING=

printf '%s\n' "jdtls ${JDTLS_VERSION} installed at ${TARGET}"
