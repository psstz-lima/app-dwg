import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter
S, F, OUT = sys.argv[1:4]
scenes = [
 ("ph2", "Estaca, afastamento e corte com um toque", "Station, offset and cut with one tap"),
 ("ph3", "Superfície em 3D no celular", "3D surface on your phone"),
 ("ph4", "Perfil com greide e curvas verticais", "Profile with grade and vertical curves"),
 ("ph5", "Seção em qualquer estaca", "Cross section at any station"),
]
W, H, BAND = 1080, 1920, 300
font = ImageFont.truetype(F + "/manrope_700.ttf", 64)
def wrap(d, text):
    words, lines, cur = text.split(), [], ""
    for w in words:
        t = (cur + " " + w).strip()
        if d.textlength(t, font=font) > W - 120 and cur: lines.append(cur); cur = w
        else: cur = t
    return lines + [cur]
for i, (name, pt, en) in enumerate(scenes, 1):
    shot = Image.open(f"{S}/store/{name}.png").convert("RGB").crop((0, 70, 1080, 1920))
    h = H - BAND - 60; w = round(shot.width * h / shot.height)
    shot = shot.resize((w, h), Image.LANCZOS)
    for lang, text in (("pt-BR", pt), ("en-US", en)):
        bg = Image.new("RGB", (W, H)); d = ImageDraw.Draw(bg)
        for y in range(H):
            t = y / H; d.line([(0, y), (W, y)], fill=(int(17 + 12 * t), int(24 + 20 * t), int(28 + 22 * t)))
        x, y = (W - w) // 2, BAND
        sh = Image.new("L", (W, H), 0); ImageDraw.Draw(sh).rounded_rectangle((x, y + 12, x + w, y + h + 12), 36, fill=150)
        bg.paste((0, 0, 0), (0, 0), sh.filter(ImageFilter.GaussianBlur(24)))
        m = Image.new("L", (w, h), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, w - 1, h - 1), 36, fill=255)
        bg.paste(shot, (x, y), m)
        lines = wrap(d, text); y0 = 70 if len(lines) > 1 else 105
        for k, l in enumerate(lines):
            d.text(((W - d.textlength(l, font=font)) / 2, y0 + k * 82), l, font=font, fill=(238, 242, 244))
        d.rounded_rectangle(((W - 100) / 2, 250, (W + 100) / 2, 257), 4, fill=(242, 169, 59))
        bg.save(f"{OUT}/{lang}/celular-{i:02d}.png", optimize=True)
