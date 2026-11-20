<?php
/*
 * Relais entre le site et GitHub pour le bouton de téléchargement.
 * - GitHub est interrogé au plus une fois par heure, côté serveur :
 *   l'adresse IP des visiteurs ne part jamais chez GitHub.
 * - La réponse réduite est gardée dans un fichier cache, à côté de ce script.
 * - Source 1 : l'API GitHub (version, date, taille, SHA-256).
 *   Source 2, si l'API refuse (fréquent sur un hébergement mutualisé, dont l'adresse IP
 *   est partagée et dépasse vite la limite de 60 appels par heure) : la page web
 *   /releases/latest (version, date, SHA-256 lu sur la ligne « SHA-256 APK : » du texte
 *   de la release) et l'en-tête du fichier APK (taille).
 * - Si tout échoue, on renvoie la dernière réponse connue ; à défaut, une erreur 503
 *   et le site garde son lien /releases/latest. Dans les deux cas, GitHub n'est plus
 *   sollicité pendant 10 minutes (même sans cache : premier dépôt, dossier en lecture seule…).
 * - release.php?diag=1 affiche ce qui s'est passé lors de l'appel, pour le dépannage.
 *   L'adresse est publique : elle force un nouvel appel à GitHub au plus une fois par minute,
 *   pour que personne ne puisse épuiser le quota de l'adresse IP partagée en la rappelant en boucle.
 */

const DEPOT   = 'ybediat/OCnotes';
const DUREE   = 3600;                        // durée de vie du cache, en secondes
const CACHE   = __DIR__ . '/.release-cache.json';
const PREFIXE = 'https://github.com/ybediat/OCnotes/releases/download/';
const ESSAI   = CACHE . '.essai';            // sa date de modification = dernier appel à GitHub
const PAUSE   = 600;                         // délai minimal entre deux appels, en secondes
const PAUSE_DIAG = 60;                       // idem quand l'appel est forcé par ?diag=1

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

/* Source 2 : la page /releases/latest redirige vers /releases/tag/vX.Y.Z, dont on lit :
   - la version, dans l'adresse finale ;
   - la date de publication, dans la première balise <relative-time datetime="…"> ;
   - le SHA-256, sur la ligne « SHA-256 APK : … » écrite dans le texte de chaque release.
   L'APK suit le nommage OCnotes-X.Y.Z.apk. */
function depuis_page($ancien) {
    global $trace;
    list($code, $html, $finale) = requete('https://github.com/' . DEPOT . '/releases/latest');
    /* $m[1] = le tag tel quel (avec ou sans « v »), $m[2] = la version seule */
    if (!preg_match('#/releases/tag/(v?([0-9][0-9A-Za-z.\-]*))$#i', $finale, $m)) {
        $trace[] = "page web : pas de version trouvée dans $finale";
        return null;
    }
    $tag = $m[1];
    $v   = $m[2];
    $nom = 'OCnotes-' . $v . '.apk';
    $url = PREFIXE . $tag . '/' . $nom;

    /* Date : première balise <relative-time> de la page, gardée seulement si c'est une vraie date.
       Si GitHub change son HTML, la date est simplement omise par le site. */
    $date = '';
    if (preg_match('#<relative-time[^>]*\bdatetime="(\d{4})-(\d{2})-(\d{2})#', $html, $d)
        && checkdate((int) $d[2], (int) $d[3], (int) $d[1])) {
        $date = "$d[1]-$d[2]-$d[3]";
    }
    /* texte brut de la page, normalisé (accents, espaces insécables, majuscules) avant la recherche */
    $texte = normaliser(html_entity_decode(strip_tags($html), ENT_QUOTES | ENT_HTML5, 'UTF-8'));
    $sha   = preg_match('#sha-?256\s*apk\s*:\s*([0-9a-f]{64})\b#', $texte, $h) ? $h[1] : '';
    /* Garde-fou : la même empreinte que la version précédente trahit une ligne recopiée
       d'une ancienne release. On ne l'affiche pas plutôt que d'afficher une empreinte fausse. */
    if ($sha && is_array($ancien) && isset($ancien['version'], $ancien['sha256'])
        && $ancien['version'] !== $v && $ancien['sha256'] === $sha) {
        $trace[] = "page web : SHA-256 identique à celui de la version {$ancien['version']}, ignoré";
        $sha = '';
    }
    $trace[] = 'page web : date ' . ($date ?: 'absente') . ', SHA-256 ' . ($sha ? 'trouvé' : 'absent');

    /* On vérifie que l'APK existe vraiment (premier octet seulement) et on récupère sa taille */
    list($code, , , $taille) = requete($url, false, true);
    if ($code !== 200 && $code !== 206) return null;
    return ['version' => $v, 'date' => $date, 'apk' => $nom, 'url' => $url, 'size' => max(0, $taille), 'sha256' => $sha];
}

/* Lecture du cache : renvoie le tableau enregistré, ou null s'il est absent ou illisible */
function lire_cache() {
    $brut = is_file(CACHE) ? @file_get_contents(CACHE) : false;
    $donnee = $brut ? json_decode($brut, true) : null;
    return is_array($donnee) && !empty($donnee['url']) ? $donnee : null;
}

/* Âge d'un fichier en secondes (très grand s'il n'existe pas) */
function age($fichier) {
    clearstatcache(true, $fichier);
    return is_file($fichier) ? time() - filemtime($fichier) : PHP_INT_MAX;
}

/* Faut-il rappeler GitHub ? Cache périmé (ou ?diag=1), et dernier appel assez ancien */
function a_rafraichir($diag) {
    if (!$diag && age(CACHE) < DUREE && lire_cache()) return false;
    return age(ESSAI) >= ($diag ? PAUSE_DIAG : PAUSE);
}

if (a_rafraichir($diag)) {
    /* Verrou : un seul visiteur rafraîchit le cache, les autres lisent l'ancien */
    $verrou = @fopen(CACHE . '.lock', 'c');
    if (!$verrou) $trace[] = 'impossible de créer le verrou : dossier non accessible en écriture ?';
    if (!$verrou || flock($verrou, LOCK_EX | LOCK_NB)) {
        /* Nouvelle vérification une fois le verrou obtenu : un autre visiteur vient peut-être de rafraîchir */
        if (a_rafraichir($diag)) {
            /* On note l'appel avant de le faire : succès ou échec, le suivant attendra PAUSE */
            if (!@touch(ESSAI)) $trace[] = 'impossible de noter la date de l\'appel : GitHub sera rappelé à chaque visite';
            $ancien = lire_cache();
            $donnee = depuis_api();
            if (!$donnee) $donnee = depuis_page($ancien);
            $json = $donnee ? json_encode($donnee, JSON_UNESCAPED_SLASHES) : false;
            if ($donnee && $json === false) $trace[] = 'réponse impossible à encoder en JSON : ' . json_last_error_msg();
            if ($json !== false) {
                /* écriture atomique : fichier temporaire propre à ce processus, puis renommage */
                $tmp = CACHE . '.' . getmypid() . '.tmp';
                if (@file_put_contents($tmp, $json) !== false && @rename($tmp, CACHE)) {
                    $trace[] = 'cache écrit';
                } else {
                    @unlink($tmp);
                    $trace[] = 'cache NON écrit : dossier non accessible en écriture';
                    /* on sert quand même la réponse obtenue, sans cache */
                    if (!$diag) { header('Cache-Control: no-store'); echo $json; exit; }
                    $trace[] = 'réponse obtenue : ' . $json;
                }
            } else {
                $trace[] = 'GitHub injoignable : ' . (lire_cache() ? 'ancienne réponse conservée' : 'aucune réponse à servir') . ', nouvel essai dans ' . (PAUSE / 60) . ' minutes';
            }
        } else {
            $trace[] = 'un autre visiteur vient de rafraîchir le cache';
        }
        if ($verrou) flock($verrou, LOCK_UN);
    } else {
        $trace[] = 'rafraîchissement déjà en cours par un autre visiteur';
    }
    if ($verrou) fclose($verrou);
} elseif ($diag) {
    $trace[] = 'pas de nouvel appel à GitHub : le dernier date de ' . age(ESSAI) . ' s (minimum ' . PAUSE_DIAG . ' s en mode dépannage)';
}

$cache = lire_cache();
if ($diag) {
    header('Cache-Control: no-store');
    /* pas de version de PHP ici : l'adresse est publique */
    $trace[] = 'cURL ' . (function_exists('curl_init') ? 'oui' : 'non') . ', allow_url_fopen ' . (ini_get('allow_url_fopen') ? 'oui' : 'non') . ', dossier inscriptible ' . (is_writable(__DIR__) ? 'oui' : 'non');
    echo json_encode(['trace' => $trace, 'cache' => $cache], JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE | JSON_PRETTY_PRINT | JSON_INVALID_UTF8_SUBSTITUTE);
} elseif ($cache) {
    header('Cache-Control: public, max-age=600');
    echo json_encode($cache, JSON_UNESCAPED_SLASHES);
} else {
    http_response_code(503);
    header('Cache-Control: no-store');
    echo '{}';
}
