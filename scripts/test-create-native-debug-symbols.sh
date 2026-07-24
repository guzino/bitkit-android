#!/usr/bin/env sh
set -eu

script_dir=$(cd "$(dirname "$0")" && pwd)
test_root=$(mktemp -d)
trap 'rm -rf "$test_root"' EXIT

fake_readelf="$test_root/llvm-readelf"
cat > "$fake_readelf" <<'EOF'
#!/usr/bin/env sh
set -eu

mode=$1
file=$2
id=$(sed -n 's/.*id=\([^ ]*\).*/\1/p' "$file")

case "$mode" in
    -S)
        if grep -q 'dwarf=true' "$file"; then
            echo "  [1] .debug_info"
        fi
        ;;
    -n)
        if grep -q 'gnu=true' "$file"; then
            echo "  GNU                  0x00000014 NT_GNU_BUILD_ID (unique build ID bitstring)"
        else
            echo "  OTHER                0x00000014 OTHER_NOTE"
        fi
        if [ -n "$id" ]; then
            echo "    Build ID: $id"
        fi
        ;;
    *)
        exit 1
        ;;
esac
EOF
chmod +x "$fake_readelf"

export NATIVE_SYMBOLS_REPO_ROOT="$test_root"
export NATIVE_SYMBOLS_ARTIFACT_ROOT="$test_root/artifacts"
export NATIVE_SYMBOLS_BUILD_NUMBER=999
export NATIVE_SYMBOLS_READELF_BIN="$fake_readelf"
export NATIVE_SYMBOLS_FUNCTIONS_ONLY=true
required_libs="libbitkitcore.so libldk_node.so libpaykit.so libvss_rust_client_ffi.so"
tmp_root=""
# shellcheck disable=SC1091
. "$script_dir/create-native-debug-symbols.sh"
trap 'rm -rf "$tmp_root" "$test_root"' EXIT

write_library_tree() {
    root=$1
    prefix=$2
    mismatch=$3

    for abi in arm64-v8a armeabi-v7a; do
        for lib_name in $required_libs; do
            id="${abi}-${lib_name}"
            if [ "$mismatch" = true ] && [ "$abi/$lib_name" = "arm64-v8a/libpaykit.so" ]; then
                id="mismatched-paykit"
            fi
            mkdir -p "$root/$prefix/$abi"
            printf 'gnu=true id=%s dwarf=true\n' "$id" > "$root/$prefix/$abi/$lib_name"
        done
    done
}

create_archive() {
    tree=$1
    archive=$2
    mkdir -p "$(dirname "$archive")"
    (
        cd "$tree"
        zip -qr "$archive" .
    )
}

symbol_root="$test_root/symbols"
write_library_tree "$symbol_root" "" false

missing_gnu_note="$test_root/missing-gnu-note.so"
printf 'gnu=false id=looks-valid dwarf=true\n' > "$missing_gnu_note"
if (require_build_id "$missing_gnu_note" >/dev/null 2>&1); then
    echo "Accepted a Build ID outside NT_GNU_BUILD_ID." >&2
    exit 1
fi

aab_tree="$test_root/aab"
apk_tree="$test_root/apk"
aab="$NATIVE_SYMBOLS_ARTIFACT_ROOT/bundle/mainnetRelease/bitkit-mainnet-release-999.aab"
apk="$NATIVE_SYMBOLS_ARTIFACT_ROOT/apk/mainnet/release/bitkit-mainnet-release-999-universal.apk"
write_library_tree "$aab_tree" "base/lib" false
write_library_tree "$apk_tree" "lib" true
create_archive "$aab_tree" "$aab"
create_archive "$apk_tree" "$apk"

mismatch_log="$test_root/mismatch.log"
if (validate_packaged_build_id_parity "$symbol_root" > "$mismatch_log" 2>&1); then
    echo "Accepted a mismatched universal APK build ID." >&2
    exit 1
fi
grep -q "$apk" "$mismatch_log"

rm -f "$apk"
rm -rf "$apk_tree"
mkdir -p "$apk_tree"
write_library_tree "$apk_tree" "lib" false
create_archive "$apk_tree" "$apk"

success_log="$test_root/success.log"
validate_packaged_build_id_parity "$symbol_root" > "$success_log"
grep -q "$aab" "$success_log"
grep -q "$apk" "$success_log"

echo "Native debug symbol fixture validation passed."
