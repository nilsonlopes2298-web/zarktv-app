#!/usr/bin/env python3
"""Gera os ícones de cada app (Celular, TV, ZarkPlay e Universal) e das datas comemorativas.
Uso: python3 tools/icones/gerar_icones.py   (precisa do rsvg-convert)
Para uma data nova: crie o enfeite em ENFEITES e acrescente o nome em DATAS (e no IconeSazonal.kt)."""
import os, subprocess, importlib.util
AQUI = os.path.dirname(os.path.abspath(__file__))
RAIZ = os.path.abspath(os.path.join(AQUI, "..", ".."))
spec = importlib.util.spec_from_file_location("d", os.path.join(AQUI, "desenhos.py")); d = importlib.util.module_from_spec(spec); spec.loader.exec_module(d)
R, AM = "#E50914", "#FFD60A"
DATAS = ["padrao", "natal", "anonovo", "junina", "carnaval"]

def fundo_grad(c1, c2, luz=.30):
    return f'''<defs><linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{c1}"/><stop offset="1" stop-color="{c2}"/></linearGradient>
<radialGradient id="luz" cx=".5" cy="0" r=".9"><stop offset="0" stop-color="#fff" stop-opacity="{luz}"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient></defs>
<rect width="512" height="512" fill="url(#bg)"/><rect width="512" height="512" fill="url(#luz)"/>'''

def tela(c1, c2, pe):
    return f'''<defs><linearGradient id="tela" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{c1}"/><stop offset="1" stop-color="{c2}"/></linearGradient>
<filter id="tsh" x="-20%" y="-20%" width="140%" height="160%"><feDropShadow dx="0" dy="10" stdDeviation="14" flood-color="#000" flood-opacity=".35"/></filter></defs>
<g filter="url(#tsh)"><rect x="92" y="132" width="328" height="220" rx="44" fill="url(#tela)"/></g>
<rect x="204" y="372" width="104" height="20" rx="10" fill="{pe}"/>''' + d.play(256, 242, 0.62, "#fff", R, False, "t")

def mosaico():
    return '''<defs><linearGradient id="big" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#FF4D5A"/><stop offset="1" stop-color="#C70818"/></linearGradient>
<filter id="msh" x="-20%" y="-20%" width="140%" height="140%"><feDropShadow dx="0" dy="8" stdDeviation="10" flood-color="#000" flood-opacity=".3"/></filter></defs>
<g filter="url(#msh)"><rect x="100" y="108" width="184" height="296" rx="40" fill="url(#big)"/>
<rect x="300" y="108" width="112" height="140" rx="34" fill="#1A1A1F"/><rect x="300" y="264" width="112" height="140" rx="34" fill="#1A1A1F" fill-opacity=".55"/></g>''' + d.play(192, 256, 0.52, "#fff", R, False, "z")

# Mistura escolhida: Celular vermelho, TV escura com tela vermelha e pé amarelo, ZarkPlay amarelo.
APPS = {
    "mobile":   {"fundo": fundo_grad("#FF4D5A", "#D3091A"), "frente": d.play(256, 256, 1.0, "#fff", R, True, "c"), "pos": "celular"},
    "tv":       {"fundo": fundo_grad("#2B2B33", "#0A0A0D", .18), "frente": tela("#FF4D5A", "#C70818", AM), "pos": "tv"},
    "zarkplay": {"fundo": fundo_grad("#FFE45C", "#FFC300", .45), "frente": mosaico(), "pos": "zarkplay", "legenda": "versão XCIPTV"},
}
APPS["unico"] = dict(APPS["mobile"])  # Universal: mesmo visual do celular

def enfeite(data, pos):
    if data == "padrao": return ""
    if data in ("natal", "anonovo"): return d.enfeite(data, pos)
    x, y, rot = {"celular": (150, 92, -16), "tv": (318, 82, 16), "zarkplay": (118, 62, -14)}[pos]
    if data == "junina":  # chapéu de palha com fita vermelha e bandeirinhas
        return f'''<defs><linearGradient id="palha" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#F7D98B"/><stop offset="1" stop-color="#C9932F"/></linearGradient>
<filter id="hsh" x="-30%" y="-30%" width="160%" height="160%"><feDropShadow dx="0" dy="6" stdDeviation="7" flood-color="#000" flood-opacity=".45"/></filter></defs>
<g transform="translate({x - 10} {y + 6}) rotate({rot} 80 50)" filter="url(#hsh)">
<ellipse cx="80" cy="78" rx="86" ry="20" fill="url(#palha)"/>
<path d="M36 76 C38 30 58 12 80 12 C102 12 122 30 124 76 Z" fill="url(#palha)"/>
<rect x="38" y="58" width="84" height="14" fill="#E50914"/>
<path d="M50 30 L110 30 M44 46 L116 46" stroke="#B07A22" stroke-width="3" opacity=".6"/></g>
<g transform="translate(40 30)"><path d="M0 8 Q216 46 432 8" fill="none" stroke="#5C3A10" stroke-width="3"/>
<path d="M40 16 l26 4 -10 22z" fill="#2E7BFF"/><path d="M120 28 l26 2 -12 22z" fill="#FFD60A"/><path d="M200 32 l26 0 -13 22z" fill="#22C55E"/>
<path d="M280 28 l26 -2 -12 22z" fill="#FF4D5A"/><path d="M360 18 l26 -4 -14 22z" fill="#A855F7"/></g>'''
    # carnaval: máscara colorida e confetes
    return f'''<defs><linearGradient id="masc" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#A855F7"/><stop offset=".5" stop-color="#EC4899"/><stop offset="1" stop-color="#F59E0B"/></linearGradient>
<filter id="hsh" x="-30%" y="-30%" width="160%" height="160%"><feDropShadow dx="0" dy="6" stdDeviation="7" flood-color="#000" flood-opacity=".45"/></filter></defs>
<g transform="translate({x - 4} {y + 4}) rotate({rot} 70 40)" filter="url(#hsh)">
<path d="M4 30 C24 6 56 8 70 26 C84 8 116 6 136 30 C130 64 96 72 70 52 C44 72 10 64 4 30 Z" fill="url(#masc)"/>
<ellipse cx="40" cy="36" rx="15" ry="10" fill="#1A1A1F"/><ellipse cx="100" cy="36" rx="15" ry="10" fill="#1A1A1F"/>
<path d="M120 14 C140 -16 168 -6 160 18" fill="none" stroke="#22C55E" stroke-width="7" stroke-linecap="round"/></g>
<g><circle cx="{x + 190}" cy="{y + 20}" r="9" fill="#22C55E"/><circle cx="{x - 20}" cy="{y + 90}" r="8" fill="#2E7BFF"/>
<rect x="{x + 160}" y="{y + 80}" width="18" height="8" rx="4" fill="#FFD60A" transform="rotate(30 {x + 169} {y + 84})"/>
<rect x="{x + 40}" y="{y - 20}" width="18" height="8" rx="4" fill="#EC4899" transform="rotate(-25 {x + 49} {y - 16})"/></g>'''

def svg(corpo, w=512, h=512, vb="0 0 512 512"):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="{vb}">{corpo}</svg>'

def png(conteudo, saida, w, h):
    os.makedirs(os.path.dirname(saida), exist_ok=True)
    tmp = saida + ".svg"; open(tmp, "w").write(conteudo)
    subprocess.run(["rsvg-convert", "-w", str(w), "-h", str(h), tmp, "-o", saida], check=True); os.remove(tmp)

for sabor, a in APPS.items():
    res = os.path.join(RAIZ, "app", "src", sabor, "res")
    # camada de trás (igual em todas as datas)
    png(svg(a["fundo"]), f"{res}/drawable-nodpi/ic_fundo.png", 432, 432)
    for data in DATAS:
        arte = a["frente"] + enfeite(data, a["pos"])
        # camada da frente: a arte inteira cabe na área que nunca é cortada (72 dp de 108)
        png(svg(f'<g transform="translate(256 256) scale(0.70) translate(-256 -256)">{arte}</g>'), f"{res}/drawable-nodpi/ic_frente_{data}.png", 432, 432)
        nome = "ic_launcher" if data == "padrao" else f"ic_launcher_{data}"
        os.makedirs(f"{res}/mipmap-anydpi-v26", exist_ok=True)
        open(f"{res}/mipmap-anydpi-v26/{nome}.xml", "w").write(f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Gerado por tools/icones/gerar_icones.py — ícone "{data}". -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_fundo" />
    <foreground android:drawable="@drawable/ic_frente_{data}" />
</adaptive-icon>
''')
        # Android 7 e anteriores: quadrado arredondado pronto
        quadrado = f'<defs><clipPath id="m"><rect width="512" height="512" rx="116"/></clipPath></defs><g clip-path="url(#m)">{a["fundo"]}{arte}</g>'
        for pasta, t in (("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96), ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)):
            png(svg(quadrado), f"{res}/{pasta}/{nome}.png", t, t)
        # banner da TV (Fire Stick / Android TV)
        sub = a.get("legenda", "")
        ty = 205 if sub else 222
        ban = (f'<rect width="640" height="360" fill="#0B0B0D"/><svg x="52" y="78" width="204" height="204" viewBox="0 0 512 512">{quadrado}</svg>'
               f'<text x="290" y="{ty}" font-family="Montserrat" font-weight="800" font-size="86" fill="#fff">Zark<tspan fill="#E50914">TV</tspan></text>'
               + (f'<text x="294" y="252" font-family="Montserrat" font-weight="700" font-size="30" fill="#A3A3AD">{sub}</text>' if sub else ""))
        bnome = "banner" if data == "padrao" else f"banner_{data}"
        for pasta, w in (("drawable-xhdpi", 320), ("drawable-xxxhdpi", 640)):
            png(svg(ban, 640, 360, "0 0 640 360"), f"{res}/{pasta}/{bnome}.png", w, w * 9 // 16)
print("Ícones gerados:", ", ".join(APPS), "×", ", ".join(DATAS))
