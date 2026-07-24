#!/usr/bin/env bash

set -euo pipefail

readonly PAGE_SIZE=16384
readonly PAGE_SIZE_HEX=0x4000

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

find_readelf() {
    if [[ -n "${LLVM_READELF:-}" && -x "${LLVM_READELF}" ]]; then
        printf '%s\n' "${LLVM_READELF}"
        return
    fi

    local ndk_dir
    local readelf
    for ndk_dir in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}" "${NDK_HOME:-}"; do
        [[ -n "${ndk_dir}" && -d "${ndk_dir}/toolchains/llvm/prebuilt" ]] || continue
        readelf="$(
            find "${ndk_dir}/toolchains/llvm/prebuilt" \
                \( -type f -o -type l \) \
                -path '*/bin/llvm-readelf' \
                -perm -111 \
                -print \
                -quit
        )"
        if [[ -n "${readelf}" ]]; then
            printf '%s\n' "${readelf}"
            return
        fi
    done

    local sdk_dir
    for sdk_dir in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
        [[ -n "${sdk_dir}" && -d "${sdk_dir}/ndk" ]] || continue
        readelf="$(
            find "${sdk_dir}/ndk" \
                \( -type f -o -type l \) \
                -path '*/toolchains/llvm/prebuilt/*/bin/llvm-readelf' \
                -perm -111 \
                -print \
                | sort -V \
                | tail -n 1
        )"
        if [[ -n "${readelf}" ]]; then
            printf '%s\n' "${readelf}"
            return
        fi
    done

    if command -v llvm-readelf >/dev/null 2>&1; then
        command -v llvm-readelf
        return
    fi

    if command -v readelf >/dev/null 2>&1; then
        command -v readelf
        return
    fi

    fail "llvm-readelf or readelf is required"
}

find_zipalign() {
    if [[ -n "${ZIPALIGN:-}" && -x "${ZIPALIGN}" ]]; then
        printf '%s\n' "${ZIPALIGN}"
        return
    fi

    if command -v zipalign >/dev/null 2>&1; then
        command -v zipalign
        return
    fi

    local sdk_dir
    local zipalign
    for sdk_dir in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}"; do
        [[ -n "${sdk_dir}" && -d "${sdk_dir}/build-tools" ]] || continue
        zipalign="$(
            find "${sdk_dir}/build-tools" \
                -mindepth 2 \
                -maxdepth 2 \
                -type f \
                -name zipalign \
                -perm -111 \
                -print \
                | sort -V \
                | tail -n 1
        )"
        if [[ -n "${zipalign}" ]]; then
            printf '%s\n' "${zipalign}"
            return
        fi
    done

    fail "zipalign is required to validate APK native-library alignment"
}

configure_bundletool() {
    if [[ -n "${BUNDLETOOL:-}" && -x "${BUNDLETOOL}" ]]; then
        BUNDLETOOL_COMMAND=("${BUNDLETOOL}")
        return
    fi

    if [[ -n "${BUNDLETOOL_JAR:-}" && -f "${BUNDLETOOL_JAR}" ]]; then
        command -v java >/dev/null 2>&1 || fail "java is required to run BUNDLETOOL_JAR"
        BUNDLETOOL_COMMAND=("$(command -v java)" -jar "${BUNDLETOOL_JAR}")
        return
    fi

    if command -v bundletool >/dev/null 2>&1; then
        BUNDLETOOL_COMMAND=("$(command -v bundletool)")
        return
    fi

    fail "bundletool or BUNDLETOOL_JAR is required to validate AAB page alignment"
}

abi_for_library() {
    local library_path="$1"
    local previous=""
    local component

    IFS='/' read -r -a components <<< "${library_path}"
    for component in "${components[@]}"; do
        if [[ "${previous}" == "lib" ]]; then
            printf '%s\n' "${component}"
            return
        fi
        previous="${component}"
    done

    printf '%s\n' "unknown"
}

validate_elf() {
    local artifact="$1"
    local extracted_root="$2"
    local library="$3"
    local entry="${library#"${extracted_root}/"}"
    local abi
    local headers
    local line
    local type
    local alignment
    local virtual_address
    local memory_size
    local relro_end
    local load_count=0
    local relro_count=0

    abi="$(abi_for_library "${entry}")"
    if ! headers="$("${READELF}" -W -l "${library}" 2>&1)"; then
        if ! headers="$("${READELF}" -l "${library}" 2>&1)"; then
            fail "artifact='${artifact}' abi='${abi}' library='${entry}' readelf failed: ${headers}"
        fi
    fi

    while IFS= read -r line; do
        read -r -a fields <<< "${line}"
        [[ "${#fields[@]}" -gt 0 ]] || continue
        type="${fields[0]}"

        case "${type}" in
            LOAD)
                load_count=$((load_count + 1))
                alignment="${fields[${#fields[@]} - 1]}"
                [[ "${alignment}" =~ ^(0x)?[0-9a-fA-F]+$ ]] \
                    || fail "artifact='${artifact}' abi='${abi}' library='${entry}' invalid PT_LOAD alignment='${alignment}'"
                if (( alignment < PAGE_SIZE )); then
                    fail "artifact='${artifact}' abi='${abi}' library='${entry}' PT_LOAD alignment='${alignment}' required='${PAGE_SIZE_HEX}'"
                fi
                ;;
            GNU_RELRO)
                relro_count=$((relro_count + 1))
                virtual_address="${fields[2]}"
                memory_size="${fields[5]}"
                [[ "${virtual_address}" =~ ^0x[0-9a-fA-F]+$ && "${memory_size}" =~ ^0x[0-9a-fA-F]+$ ]] \
                    || fail "artifact='${artifact}' abi='${abi}' library='${entry}' invalid PT_GNU_RELRO virtual_address='${virtual_address}' memory_size='${memory_size}'"
                relro_end=$((virtual_address + memory_size))
                if (( relro_end % PAGE_SIZE != 0 )); then
                    printf -v relro_end_hex '0x%x' "${relro_end}"
                    fail "artifact='${artifact}' abi='${abi}' library='${entry}' PT_GNU_RELRO end='${relro_end_hex}' virtual_address='${virtual_address}' memory_size='${memory_size}' required_multiple='${PAGE_SIZE_HEX}'"
                fi
                ;;
        esac
    done <<< "${headers}"

    (( load_count > 0 )) \
        || fail "artifact='${artifact}' abi='${abi}' library='${entry}' has no PT_LOAD segment"
    (( relro_count > 0 )) \
        || fail "artifact='${artifact}' abi='${abi}' library='${entry}' has no PT_GNU_RELRO segment"

    printf "PASS: artifact='%s' abi='%s' library='%s' PT_LOAD>='%s' PT_GNU_RELRO-end='%s'-aligned\n" \
        "${artifact}" \
        "${abi}" \
        "${entry}" \
        "${PAGE_SIZE_HEX}" \
        "${PAGE_SIZE_HEX}"
}

validate_archive_libraries() {
    local artifact="$1"
    local extracted_root="$2"
    local library
    local library_count=0

    while IFS= read -r -d '' library; do
        library_count=$((library_count + 1))
        validate_elf "${artifact}" "${extracted_root}" "${library}"
    done < <(find "${extracted_root}" -type f -name '*.so' -print0 | sort -z)

    (( library_count > 0 )) || fail "artifact='${artifact}' contains no native libraries"
}

validate_apk() {
    local artifact="$1"
    local extracted_root="$2"
    local zipalign

    zipalign="$(find_zipalign)"
    "${zipalign}" -v -c -P 16 4 "${artifact}" >/dev/null \
        || fail "artifact='${artifact}' failed zipalign -v -c -P 16 4"
    printf "PASS: artifact='%s' APK zip alignment is 16 KB\n" "${artifact}"

    unzip -q "${artifact}" -d "${extracted_root}"
    validate_archive_libraries "${artifact}" "${extracted_root}"
}

validate_aab() {
    local artifact="$1"
    local extracted_root="$2"
    local bundle_config

    configure_bundletool
    if ! bundle_config="$("${BUNDLETOOL_COMMAND[@]}" dump config --bundle="${artifact}" 2>&1)"; then
        fail "artifact='${artifact}' bundletool config inspection failed: ${bundle_config}"
    fi
    grep -q 'PAGE_ALIGNMENT_16K' <<< "${bundle_config}" \
        || fail "artifact='${artifact}' declares no PAGE_ALIGNMENT_16K bundle alignment"
    printf "PASS: artifact='%s' AAB page alignment is PAGE_ALIGNMENT_16K\n" "${artifact}"

    unzip -q "${artifact}" -d "${extracted_root}"
    validate_archive_libraries "${artifact}" "${extracted_root}"
}

collect_artifacts() {
    local input
    local artifact

    for input in "$@"; do
        if [[ -f "${input}" ]]; then
            case "${input}" in
                *.apk|*.aab) ARTIFACTS+=("${input}") ;;
                *) fail "unsupported artifact='${input}'; expected .apk or .aab" ;;
            esac
            continue
        fi

        if [[ -d "${input}" ]]; then
            while IFS= read -r -d '' artifact; do
                ARTIFACTS+=("${artifact}")
            done < <(find "${input}" -type f \( -name '*.apk' -o -name '*.aab' \) -print0 | sort -z)
            continue
        fi

        fail "artifact input does not exist: '${input}'"
    done
}

if (( $# == 0 )); then
    fail "usage: $0 <apk-or-aab-or-directory> [...]"
fi

command -v unzip >/dev/null 2>&1 || fail "unzip is required"

READELF="$(find_readelf)"
BUNDLETOOL_COMMAND=()
ARTIFACTS=()
collect_artifacts "$@"
(( ${#ARTIFACTS[@]} > 0 )) || fail "no APK or AAB artifacts found"

TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/bitkit-16kb.XXXXXX")"
cleanup() {
    if [[ -n "${TEMP_ROOT:-}" && -d "${TEMP_ROOT}" ]]; then
        rm -rf -- "${TEMP_ROOT}"
    fi
}
trap cleanup EXIT

artifact_index=0
for artifact in "${ARTIFACTS[@]}"; do
    artifact_index=$((artifact_index + 1))
    extracted_root="${TEMP_ROOT}/${artifact_index}"
    mkdir -p "${extracted_root}"

    case "${artifact}" in
        *.apk) validate_apk "${artifact}" "${extracted_root}" ;;
        *.aab) validate_aab "${artifact}" "${extracted_root}" ;;
    esac
done

printf 'PASS: validated %d Android artifact(s) for 16 KB compatibility\n' "${#ARTIFACTS[@]}"
