#!/usr/bin/env python3
"""
Generates web/og-image.png (1200x630) — the social-share preview card used by
og:image / twitter:image on the landing, browse, and route pages.

Same pure-Python PNG encoder approach as scripts/gen_splash_logo.py (this
sandbox has no PIL/numpy): zlib + struct only. Drawn with simple filled
rects and a hard-edged mountain silhouette matching the landing hero's
"trail map" identity — forest green background, cream sky band, amber sun,
and the classic trail line (dark casing + amber core).

Regenerate after changing the design:  python3 scripts/gen_og_image.py
(then commit; workflow Pages deploys it automatically.)
"""
import zlib
import struct
import os

W, H = 1200, 630
OUT = os.path.join("web", "og-image.png")

# Palette — persis token web/style.css / app Color.kt
FOREST = (46, 83, 57)        # #2E5339
NYASAR = (90, 117, 98)       # #5A7562
CREAM = (251, 253, 249)      # #FBFDF9
SKY = (243, 247, 241)        # #F3F7F1
AMBER = (242, 169, 0)        # #F2A900
INK = (26, 28, 25)           # #1A1C19
SURFACE_TINT = (221, 229, 218)  # #DDE5DA


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def build_pixels():
    px = [[None] * W for _ in range(H)]

    # --- Background: vertical gradient SKY -> CREAM (atas 62% tinggi) ---
    sky_h = int(H * 0.62)
    for y in range(H):
        base = lerp(SKY, CREAM, y / sky_h) if y < sky_h else CREAM
        row = px[y]
        for x in range(W):
            row[x] = base

    # --- Matahari amber (lingkaran sederhana, kanan atas) ---
    sun_cx, sun_cy, sun_r = 960, 140, 70
    for y in range(max(0, sun_cy - sun_r), min(H, sun_cy + sun_r + 1)):
        dy = y - sun_cy
        span = int((sun_r * sun_r - dy * dy) ** 0.5) if abs(dy) <= sun_r else -1
        if span < 0:
            continue
        row = px[y]
        for x in range(max(0, sun_cx - span), min(W, sun_cx + span + 1)):
            row[x] = AMBER

    # --- Siluet gunung dua lapis (identitas hero landing) ---
    # Puncak digambar per kolom: tinggi garis punggung lalu isi ke bawah.
    def ridge(points, color):
        # points: list (x, y) patahan punggung gunung, x menaik.
        for i in range(len(points) - 1):
            x0, y0 = points[i]
            x1, y1 = points[i + 1]
            if x1 == x0:
                continue
            for x in range(max(0, x0), min(W, x1)):
                t = (x - x0) / (x1 - x0)
                top = int(y0 + (y1 - y0) * t)
                col_from = top if 0 <= x < W else H
                for y in range(max(0, col_from), H):
                    px[y][x] = color

    # Lapis belakang (lebih pudar)
    ridge([(0, 330), (170, 190), (300, 300), (430, 170), (560, 290),
           (700, 200), (860, 320), (1000, 210), (1200, 330)], lerp(SURFACE_TINT, NYASAR, 0.35))
    # Lapis depan (forest pekat)
    ridge([(0, 420), (140, 300), (270, 400), (400, 260), (530, 395),
           (660, 280), (820, 410), (980, 300), (1200, 430)], FOREST)

    # --- Jalur pendakian: casing gelap + garis amber (identitas peta web) ---
    trail = [(140, 560), (300, 480), (450, 500), (620, 420), (760, 440), (900, 350), (1040, 300)]

    def draw_polyline(points, radius, color):
        for i in range(len(points) - 1):
            x0, y0 = points[i]
            x1, y1 = points[i + 1]
            steps = max(abs(x1 - x0), abs(y1 - y0)) * 2 + 1
            for s in range(steps + 1):
                t = s / steps
                cx = x0 + (x1 - x0) * t
                cy = y0 + (y1 - y0) * t
                for yy in range(int(cy - radius), int(cy + radius) + 1):
                    if not (0 <= yy < H):
                        continue
                    dy2 = yy - cy
                    inner = radius * radius - dy2 * dy2
                    if inner < 0:
                        continue  # pembulatan int bisa melebihi radius
                    span2 = inner ** 0.5
                    for xx in range(int(cx - span2), int(cx + span2) + 1):
                        if 0 <= xx < W:
                            px[yy][xx] = color

    draw_polyline(trail, 9, INK)      # casing gelap
    draw_polyline(trail, 4, AMBER)    # core amber

    # --- Kartu putih "badge" kiri-bawah dengan strip brand (tanpa teks:
    #     og:image tanpa font — brand dibawa bentuk & warna) ---
    card_x0, card_y0, card_x1, card_y1 = 90, 440, 560, 560
    for y in range(card_y0, card_y1 + 1):
        for x in range(card_x0, card_x1 + 1):
            px[y][x] = CREAM
    # Strip kiri kartu = gradasi forest->nyasar (brand-mark)
    for y in range(card_y0, card_y1 + 1):
        t = (y - card_y0) / (card_y1 - card_y0)
        c = lerp(FOREST, NYASAR, t)
        for x in range(card_x0, card_x0 + 18):
            px[y][x] = c
    # Tiga "baris teks" abstrak (blok abu) — struktur tanpa font.
    for (bx, by, bw, bh) in [
        (128, 462, 300, 16),
        (128, 492, 360, 12),
        (128, 514, 250, 12),
    ]:
        for y in range(by, by + bh):
            for x in range(bx, bx + bw):
                if x < card_x1 - 8:
                    px[y][x] = SURFACE_TINT

    return px


def write_png(path, px, w, h):
    raw = bytearray()
    for y in range(h):
        raw.append(0)  # filter: none
        row = px[y]
        for x in range(w):
            r, g, b = row[x]
            raw.extend((r, g, b))

    def chunk(ctype, payload):
        c = struct.pack(">I", len(payload)) + ctype + payload
        return c + struct.pack(">I", zlib.crc32(ctype + payload) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)  # 8-bit RGB
    body = chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + body)
    print(f"wrote {path}: {w}x{h}, {os.path.getsize(path)} bytes")


if __name__ == "__main__":
    write_png(OUT, build_pixels(), W, H)
