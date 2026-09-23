#!/usr/bin/env python3
"""
Generates the web favicons from the master launcher foreground PNG
(app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png) — the SAME
artwork the Android app icon uses, so the browser tab and the home-screen
icon read as one brand.

Outputs (committed):
  web/favicon.ico          16 + 32 + 48 px, PNG-in-ICO (all modern browsers)
  web/apple-touch-icon.png 180x180, opaque (iOS home screen; iOS fills
                           transparent areas black otherwise)

Reuses the pure-Python PNG codec from gen_splash_logo.py (no PIL/numpy in
this sandbox): decode the source scanlines, resample into the target size
with box-averaging (good downscale quality for tiny icons), composite the
brand backdrop color, encode PNG, and pack the ICO container by hand.

Run from repo root:  python3 scripts/gen_web_favicon.py
"""
import struct
import zlib

SRC = "app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png"
ICO_OUT = "web/favicon.ico"
APPLE_OUT = "web/apple-touch-icon.png"

# Adaptive-icon background — the pale cream/green disk the launcher artwork
# sits on (see ic_launcher_background.xml). Favicons sit on the same color so
# the tab icon matches the app icon's identity exactly.
BACKDROP = (0xE6, 0xEB, 0xE5)

# Apple touch icons are opaque; use the same backdrop.
APPLE_SIZE = 180
SIZES = [16, 32, 48]


# ---------------------------------------------------------------- decode PNG
def read_png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos = 8
    idat = bytearray()
    meta = None
    palette = None
    trns = None
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if ctype == b"IHDR":
            w, h, depth, ct = struct.unpack(">IIBB", chunk[:10])
            assert depth == 8, f"unsupported bit depth {depth}"
            meta = (w, h, ct)
        elif ctype == b"IDAT":
            idat.extend(chunk)
        elif ctype == b"PLTE":
            palette = chunk
        elif ctype == b"tRNS":
            trns = chunk
        elif ctype == b"IEND":
            break
        pos += 12 + length
    w, h, ct = meta
    nch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[ct]
    raw = zlib.decompress(bytes(idat))
    return w, h, ct, nch, unfilter(raw, w, h, nch), palette, trns


def unfilter(raw, w, h, nch):
    stride = 1 + w * nch
    prev = bytearray(w * nch)
    rows = []
    for y in range(h):
        f = raw[y * stride]
        line = bytearray(raw[y * stride + 1:(y + 1) * stride])
        if f == 1:
            for i in range(nch, len(line)):
                line[i] = (line[i] + line[i - nch]) & 0xFF
        elif f == 2:
            for i in range(len(line)):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif f == 3:
            for i in range(len(line)):
                a = line[i - nch] if i >= nch else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif f == 4:
            for i in range(len(line)):
                a = line[i - nch] if i >= nch else 0
                b = prev[i]
                c = prev[i - nch] if i >= nch else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        rows.append(line)
        prev = line
    return rows


def to_rgba(pixels, w, h, ct, nch, palette, trns):
    """Normalize any color type to a flat RGBA byte list."""
    out = bytearray(w * h * 4)
    if ct == 6:
        for y in range(h):
            row = pixels[y]
            for x in range(w):
                i = x * 4
                o = (y * w + x) * 4
                out[o:o + 4] = row[i:i + 4]
    elif ct == 2:
        for y in range(h):
            row = pixels[y]
            for x in range(w):
                i = x * 3
                o = (y * w + x) * 4
                out[o:o + 3] = row[i:i + 3]
                out[o + 3] = 255
    else:
        raise SystemExit(f"unsupported source color type {ct}")
    return out


# ---------------------------------------------------------------- box sample
def resample_rgba(src, sw, sh, tw, th):
    """Box-average downscale/upscale. Keeps artwork crisp at 16px because it
    averages ALL contributing source pixels (area sampling), not nearest."""
    out = bytearray(tw * th * 4)
    x_ratio = sw / tw
    y_ratio = sh / th
    for ty in range(th):
        sy0 = int(ty * y_ratio)
        sy1 = max(sy0 + 1, int((ty + 1) * y_ratio))
        sy1 = min(sy1, sh)
        for tx in range(tw):
            sx0 = int(tx * x_ratio)
            sx1 = max(sx0 + 1, int((tx + 1) * x_ratio))
            sx1 = min(sx1, sw)
            r = g = b = a = 0
            count = 0
            for sy in range(sy0, sy1):
                srow = sy * sw
                for sx in range(sx0, sx1):
                    si = (srow + sx) * 4
                    sa = src[si + 3]
                    # premultiply during averaging so thin antialiased strokes
                    # don't bleed dark halos into transparent areas
                    r += src[si] * sa
                    g += src[si + 1] * sa
                    b += src[si + 2] * sa
                    a += sa
                    count += 1
            if a == 0:
                oi = (ty * tw + tx) * 4
                out[oi:oi + 4] = (0, 0, 0, 0)
                continue
            oi = (ty * tw + tx) * 4
            # un-premultiply
            out[oi] = min(255, r // a)
            out[oi + 1] = min(255, g // a)
            out[oi + 2] = min(255, b // a)
            out[oi + 3] = a // count
    return out


def composite_backdrop(rgba, w, h, backdrop):
    """Flatten onto the brand backdrop (opaque result)."""
    out = bytearray(w * h * 3)
    br, bg_, bb = backdrop
    for i in range(w * h):
        si = i * 4
        a = rgba[si + 3] / 255.0
        out[i * 3] = int(rgba[si] * a + br * (1 - a))
        out[i * 3 + 1] = int(rgba[si + 1] * a + bg_ * (1 - a))
        out[i * 3 + 2] = int(rgba[si + 2] * a + bb * (1 - a))
    return out


# ---------------------------------------------------------------- encode PNG
def encode_png(w, h, rgb=None, rgba=None):
    """PNG encode from either RGB (no alpha) or RGBA rows, filter type 0."""
    if rgba is not None:
        nch = 4
        ct = 6
        raw = bytearray()
        for y in range(h):
            raw.append(0)
            raw.extend(rgba[y * w * 4:(y + 1) * w * 4])
    else:
        nch = 3
        ct = 2
        raw = bytearray()
        for y in range(h):
            raw.append(0)
            raw.extend(rgb[y * w * 3:(y + 1) * w * 3])

    def chunk(ctype, data):
        c = struct.pack(">I", len(data)) + ctype + data
        c += struct.pack(">I", zlib.crc32(ctype + data) & 0xFFFFFFFF)
        return c

    ihdr = struct.pack(">IIBBBBB", w, h, 8, ct, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))


# ---------------------------------------------------------------- ICO pack
def encode_ico(images):
    """PNG-in-ICO container. images = [(size, png_bytes), ...]"""
    count = len(images)
    header = struct.pack("<HHH", 0, 1, count)
    entries = b""
    offset = 6 + count * 16
    blobs = b""
    for size, png in images:
        entries += struct.pack("<BBBBHHII",
                               size if size < 256 else 0,
                               size if size < 256 else 0,
                               0, 0, 1, 32, len(png), offset)
        blobs += png
        offset += len(png)
    return header + entries + blobs


# ---------------------------------------------------------------- main
def main():
    w, h, ct, nch, pixels, palette, trns = read_png(SRC)
    src = to_rgba(pixels, w, h, ct, nch, palette, trns)

    # Crop to the painted artwork bbox so the favicon isn't mostly empty
    # margin (adaptive-icon foreground keeps the art in the center third).
    # Fixed geometry measured from the source (see gen_splash_logo.py notes):
    # art occupies x 78..352, y 73..358 on the 432x432 canvas.
    x0, y0, x1, y1 = 78, 73, 353, 359
    crop_w, crop_h = x1 - x0, y1 - y0
    cropped = bytearray(crop_w * crop_h * 4)
    for y in range(crop_h):
        si = ((y0 + y) * w + x0) * 4
        oi = y * crop_w * 4
        cropped[oi:oi + crop_w * 4] = src[si:si + crop_w * 4]

    # Favicons: keep transparency (tab bg varies), artwork fills the icon.
    ico_images = []
    for size in SIZES:
        scaled = resample_rgba(cropped, crop_w, crop_h, size, size)
        png = encode_png(size, size, rgba=scaled)
        ico_images.append((size, png))
    ico = encode_ico(ico_images)
    open(ICO_OUT, "wb").write(ico)
    print(f"{ICO_OUT}: {len(ico)} bytes ({', '.join(str(s) for s in SIZES)})")

    # Apple touch icon: opaque brand backdrop, square-fill.
    scaled = resample_rgba(cropped, crop_w, crop_h, APPLE_SIZE, APPLE_SIZE)
    flat = composite_backdrop(scaled, APPLE_SIZE, APPLE_SIZE, BACKDROP)
    apple = encode_png(APPLE_SIZE, APPLE_SIZE, rgb=flat)
    open(APPLE_OUT, "wb").write(apple)
    print(f"{APPLE_OUT}: {len(apple)} bytes ({APPLE_SIZE}x{APPLE_SIZE})")


if __name__ == "__main__":
    main()
