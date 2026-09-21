/*
 * Nyasar web i18n — 2 bahasa (ID default / EN).
 * Elemen diterjemahkan lewat atribut data-i18n="key" (textContent) atau
 * data-i18n-html="key" (innerHTML, untuk paragraf ber-tag). Preferensi
 * disimpan di localStorage 'nyasar_lang'.
 */
(function () {
  var I18N = {
    id: {
      "nav.features": "Fitur",
      "nav.how": "Cara Pakai",
      "nav.privacy": "Privasi",
      "nav.terms": "Syarat",

      "hero.kicker": "Offline-first · GPS · Untuk pendaki Indonesia",
      "hero.title": "Sesat itu biasa. <em>Nyasar</em> itu pilihan.",
      "hero.lead": "Nyasar adalah aplikasi navigasi hiking offline-first untuk Android. Rekam rute GPS di tengah hutan tanpa sinyal, ikuti jalur yang sudah dibagikan pendaki lain, dan pulang dengan selamat — tanpa paket data.",
      "hero.cta.download": "Unduh APK (gratis)",
      "hero.cta.browse": "Jelajahi Rute Publik",
      "hero.note": "Gratis · Tanpa Play Store · Data GPX-mu tetap di HP-mu",

      "stat.offline": "Peta & rute offline",
      "stat.gpx": "Import / export GPX",
      "stat.share": "Bagikan rute ke sesama pendaki",
      "stat.priv": "GPS mentah tak pernah diunggah",

      "feat.title": "Semua yang kamu butuh di gunung",
      "feat.sub": "Dirancang untuk kondisi sinyal kosong: peta, rekaman, dan navigasi bekerja penuh secara lokal di perangkat.",
      "feat.1.t": "Rekam GPS tanpa sinyal",
      "feat.1.d": "Lacak jalur, jarak, elevasi, dan kecepatan. Perekaman berjalan di latar belakang dengan notifikasi — layar mati pun tetap jalan.",
      "feat.2.t": "Peta topografi offline",
      "feat.2.d": "Unduh area gunung sebelum berangkat. Peta topografi & satelit tetap tampil jauh dari tower BTS.",
      "feat.3.t": "Navigasi ikuti rute",
      "feat.3.d": "Ambil rute dari Library atau Explore — aplikasi memandu dengan penanda arah dan peringatan kalau keluar jalur.",
      "feat.4.t": "Waypoint & penanda",
      "feat.4.d": "Simpan titik penting: sumber air, simpang, pos. Beri kategori dan catatan agar mudah ditemukan lagi.",
      "feat.5.t": "Jelajahi rute pendaki lain",
      "feat.5.d": "Rute publik dari komunitas: lihat statistik, baca catatan medan, unduh GPX-nya, langsung ikuti.",
      "feat.6.t": "Backup otomatis",
      "feat.6.d": "Aktifkan akun dan seluruh aktivitasmu tersimpan aman di cloud — ganti HP tanpa kehilangan sejarah pendakian.",

      "how.title": "Siap naik dalam tiga langkah",
      "how.sub": "Sebelum berangkat siapkan semuanya dari rumah — nanti di atas, sinyal bukan urusan lagi.",
      "how.1.t": "Unduh area peta",
      "how.1.d": "Pilih area gunung tujuanmu dan simpan untuk mode offline.",
      "how.2.t": "Rekam atau ambil rute",
      "how.2.d": "Rekam jalurmu sendiri, atau unduh rute publik dari pendaki lain.",
      "how.3.t": "Naik & bagikan",
      "how.3.d": "Nikmati pendakian, simpan jejaknya, lalu bagikan ke komunitas.",

      "band.title": "Datamu milikmu",
      "band.d": "Nyasar menyimpan rekaman GPS-mu di perangkat secara default. Titik GPS mentah tidak pernah diunggah — yang dibagikan hanya rute ringkas yang kamu pilih sendiri untuk dipublikasikan.",
      "band.cta": "Baca Kebijakan Privasi",

      "cta.title": "Naik gunung dengan percaya diri",
      "cta.d": "Unduh Nyasar, dan jadikan sesat bagian dari petualangan — bukan kekhawatiran.",
      "cta.get": "Unduh APK Nyasar",
      "cta.hint": "APK langsung dari GitHub Releases — gratis, tanpa Play Store. Buka file-nya di HP untuk install.",

      "footer.privacy": "Privacy Policy",
      "footer.terms": "Terms of Service",
      "footer.contact": "Kontak",
      "footer.made": "Dibuat dengan teliti untuk pendaki Indonesia.",

      "route.load": "Memuat rute…",
      "route.notfound": "Rute tidak tersedia",
      "route.notfound.d": "Rute ini privat, sudah dihapus, atau tautannya tidak valid.",
      "route.generic": "Rute Nyasar",
      "route.generic.d": "Detail rute tersedia di aplikasi Nyasar — unduh lewat tombol di bawah.",
      "route.byline": "Rute pendakian publik dibagikan lewat Nyasar",
      "route.dist": "Jarak",
      "route.gain": "Naik",
      "route.loss": "Turun",
      "route.max": "Puncak",
      "route.min": "Terendah",
      "route.time": "Waktu",
      "route.diff": "Kesulitan",
      "route.desc": "Deskripsi",
      "route.dl": "Unduh file GPX",
      "route.openapp": "Buka di aplikasi Nyasar",
      "route.dl.note": "File GPX terkompresi (.gz) — bisa langsung disimpan ke Library lewat aplikasi Nyasar.",
      "route.browse": "Jelajahi rute lainnya",
      "route.share": "Bagikan rute ini",
      "route.learn": "Pelajari aplikasinya",

      "nav.download": "Unduh",
      "browse.title": "Jelajahi Rute Publik",
      "browse.sub": "Jalur-jalur yang dibagikan pendaki lain lewat aplikasi Nyasar — lihat statistiknya, unduh GPX-nya, langsung ikuti.",
      "browse.search_hint": "Cari nama rute…",
      "browse.diff_easy": "Mudah",
      "browse.diff_moderate": "Sedang",
      "browse.diff_difficult": "Sulit",
      "browse.diff_very": "Sangat sulit",
      "browse.no_match": "Tidak ada rute yang cocok dengan pencarian/filter ini.",
      "browse.loading": "Memuat rute…",
      "browse.empty": "Belum ada rute publik. Jadilah yang pertama mempublikasikan dari aplikasi!",
      "browse.error": "Koneksi bermasalah — muat ulang halaman untuk mencoba lagi.",
      "browse.noconfig": "Daftar rute belum tersedia di halaman ini — buka aplikasi Nyasar untuk menjelajah rute publik.",

      "legal.back": "Kembali ke beranda"
    },

    en: {
      "nav.features": "Features",
      "nav.how": "How it works",
      "nav.privacy": "Privacy",
      "nav.terms": "Terms",

      "hero.kicker": "Offline-first · GPS · Built for Indonesian hikers",
      "hero.title": "Getting lost is normal. <em>Being prepared</em> is better.",
      "hero.lead": "Nyasar is an offline-first hiking navigation app for Android. Record GPS tracks deep in the forest with zero signal, follow trails shared by other hikers, and make it home safe — no data plan required.",
      "hero.cta.download": "Download APK (free)",
      "hero.cta.browse": "Explore public routes",
      "hero.note": "Free · No Play Store needed · Your raw GPS data stays on your phone",

      "stat.offline": "Offline maps & routes",
      "stat.gpx": "Import / export GPX",
      "stat.share": "Share trails with fellow hikers",
      "stat.priv": "Raw GPS never uploaded",

      "feat.title": "Everything you need on the mountain",
      "feat.sub": "Designed for dead-signal conditions: maps, recording and navigation all work fully on-device.",
      "feat.1.t": "Record GPS without signal",
      "feat.1.d": "Track your path, distance, elevation and speed. Recording runs in the background with a visible notification — even with the screen off.",
      "feat.2.t": "Offline topographic maps",
      "feat.2.d": "Download the mountain area before you leave. Topo & satellite layers keep rendering far from any cell tower.",
      "feat.3.t": "Route-following navigation",
      "feat.3.d": "Pick a route from your Library or Explore — the app guides you with a direction marker and alerts when you stray off the trail.",
      "feat.4.t": "Waypoints & markers",
      "feat.4.d": "Save the important spots: water sources, junctions, camps. Categorize and annotate them to find them again fast.",
      "feat.5.t": "Explore other hikers' routes",
      "feat.5.d": "Public community routes: check the stats, read terrain notes, download the GPX, and follow it yourself.",
      "feat.6.t": "Automatic backup",
      "feat.6.d": "Sign in and every activity is safely backed up to the cloud — switch phones without losing your hiking history.",

      "how.title": "Trail-ready in three steps",
      "how.sub": "Prepare everything from home — out there, signal becomes someone else's problem.",
      "how.1.t": "Download the map area",
      "how.1.d": "Select your destination mountain and save it for offline use.",
      "how.2.t": "Record or pick a route",
      "how.2.d": "Record your own track, or grab a public route shared by other hikers.",
      "how.3.t": "Hike & share",
      "how.3.d": "Enjoy the hike, save the track, then share it with the community.",

      "band.title": "Your data stays yours",
      "band.d": "Nyasar keeps your GPS recordings on-device by default. Raw GPS points are never uploaded — only the summarized routes you explicitly choose to publish get shared.",
      "band.cta": "Read the Privacy Policy",

      "cta.title": "Hike with confidence",
      "cta.d": "Get Nyasar and make getting lost part of the adventure — not the worry.",
      "cta.get": "Download the Nyasar APK",
      "cta.hint": "APK straight from GitHub Releases — free, no Play Store. Open the file on your phone to install.",

      "footer.privacy": "Privacy Policy",
      "footer.terms": "Terms of Service",
      "footer.contact": "Contact",
      "footer.made": "Crafted carefully for Indonesian hikers.",

      "route.load": "Loading route…",
      "route.notfound": "Route unavailable",
      "route.notfound.d": "This route is private, was removed, or the link is invalid.",
      "route.generic": "Nyasar route",
      "route.generic.d": "Full route details are available in the Nyasar app — get it via the button below.",
      "route.byline": "A public hiking route shared via Nyasar",
      "route.dist": "Distance",
      "route.gain": "Ascent",
      "route.loss": "Descent",
      "route.max": "Max alt",
      "route.min": "Min alt",
      "route.time": "Time",
      "route.diff": "Difficulty",
      "route.desc": "Description",
      "route.dl": "Download GPX file",
      "route.openapp": "Open in the Nyasar app",
      "route.dl.note": "Compressed GPX file (.gz) — import it into your Library via the Nyasar app.",
      "route.browse": "Explore more routes",
      "route.share": "Share this route",
      "route.learn": "Learn about the app",

      "nav.download": "Download",
      "browse.title": "Explore Public Routes",
      "browse.sub": "Trails shared by fellow hikers via the Nyasar app — check the stats, download the GPX, follow along.",
      "browse.search_hint": "Search route names…",
      "browse.diff_easy": "Easy",
      "browse.diff_moderate": "Moderate",
      "browse.diff_difficult": "Difficult",
      "browse.diff_very": "Very difficult",
      "browse.no_match": "No routes match this search/filter.",
      "browse.loading": "Loading routes…",
      "browse.empty": "No public routes yet. Be the first to publish one from the app!",
      "browse.error": "Connection problem — reload the page to try again.",
      "browse.noconfig": "The route list is not available on this page — open the Nyasar app to explore public routes.",

      "legal.back": "Back to home"
    }
  };

  function apply(lang) {
    var dict = I18N[lang] || I18N.id;
    document.querySelectorAll("[data-i18n]").forEach(function (el) {
      var v = dict[el.getAttribute("data-i18n")];
      if (typeof v === "string") el.textContent = v;
    });
    document.querySelectorAll("[data-i18n-html]").forEach(function (el) {
      var v = dict[el.getAttribute("data-i18n-html")];
      if (typeof v === "string") el.innerHTML = v;
    });
    // Terjemahan ATRIBUT (mis. placeholder input): format "[attr,key]".
    // Dipakai elemen form yang teksnya hidup di atribut, bukan textContent.
    document.querySelectorAll("[data-i18n-attr]").forEach(function (el) {
      var spec = el.getAttribute("data-i18n-attr") || "";
      var m = spec.match(/^\s*\[\s*([a-zA-Z-]+)\s*,\s*([^\]]+)\s*\]\s*$/);
      if (!m) return;
      var v = dict[m[2]];
      if (typeof v === "string") el.setAttribute(m[1], v);
    });
    // Blok konten panjang (dokumen legal) memakai pasangan data-show-lang:
    // elemen dengan bahasa lain disembunyikan — pola render-dual / show-one
    // supaya dokumen legal tetap satu HTML tanpa fetch terpisah.
    document.querySelectorAll("[data-show-lang]").forEach(function (el) {
      el.hidden = el.getAttribute("data-show-lang") !== lang;
    });
    document.documentElement.lang = lang === "en" ? "en" : "id";
    document.querySelectorAll(".lang-switch button").forEach(function (b) {
      b.classList.toggle("active", b.getAttribute("data-lang") === lang);
    });
    try { localStorage.setItem("nyasar_lang", lang); } catch (e) { /* private mode */ }
    window.NYASAR_LANG = lang;
  }

  function initial() {
    var saved = null;
    try { saved = localStorage.getItem("nyasar_lang"); } catch (e) {}
    if (saved === "en" || saved === "id") return saved;
    var nav = (navigator.language || "id").toLowerCase();
    return nav.indexOf("en") === 0 ? "en" : "id"; // default: bahasa Indonesia
  }

  window.nyasarApplyLang = apply;
  window.nyasarInitialLang = initial;
  document.addEventListener("DOMContentLoaded", function () { apply(initial()); });
})();
