<?php
/*
 * Relais entre le site et GitHub pour le bouton de téléchargement.
 * - GitHub est interrogé au plus une fois par heure, côté serveur :
 *   l'adresse IP des visiteurs ne part jamais chez GitHub.
 * - La réponse réduite est gardée dans un fichier cache, à côté de ce script.
 * - Source 1 : l'API GitHub (version, date, taille, SHA-256).
 *   Source 2, si l'API refuse (fréquent sur un hébergement mutualisé, dont l'adresse IP
 *   est partagée et dépasse vite la limite de 60 appels par heure) : la page web
 *   /releases/latest et l'en-tête du fichier APK (version, taille ; pas de date ni de SHA-256).
 * - Si tout échoue, on renvoie la dernière réponse connue ; à défaut, une erreur 503
 *   et le site garde son lien /releases/latest.
 * - release.php?diag=1 affiche ce qui s'est passé lors de l'appel, pour le dépannage.
 */

const DEPOT   = 'ybediat/OCnotes';
const DUREE   = 3600;                        // durée de vie du cache, en secondes
const CACHE   = __DIR__ . '/.release-cache.json';
const PREFIXE = 'https://github.com/ybediat/OCnotes/releases/download/';

$diag  = isset($_GET['diag']);
$trace = [];                                 // journal de dépannage, affiché seulement avec ?diag=1

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
    return function_exists('mb_strtolower') ? mb_strtolower($s, 'UTF-8') : strtolower($s);
}

/*
 * Requête HTTPS. Renvoie [code HTTP, corps, URL finale après redirections, taille totale du fichier].
 * $tete = true : requête HEAD (on ne veut que les en-têtes).
 * $octet = true : on ne télécharge que le premier octet (Range) ; la taille totale vient de Content-Range.
 *   (les fichiers des releases sont servis par des liens signés qui refusent HEAD)
 */
function requete($url, $tete = false, $octet = false) {
    global $trace;
    $entetes = ['User-Agent: ocnotes-site (+https://ocnotes.fr)', 'Accept: application/vnd.github+json, text/html, */*'];
    if ($octet) $entetes[] = 'Range: bytes=0-0';
    $total = 0;
    /* Content-Range: bytes 0-0/20552213 -> taille totale */
    $lire_total = function ($h) use (&$total) {
        if (preg_match('#^Content-Range:\s*bytes\s+\d+-\d+/(\d+)#i', trim($h), $m)) $total = (int) $m[1];
    };
    if (function_exists('curl_init')) {
        $c = curl_init($url);
        curl_setopt_array($c, [
            CURLOPT_RETURNTRANSFER => true,
            CURLOPT_TIMEOUT        => 8,
            CURLOPT_HTTPHEADER     => $entetes,
            CURLOPT_FOLLOWLOCATION => true,
            CURLOPT_MAXREDIRS      => 5,
            CURLOPT_NOBODY         => $tete,
            CURLOPT_HEADERFUNCTION => function ($c, $h) use ($lire_total) { $lire_total($h); return strlen($h); },
        ]);
        $corps  = curl_exec($c);
        $code   = (int) curl_getinfo($c, CURLINFO_HTTP_CODE);
        $finale = (string) curl_getinfo($c, CURLINFO_EFFECTIVE_URL);
        $taille = (int) curl_getinfo($c, CURLINFO_CONTENT_LENGTH_DOWNLOAD);
        $err    = curl_error($c);
        curl_close($c);
        $trace[] = "curl $url -> HTTP $code" . ($err ? " ($err)" : '');
        return [$code, $corps === false ? '' : (string) $corps, $finale, $total ?: $taille];
    }
    if (!ini_get('allow_url_fopen')) {
        $trace[] = "ni cURL ni allow_url_fopen : impossible de joindre $url";
        return [0, '', $url, 0];
    }
    $ctx = stream_context_create(['http' => [
        'method'        => $tete ? 'HEAD' : 'GET',
        'header'        => implode("\r\n", $entetes),
        'timeout'       => 8,
        'ignore_errors' => true,
    ]]);
    $corps = @file_get_contents($url, false, $ctx);
    /* $http_response_header liste les en-têtes de chaque saut de redirection, dans l'ordre */
    $code = 0; $finale = $url; $taille = 0;
    foreach (isset($http_response_header) ? $http_response_header : [] as $h) {
        if (preg_match('#^HTTP/\S+\s+(\d+)#', $h, $m)) $code = (int) $m[1];
        elseif (preg_match('#^Location:\s*(\S+)#i', $h, $m)) $finale = $m[1];
        elseif (preg_match('#^Content-Length:\s*(\d+)#i', $h, $m)) $taille = (int) $m[1];
        $lire_total($h);
    }
    $trace[] = "fopen $url -> HTTP $code";
    return [$code, $corps === false ? '' : (string) $corps, $finale, $total ?: $taille];
}

/* Source 1 : API GitHub. Renvoie le résultat réduit, ou null */
function depuis_api() {
    list($code, $corps) = requete('https://api.github.com/repos/' . DEPOT . '/releases/latest');
    if ($code !== 200) return null;
    $rel = json_decode($corps, true);
    if (!is_array($rel) || empty($rel['assets'])) return null;
    foreach ($rel['assets'] as $a) {
        $nom = normaliser(isset($a['name']) ? $a['name'] : '');
        $url = (string) (isset($a['browser_download_url']) ? $a['browser_download_url'] : '');
        /* seul l'APK signé s'installe : on écarte l'artefact « unsigned » de la CI */
        if (substr($nom, -4) !== '.apk' || strpos($nom, 'unsigned') !== false) continue;
        if (strpos($url, PREFIXE) !== 0) continue;
        $sha = preg_replace('/^sha256:/i', '', trim((string) (isset($a['digest']) ? $a['digest'] : '')));
        return [
            'version' => preg_replace('/^v/i', '', trim((string) (isset($rel['tag_name']) ? $rel['tag_name'] : ''))),
            'date'    => substr((string) (isset($rel['published_at']) ? $rel['published_at'] : ''), 0, 10),   // AAAA-MM-JJ, mis en forme par site.js
            'apk'     => (string) $a['name'],
            'url'     => $url,
            'size'    => (int) (isset($a['size']) ? $a['size'] : 0),
            'sha256'  => preg_match('/^[0-9a-f]{64}$/i', $sha) ? strtolower($sha) : '',
        ];
    }
    return null;
}

/* Source 2 : la page /releases/latest redirige vers /releases/tag/vX.Y.Z ; l'APK suit le nommage OCnotes-X.Y.Z.apk */
function depuis_page() {
    global $trace;
    list($code, , $finale) = requete('https://github.com/' . DEPOT . '/releases/latest', true);
    if (!preg_match('#/releases/tag/v?([0-9][0-9A-Za-z.\-]*)$#', $finale, $m)) {
        $trace[] = "page web : pas de version trouvée dans $finale";
        return null;
    }
    $v   = $m[1];
    $nom = 'OCnotes-' . $v . '.apk';
    $url = PREFIXE . 'v' . $v . '/' . $nom;
    /* On vérifie que l'APK existe vraiment (premier octet seulement) et on récupère sa taille */
    list($code, , , $taille) = requete($url, false, true);
    if ($code !== 200 && $code !== 206) return null;
    return ['version' => $v, 'date' => '', 'apk' => $nom, 'url' => $url, 'size' => max(0, $taille), 'sha256' => ''];
}

/* Cache encore frais : on le renvoie tel quel (sauf en mode dépannage, qui force un appel) */
$frais = is_file(CACHE) && (time() - filemtime(CACHE)) < DUREE;
if (!$frais || $diag) {
    /* Verrou : un seul visiteur rafraîchit le cache, les autres lisent l'ancien */
    $verrou = @fopen(CACHE . '.lock', 'c');
    if (!$verrou) $trace[] = 'impossible de créer le verrou : dossier non accessible en écriture ?';
    if (!$verrou || flock($verrou, LOCK_EX | LOCK_NB)) {
        $donnee = depuis_api();
        if (!$donnee) $donnee = depuis_page();
        if ($donnee) {
            /* écriture atomique : fichier temporaire puis renommage */
            $tmp = CACHE . '.tmp';
            if (@file_put_contents($tmp, json_encode($donnee, JSON_UNESCAPED_SLASHES)) !== false && @rename($tmp, CACHE)) {
                $trace[] = 'cache écrit';
            } else {
                $trace[] = 'cache NON écrit : dossier non accessible en écriture';
                /* on sert quand même la réponse obtenue, sans cache */
                if (!is_file(CACHE)) { header('Cache-Control: no-store'); echo $diag ? json_encode(['trace' => $trace, 'resultat' => $donnee], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT) : json_encode($donnee, JSON_UNESCAPED_SLASHES); exit; }
            }
        } elseif (is_file(CACHE)) {
            /* GitHub indisponible : on garde l'ancienne réponse et on ne réessaie que dans 10 minutes */
            @touch(CACHE, time() - DUREE + 600);
            $trace[] = 'GitHub injoignable : ancienne réponse conservée';
        }
        if ($verrou) flock($verrou, LOCK_UN);
    }
    if ($verrou) fclose($verrou);
}

if ($diag) {
    header('Cache-Control: no-store');
    $trace[] = 'PHP ' . PHP_VERSION . ', cURL ' . (function_exists('curl_init') ? 'oui' : 'non') . ', allow_url_fopen ' . (ini_get('allow_url_fopen') ? 'oui' : 'non') . ', dossier inscriptible ' . (is_writable(__DIR__) ? 'oui' : 'non');
    echo json_encode(['trace' => $trace, 'cache' => is_file(CACHE) ? json_decode(file_get_contents(CACHE), true) : null], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT);
} elseif (is_file(CACHE)) {
    header('Cache-Control: public, max-age=600');
    readfile(CACHE);
} else {
    http_response_code(503);
    header('Cache-Control: no-store');
    echo '{}';
}
