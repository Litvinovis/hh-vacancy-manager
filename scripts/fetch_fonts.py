import re, urllib.request, os, sys

CSS_URL = "https://fonts.googleapis.com/css2?family=Fraunces:opsz,wght@9..144,450;9..144,550;9..144,650&family=Nunito+Sans:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500&display=swap"
UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
OUT_FONTS = "/home/clawd/hh-vacancy-manager/backend/src/main/resources/static/fonts"
OUT_CSS = "/home/clawd/hh-vacancy-manager/backend/src/main/resources/static/css/fonts.css"
KEEP_SUBSETS = {"latin", "cyrillic", "cyrillic-ext"}

req = urllib.request.Request(CSS_URL, headers={"User-Agent": UA})
css = urllib.request.urlopen(req).read().decode()

os.makedirs(OUT_FONTS, exist_ok=True)
blocks = re.findall(r"/\* (\S+) \*/\s*(@font-face \{.*?\})", css, re.S)
out, seen = [], {}
for subset, block in blocks:
    if subset not in KEEP_SUBSETS:
        continue
    m = re.search(r"url\((https://fonts\.gstatic\.com/\S+?\.woff2)\)", block)
    if not m:
        continue
    url = m.group(1)
    fam = re.search(r"font-family: '([^']+)'", block).group(1).replace(" ", "")
    weight = re.search(r"font-weight: (\S+);", block).group(1).replace(" ", "-")
    fname = f"{fam}-{weight}-{subset}.woff2"
    if fname in seen and seen[fname] != url:
        fname = f"{fam}-{weight}-{subset}-{len(seen)}.woff2"
    seen[fname] = url
    data = urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": UA})).read()
    with open(os.path.join(OUT_FONTS, fname), "wb") as f:
        f.write(data)
    out.append(f"/* {subset} */\n" + block.replace(url, f"/fonts/{fname}"))
    print(f"{fname}: {len(data)//1024}KB")

header = "/* Self-hosted Google Fonts (latin + cyrillic subsets) — no external CDN dependency.\n   Regenerate with scripts/fetch_fonts.py if the font set changes. */\n\n"
with open(OUT_CSS, "w") as f:
    f.write(header + "\n".join(out) + "\n")
print("TOTAL blocks:", len(out))
