#!/usr/bin/env bash
# Usage: release.sh <version> [--publish]
#
# Builds, signs and verifies dist/cardo-<version>.apk (e.g. release.sh 0.1.0). Without --publish this is a
# dry run: nothing is tagged, pushed or uploaded. With --publish it also tags v<version>, pushes the tag and
# creates the GitHub release from docs/release-notes/<version>.md.
#
# The signing key lives in ~/.cardo-keys (created on first run, never committed). Cardo has no INTERNET
# permission and therefore no in-app updater: users update by installing the next signed APK over this one,
# which only works if it is signed with the SAME key. BACK UP ~/.cardo-keys.
#
# RELEASE_ALLOW_ANY_BRANCH=1 lets a dry run (never --publish) work from a branch other than main.
set -euo pipefail

usage() { echo "usage: $0 <version> [--publish]   (version like 0.1.0 or 1.0.0)" >&2; exit 2; }
die() { echo "ERROR: $*" >&2; exit 1; }

version=""
publish=0
for arg in "$@"; do
    case $arg in
        --publish) publish=1 ;;
        -*) usage ;;
        *) [[ -z $version ]] || usage; version=$arg ;;
    esac
done
[[ -n $version ]] || usage
[[ $version =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || die "version must look like MAJOR.MINOR.PATCH, got '$version'"
major=${BASH_REMATCH[1]} minor=${BASH_REMATCH[2]} patch=${BASH_REMATCH[3]}
# versionCode = MAJOR*10000 + MINOR*100 + PATCH only stays monotonic while minor and patch are below 100.
(( minor < 100 && patch < 100 )) || die "minor and patch must be below 100 for the versionCode scheme"
version_code=$(( 10#$major * 10000 + 10#$minor * 100 + 10#$patch ))
tag="v$version"
notes="docs/release-notes/$version.md"

cd "$(dirname "$0")/.."

# 1. Preconditions.
[[ -z $(git status --porcelain) ]] || die "working tree is not clean"
branch=$(git rev-parse --abbrev-ref HEAD)
if [[ $branch != main ]]; then
    (( publish == 0 )) && [[ ${RELEASE_ALLOW_ANY_BRANCH:-} == 1 ]] \
        || die "HEAD is on '$branch', not main"
    echo "WARNING: building from '$branch' (RELEASE_ALLOW_ANY_BRANCH=1, dry run only)" >&2
fi
if (( publish )); then
    [[ -f $notes ]] || die "release notes missing: $notes (copy docs/release-notes/TEMPLATE.md)"
    git rev-parse -q --verify "refs/tags/$tag" >/dev/null && die "tag $tag already exists"
    gh auth status >/dev/null 2>&1 || die "gh is not authenticated"
fi

# Locate the Android SDK build-tools (apksigner, aapt2).
sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
if [[ -z $sdk && -f local.properties ]]; then
    sdk=$(sed -n 's/^sdk\.dir=//p' local.properties | tail -n1)
fi
[[ -d $sdk/build-tools ]] || die "Android SDK not found (set ANDROID_HOME or sdk.dir in local.properties)"
build_tools=""
for d in $(ls -1 "$sdk/build-tools" | sort -V -r); do
    if [[ -x $sdk/build-tools/$d/apksigner && -x $sdk/build-tools/$d/aapt2 ]]; then
        build_tools=$sdk/build-tools/$d
        break
    fi
done
[[ -n $build_tools ]] || die "no build-tools with apksigner and aapt2 under $sdk/build-tools"
apksigner=$build_tools/apksigner
aapt2=$build_tools/aapt2

# 2. Keystore (created once, then reused for every release).
keys_dir=$HOME/.cardo-keys
keystore=$keys_dir/cardo-release.jks
props=$keys_dir/keystore.properties
if [[ ! -f $keystore ]]; then
    [[ ! -f $props ]] || die "$props exists but $keystore does not; refusing to overwrite"
    echo "Creating release keystore in $keys_dir"
    mkdir -p "$keys_dir"
    chmod 700 "$keys_dir"
    random32() { head -c 256 /dev/urandom | base64 -w0 | tr -dc 'A-Za-z0-9' | head -c 32; }
    store_pw=$(random32)
    key_pw=$(random32)
    [[ ${#store_pw} -eq 32 && ${#key_pw} -eq 32 ]] || die "could not generate passwords"
    STORE_PW=$store_pw KEY_PW=$key_pw keytool -genkeypair -keystore "$keystore" \
        -storepass:env STORE_PW -keypass:env KEY_PW \
        -alias cardo -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Cardo" >/dev/null
    umask 077
    cat > "$props" <<PROPS
storeFile=$keystore
storePassword=$store_pw
keyAlias=cardo
keyPassword=$key_pw
PROPS
    chmod 600 "$props" "$keystore"
    cat >&2 <<'WARN'

################################################################################
#  NEW RELEASE KEY CREATED IN ~/.cardo-keys
#  BACK IT UP NOW (password manager / encrypted drive, off this machine).
#  If this key is lost, nobody can update Cardo: users would have to uninstall
#  and reinstall, losing their data. It cannot be recreated or recovered.
################################################################################

WARN
fi
[[ -f $props ]] || die "$props is missing"

# 3-4. Build.
echo "Building Cardo $version (versionCode $version_code)"
./gradlew clean :app:assembleRelease \
    -PcardoVersionName="$version" -PcardoVersionCode="$version_code"

built=app/build/outputs/apk/release/app-release.apk
[[ -f $built ]] || die "expected signed APK $built was not produced (is the release variant unsigned?)"
mkdir -p dist
apk=dist/cardo-$version.apk
cp "$built" "$apk"
(cd dist && sha256sum "cardo-$version.apk" > SHA256SUMS)

# 5. Verify.
echo "--- apksigner verify"
"$apksigner" verify --print-certs "$apk" | tee dist/.certs.txt
echo "--- permissions"
permissions=$("$aapt2" dump permissions "$apk")
echo "$permissions"
if grep -qi 'android.permission.INTERNET' <<<"$permissions"; then
    die "$apk requests INTERNET"
fi
echo "--- native libraries"
scripts/check-apk-libs.sh "$apk"

# 6. Fingerprint.
cert_sha256=$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' dist/.certs.txt)
rm -f dist/.certs.txt
[[ -n $cert_sha256 ]] || die "could not read the signing certificate fingerprint"
size=$(stat -c %s "$apk")
echo
echo "APK:                $apk ($size bytes)"
echo "APK SHA-256:        $(cut -d' ' -f1 dist/SHA256SUMS)"
echo "Signing cert SHA-256: $cert_sha256"

# 7. Publish.
if (( publish )); then
    flags=()
    (( major < 1 )) && flags+=(--prerelease)
    git tag -a "$tag" -m "Cardo $version"
    git push origin "$tag"
    gh release create "$tag" "$apk" dist/SHA256SUMS \
        --title "Cardo $version" --notes-file "$notes" "${flags[@]}"
else
    echo
    echo "Dry run complete: nothing tagged, pushed or published. Re-run with --publish to release."
fi
