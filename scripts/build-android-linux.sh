#!/usr/bin/env bash

# Construit OpenNote depuis les sources sur Linux, sans AAR préexistant.
#
# Prérequis : JDK 17, Go 1.26.0, Android SDK 35, plateforme Android 26,
# NDK 29.0.14206865 et Gradle 8.9. La CI du dépôt installe exactement cette
# chaîne avant d'appeler ce script.

set -Eeuo pipefail

readonly NDK_VERSION="27.3.13750724"
readonly GRADLE_VERSION="8.9"

die() {
    printf 'Erreur : %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "commande introuvable : $1"
}

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_dir="$(cd -- "$script_dir/.." && pwd -P)"

require_command go
require_command java
require_command javac
require_command sha256sum

required_go_version="$(awk '$1 == "go" { print $2; exit }' "$repo_dir/go.mod")"
[[ -n "$required_go_version" ]] || die "version de Go absente de go.mod"

actual_go_version="$(go env GOVERSION)"
actual_go_version="${actual_go_version#go}"
[[ "$actual_go_version" == "$required_go_version" ]] ||
    die "Go $required_go_version requis, Go $actual_go_version détecté"

java_major="$(java -version 2>&1 | awk -F '[\".]' '/version/ { print $2; exit }')"
[[ "$java_major" == "17" ]] || die "JDK 17 requis, version majeure $java_major détectée"

android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$android_sdk" ]] || die "ANDROID_SDK_ROOT ou ANDROID_HOME doit désigner le SDK Android"
android_sdk="$(cd -- "$android_sdk" && pwd -P)"

[[ -f "$android_sdk/platforms/android-35/android.jar" ]] ||
    die "plateforme Android 35 absente du SDK"
[[ -f "$android_sdk/platforms/android-26/android.jar" ]] ||
    die "plateforme Android 26 absente du SDK (requise par gomobile -androidapi 26)"

android_ndk="${ANDROID_NDK_HOME:-$android_sdk/ndk/$NDK_VERSION}"
[[ -f "$android_ndk/source.properties" ]] ||
    die "NDK $NDK_VERSION introuvable dans $android_ndk"

actual_ndk_version="$(sed -n 's/^Pkg\.Revision[[:space:]]*=[[:space:]]*//p' "$android_ndk/source.properties" | head -n 1)"
[[ "$actual_ndk_version" == "$NDK_VERSION" ]] ||
    die "NDK $NDK_VERSION requis, NDK ${actual_ndk_version:-inconnu} détecté"

export ANDROID_HOME="$android_sdk"
export ANDROID_SDK_ROOT="$android_sdk"
export ANDROID_NDK_HOME="$(cd -- "$android_ndk" && pwd -P)"
export GOFLAGS="${GOFLAGS:+$GOFLAGS }-mod=readonly"

if [[ -n "${OPENNOTE_GRADLE_BIN:-}" ]]; then
    [[ -x "$OPENNOTE_GRADLE_BIN" ]] || command -v "$OPENNOTE_GRADLE_BIN" >/dev/null 2>&1 ||
        die "Gradle introuvable : $OPENNOTE_GRADLE_BIN"
    gradle_command=("$OPENNOTE_GRADLE_BIN")
elif [[ -f "$repo_dir/android/gradle/wrapper/gradle-wrapper.jar" ]]; then
    gradle_command=(sh "$repo_dir/android/gradlew")
elif command -v gradle >/dev/null 2>&1; then
    gradle_command=(gradle)
else
    die "Gradle $GRADLE_VERSION est requis (ou définir OPENNOTE_GRADLE_BIN)"
fi

actual_gradle_version="$("${gradle_command[@]}" --version | awk '/^Gradle / { print $2; exit }')"
[[ "$actual_gradle_version" == "$GRADLE_VERSION" ]] ||
    die "Gradle $GRADLE_VERSION requis, Gradle ${actual_gradle_version:-inconnu} détecté"

cd "$repo_dir"

mobile_version="$(go list -m -f '{{.Version}}' golang.org/x/mobile)"
[[ "$mobile_version" == v* ]] || die "version de golang.org/x/mobile non verrouillée dans go.mod"

tools_dir="${OPENNOTE_TOOLS_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/opennote/go-tools/$mobile_version}"
mkdir -p "$tools_dir"

printf 'Chaîne de build : Go %s, x/mobile %s, JDK 17, Gradle %s, NDK %s\n' \
    "$required_go_version" "$mobile_version" "$GRADLE_VERSION" "$NDK_VERSION"

GOBIN="$tools_dir" go install "golang.org/x/mobile/cmd/gomobile@$mobile_version"
GOBIN="$tools_dir" go install "golang.org/x/mobile/cmd/gobind@$mobile_version"
export PATH="$tools_dir:$PATH"

go mod download
go test ./... -short

gomobile init
mkdir -p android/app/libs
gomobile bind \
    -target=android/arm64 \
    -androidapi=26 \
    -trimpath \
    -ldflags="-s -w" \
    -o android/app/libs/opennote.aar \
    ./mobile

[[ -s android/app/libs/opennote.aar ]] || die "gomobile n'a pas produit opennote.aar"

if (($# == 0)); then
    gradle_tasks=(testDebugUnitTest lintRelease assembleRelease)
else
    gradle_tasks=("$@")
fi

(
    cd android
    "${gradle_command[@]}" --no-daemon --stacktrace "${gradle_tasks[@]}"
)

readonly apk="$repo_dir/android/app/build/outputs/apk/release/app-release-unsigned.apk"
[[ -s "$apk" ]] || die "APK release introuvable : $apk"

sha256sum android/app/libs/opennote.aar "$apk"
printf 'Build Linux terminé : %s\n' "$apk"
