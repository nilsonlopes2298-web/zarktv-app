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

echo "==> 3/4 Serviço do checkout (porta 3090)"
if [ ! -f "$PASTA/.env" ]; then
  read -rsp "    Crie uma senha para o admin do checkout: " SENHA; echo
  printf 'ADMIN_SENHA=%s\nPORTA=3090\nURL_PUBLICA=https://%s\n' "$SENHA" "$DOMINIO" > "$PASTA/.env"
  chmod 600 "$PASTA/.env"
else
  echo "    Senha do admin já criada antes (mantida)."
fi
cp "$PASTA/zarktv-checkout.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now zarktv-checkout >/dev/null 2>&1
systemctl restart zarktv-checkout
sleep 2
if curl -fs -o /dev/null http://127.0.0.1:3090/checkout/api/planos; then
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
  echo "    Já estava ligado no Caddy."
else
  cp "$CADDYFILE" "$CADDYFILE.antes-checkout"
  # Bloco próprio só para /checkout: não mexe no bloco do painel que já existe.
  printf '\n# Checkout Pix do ZarkTV\n%s/checkout* {\n\treverse_proxy 127.0.0.1:3090\n}\n' "$DOMINIO" >> "$CADDYFILE"
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
