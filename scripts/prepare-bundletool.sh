#!/usr/bin/env bash

set -euo pipefail

BUNDLETOOL_VERSION="1.18.3"
BUNDLETOOL_SHA256="a099cfa1543f55593bc2ed16a70a7c67fe54b1747bb7301f37fdfd6d91028e29"
BUNDLETOOL_CACHE_DIR="${BUNDLETOOL_CACHE_DIR:-${PWD}/app/build/tools/bundletool}"
BUNDLETOOL_JAR="${BUNDLETOOL_CACHE_DIR}/bundletool-all-${BUNDLETOOL_VERSION}.jar"

verify_checksum() {
    local jar_path="$1"

    if command -v sha256sum >/dev/null 2>&1; then
        [[ "$(sha256sum "${jar_path}" | awk '{ print $1 }')" == "${BUNDLETOOL_SHA256}" ]]
        return
    fi

    if command -v shasum >/dev/null 2>&1; then
        [[ "$(shasum -a 256 "${jar_path}" | awk '{ print $1 }')" == "${BUNDLETOOL_SHA256}" ]]
        return
    fi

    printf '%s\n' "sha256sum or shasum is required to verify bundletool" >&2
    exit 1
}

mkdir -p "${BUNDLETOOL_CACHE_DIR}"

if [[ ! -f "${BUNDLETOOL_JAR}" ]] || ! verify_checksum "${BUNDLETOOL_JAR}"; then
    command -v curl >/dev/null 2>&1 || {
        printf '%s\n' "curl is required to download bundletool" >&2
        exit 1
    }

    download_path="${BUNDLETOOL_JAR}.download"
    curl --fail --location --silent --show-error \
        "https://github.com/google/bundletool/releases/download/${BUNDLETOOL_VERSION}/bundletool-all-${BUNDLETOOL_VERSION}.jar" \
        --output "${download_path}"
    verify_checksum "${download_path}"
    mv "${download_path}" "${BUNDLETOOL_JAR}"
fi

verify_checksum "${BUNDLETOOL_JAR}"
printf '%s\n' "${BUNDLETOOL_JAR}"
