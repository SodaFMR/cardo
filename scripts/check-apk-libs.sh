#!/usr/bin/env bash
# Usage: check-apk-libs.sh <apk>
# Prints the lib/<abi>/*.so entries of an APK; exits 1 if any .so name appears twice within one ABI,
# or if the APK cannot be listed (unzip failure).
set -euo pipefail

if [[ $# -ne 1 || ! -f $1 ]]; then
    echo "usage: $0 <apk>" >&2
    exit 2
fi

# Capture the listing first: a failing `unzip` inside a pipeline would otherwise be masked by `|| true`.
entries=$(unzip -Z1 "$1") || { echo "ERROR: cannot list $1" >&2; exit 1; }
libs=$(printf '%s\n' "$entries" | grep -E '^lib/[^/]+/[^/]+\.so$' | sort || true)
printf '%s\n' "$libs"

duplicates=$(printf '%s\n' "$libs" | uniq -d)
if [[ -n $duplicates ]]; then
    echo "ERROR: duplicate .so entries within one ABI:" >&2
    printf '%s\n' "$duplicates" >&2
    exit 1
fi
