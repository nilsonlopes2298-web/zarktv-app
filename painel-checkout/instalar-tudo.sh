#!/bin/bash
# Checkout Pix do ZarkTV — instala tudo de uma vez na VPS (Roxinha). Rode como root:
#   curl -fsSL https://raw.githubusercontent.com/nilsonlopes2298-web/zarktv-app/mobile-ui/painel-checkout/instalar-tudo.sh -o /root/checkout.sh && bash /root/checkout.sh
set -e
BASE="https://raw.githubusercontent.com/nilsonlopes2298-web/zarktv-app/0474b9780c/painel-checkout"
DOMINIO="painel.zarktvqx.cfd"
PASTA=/opt/zarktv-checkout

echo "==> 1/4 Baixando o checkout"
mkdir -p "$PASTA"
curl -fsSL "$BASE/server.js" -o "$PASTA/server.js"
curl -fsSL "$BASE/zarktv-checkout.service" -o "$PASTA/zarktv-checkout.service"
if [ ! -d "$PASTA/node_modules/qrcode" ] && command -v npm >/dev/null; then
  echo "    Instalando o gerador de QR Code…"
  (cd "$PASTA" && npm install --silent --no-audit --no-fund qrcode@1 >/dev/null 2>&1) || echo "    (não deu para instalar o QR Code; a página mostra só o Copia e Cola)"
fi

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
TESTE_IP=$(grep -s "^ENDERECO=" "$PASTA/.env" | cut -d= -f2); TESTE_IP=${TESTE_IP:-127.0.0.1}
if curl -fs -o /dev/null http://$TESTE_IP:$PORTA/checkout/api/planos; then
  echo "    Checkout rodando."
else
  echo "    ERRO: o checkout não respondeu. Últimas linhas do registro:"
  journalctl -u zarktv-checkout -n 20 --no-pager
  exit 1
fi

echo "==> 4/4 Ligando $DOMINIO/checkout no Caddy"
BLOCO_JA=0
if systemctl is-active --quiet caddy && [ -f /etc/caddy/Caddyfile ]; then
  # Caddy instalado direto no sistema
  CADDYFILE=/etc/caddy/Caddyfile
  ALVO="127.0.0.1:$PORTA"
  validar() { caddy validate --config "$CADDYFILE" --adapter caddyfile; }
  recarregar() { systemctl reload caddy; }
else
  # Caddy em Docker (o da Evolution em /opt/evolution-api)
  C=$(docker ps --format '{{.Names}} {{.Image}}' | awk 'tolower($0) ~ /caddy/ {print $1; exit}')
  [ -z "$C" ] && { echo "    ERRO: não achei o Caddy rodando (nem no sistema nem em Docker). Mande isto para o Claude."; exit 1; }
  CADDYFILE=""; DEST=""
  for linha in $(docker inspect -f '{{range .Mounts}}{{.Source}}|{{.Destination}} {{end}}' "$C"); do
    src=${linha%%|*}; dst=${linha##*|}
    if [ -f "$src" ] && [[ "$src" == *Caddyfile ]]; then CADDYFILE=$src; DEST=$dst; break; fi
    if [ -d "$src" ] && [ -f "$src/Caddyfile" ]; then CADDYFILE=$src/Caddyfile; DEST=$dst/Caddyfile; break; fi
  done
  [ -z "$CADDYFILE" ] && { echo "    ERRO: não achei o Caddyfile do contêiner $C. Mande isto para o Claude."; exit 1; }
  echo "    Caddy em Docker: contêiner $C, arquivo $CADDYFILE"
  REDE=$(docker inspect -f '{{.HostConfig.NetworkMode}}' "$C")
  if [ "$REDE" = "host" ]; then
    ALVO="127.0.0.1:$PORTA"
  else
    # O contêiner chega na VPS pelo "portão" da rede do Docker; o checkout escuta só nesse endereço (não fica aberto na internet).
    GW=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.Gateway}} {{end}}' "$C" | awk '{print $1}')
    [ -z "$GW" ] && { echo "    ERRO: não achei a rede do contêiner. Mande isto para o Claude."; exit 1; }
    if grep -q '^ENDERECO=' "$PASTA/.env"; then sed -i "s/^ENDERECO=.*/ENDERECO=$GW/" "$PASTA/.env"; else echo "ENDERECO=$GW" >> "$PASTA/.env"; fi
    systemctl restart zarktv-checkout; sleep 2
    curl -fs -o /dev/null "http://$GW:$PORTA/checkout/api/planos" || { echo "    ERRO: o checkout não respondeu em $GW:$PORTA."; journalctl -u zarktv-checkout -n 10 --no-pager; exit 1; }
    if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
      ufw allow from 172.16.0.0/12 to "$GW" port "$PORTA" proto tcp >/dev/null && echo "    Firewall liberado só para o Docker."
    fi
    ALVO="$GW:$PORTA"
  fi
  validar() { docker exec "$C" caddy validate --config "$DEST" --adapter caddyfile; }
  recarregar() { docker exec "$C" caddy reload --config "$DEST" --adapter caddyfile; }
fi
echo "    O Caddy vai mandar /checkout para $ALVO"
if grep -q "$DOMINIO/checkout" "$CADDYFILE"; then
  echo "    O endereço já estava no Caddy; não mexi."
else
  cp "$CADDYFILE" "$CADDYFILE.antes-checkout-$(date +%Y%m%d%H%M)"
  ANTES=$(cat "$CADDYFILE")
  # Bloco próprio só para /checkout: não mexe no bloco do painel que já existe.
  printf '\n# Checkout Pix do ZarkTV\n%s/checkout* {\n\treverse_proxy %s\n}\n' "$DOMINIO" "$ALVO" >> "$CADDYFILE"
  if validar >/dev/null 2>&1 && recarregar >/dev/null 2>&1; then
    echo "    Caddy recarregado."
  else
    printf '%s\n' "$ANTES" > "$CADDYFILE"   # volta como estava (sem trocar o arquivo, para o Docker continuar vendo)
    echo "    ERRO: o Caddy não aceitou a mudança; voltei o arquivo como estava. Nada foi alterado."
    validar 2>&1 | tail -5
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
