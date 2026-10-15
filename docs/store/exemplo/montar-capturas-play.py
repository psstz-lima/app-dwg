# Frames the raw tablet and phone captures (docs/store/capturas-brutas) with a pt-BR caption for Google Play.
import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter
RAW, FONT, OUT = sys.argv[1:4]
tablet = [
 ("r11_corredor_dark", "Projeto de estrada com mapa de corte e aterro"),
 ("r2_info_eng", "Toque e veja estaca, afastamento, greide e corte"),
 ("extra_sugestoes", "O app sugere o que fazer com os seus arquivos"),
 ("n1_3d", "Superfícies e projeto em 3D, direto no campo"),
 ("r4_perfil_eng", "Perfil longitudinal com greide e curvas verticais"),
 ("r5_secao_dark", "Seção com terreno e projeto em qualquer estaca"),
 ("extra_exportar", "Exporte em DXF, LandXML, KML, GeoJSON e mais"),
 ("r7_full_sun", "Tela cheia e alto contraste para sol forte"),
 ("n6_curvas", "Curvas de nível automáticas"),
 ("r6_temas_dark", "Temas para escritório, campo e sol forte"),
]
phone = [
 ("cel_info", "Estaca, afastamento e corte com um toque"),
 ("cel_3d", "Superfície em 3D no celular"),
 ("cel_perfil", "Perfil com greide e curvas verticais"),
 ("cel_secao", "Seção em qualquer estaca"),
]
STATUS_BAR = 70  # Android status bar on the raw captures (absent in full-screen mode)

def background(W, H):
    bg = Image.new("RGB", (W, H)); d = ImageDraw.Draw(bg)
    for y in range(H):
        t = y / H; d.line([(0, y), (W, y)], fill=(int(17 + 12 * t), int(24 + 20 * t), int(28 + 22 * t)))
    return bg

def frame(shot, W, H, band, radius, size, lines_max_w, out):
    font = ImageFont.truetype(FONT, size)
    h = H - band - 50; w = round(shot.width * h / shot.height)
    if w > W - 80: w = W - 80; h = round(shot.height * w / shot.width)
    shot = shot.resize((w, h), Image.LANCZOS)
    bg = background(W, H); d = ImageDraw.Draw(bg)
    x, y = (W - w) // 2, band
    sh = Image.new("L", (W, H), 0); ImageDraw.Draw(sh).rounded_rectangle((x, y + 12, x + w, y + h + 12), radius, fill=150)
    bg.paste((0, 0, 0), (0, 0), sh.filter(ImageFilter.GaussianBlur(24)))
    m = Image.new("L", (w, h), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, w - 1, h - 1), radius, fill=255)
    bg.paste(shot, (x, y), m)
    return bg, d, font

def wrap(d, text, font, maxw):
    lines, cur = [], ""
    for word in text.split():
        t = (cur + " " + word).strip()
        if d.textlength(t, font=font) > maxw and cur: lines.append(cur); cur = word
        else: cur = t
    return lines + [cur]

for i, (name, text) in enumerate(tablet, 1):
    raw = Image.open(f"{RAW}/{name}.png").convert("RGB")
    if "full" not in name: raw = raw.crop((0, STATUS_BAR, raw.width, raw.height))
    W, H, BAND = 2560, 1600, 220
    bg, d, font = frame(raw, W, H, BAND, 28, 76, W - 200, None)
    d.text(((W - d.textlength(text, font=font)) / 2, 62), text, font=font, fill=(238, 242, 244))
    d.rounded_rectangle(((W - 120) / 2, 168, (W + 120) / 2, 176), 4, fill=(242, 169, 59))
    sub = "" if i <= 8 else "extras/"
    bg.save(f"{OUT}/capturas-tablet/{sub}{i:02d}-{name}.png", optimize=True)

for i, (name, text) in enumerate(phone, 1):
    raw = Image.open(f"{RAW}/{name}.png").convert("RGB").crop((0, STATUS_BAR, 1080, 1920))
    W, H, BAND = 1080, 1920, 300
    bg, d, font = frame(raw, W, H, BAND, 36, 64, W - 120, None)
    lines = wrap(d, text, font, W - 120); y0 = 70 if len(lines) > 1 else 105
    for k, l in enumerate(lines):
        d.text(((W - d.textlength(l, font=font)) / 2, y0 + k * 82), l, font=font, fill=(238, 242, 244))
    d.rounded_rectangle(((W - 100) / 2, 250, (W + 100) / 2, 257), 4, fill=(242, 169, 59))
    bg.save(f"{OUT}/capturas-celular/{i:02d}-{name}.png", optimize=True)
