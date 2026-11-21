#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "$0")/.." && pwd)
generated_dir=${1:?Pass the absolute directory for generated Android sources}
sdk_dir=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
ndk_dir=${ANDROID_NDK_HOME:-${sdk_dir:?Set ANDROID_HOME or ANDROID_NDK_HOME}/ndk/28.2.13676358}
host_tag=${ANDROID_NDK_HOST_TAG:-}
if [[ -z "$host_tag" ]]; then
    case "$(uname -s)" in
        Linux) host_tag=linux-x86_64 ;;
        Darwin) host_tag=darwin-x86_64 ;;
        *) echo "Set ANDROID_NDK_HOST_TAG for this host." >&2; exit 1 ;;
    esac
fi
bin_dir="$ndk_dir/toolchains/llvm/prebuilt/$host_tag/bin"
test -x "$bin_dir/llvm-ar"

cd "$project_root/core-rust"
cargo build --locked --lib
host_library=target/debug/libcore_rust.so
if [[ $(uname -s) == Darwin ]]; then
    host_library=target/debug/libcore_rust.dylib
fi
mkdir -p "$generated_dir/kotlin"
cargo run --locked --features bindgen --bin uniffi-bindgen -- \
    generate --library "$host_library" --config uniffi.toml --language kotlin --no-format \
    --out-dir "$generated_dir/kotlin"

abis=(armeabi-v7a arm64-v8a x86_64)
if [[ ${ANDROID_ABIS:-} ]]; then
    read -r -a abis <<< "$ANDROID_ABIS"
fi
for abi in "${abis[@]}"; do
    case "$abi" in
        armeabi-v7a) target=armv7-linux-androideabi; clang=armv7a-linux-androideabi ;;
        arm64-v8a) target=aarch64-linux-android; clang=aarch64-linux-android ;;
        x86_64) target=x86_64-linux-android; clang=x86_64-linux-android ;;
        *) echo "Unsupported ABI: $abi" >&2; exit 1 ;;
    esac
    target_env=${target//-/_}
    linker="$bin_dir/${clang}26-clang"
    linker_env=$(printf '%s' "$target_env" | tr '[:lower:]' '[:upper:]')
    env "CARGO_TARGET_${linker_env}_LINKER=$linker" "CC_$target_env=$linker" \
        "AR_$target_env=$bin_dir/llvm-ar" cargo build --locked --lib --release --target "$target"
    mkdir -p "$generated_dir/jniLibs/$abi"
    install -m 0644 "target/$target/release/libcore_rust.so" "$generated_dir/jniLibs/$abi/libcore_rust.so"
done
