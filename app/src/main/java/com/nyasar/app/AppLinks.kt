package com.nyasar.app

/**
 * Web/app link endpoints — satu sumber kebenaran untuk domain landing +
 * App Links (konsep "app bisa dibuka pakai link", 2026-09).
 *
 * Domain `app.nyasarnyaman.my.id` di-host GitHub Pages (workflow
 * `pages-deploy.yaml`, sumber folder `web/`) berisi:
 *  - landing statis (index / privacy / terms)
 *  - `route/{id}` — landing ringkas per rute (browser tanpa app)
 *  - `/.well-known/assetlinks.json` — bukti kepemilikan app untuk
 *    autoVerify di AndroidManifest (isi SHA-256 release/debug cert).
 *
 * Link https://app.nyasarnyaman.my.id/route/{id}:
 *  - HP dengan app terpasang → intent-filter autoVerify membuka app
 *    langsung ke Route Detail (navDeepLink di MainActivity).
 *  - HP tanpa app → dibuka browser, landing route tampil.
 */
object AppLinks {
    const val BASE = "https://app.nyasarnyaman.my.id"

    /** App Link callback auth (auto-login setelah konfirmasi email,
     *  2026-09): tautan konfirmasi dari email sign-up di-redirect Supabase
     *  ke sini (AuthRepository.signUpWith redirectUrl). HP dengan app →
     *  Android membuka MainActivity (intent-filter /auth/callback) dan
     *  gotrue-kt meng-import sesi dari fragment URL → user langsung
     *  ter-login, gate username jalan seperti biasa. HP tanpa app →
     *  browser menampilkan web/auth/callback (fallback informatif).
     *  Path spesifik ini sengaja TIDAK menyentuh /auth/reset/ — tautan
     *  pemulihan kata sandi harus tetap terbuka di BROWSER (form reset
     *  ada di halaman web, bukan di app). */
    const val AUTH_CALLBACK = "$BASE/auth/callback"

    /** Deep link layar Route Detail (browse rute publik). Format
     *  query-param (`/route?id=…`) — hosting GitHub Pages statis melayani
     *  `web/route/index.html` untuk path `/route` apa pun query-nya, jadi
     *  tanpa perlu trik redirect 404 seperti path-segment `/route/{id}`. */
    fun routeLink(routeId: String): String = "$BASE/route?id=$routeId"
}
