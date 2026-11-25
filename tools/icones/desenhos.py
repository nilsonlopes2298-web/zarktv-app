# Ícones modernos ZarkTV (512x512): fundo (camada de trás) + símbolo (camada da frente) + enfeite da data.
import subprocess, os
R="#E50914"
def play(cx=256, cy=256, s=1.0, cor="#fff", z=R, sombra=True, uid="a"):
    # play arredondado com Z, centrado em (cx,cy); s=escala
    t=f'translate({cx-256*s} {cy-256*s}) scale({s})'
    sh=f'<filter id="sh{uid}" x="-30%" y="-30%" width="160%" height="160%"><feDropShadow dx="0" dy="10" stdDeviation="14" flood-color="#000" flood-opacity=".35"/></filter>' if sombra else ""
    return f'''<defs>{sh}<linearGradient id="pl{uid}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="{cor}"/><stop offset="1" stop-color="{cor}" stop-opacity=".92"/></linearGradient></defs>
<g transform="{t}" {'filter="url(#sh'+uid+')"' if sombra else ''}>
<path d="M178 132 C178 108 202 94 223 106 L382 230 C401 242 401 270 382 282 L223 406 C202 418 178 404 178 380 Z" fill="url(#pl{uid})"/>
<path d="M214 203 L300 203 L214 309 L300 309" fill="none" stroke="{z}" stroke-width="30" stroke-linecap="round" stroke-linejoin="round"/></g>'''

def fundo(app):
    if app=="celular":
        return '''<defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FF4655"/><stop offset=".55" stop-color="#E50914"/><stop offset="1" stop-color="#8B0A16"/></linearGradient>
<radialGradient id="luz" cx=".2" cy=".08" r=".75"><stop offset="0" stop-color="#fff" stop-opacity=".38"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient></defs>
<rect width="512" height="512" fill="url(#bg)"/><rect width="512" height="512" fill="url(#luz)"/>'''
    if app=="tv":
        return '''<defs><linearGradient id="bg" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#2A2A31"/><stop offset="1" stop-color="#060608"/></linearGradient>
<radialGradient id="brilho" cx=".5" cy=".62" r=".55"><stop offset="0" stop-color="#E50914" stop-opacity=".55"/><stop offset="1" stop-color="#E50914" stop-opacity="0"/></radialGradient></defs>
<rect width="512" height="512" fill="url(#bg)"/><rect width="512" height="512" fill="url(#brilho)"/>'''
    return '''<defs><linearGradient id="bg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#34343D"/><stop offset="1" stop-color="#0D0D11"/></linearGradient>
<radialGradient id="luz" cx=".15" cy=".05" r=".8"><stop offset="0" stop-color="#fff" stop-opacity=".16"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient></defs>
<rect width="512" height="512" fill="url(#bg)"/><rect width="512" height="512" fill="url(#luz)"/>'''

def simbolo(app):
    if app=="celular":
        return play(256,256,1.0,"#fff",R,True,"c")
    if app=="tv":
        # tela vermelha moderna (degradê) com reflexo, play branco dentro e pezinho
        return '''<defs><linearGradient id="tela" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FF3B4A"/><stop offset="1" stop-color="#A80714"/></linearGradient>
<filter id="tsh" x="-20%" y="-20%" width="140%" height="160%"><feDropShadow dx="0" dy="12" stdDeviation="16" flood-color="#000" flood-opacity=".5"/></filter>
<linearGradient id="refl" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity=".28"/><stop offset=".5" stop-color="#fff" stop-opacity="0"/></linearGradient></defs>
<g filter="url(#tsh)"><rect x="100" y="138" width="312" height="208" rx="38" fill="url(#tela)"/></g>
<rect x="100" y="138" width="312" height="104" rx="38" fill="url(#refl)"/>
<rect x="206" y="364" width="100" height="18" rx="9" fill="#E50914"/>''' + play(256,242,0.58,"#fff",R,False,"t")
    # zarkplay: mosaico moderno — bloco grande vermelho + dois blocos de vidro
    return '''<defs><linearGradient id="big" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FF4655"/><stop offset="1" stop-color="#B00818"/></linearGradient>
<filter id="msh" x="-20%" y="-20%" width="140%" height="140%"><feDropShadow dx="0" dy="10" stdDeviation="12" flood-color="#000" flood-opacity=".45"/></filter></defs>
<g filter="url(#msh)"><rect x="104" y="112" width="176" height="288" rx="34" fill="url(#big)"/>
<rect x="296" y="112" width="112" height="136" rx="30" fill="#fff"/>
<rect x="296" y="264" width="112" height="136" rx="30" fill="#fff" fill-opacity=".55"/></g>''' + play(192,256,0.5,"#fff",R,False,"z")

def enfeite(tipo, app):
    # posição: em cima do símbolo, dentro da zona segura
    pos={"celular":(150,92,-16),"tv":(318,82,16),"zarkplay":(118,62,-14)}[app]
    x,y,rot=pos
    if tipo=="natal":
        return f'''<defs><linearGradient id="gorro" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FF3B4A"/><stop offset="1" stop-color="#9E0A15"/></linearGradient>
<filter id="hsh" x="-30%" y="-30%" width="160%" height="160%"><feDropShadow dx="0" dy="6" stdDeviation="7" flood-color="#000" flood-opacity=".45"/></filter></defs>
<g transform="translate({x} {y}) rotate({rot} 70 60)" filter="url(#hsh)">
<path d="M6 84 C14 30 60 0 108 14 C132 22 146 44 150 66 C132 56 112 58 98 72 L118 84 Z" fill="url(#gorro)"/>
<rect x="-6" y="74" width="136" height="30" rx="15" fill="#fff"/>
<circle cx="150" cy="66" r="19" fill="#fff"/></g>'''
    return f'''<defs><linearGradient id="ouro" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FFF0A6"/><stop offset=".5" stop-color="#F5B82E"/><stop offset="1" stop-color="#B8740A"/></linearGradient>
<filter id="hsh" x="-30%" y="-30%" width="160%" height="160%"><feDropShadow dx="0" dy="6" stdDeviation="7" flood-color="#000" flood-opacity=".45"/></filter></defs>
<g transform="translate({x} {y-10}) rotate({rot} 60 60)" filter="url(#hsh)">
<path d="M60 0 L112 104 Q60 120 8 104 Z" fill="url(#ouro)"/>
<path d="M42 40 L82 52 M26 74 L100 90" stroke="#FFF6C8" stroke-width="9" stroke-linecap="round" opacity=".8"/>
<circle cx="60" cy="2" r="13" fill="#FFF2B2"/></g>
<g fill="#FFD452"><path d="M{x+150} {y+6} l6 14 14 6 -14 6 -6 14 -6 -14 -14 -6 14 -6z"/><circle cx="{x-14}" cy="{y+40}" r="7"/><circle cx="{x+176}" cy="{y+70}" r="6" fill="#FFF0A6"/></g>'''

def icone(app, tipo="normal", mascara="squircle"):
    clip={"squircle":'<clipPath id="m"><rect width="512" height="512" rx="116"/></clipPath>',"circulo":'<clipPath id="m"><circle cx="256" cy="256" r="256"/></clipPath>'}[mascara]
    corpo=fundo(app)+simbolo(app)+("" if tipo=="normal" else enfeite(tipo,app))
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="0 0 512 512"><defs>{clip}</defs><g clip-path="url(#m)">{corpo}</g></svg>'

if __name__=="__main__":
    os.makedirs("png",exist_ok=True)
    for app in ("celular","tv","zarkplay"):
        for tipo in ("normal","natal","anonovo"):
            open(f"{app}-{tipo}.svg","w").write(icone(app,tipo))
            subprocess.run(["rsvg-convert","-w","220","-h","220",f"{app}-{tipo}.svg","-o",f"png/{app}-{tipo}.png"],check=True)
            subprocess.run(["rsvg-convert","-w","48","-h","48",f"{app}-{tipo}.svg","-o",f"png/{app}-{tipo}-48.png"],check=True)
        open(f"{app}-circ.svg","w").write(icone(app,"normal","circulo"))
        subprocess.run(["rsvg-convert","-w","220","-h","220",f"{app}-circ.svg","-o",f"png/{app}-circ.png"],check=True)
    print("ok")
