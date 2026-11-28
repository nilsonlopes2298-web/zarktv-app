#!/bin/bash
# Instala o painel das boxes (ZarkTV Launcher) na VPS. Rode como root:  bash instalar.sh
set -e
echo "==> Instalando o painel das boxes em /opt/zarktv-boxes"
command -v node >/dev/null || { echo "==> Instalando o Node.js"; curl -fsSL https://deb.nodesource.com/setup_22.x | bash - && apt-get install -y nodejs; }
mkdir -p /opt/zarktv-boxes
cp server.js zarktv-boxes.service /opt/zarktv-boxes/
if [ ! -f /opt/zarktv-boxes/.env ]; then
  read -rsp "Crie a senha do painel das boxes: " SENHA; echo
  printf 'ADMIN_SENHA=%s\nPORTA=3091\nURL_PUBLICA=https://painel.zarktvqx.cfd\n' "$SENHA" > /opt/zarktv-boxes/.env
  chmod 600 /opt/zarktv-boxes/.env
fi
cp /opt/zarktv-boxes/zarktv-boxes.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now zarktv-boxes
systemctl restart zarktv-boxes
sleep 1
systemctl --no-pager status zarktv-boxes | head -5
echo
echo "==> Pronto. Falta ligar o endereço no Caddy (veja o LEIAME.md, passo 3)."
