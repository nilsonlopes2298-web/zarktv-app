#!/bin/bash
# Instala o checkout Pix do ZarkTV na VPS (Ubuntu). Rode como root:  bash instalar.sh
set -e
echo "==> Instalando o checkout Pix do ZarkTV em /opt/zarktv-checkout"
command -v node >/dev/null || { echo "==> Instalando o Node.js"; curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && apt-get install -y nodejs; }
mkdir -p /opt/zarktv-checkout
cp server.js zarktv-checkout.service /opt/zarktv-checkout/
if [ ! -f /opt/zarktv-checkout/.env ]; then
  read -rsp "Crie a senha do admin do checkout: " SENHA; echo
  printf 'ADMIN_SENHA=%s\nPORTA=3090\nURL_PUBLICA=https://painel.zarktvqx.cfd\n' "$SENHA" > /opt/zarktv-checkout/.env
  chmod 600 /opt/zarktv-checkout/.env
fi
cp /opt/zarktv-checkout/zarktv-checkout.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now zarktv-checkout
systemctl restart zarktv-checkout
sleep 1
systemctl --no-pager status zarktv-checkout | head -5
echo
echo "==> Pronto. Agora ligue o endereço no Caddy (veja o LEIAME.md, passo 3)."
