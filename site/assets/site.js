/* Script commun aux versions fr et en. La langue vient de <html lang>.
   Le site reste complet sans JavaScript : ce script ne fait qu'ajouter
   le bandeau de langue, fermer le menu mobile et préciser le lien de téléchargement. */
(function () {
  var en = document.documentElement.lang === "en";
  /* Adresse de release.json, à côté de ce script (fonctionne depuis / comme depuis /en/) */
  var script = document.currentScript;
  var releaseUrl = new URL("release.json", script ? script.src : location.href);

  /* Normalisation des textes lus : sans accents, espaces insécables remplacées, minuscules */
  var norm = function (s) {
    return String(s || "")
      .normalize("NFD")
      .replace(/[̀-ͯ]/g, "")
      .replace(/[  ]/g, " ")
      .toLowerCase()
      .trim();
  };

  /* Lecture et écriture du choix de langue, protégées : le stockage peut être refusé */
  var lireChoix = function () {
    try { return localStorage.getItem("ocnotes-lang"); } catch (err) { return null; }
  };
  var ecrireChoix = function (v) {
    try { localStorage.setItem("ocnotes-lang", v); } catch (err) { /* stockage refusé */ }
  };

  /* ---------- Choix de langue : mémorisé dès qu'on clique un lien FR/EN ou le bouton « fermer » du bandeau ---------- */
  document.addEventListener("click", function (e) {
    var el = e.target.closest && e.target.closest("[data-lang]");
    if (!el) return;
    ecrireChoix(el.getAttribute("data-lang"));
    /* Le bouton de fermeture du bandeau ne fait que le masquer */
    if (el.tagName === "BUTTON") {
      var hint = document.getElementById("lang-hint");
      if (hint) hint.hidden = true;
    }
  });

  /* ---------- Bandeau de langue : on propose l'autre version, on ne redirige jamais ----------
     (une redirection automatique empêcherait les robots d'indexer la page française) */
  (function () {
    var hint = document.getElementById("lang-hint");
    if (!hint || lireChoix()) return;
    var langues = navigator.languages && navigator.languages.length ? navigator.languages : [navigator.language || ""];
    var francophone = Array.prototype.some.call(langues, function (l) { return norm(l).indexOf("fr") === 0; });
    /* Page française vue par un non-francophone, ou page anglaise vue par un francophone */
    if (langues[0] && (en ? francophone : !francophone)) hint.hidden = false;
  })();

  /* ---------- Menu mobile : se referme après un clic sur un lien interne ---------- */
  var menu = document.getElementById("menu");
  if (menu) {
    menu.addEventListener("click", function (e) {
      if (!e.target.closest("a")) return;
      try { if (menu.matches(":popover-open")) menu.hidePopover(); } catch (err) { /* popover non géré */ }
    });
  }

  /* ---------- Bouton de téléchargement ----------
     Sans JavaScript, ou si release.json est absent, le lien reste /releases/latest,
     qui fonctionne aussi. Les infos viennent d'un fichier du site lui-même :
     aucun appel à un service tiers, donc l'adresse IP du visiteur ne sort pas. */
  var size = function (octets) {
    var mo = (octets / 1048576).toFixed(1);
    return en ? mo + " MB" : mo.replace(".", ",") + " Mo";
  };
  /* Date ISO (AAAA-MM-JJ) affichée en JJ/MM/AAAA, sans passer par Date pour éviter tout décalage de fuseau */
  var fmtDate = function (iso) {
    var m = /^(\d{4})-(\d{2})-(\d{2})/.exec(String(iso || "").trim());
    return m ? m[3] + "/" + m[2] + "/" + m[1] : "";
  };

  fetch(releaseUrl, { cache: "no-cache" })
    .then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
    .then(function (rel) {
      /* On n'accepte qu'un APK signé : jamais l'artefact « unsigned » de la CI */
      var nom = norm(rel.apk);
      if (!rel.url || nom.slice(-4) !== ".apk" || nom.indexOf("unsigned") !== -1) return;
      var v = String(rel.version || "").trim().replace(/^v/i, "");

      document.getElementById("dl-btn").href = rel.url;
      document.getElementById("dl-label").textContent = (en ? "Download APK " : "Télécharger l'APK ") + v;

      var date = fmtDate(rel.date);
      var meta = document.getElementById("dl-meta");
      meta.textContent = en
        ? "Version " + v + (date ? ", released " + date : "") + ", " + size(rel.size) + ". Android 8.0 or newer."
        : "Version " + v + (date ? " du " + date : "") + ", " + size(rel.size) + ". Android 8.0 ou plus récent.";

      var sha = String(rel.sha256 || "").trim().replace(/^sha256:/i, "");
      if (/^[0-9a-f]{64}$/i.test(sha)) {
        var p = document.createElement("p");
        var code = document.createElement("code");
        p.className = "dl-meta";
        p.append(en ? "SHA-256: " : "SHA-256 : ", code);
        code.textContent = sha.toLowerCase();
        meta.after(p);
      }
    })
    .catch(function () { /* on garde le lien de repli */ });
})();
