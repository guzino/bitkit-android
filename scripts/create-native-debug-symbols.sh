#!/usr/bin/env sh
set -eu

script_dir=$(cd "$(dirname "$0")" && pwd)
default_repo_root=$(cd "$script_dir/.." && pwd)
repo_root=${NATIVE_SYMBOLS_REPO_ROOT:-$default_repo_root}
cd "$repo_root"

variant="mainnetRelease"
if [ -n "${NATIVE_SYMBOLS_BUILD_NUMBER:-}" ]; then
    build_number=$NATIVE_SYMBOLS_BUILD_NUMBER
else
    build_number=$(
        awk -F= '
            /^[[:space:]]*versionCode[[:space:]]*=/ {
                value = $2
                gsub(/[[:space:]]/, "", value)
                print value
                exit
            }
        ' app/build.gradle.kts
    )
fi
case "$build_number" in
    ''|*[!0-9]*)
        echo "Unable to read numeric versionCode from app/build.gradle.kts." >&2
        exit 1
        ;;
esac

artifact_root=${NATIVE_SYMBOLS_ARTIFACT_ROOT:-app/build/outputs}
output="$artifact_root/native-debug-symbols/$variant/native-debug-symbols-$build_number.zip"
output_dir=$(dirname "$output")
dependency_symbols_dir=${NATIVE_SYMBOLS_DEPENDENCY_DIR:-app/build/intermediates/native-debug-symbol-artifacts}
required_libs="libbitkitcore.so libldk_node.so libpaykit.so libvss_rust_client_ffi.so"
archive_symbol_suffixes=".dbg .sym"

tmp_root=$(mktemp -d)
cleanup() {
    rm -rf "$tmp_root"
}
trap cleanup EXIT

make_tmp_dir() {
    mktemp -d "$tmp_root/native-symbols.XXXXXX"
}

local_properties_value() {
    key="$1"
    if [ ! -f "$repo_root/local.properties" ]; then
        return
    fi

    awk -F= -v key="$key" '$1 == key { value = $0; sub(/^[^=]*=/, "", value) } END { print value }' \
        "$repo_root/local.properties" | sed 's/\\ / /g'
}

find_readelf() {
    local_ndk_dir=$(local_properties_value "ndk.dir")
    local_sdk_dir=$(local_properties_value "sdk.dir")

    for ndk_dir in \
        "${ANDROID_NDK_ROOT:-}" \
        "${ANDROID_NDK_HOME:-}" \
        "${NDK_HOME:-}" \
        "$local_ndk_dir" \
        "${ANDROID_HOME:+$ANDROID_HOME/ndk}" \
        "${ANDROID_SDK_ROOT:+$ANDROID_SDK_ROOT/ndk}" \
        "${local_sdk_dir:+$local_sdk_dir/ndk}"; do
        if [ -z "$ndk_dir" ]; then
            continue
        fi

        for candidate in \
            "$ndk_dir"/toolchains/llvm/prebuilt/*/bin/llvm-readelf \
            "$ndk_dir"/*/toolchains/llvm/prebuilt/*/bin/llvm-readelf; do
            if [ -x "$candidate" ]; then
                echo "$candidate"
                return
            fi
        done
    done

    if command -v llvm-readelf >/dev/null 2>&1; then
        command -v llvm-readelf
        return
    fi

    if command -v readelf >/dev/null 2>&1; then
        command -v readelf
        return
    fi

    echo "llvm-readelf or readelf is required to validate native debug symbols." >&2
    exit 1
}

readelf_bin=${NATIVE_SYMBOLS_READELF_BIN:-$(find_readelf)}

has_dwarf_debug_metadata() {
    "$readelf_bin" -S "$1" | grep -Eq '\.debug_info'
}

build_id() {
    notes=$("$readelf_bin" -n "$1") || {
        echo "Unable to inspect native library notes: '$1'." >&2
        return 1
    }

    printf '%s\n' "$notes" | awk '
        /NT_GNU_BUILD_ID/ { found_gnu_build_id = 1; next }
        found_gnu_build_id && /Build ID:/ { print $3; exit }
    '
}

require_build_id() {
    id=$(build_id "$1")
    if [ -z "$id" ]; then
        echo "Native library has no NT_GNU_BUILD_ID: '$1'." >&2
        exit 1
    fi

    printf '%s\n' "$id"
}

validate_symbol_tree() {
    root="$1"

    for abi in arm64-v8a armeabi-v7a; do
        for lib_name in $required_libs; do
            lib="$root/$abi/$lib_name"
            if [ ! -f "$lib" ]; then
                echo "Missing required native symbol library '$abi/$lib_name'." >&2
                exit 1
            fi

            if ! has_dwarf_debug_metadata "$lib"; then
                echo "Native debug symbols unavailable: '$abi/$lib_name' has no .debug_info DWARF metadata." >&2
                echo "Refusing to create '$output' from stripped native libraries." >&2
                echo "Publish or consume native dependencies with full DWARF debug metadata before releasing." >&2
                exit 1
            fi

            require_build_id "$lib" >/dev/null
        done
    done
}

require_packaged_artifacts() {
    aab_artifact="$artifact_root/bundle/mainnetRelease/bitkit-mainnet-release-$build_number.aab"
    universal_apk_artifact="$artifact_root/apk/mainnet/release/bitkit-mainnet-release-$build_number-universal.apk"

    for artifact in "$aab_artifact" "$universal_apk_artifact"; do
        if [ ! -f "$artifact" ]; then
            echo "Required build-numbered app artifact is unavailable for native build-ID validation: '$artifact'." >&2
            exit 1
        fi
    done
}

extract_packaged_lib() {
    archive="$1"
    output_root="$2"
    abi="$3"
    lib_name="$4"

    aab_entry="base/lib/$abi/$lib_name"
    apk_entry="lib/$abi/$lib_name"
    if unzip -Z -1 "$archive" "$aab_entry" >/dev/null 2>&1; then
        entry="$aab_entry"
    elif unzip -Z -1 "$archive" "$apk_entry" >/dev/null 2>&1; then
        entry="$apk_entry"
    else
        echo "Packaged app artifact is missing '$abi/$lib_name': '$archive'." >&2
        exit 1
    fi

    mkdir -p "$output_root/$abi"
    unzip -p "$archive" "$entry" > "$output_root/$abi/$lib_name"
}

validate_packaged_build_id_parity() {
    symbol_root="$1"
    require_packaged_artifacts

    for packaged_artifact in "$aab_artifact" "$universal_apk_artifact"; do
        packaged_root=$(make_tmp_dir)

        for abi in arm64-v8a armeabi-v7a; do
            for lib_name in $required_libs; do
                extract_packaged_lib "$packaged_artifact" "$packaged_root" "$abi" "$lib_name"

                packaged_lib="$packaged_root/$abi/$lib_name"
                symbol_lib="$symbol_root/$abi/$lib_name"
                packaged_build_id=$(require_build_id "$packaged_lib")
                symbol_build_id=$(require_build_id "$symbol_lib")
                if [ "$packaged_build_id" != "$symbol_build_id" ]; then
                    echo "Native build ID mismatch for '$abi/$lib_name' in '$packaged_artifact': packaged=$packaged_build_id symbols=$symbol_build_id." >&2
                    exit 1
                fi
            done
        done

        echo "Validated native build-ID parity against '$packaged_artifact'."
    done
}

extract_archive_lib() {
    archive="$1"
    tmp_dir="$2"
    abi="$3"
    lib_name="$4"

    entry="$abi/$lib_name"
    if unzip -q "$archive" "$entry" -d "$tmp_dir" 2>/dev/null; then
        return
    fi

    for suffix in $archive_symbol_suffixes; do
        entry="$abi/$lib_name$suffix"
        if unzip -q "$archive" "$entry" -d "$tmp_dir" 2>/dev/null; then
            mv "$tmp_dir/$entry" "$tmp_dir/$abi/$lib_name"
            return
        fi
    done

    echo "Native debug symbols archive is missing '$abi/$lib_name' or accepted AGP variants '$abi/$lib_name.dbg' / '$abi/$lib_name.sym'." >&2
    exit 1
}

copy_archive_symbols() {
    archive="$1"
    tmp_dir="$2"

    for abi in arm64-v8a armeabi-v7a; do
        mkdir -p "$tmp_dir/$abi"
        for lib_name in $required_libs; do
            copied=false
            entry="$abi/$lib_name"
            if copy_archive_entry "$archive" "$tmp_dir" "$abi" "$lib_name" "$entry"; then
                copied=true
            fi

            if [ "$copied" = false ]; then
                for suffix in $archive_symbol_suffixes; do
                    entry="$abi/$lib_name$suffix"
                    if copy_archive_entry "$archive" "$tmp_dir" "$abi" "$lib_name" "$entry"; then
                        copied=true
                        break
                    fi
                done
            fi
        done
    done
}

copy_archive_entry() {
    archive="$1"
    tmp_dir="$2"
    abi="$3"
    lib_name="$4"
    entry="$5"
    output_lib="$tmp_dir/$abi/$lib_name"

    if ! unzip -Z -1 "$archive" "$entry" >/dev/null 2>&1; then
        return 1
    fi

    if [ -f "$output_lib" ]; then
        echo "Duplicate native debug symbol entry '$abi/$lib_name' found while reading '$archive'." >&2
        echo "Refusing to overwrite symbol metadata from an earlier archive." >&2
        exit 1
    fi

    unzip -q "$archive" "$entry" -d "$tmp_dir"
    if [ "$entry" != "$abi/$lib_name" ]; then
        mv "$tmp_dir/$entry" "$output_lib"
    fi
}

validate_output_zip() {
    archive="$1"
    zip -T "$archive" >/dev/null

    tmp_dir=$(make_tmp_dir)
    for abi in arm64-v8a armeabi-v7a; do
        for lib_name in $required_libs; do
            extract_archive_lib "$archive" "$tmp_dir" "$abi" "$lib_name"
        done
    done

    validate_symbol_tree "$tmp_dir"
    validate_packaged_build_id_parity "$tmp_dir"
}

create_output_zip_from_tree() {
    root="$1"

    validate_symbol_tree "$root"
    validate_packaged_build_id_parity "$root"

    mkdir -p "$output_dir"
    rm -f "$output_dir"/native-debug-symbols*.zip

    (
        cd "$root"
        zip -qr "$repo_root/$output" arm64-v8a armeabi-v7a
    )

    zip -T "$output" >/dev/null
    echo "Native debug symbols: $output"
    ls -lh "$output"
}

main() {
if [ -d "$dependency_symbols_dir" ]; then
    tmp_dir=$(make_tmp_dir)
    found_archive=false

    for archive in "$dependency_symbols_dir"/*.zip; do
        if [ ! -f "$archive" ]; then
            continue
        fi

        found_archive=true
        copy_archive_symbols "$archive" "$tmp_dir"
    done

    if [ "$found_archive" = false ]; then
        echo "No native debug symbol archives found in '$dependency_symbols_dir'." >&2
        echo "Run './gradlew :app:syncNativeDebugSymbolArtifacts' before creating release symbols." >&2
        exit 1
    fi

    create_output_zip_from_tree "$tmp_dir"
    exit 0
fi

if [ -f "$output" ]; then
    validate_output_zip "$output"
    echo "Native debug symbols: $output"
    ls -lh "$output"
    exit 0
fi

native_lib_dir=""
for candidate in "app/build/intermediates/merged_native_libs/$variant"/*/out/lib; do
    if [ -d "$candidate" ]; then
        native_lib_dir="$candidate"
        break
    fi
done

if [ -z "$native_lib_dir" ]; then
    echo "No merged native libraries found for '$variant'." >&2
    exit 1
fi

tmp_dir=$(make_tmp_dir)

for abi in arm64-v8a armeabi-v7a; do
    source_dir="$native_lib_dir/$abi"
    if [ ! -d "$source_dir" ]; then
        echo "Missing native libraries for '$abi' in '$native_lib_dir'." >&2
        exit 1
    fi

    mkdir -p "$tmp_dir/$abi"
    found_lib=false
    for lib in "$source_dir"/*.so; do
        if [ -f "$lib" ]; then
            cp "$lib" "$tmp_dir/$abi/"
            found_lib=true
        fi
    done

    if [ "$found_lib" = false ]; then
        echo "No native libraries found for '$abi' in '$source_dir'." >&2
        exit 1
    fi
done

create_output_zip_from_tree "$tmp_dir"
}

if [ "${NATIVE_SYMBOLS_FUNCTIONS_ONLY:-false}" != true ]; then
    main
fi
