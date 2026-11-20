<?php
/*
 * Relais entre le site et l'API GitHub pour le bouton de téléchargement.
 * - GitHub est interrogé au plus une fois par heure, côté serveur :
 *   l'adresse IP des visiteurs ne part jamais chez GitHub.
 * - La réponse réduite est gardée dans un fichier cache, à côté de ce script.
 * - Si GitHub ne répond pas, on renvoie la dernière réponse connue ;
 *   à défaut, une erreur 503 et le site garde son lien /releases/latest.
 */

const DEPOT    = 'ybediat/OCnotes';
const DUREE    = 3600;                        // durée de vie du cache, en secondes
const CACHE    = __DIR__ . '/.release-cache.json';
const PREFIXE  = 'https://github.com/ybediat/OCnotes/releases/download/';

header('Content-Type: application/json; charset=utf-8');
header('X-Content-Type-Options: nosniff');

/* Normalisation des noms lus : sans accents, sans espaces insécables, en minuscules */
function normaliser($s) {
    $s = trim((string) $s);
    if (class_exists('Normalizer')) {
        $s = Normalizer::normalize($s, Normalizer::FORM_D);
        $s = preg_replace('/\p{Mn}/u', '', $s);
    }
    $s = str_replace(["\u{00A0}", "\u{202F}"], ' ', $s);
    return mb_strtolower($s, 'UTF-8');
}

/* Appel HTTPS à l'API GitHub, avec cURL si présent, sinon file_get_contents */
function telecharger($url) {
    $entetes = ['User-Agent: ocnotes-site', 'Accept: application/vnd.github+json'];
    if (function_exists('curl_init')) {
        $c = curl_init($url);
        curl_setopt_array($c, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT        => 5,
            CURLOPT_HTTPHEADER     => $entetes,
            CURLOPT_FOLLOWLOCATION => true,
        ]);
        $corps = curl_exec($c);
        $code  = curl_getinfo($c, CURLINFO_HTTP_CODE);
        curl_close($c);
        return ($corps !== false && $code === 200) ? $corps : null;
    }
    $ctx = stream_context_create(['http' => [
        'header'  => implode("\r\n", $entetes),
        'timeout' => 5,
    ]]);
    $corps = @file_get_contents($url, false, $ctx);
    return $corps === false ? null : $corps;
}

/* Réduit la réponse GitHub au strict nécessaire, ou null si aucun APK signé n'est trouvé */
function reduire($json) {
    $rel = json_decode($json, true);
    if (!is_array($rel) || empty($rel['assets'])) return null;
    foreach ($rel['assets'] as $a) {
        $nom = normaliser($a['name'] ?? '');
        $url = (string) ($a['browser_download_url'] ?? '');
        /* seul l'APK signé s'installe : on écarte l'artefact « unsigned » de la CI */
        if (substr($nom, -4) !== '.apk' || strpos($nom, 'unsigned') !== false) continue;
        if (strpos($url, PREFIXE) !== 0) continue;
        $sha = preg_replace('/^sha256:/i', '', trim((string) ($a['digest'] ?? '')));
        return [
            'version' => preg_replace('/^v/i', '', trim((string) ($rel['tag_name'] ?? ''))),
            'date'    => substr((string) ($rel['published_at'] ?? ''), 0, 10),   // AAAA-MM-JJ, mis en forme par site.js
            'apk'     => (string) $a['name'],
            'url'     => $url,
            'size'    => (int) ($a['size'] ?? 0),
            'sha256'  => preg_match('/^[0-9a-f]{64}$/i', $sha) ? strtolower($sha) : '',
        ];
    }
    return null;
}

/* Cache encore frais : on le renvoie tel quel */
$frais = is_file(CACHE) && (time() - filemtime(CACHE)) < DUREE;
if (!$frais) {
    /* Verrou : un seul visiteur rafraîchit le cache, les autres lisent l'ancien */
    $verrou = @fopen(CACHE . '.lock', 'c');
    if ($verrou && flock($verrou, LOCK_EX | LOCK_NB)) {
        $corps  = telecharger('https://api.github.com/repos/' . DEPOT . '/releases/latest');
        $donnee = $corps ? reduire($corps) : null;
        if ($donnee) {
            /* écriture atomique : fichier temporaire puis renommage */
            $tmp = CACHE . '.tmp';
            if (@file_put_contents($tmp, json_encode($donnee, JSON_UNESCAPED_SLASHES)) !== false) @rename($tmp, CACHE);
        } elseif (is_file(CACHE)) {
            /* GitHub indisponible : on garde l'ancienne réponse et on ne réessaie que dans 10 minutes */
            @touch(CACHE, time() - DUREE + 600);
        }
        flock($verrou, LOCK_UN);
    }
    if ($verrou) fclose($verrou);
}

if (is_file(CACHE)) {
    header('Cache-Control: public, max-age=600');
    readfile(CACHE);
} else {
    http_response_code(503);
    header('Cache-Control: no-store');
    echo '{}';
}
