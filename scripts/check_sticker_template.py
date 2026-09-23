#!/usr/bin/env python3
"""
Sanity checks for the 7th share template (Sticker — Strava card #7):
1. Template key "sticker" is registered in TEMPLATES and dispatch.
2. templateLabel covers it.
3. drawStickerTemplate exists and uses the 6-stat grid layout
   (Distance/Pace/Max Elev // Time/Elev Gain/Elev Loss).
4. ShareCardScreen's checkerboard list includes "sticker" (transparent bg).
"""
import re, sys

APP = "app/src/main/java/com/nyasar/app"

gen = open(f"{APP}/ui/share/ShareCardGenerator.kt", encoding="utf-8").read()
screen = open(f"{APP}/ui/share/ShareCardScreen.kt", encoding="utf-8").read()

errors = []

def check(cond, msg):
    if not cond:
        errors.append(msg)

# 1. Registered template
check('"sticker"' in gen, 'ShareCardGenerator: "sticker" key missing from TEMPLATES/when')
check(gen.count('"sticker"') >= 3, 'ShareCardGenerator: "sticker" must appear in TEMPLATES + when dispatch')
check('"sticker" -> drawStickerTemplate' in gen.replace("\n", " ") or
      re.search(r'"sticker"\s*->\s*drawStickerTemplate', gen),
      "dispatch: 'sticker' not wired to drawStickerTemplate")

# 2. Label
check(re.search(r'"sticker"\s*->\s*"Sticker"', gen), 'templateLabel: no "Sticker" label')

# 3. Draw function + grid content
check("private fun drawStickerTemplate(" in gen, "drawStickerTemplate function missing")
for needle in ["share_stat_max_elev", "share_stat_elev_gain", "share_stat_elev_loss"]:
    check(needle in gen, f"sticker template missing stat string {needle}")
# 6-stat layout: two rows x three columns via the shared drawGridRow loop
# (labels + values drawn inside forEachIndexed — count string references instead)
fn = gen[gen.index("private fun drawStickerTemplate("):gen.index("// ── Helpers ──")]
for needle in ["share_stat_distance", "share_stat_pace", "share_stat_time",
               "share_stat_elev_gain", "share_stat_elev_loss", "share_stat_max_elev"]:
    check(needle in fn, f"sticker fn missing {needle}")
check(fn.count("drawGridRow(") >= 2, "sticker: two grid rows not drawn")

# 4. Transparent hint in screen
check('"sticker"' in screen, "ShareCardScreen: sticker not in the transparent/checkerboard list")

# 5. Share-image fix: card PNG must be written under the FileProvider-declared
#    cache subpath ("exports/") — root cacheDir files are NOT shareable
#    (getUriForFile threw IllegalArgumentException → "Failed to open share menu").
check('File(context.cacheDir, "exports")' in screen,
      "ShareCardScreen.shareImage: PNG not written under cache/exports (FileProvider path fix missing)")

# 6. Balance sanity: braces and parens in the generator
for open_c, close_c in [("{", "}"), ("(", ")")]:
    diff = gen.count(open_c) - gen.count(close_c)
    check(diff == 0, f"ShareCardGenerator: unbalanced {open_c}{close_c} (diff={diff})")

if errors:
    print("FAIL")
    for e in errors:
        print(" -", e)
    sys.exit(1)
print("PASS: sticker template registered, 6-stat grid wired, screen updated, share-path fixed")
