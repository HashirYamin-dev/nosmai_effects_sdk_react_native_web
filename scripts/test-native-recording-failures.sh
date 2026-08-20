#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_dir="$(cd "${script_dir}/.." && pwd)"
test_output_dir="$(mktemp -d "${TMPDIR:-/tmp}/nosmai-native-failures.XXXXXX")"
trap 'rm -rf "${test_output_dir}"' EXIT

android_output_dir="${test_output_dir}/android"
mkdir -p "${android_output_dir}"
javac \
  -d "${android_output_dir}" \
  "${repo_dir}/android/src/main/java/com/nosmai/camerasdk/reactnative/NosmaiRecordingFailurePolicy.java" \
  "${repo_dir}/native-tests/android/com/nosmai/camerasdk/reactnative/NosmaiRecordingFailurePolicyTests.java"
java \
  -cp "${android_output_dir}" \
  com.nosmai.camerasdk.reactnative.NosmaiRecordingFailurePolicyTests

if [[ "$(uname -s)" == "Darwin" ]]; then
  ios_test_binary="${test_output_dir}/NosmaiRecordingFailurePolicyTests"
  xcrun --sdk macosx clang++ \
    -std=c++17 \
    -fobjc-arc \
    -framework Foundation \
    "${repo_dir}/ios/NosmaiRecordingFailurePolicy.mm" \
    "${repo_dir}/native-tests/ios/NosmaiRecordingFailurePolicyTests.mm" \
    -o "${ios_test_binary}"
  "${ios_test_binary}"
else
  echo "iOS recording failure policies: skipped (requires macOS/Xcode)"
fi
