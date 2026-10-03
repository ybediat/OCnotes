/* Script commun aux versions fr et en. La langue vient de <html lang>. */
(function () {
  var en = document.documentElement.lang === "en";

  /* ---------- Choix de langue : mémorisé, pour que la détection ne le contredise pas ---------- */
  document.addEventListener("click", function (e) {
    var a = e.target.closest && e.target.closest("a[data-lang]");
    if (!a) return;
    try { localStorage.setItem("ocnotes-lang", a.getAttribute("data-lang")); } catch (err) { /* stockage refusé */ }
  });

  /* ---------- Bouton de téléchargement : pointe toujours vers la dernière release ----------
     Sans JavaScript (ou si l'API GitHub répond mal), le lien reste /releases/latest,
     qui fonctionne aussi. Avec JavaScript, on le remplace par l'APK direct. */
  var norm = function (s) {
    return String(s || "").normalize("NFD").replace(/[̀-ͯ]/g, "").replace(/[  ]/g, " ").toLowerCase().trim();
  };
  var fmtDate = function (iso) {
    var d = new Date(iso);
    if (isNaN(d)) return "";
    var p = function (n) { return String(n).padStart(2, "0"); };
    return en
      ? d.getFullYear() + "-" + p(d.getMonth() + 1) + "-" + p(d.getDate())
      : p(d.getDate()) + "/" + p(d.getMonth() + 1) + "/" + d.getFullYear();
  };

  fetch("https://api.github.com/repos/ybediat/OCnotes/releases/latest", { headers: { Accept: "application/vnd.github+json" } })
    .then(function (r) { if (!r.ok) throw new Error(r.status); return r.json(); })
    .then(function (rel) {
      var apk = (rel.assets || []).filter(function (a) { return norm(a.name).slice(-4) === ".apk"; })[0];
      if (!apk) return;
      var v = String(rel.tag_name).replace(/^v/i, "");

      document.getElementById("dl-btn").href = apk.browser_download_url;
      document.getElementById("dl-label").textContent = (en ? "Download APK " : "Télécharger l'APK ") + v;

      var size = (apk.size / 1048576).toFixed(1);
      var meta = document.getElementById("dl-meta");
      meta.textContent = en
        ? "Version " + v + ", released " + fmtDate(rel.published_at) + ", " + size + " MB. Android 8.0 or newer."
        : "Version " + v + " du " + fmtDate(rel.published_at) + ", " + size.replace(".", ",") + " Mo. Android 8.0 ou plus récent.";

      var sha = String(apk.digest || "").replace(/^sha256:/i, "");
      if (sha) {
        var p = document.createElement("p");
        p.className = "dl-meta";
        p.innerHTML = "SHA-256 : <code></code>";
        if (en) p.firstChild.nodeValue = "SHA-256: ";
        p.querySelector("code").textContent = sha;
        meta.insertAdjacentElement("afterend", p);
      }
    })
    .catch(function () { /* on garde le lien de repli */ });

  /* ---------- Apparition au défilement (IntersectionObserver, pas d'écouteur scroll) ---------- */
  var els = document.querySelectorAll(".reveal");
  if (!("IntersectionObserver" in window)) { els.forEach(function (e) { e.classList.add("in"); }); return; }
  var io = new IntersectionObserver(function (entries) {
    entries.forEach(function (en) { if (en.isIntersecting) { en.target.classList.add("in"); io.unobserve(en.target); } });
  }, { threshold: 0.15 });
  els.forEach(function (e) { io.observe(e); });
})();
