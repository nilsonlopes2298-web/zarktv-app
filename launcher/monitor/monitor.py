"""
Monitor da VPS Roxinha para a tela inicial ZarkTV.

Só LEITURA: lê /proc e o disco do servidor (montados como somente leitura) e a
lista de containers do Docker. Não muda nada no servidor.

Endereços (todos em JSON):
  /k/<CHAVE>/metricas              processador, memória, disco e há quanto tempo está ligada
  /k/<CHAVE>/containers            todos os containers com situação
  /k/<CHAVE>/detalhes?busca=acai   métricas dos containers cujo nome contém "acai"
                                   (vários termos separados por vírgula)
Somente a biblioteca padrão do Python.
"""
import http.client
import json
import os
import socket
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

CHAVE = os.environ.get("CHAVE", "")
PROC = os.environ.get("PROC", "/host/proc")
RAIZ = os.environ.get("RAIZ", "/host/root")
SOQUETE = os.environ.get("DOCKER_SOCK", "/var/run/docker.sock")
PORTA = int(os.environ.get("PORTA", "9123"))


# ---------------------------------------------------------------- servidor (host)

def _cpu_tempos():
    with open(os.path.join(PROC, "stat")) as f:
        partes = [int(x) for x in f.readline().split()[1:]]
    ocioso = partes[3] + (partes[4] if len(partes) > 4 else 0)
    return sum(partes), ocioso


def uso_cpu():
    t1, o1 = _cpu_tempos()
    time.sleep(0.5)
    t2, o2 = _cpu_tempos()
    total, ocioso = t2 - t1, o2 - o1
    return round(100.0 * (total - ocioso) / total, 1) if total > 0 else 0.0


def memoria():
    info = {}
    with open(os.path.join(PROC, "meminfo")) as f:
        for linha in f:
            nome, valor = linha.split(":", 1)
            info[nome] = int(valor.split()[0])  # kB
    total = info.get("MemTotal", 0)
    livre = info.get("MemAvailable", info.get("MemFree", 0))
    gb = 1024 * 1024
    return round((total - livre) / gb, 2), round(total / gb, 2)


def disco():
    st = os.statvfs(RAIZ)
    total = st.f_blocks * st.f_frsize
    livre = st.f_bavail * st.f_frsize
    gb = 1024 ** 3
    return round((total - livre) / gb, 1), round(total / gb, 1)


def ligada_ha():
    with open(os.path.join(PROC, "uptime")) as f:
        seg = int(float(f.read().split()[0]))
    dias, resto = divmod(seg, 86400)
    horas, resto = divmod(resto, 3600)
    minutos = resto // 60
    if dias:
        return f"{dias} dia{'s' if dias > 1 else ''} e {horas} h"
    if horas:
        return f"{horas} h e {minutos} min"
    return f"{minutos} min"


def metricas():
    mu, mt = memoria()
    du, dt = disco()
    cont = containers()
    rodando = sum(1 for c in cont if c["situacao"] == "rodando")
    return {
        "cpu": uso_cpu(),
        "memoria_usada": mu,
        "memoria_total": mt,
        "disco_usado": du,
        "disco_total": dt,
        "ligada": ligada_ha(),
        "containers_rodando": rodando,
        "containers_total": len(cont),
    }


# ---------------------------------------------------------------- docker (só leitura)

class _ConexaoUnix(http.client.HTTPConnection):
    def __init__(self, caminho):
        super().__init__("localhost", timeout=8)
        self.caminho = caminho

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(8)
        self.sock.connect(self.caminho)


def docker_get(caminho):
    c = _ConexaoUnix(SOQUETE)
    try:
        c.request("GET", caminho)
        r = c.getresponse()
        return json.loads(r.read() or b"null")
    finally:
        c.close()


def containers():
    try:
        lista = docker_get("/containers/json?all=1")
    except Exception:
        return []
    saida = []
    for c in lista or []:
        nome = (c.get("Names") or ["?"])[0].lstrip("/")
        estado = c.get("State", "")
        saida.append({
            "id": c.get("Id", "")[:12],
            "nome": nome,
            "situacao": "rodando" if estado == "running" else ("parado" if estado == "exited" else estado),
            "status": c.get("Status", ""),
            "projeto": (c.get("Labels") or {}).get("com.docker.compose.project", ""),
        })
    saida.sort(key=lambda x: x["nome"])
    return saida


def stats_container(cid):
    try:
        s = docker_get(f"/containers/{cid}/stats?stream=false")
        mem = s.get("memory_stats", {})
        uso = mem.get("usage", 0) - (mem.get("stats", {}) or {}).get("inactive_file", 0)
        cpu = s.get("cpu_stats", {})
        pre = s.get("precpu_stats", {})
        dc = cpu.get("cpu_usage", {}).get("total_usage", 0) - pre.get("cpu_usage", {}).get("total_usage", 0)
        ds = cpu.get("system_cpu_usage", 0) - pre.get("system_cpu_usage", 0)
        ncpu = cpu.get("online_cpus") or 1
        pct = (dc / ds) * ncpu * 100.0 if ds > 0 else 0.0
        return round(max(uso, 0) / (1024 * 1024)), round(pct, 1)
    except Exception:
        return None, None


def detalhes(busca):
    termos = [t.strip().lower() for t in busca.split(",") if t.strip()]
    achados = [c for c in containers()
               if any(t in c["nome"].lower() or t in c["projeto"].lower() for t in termos)]
    itens = []
    if not achados:
        itens.append({"rotulo": "Containers", "valor": "nenhum encontrado",
                      "detalhe": "procurei por: " + ", ".join(termos)})
        return {"itens": itens}
    rodando = sum(1 for c in achados if c["situacao"] == "rodando")
    itens.append({"rotulo": "Containers rodando", "valor": f"{rodando} de {len(achados)}"})
    for c in achados[:8]:
        mem, cpu = stats_container(c["id"]) if c["situacao"] == "rodando" else (None, None)
        valor = c["situacao"]
        if mem is not None:
            valor = f"{mem} MB · {cpu}% CPU"
        itens.append({"rotulo": c["nome"], "valor": valor, "detalhe": c["status"]})
    return {"itens": itens}


# ---------------------------------------------------------------- HTTP

class Atendente(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _json(self, codigo, dados):
        corpo = json.dumps(dados, ensure_ascii=False).encode("utf-8")
        self.send_response(codigo)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(corpo)))
        self.end_headers()
        self.wfile.write(corpo)

    def do_GET(self):
        u = urlparse(self.path)
        partes = [p for p in u.path.split("/") if p]
        if len(partes) < 3 or partes[0] != "k" or not CHAVE or partes[1] != CHAVE:
            return self._json(404, {"erro": "não encontrado"})
        rota = partes[2]
        q = parse_qs(u.query)
        try:
            if rota == "metricas":
                return self._json(200, metricas())
            if rota == "containers":
                return self._json(200, {"containers": containers()})
            if rota == "detalhes":
                return self._json(200, detalhes(q.get("busca", [""])[0]))
        except Exception as e:  # nunca derruba o monitor
            return self._json(500, {"erro": str(e)})
        return self._json(404, {"erro": "rota desconhecida"})


if __name__ == "__main__":
    print(f"Monitor da Roxinha ouvindo na porta {PORTA}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORTA), Atendente).serve_forever()
