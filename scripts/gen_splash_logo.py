#!/usr/bin/env python3
"""
Generates a hi-res full-canvas splash-screen icon from the master launcher
foreground PNG (app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png).

Why a generated PNG instead of reusing the launcher mipmap: the launcher
foreground follows adaptive-icon layout rules — the artwork occupies only the
center ~third of its 432x432 canvas (real painted bbox ~278x289 px) because the
launcher crops to the inner circle. Rendered full-canvas on the splash screen
(1080-class phones), that 278px-wide artwork is upscaled ~2.5x and looks soft.
This script decodes the xxxhdpi PNG and re-encodes it at 1152x1152 with a
bilinear resample, keeping the artwork at the EXACT same relative position and
size (full-canvas layout, same adaptive geometry) so the splash icon still
lands identically to the launcher icon — just sharp instead of blurry.

Pure Python (zlib + struct only) because this sandbox has no PIL/numpy.
Run from repo root:  python3 scripts/gen_splash_logo.py
"""
import zlib
import struct
import os

SRC = "app/src/main/res/mipmap-xxxhdpi/ic_launcher_foreground.png"
DST = "app/src/main/res/drawable-nodpi/splash_logo.png"
SIZE = 1152  # > 2x the ~480px the icon occupies on a 1080p-class splash


def read_png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos = 8
    width = height = None
    bit_depth = color_type = None
    idat = bytearray()
    palette = None
    trns = None
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        ctype = data[pos + 4:pos + 8]
        chunk = data[pos + 8:pos + 8 + length]
        if ctype == b"IHDR":
            width, height, bit_depth, color_type = struct.unpack(">IIBB", chunk[:10])
        elif ctype == b"IDAT":
            idat.extend(chunk)
        elif ctype == b"PLTE":
            palette = chunk
        elif ctype == b"tRNS":
            trns = chunk
        elif ctype == b"IEND":
            break
        pos += 12 + length
    raw = zlib.decompress(bytes(idat))
    n_ch = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color_type]
    return {
        "w": width, "h": height, "depth": bit_depth, "ct": color_type,
        "n_ch": n_ch, "raw": raw, "palette": palette, "trns": trns,
    }


def unfilter(png):
    """Reconstruct raw scanlines into RGBA pixels (supports 8-bit RGB/RGBA/
    gray/gray+alpha and palette types — enough for any sane launcher asset)."""
    w, h, n = png["w"], png["h"], png["n_ch"]
    ct = png["ct"]
    assert png["depth"] == 8, "only 8-bit depth supported"

    # Paletted input: expand via PLTE (+ optional tRNS) into RGBA lookups.
    pal = None
    if ct == 3:
        pal_rgb = [tuple(png["palette"][i * 3:i * 3 + 3]) for i in range(len(png["palette"]) // 3)]
        pal_a = list(png["trns"]) if png["trns"] else None
        pal = lambda i: (  # noqa: E731
            pal_rgb[i] + ((pal_a[i] if pal_a and i < len(pal_a) else 255),)
        )

    stride = w * n
    bpp = n  # bytes per pixel for filtering
    out = bytearray(w * h * 4)
    prev = bytearray(stride)
    pos = 0
    for y in range(h):
        ftype = png["raw"][pos]
        pos += 1
        line = bytearray(png["raw"][pos:pos + stride])
        pos += stride
        if ftype == 1:  # Sub
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 0xFF
        elif ftype == 2:  # Up
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 0xFF
        elif ftype == 3:  # Average
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 0xFF
        elif ftype == 4:  # Paeth
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        prev = line
        for x in range(w):
            o = (y * w + x) * 4
            if ct == 6:
                out[o:o + 4] = line[x * 4:x * 4 + 4]
            elif ct == 2:
                out[o:o + 3] = line[x * 3:x * 3 + 3]
                out[o + 3] = 255
            elif ct == 4:
                out[o:o + 2] = line[x * 2:x * 2 + 2]
                out[o + 2] = line[x * 2]
            elif ct == 0:
                v = line[x]
                out[o] = out[o + 1] = out[o + 2] = v
                out[o + 3] = 255
            elif ct == 3:
                r, g, b, a = pal(line[x])
                out[o] = r
                out[o + 1] = g
                out[o + 2] = b
                out[o + 3] = a
    return out, w, h


def bilinear_scale(pix, sw, sh, dw, dh):
    """Bilinear resample over premultiplied alpha to avoid halo/edge darkening
    on the transparent surround, then un-premultiply."""
    out = bytearray(dw * dh * 4)
    pre = bytearray(sw * sh * 4)
    for i in range(0, len(pix), 4):
        a = pix[i + 3]
        pre[i] = (pix[i] * a + 127) // 255
        pre[i + 1] = (pix[i + 1] * a + 127) // 255
        pre[i + 2] = (pix[i + 2] * a + 127) // 255
        pre[i + 3] = a
    x_ratio = sw / dw
    y_ratio = sh / dh
    for y in range(dh):
        sy = (y + 0.5) * y_ratio - 0.5
        y0 = max(0, int(sy))
        y1 = min(sh - 1, y0 + 1)
        fy = sy - y0
        ry0 = y0 * sw * 4
        ry1 = y1 * sw * 4
        ro = y * dw * 4
        for x in range(dw):
            sx = (x + 0.5) * x_ratio - 0.5
            x0 = max(0, int(sx))
            x1 = min(sw - 1, x0 + 1)
            fx = sx - x0
            i00 = ry0 + x0 * 4
            i01 = ry0 + x1 * 4
            i10 = ry1 + x0 * 4
            i11 = ry1 + x1 * 4
            for c in range(4):
                top = pre[i00 + c] * (1 - fx) + pre[i01 + c] * fx
                bot = pre[i10 + c] * (1 - fx) + pre[i11 + c] * fx
                v = top * (1 - fy) + bot * fy
                out[ro + x * 4 + c] = int(v + 0.5)
    # Un-premultiply.
    for i in range(0, len(out), 4):
        a = out[i + 3]
        if a not in (0, 255):
            out[i] = min(255, (out[i] * 255 + a // 2) // a)
            out[i + 1] = min(255, (out[i + 1] * 255 + a // 2) // a)
            out[i + 2] = min(255, (out[i + 2] * 255 + a // 2) // a)
    return out


def write_png(path, pix, w, h):
    def chunk(ctype, payload):
        c = struct.pack(">I", len(payload)) + ctype + payload
        return c + struct.pack(">I", zlib.crc32(ctype + payload) & 0xFFFFFFFF)

    raw = bytearray()
    stride = w * 4
    for y in range(h):
        raw.append(0)  # filter 0
        raw.extend(pix[y * stride:(y + 1) * stride])
    ihdr = struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0)
    body = chunk(b"IHDR", ihdr) + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b"")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "wb").write(b"\x89PNG\r\n\x1a\n" + body)
    print(f"wrote {path}: {w}x{h}, {os.path.getsize(path)} bytes")


if __name__ == "__main__":
    png = read_png(SRC)
    pix, w, h = unfilter(png)
    scaled = bilinear_scale(pix, w, h, SIZE, SIZE)
    write_png(DST, scaled, SIZE, SIZE)
