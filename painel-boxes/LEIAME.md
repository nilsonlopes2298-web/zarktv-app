# Painel das Boxes do ZarkTV (ZarkTV Cloud)

Aqui você vê todas as boxes que estão com a **ZarkTV Launcher**: de quem é cada uma, modelo,
Android, modo (dona / root / simples), versão da launcher, se está online e o sinal do Wi-Fi.
Também manda APK (para uma box ou para todas), banners, aviso em tela cheia e a lojinha da box.

- Painel: `https://painel.zarktvqx.cfd/boxes/admin`
- A launcher conversa com: `https://painel.zarktvqx.cfd/boxes/api/...`

## Box minha × box do cliente

- **Do cliente** (padrão de toda box nova): livre. Ele instala o que quiser, troca a tela inicial e nada é bloqueado.
  O aviso de vencimento aparece só dentro do app.
- **Minha** (você marca na página da box): controle total. A launcher fica fixa, não dá para desinstalar,
  só entra o que vem do painel, o "lixo" é escondido e (se ligar nas regras) bloqueia com o Pix quando vencer.

## Instalar na VPS (Roxinha)

1. Copie a pasta `painel-boxes` para a VPS (ou `git clone` deste repositório).
2. Na pasta, rode: `bash instalar.sh` (pede a senha do painel e deixa rodando sozinho).
3. No Caddy, dentro do bloco de `painel.zarktvqx.cfd`, **antes do resto**, coloque:

   ```
   handle /boxes* {
       request_body {
           max_size 300MB
       }
       reverse_proxy 127.0.0.1:3091
   }
   ```

   e rode `systemctl reload caddy`.
4. Abra `https://painel.zarktvqx.cfd/boxes/admin` e entre com a senha.

Os dados ficam em `/opt/zarktv-boxes/data` (boxes, APKs, imagens). Faça cópia dessa pasta de vez em quando.
