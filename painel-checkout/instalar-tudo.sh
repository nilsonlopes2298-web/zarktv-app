#!/bin/bash
# Checkout Pix do ZarkTV — instala tudo de uma vez na VPS (Roxinha). Rode como root:
#   curl -fsSL https://raw.githubusercontent.com/nilsonlopes2298-web/zarktv-app/mobile-ui/painel-checkout/instalar-tudo.sh -o /root/checkout.sh && bash /root/checkout.sh
set -e
BASE="https://raw.githubusercontent.com/nilsonlopes2298-web/zarktv-app/mobile-ui/painel-checkout"
DOMINIO="painel.zarktvqx.cfd"
PASTA=/opt/zarktv-checkout

echo "==> 1/4 Baixando o checkout"
mkdir -p "$PASTA"
curl -fsSL "$BASE/server.js" -o "$PASTA/server.js"
curl -fsSL "$BASE/zarktv-checkout.service" -o "$PASTA/zarktv-checkout.service"

echo "==> 2/4 Node.js"
if ! command -v node >/dev/null; then
  echo "    Instalando o Node.js 22…"
  curl -fsSL https://deb.nodesource.com/setup_22.x | bash - >/dev/null && apt-get install -y nodejs >/dev/null
fi
echo "    Node $(node -v)"

echo "==> 3/4 Serviço do checkout"
systemctl stop zarktv-checkout 2>/dev/null || true
sleep 1
# Procura uma porta livre (a 3090 pode já estar sendo usada por outro sistema da VPS).
PORTA=""
for p in 3090 3091 3092 3093 3094 3095 3096 3097 3098 3099; do
  if ! ss -ltn "( sport = :$p )" | grep -q ":$p"; then PORTA=$p; break; fi
  echo "    Porta $p ocupada por: $(ss -ltnp "( sport = :$p )" | grep -o 'users:(("[^"]*' | head -1 | cut -d'"' -f2)"
done
[ -z "$PORTA" ] && { echo "    ERRO: nenhuma porta livre entre 3090 e 3099."; exit 1; }
echo "    Usando a porta $PORTA"
if [ ! -f "$PASTA/.env" ] || ! grep -q '^ADMIN_SENHA=.' "$PASTA/.env"; then
  read -rsp "    Crie uma senha para o admin do checkout: " SENHA; echo
  printf 'ADMIN_SENHA=%s\nPORTA=%s\nURL_PUBLICA=https://%s\n' "$SENHA" "$PORTA" "$DOMINIO" > "$PASTA/.env"
  chmod 600 "$PASTA/.env"
else
  echo "    Senha do admin já criada antes (mantida)."
  sed -i "s/^PORTA=.*/PORTA=$PORTA/" "$PASTA/.env"
fi
cp "$PASTA/zarktv-checkout.service" /etc/systemd/system/
systemctl daemon-reload
systemctl reset-failed zarktv-checkout 2>/dev/null || true
systemctl enable zarktv-checkout >/dev/null 2>&1
systemctl restart zarktv-checkout
sleep 2
if curl -fs -o /dev/null http://127.0.0.1:$PORTA/checkout/api/planos; then
  echo "    Checkout rodando."
else
  echo "    ERRO: o checkout não respondeu. Últimas linhas do registro:"
  journalctl -u zarktv-checkout -n 20 --no-pager
  exit 1
fi

echo "==> 4/4 Ligando $DOMINIO/checkout no Caddy"
CADDYFILE=/etc/caddy/Caddyfile
if ! systemctl is-active --quiet caddy || [ ! -f "$CADDYFILE" ]; then
  echo "    Não achei o Caddy instalado direto no sistema (pode estar em Docker)."
  echo "    Arquivos Caddyfile que citam $DOMINIO:"
  grep -rl "$DOMINIO" /etc /opt /root --include=Caddyfile* 2>/dev/null || echo "    (nenhum)"
  echo "    Mande este resultado para o Claude que ele ajusta."
  exit 1
fi
if grep -q "$DOMINIO/checkout" "$CADDYFILE"; then
  cp "$CADDYFILE" "$CADDYFILE.antes-checkout"
  # Já ligado: só acerta a porta.
  sed -i "/$DOMINIO\/checkout/,/}/ s#reverse_proxy 127.0.0.1:[0-9]*#reverse_proxy 127.0.0.1:$PORTA#" "$CADDYFILE"
  caddy validate --config "$CADDYFILE" --adapter caddyfile >/dev/null 2>&1 && systemctl reload caddy && echo "    Já estava no Caddy (porta conferida)." || { cp "$CADDYFILE.antes-checkout" "$CADDYFILE"; echo "    ERRO ao ajustar o Caddy; voltei como estava."; exit 1; }
else
  cp "$CADDYFILE" "$CADDYFILE.antes-checkout"
  # Bloco próprio só para /checkout: não mexe no bloco do painel que já existe.
  printf '\n# Checkout Pix do ZarkTV\n%s/checkout* {\n\treverse_proxy 127.0.0.1:%s\n}\n' "$DOMINIO" "$PORTA" >> "$CADDYFILE"
  if caddy validate --config "$CADDYFILE" --adapter caddyfile >/dev/null 2>&1; then
    systemctl reload caddy
    echo "    Caddy recarregado (cópia do arquivo antigo em $CADDYFILE.antes-checkout)."
  else
    cp "$CADDYFILE.antes-checkout" "$CADDYFILE"
    echo "    ERRO: o Caddy não aceitou a mudança; voltei o arquivo como estava. Nada foi alterado."
    caddy validate --config "$CADDYFILE" --adapter caddyfile 2>&1 | tail -5
    exit 1
  fi
fi

sleep 2
CODIGO=$(curl -s -o /dev/null -w "%{http_code}" "https://$DOMINIO/checkout/api/planos")
echo
if [ "$CODIGO" = "200" ]; then
  echo "PRONTO! Checkout no ar."
  echo "  Admin:   https://$DOMINIO/checkout/admin"
  echo "  Cliente: https://$DOMINIO/checkout?usuario=NOME&servidor=Club"
  echo "Agora entre no admin e coloque o Access Token do Mercado Pago."
else
  echo "O checkout está rodando, mas pelo endereço público respondeu $CODIGO. Mande isto para o Claude."
fi
