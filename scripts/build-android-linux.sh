#!/usr/bin/env bash

# Construit OpenNote depuis les sources sur Linux, sans AAR préexistant.
#
# Chaîne de référence : JDK 17, Go 1.26.0, Android SDK 35, plateforme
# Android 26, NDK 27.3.13750724 et Gradle 8.9. C'est celle qu'installe la CI du
# dépôt, et celle sur laquelle sont construits les APK publiés.
#
# Les contrôles portent sur un *minimum*, pas sur une égalité. Un empaqueteur —
# F-Droid en particulier — fournit sa propre image et n'a aucune raison de
# porter exactement ces révisions : refuser le build sur une comparaison de
# chaîne l'arrêterait sur autre chose qu'une incompatibilité réelle. Un écart
# avec la chaîne de référence est signalé, jamais fatal.
#
# Points de réglage, tous par l'environnement :
#   OPENNOTE_GRADLE_BIN    exécutable Gradle à utiliser
#   OPENNOTE_NDK_VERSION   révision de NDK à chercher dans le SDK
#   ANDROID_NDK_HOME       chemin d'un NDK, prioritaire sur la recherche
#   OPENNOTE_TOOLS_DIR     cache des binaires gomobile et gobind

set -Eeuo pipefail

readonly NDK_VERSION="27.3.13750724"
readonly GRADLE_VERSION="8.9"
readonly JAVA_MAJOR="17"

die() {
    printf 'Erreur : %s\n' "$*" >&2
    exit 1
}

avertir() {
    printf 'Avertissement : %s\n' "$*" >&2
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "commande introuvable : $1"
}

# Vrai si $1 est supérieure ou égale à $2.
#
# `sort -V` compare composante par composante, y compris quand les deux
# versions n'en ont pas le même nombre — « 8.10 » contre « 8.9 », que l'ordre
# lexicographique classerait à l'envers. Si trier les deux place $2 en tête,
# c'est que $1 lui est supérieure ou égale.
version_au_moins() {
    [[ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n 1)" == "$2" ]]
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
version_au_moins "$actual_go_version" "$required_go_version" ||
    die "Go $required_go_version ou supérieur requis, Go ${actual_go_version:-inconnu} détecté"
[[ "$actual_go_version" == "$required_go_version" ]] ||
    avertir "Go $actual_go_version au lieu de la version de référence $required_go_version"

java_major="$(java -version 2>&1 | awk -F '[".]' '/version/ { print $2; exit }')"
[[ "$java_major" =~ ^[0-9]+$ ]] ||
    die "version de JDK illisible dans la sortie de java -version"
((java_major >= JAVA_MAJOR)) ||
    die "JDK $JAVA_MAJOR ou supérieur requis, version majeure $java_major détectée"

android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
[[ -n "$android_sdk" ]] || die "ANDROID_SDK_ROOT ou ANDROID_HOME doit désigner le SDK Android"
android_sdk="$(cd -- "$android_sdk" && pwd -P)"

[[ -f "$android_sdk/platforms/android-35/android.jar" ]] ||
    die "plateforme Android 35 absente du SDK"
[[ -f "$android_sdk/platforms/android-26/android.jar" ]] ||
    die "plateforme Android 26 absente du SDK (requise par gomobile -androidapi 26)"

# Le NDK ne sert qu'à gomobile : le module Android n'a aucune source native.
# Trois sources, dans cet ordre — ce que l'environnement impose, la révision
# demandée, puis le NDK le plus récent installé.
wanted_ndk_version="${OPENNOTE_NDK_VERSION:-$NDK_VERSION}"

if [[ -n "${ANDROID_NDK_HOME:-}" ]]; then
    android_ndk="$ANDROID_NDK_HOME"
elif [[ -f "$android_sdk/ndk/$wanted_ndk_version/source.properties" ]]; then
    android_ndk="$android_sdk/ndk/$wanted_ndk_version"
else
    android_ndk="$(find "$android_sdk/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null |
        sort -V | tail -n 1)"
fi

[[ -n "$android_ndk" && -f "$android_ndk/source.properties" ]] ||
    die "aucun NDK utilisable (cherchés : ANDROID_NDK_HOME, $android_sdk/ndk/$wanted_ndk_version)"

actual_ndk_version="$(sed -n 's/^Pkg\.Revision[[:space:]]*=[[:space:]]*//p' "$android_ndk/source.properties" | head -n 1)"
[[ -n "$actual_ndk_version" ]] || die "révision illisible dans $android_ndk/source.properties"
[[ "$actual_ndk_version" == "$wanted_ndk_version" ]] ||
    avertir "NDK $actual_ndk_version au lieu de la révision de référence $wanted_ndk_version"

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
    die "Gradle $GRADLE_VERSION ou supérieur est requis (ou définir OPENNOTE_GRADLE_BIN)"
fi

actual_gradle_version="$("${gradle_command[@]}" --version | awk '/^Gradle / { print $2; exit }')"
version_au_moins "$actual_gradle_version" "$GRADLE_VERSION" ||
    die "Gradle $GRADLE_VERSION ou supérieur requis, Gradle ${actual_gradle_version:-inconnu} détecté"
[[ "$actual_gradle_version" == "$GRADLE_VERSION" ]] ||
    avertir "Gradle $actual_gradle_version au lieu de la version de référence $GRADLE_VERSION"

cd "$repo_dir"

mobile_version="$(go list -m -f '{{.Version}}' golang.org/x/mobile)"
[[ "$mobile_version" == v* ]] || die "version de golang.org/x/mobile non verrouillée dans go.mod"

tools_dir="${OPENNOTE_TOOLS_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/opennote/go-tools/$mobile_version}"
mkdir -p "$tools_dir"

printf 'Chaîne de build : Go %s, x/mobile %s, JDK %s, Gradle %s, NDK %s\n' \
    "$actual_go_version" "$mobile_version" "$java_major" \
    "$actual_gradle_version" "$actual_ndk_version"

GOBIN="$tools_dir" go install "golang.org/x/mobile/cmd/gomobile@$mobile_version"
GOBIN="$tools_dir" go install "golang.org/x/mobile/cmd/gobind@$mobile_version"
export PATH="$tools_dir:$PATH"

go mod download
go test ./... -short

gomobile init
mkdir -p android/app/libs
gomobile bind \
    -target=android/arm64,android/amd64 \
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

# `opennote.ndkVersion` dit à AGP quel NDK a réellement servi. Sans lui, le
# module exigerait la révision épinglée dans build.gradle.kts, et le build
# échouerait sur l'image d'un empaqueteur qui en porte une autre — soit
# exactement ce que les contrôles ci-dessus viennent d'éviter.
(
    cd android
    "${gradle_command[@]}" --no-daemon --stacktrace \
        "-Popennote.ndkVersion=$actual_ndk_version" \
        "${gradle_tasks[@]}"
)

readonly apk="$repo_dir/android/app/build/outputs/apk/release/app-release-unsigned.apk"
[[ -s "$apk" ]] || die "APK release introuvable : $apk"

sha256sum android/app/libs/opennote.aar "$apk"
printf 'Build Linux terminé : %s\n' "$apk"
