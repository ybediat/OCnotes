#!/usr/bin/env bash
# Tests de bout en bout sur un émulateur déjà démarré (adb connecté).
#
# Prérequis : APK debug et APK de test construits par
#   bash scripts/build-android-linux.sh assembleDebug assembleDebugAndroidTest
#
# Enchaîne :
#   1. EditeurE2ETest, sur des données effacées ;
#   2. SauvegardeE2ETest : préparation, sauvegarde par bmgr, effacement,
#      restauration, vérification.
# Le journal (logcat) est écrit dans build-e2e/logcat.txt pour diagnostic.

set -Eeuo pipefail

readonly paquet="eu.ocnotes.debug"
readonly runner="$paquet.test/androidx.test.runner.AndroidJUnitRunner"
readonly apk="android/app/build/outputs/apk/debug/app-debug.apk"
readonly apk_test="android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
readonly sortie="build-e2e"

mkdir -p "$sortie"

# Étapes en échec : on va au bout du scénario, puis on échoue à la fin.
echecs=()

die() {
    printf 'Erreur : %s\n' "$*" >&2
    exit 1
}

# Garde le logcat quoi qu'il arrive, pour lire un échec après coup.
journal() {
    adb logcat -d >"$sortie/logcat.txt" 2>&1 || true
    # Captures d'écran prises par les tests au moment d'un échec.
    adb pull /sdcard/Download "$sortie/captures" >/dev/null 2>&1 || true
}
trap journal EXIT

# Lance une classe (ou méthode) de test et échoue si elle n'est pas verte.
# « OK (n tests) » avec n > 0 est la seule sortie acceptée.
instrumenter() {
    local nom="$1"
    shift
    printf '\n=== %s ===\n' "$nom"
    local resultat
    resultat="$(adb shell am instrument -w "$@" "$runner" | tr -d '\r')"
    printf '%s\n' "$resultat"
    if ! grep -Eq '^OK \([1-9][0-9]* tests?\)' <<<"$resultat"; then
        printf 'ÉCHEC : %s\n' "$nom" >&2
        echecs+=("$nom")
    fi
}

[[ -s "$apk" ]] || die "APK debug introuvable : $apk"
[[ -s "$apk_test" ]] || die "APK de test introuvable : $apk_test"

adb wait-for-device
adb install -r -g "$apk"
adb install -r "$apk_test"

# Pas d'animations : UI Automator n'attend pas leur fin.
for reglage in window_animation_scale transition_animation_scale animator_duration_scale; do
    adb shell settings put global "$reglage" 0
done

# --- 1. Éditeur --------------------------------------------------------------
adb shell pm clear "$paquet"
adb logcat -c
instrumenter "Éditeur" -e class eu.ocnotes.e2e.EditeurE2ETest

# --- 2. Sauvegarde puis restauration -------------------------------------------
adb shell pm clear "$paquet"
instrumenter "Sauvegarde : préparation" \
    -e class eu.ocnotes.e2e.SauvegardeE2ETest#preparer -e etape preparer

# Transport local de l'émulateur. Sans « fake_encryption_flag », il se
# déclare non chiffré et SauvegardeAgent refuse, à raison, d'envoyer quoi que
# ce soit : le drapeau simule une sauvegarde chiffrée de bout en bout.
adb shell bmgr enable true
adb shell bmgr transport com.android.localtransport/.LocalTransport
adb shell settings put secure backup_local_transport_parameters "fake_encryption_flag=true"

envoi="$(adb shell bmgr backupnow "$paquet" | tr -d '\r')"
printf '%s\n' "$envoi"
grep -q "Package $paquet with result: Success" <<<"$envoi" || die "sauvegarde refusée ou en échec"

# Jeton du jeu de sauvegarde à restaurer : première ligne « jeton : nom ».
jeton="$(adb shell bmgr list sets | tr -d '\r' | awk -F' : ' 'NF == 2 { gsub(/ /, "", $1); print $1; exit }')"
[[ -n "$jeton" ]] || die "aucun jeu de sauvegarde listé par bmgr"

adb shell pm clear "$paquet"
restauration="$(adb shell bmgr restore "$jeton" "$paquet" | tr -d '\r')"
printf '%s\n' "$restauration"
grep -qi "done" <<<"$restauration" || die "restauration en échec"

instrumenter "Sauvegarde : vérification" \
    -e class eu.ocnotes.e2e.SauvegardeE2ETest#verifier -e etape verifier

if ((${#echecs[@]} > 0)); then
    die "étapes en échec : ${echecs[*]}"
fi
printf '\nTests sur émulateur : tout est passé.\n'
