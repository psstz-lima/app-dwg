import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter
S, F, OUT = sys.argv[1:4]
scenes = [
 ("n1_3d", "Superfícies do Civil 3D em 3D, no campo", "Civil 3D surfaces in 3D, in the field"),
 ("r2_info_eng", "Toque e veja estaca, afastamento, greide e corte", "Tap for station, offset, grade and cut"),
 ("n3_plan", "LandXML e DXF abertos direto no tablet", "Open LandXML and DXF right on the tablet"),
 ("r4_perfil_eng", "Perfil longitudinal com greide e curvas verticais", "Profiles with grade line and vertical curves"),
 ("r5_secao_dark", "Seção transversal em qualquer estaca", "Cross sections at any station"),
 ("n6_curvas", "Curvas de nível automáticas", "Automatic contour lines"),
 ("r7_full_sun", "Tela cheia e alto contraste para sol forte", "Full screen and high contrast for bright sun"),
 ("r6_temas_dark", "Quatro temas: escuro, claro, terra e sol", "Four looks: dark, light, earth and sun"),
]
W, H, BAND = 2560, 1440, 220
font = ImageFont.truetype(F + "/manrope_700.ttf", 76)
for i, (name, pt, en) in enumerate(scenes, 1):
    shot = Image.open(f"{S}/store/{name}.png").convert("RGB")
    if "full" not in name: shot = shot.crop((0, 70, shot.width, shot.height))
    h = H - BAND - 50; w = round(shot.width * h / shot.height)
    shot = shot.resize((w, h), Image.LANCZOS)
    for lang, text in (("pt-BR", pt), ("en-US", en)):
        bg = Image.new("RGB", (W, H), (17, 24, 28))
        d = ImageDraw.Draw(bg)
        for y in range(H):  # soft vertical gradient
            t = y / H; d.line([(0, y), (W, y)], fill=(int(17 + 12 * t), int(24 + 20 * t), int(28 + 22 * t)))
        x = (W - w) // 2; y = BAND
        sh = Image.new("L", (W, H), 0); ImageDraw.Draw(sh).rounded_rectangle((x, y + 12, x + w, y + h + 12), 28, fill=150)
        bg.paste((0, 0, 0), (0, 0), sh.filter(ImageFilter.GaussianBlur(24)))
        m = Image.new("L", (w, h), 0); ImageDraw.Draw(m).rounded_rectangle((0, 0, w - 1, h - 1), 28, fill=255)
        bg.paste(shot, (x, y), m)
        tw = d.textlength(text, font=font)
        d.text(((W - tw) / 2, 62), text, font=font, fill=(242, 169, 59) if False else (238, 242, 244))
        d.rounded_rectangle(((W - 120) / 2, 168, (W + 120) / 2, 176), 4, fill=(242, 169, 59))
        bg.save(f"{OUT}/{lang}/{i:02d}-{name.split('_',1)[1]}.png", optimize=True)
