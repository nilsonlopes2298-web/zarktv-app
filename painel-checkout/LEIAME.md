# Checkout Pix do ZarkTV (ZarkTV Cloud)

Página onde o cliente paga a renovação no Pix. Funciona com **Mercado Pago** (QR Code na própria página)
ou **InfinitePay** (página de pagamento da InfinitePay). Você escolhe no admin.

- Cliente: `https://painel.zarktvqx.cfd/checkout?usuario=NOME&servidor=Club`
- Admin: `https://painel.zarktvqx.cfd/checkout/admin`

Quando o cliente gera o Pix você recebe no WhatsApp **"Novo pedido"**; quando ele paga, **"PIX PAGO"**
com usuário, servidor e plano. O pagamento é sempre conferido direto no Mercado Pago/InfinitePay
(não confia só no aviso). O cliente também pode receber a confirmação.

## Instalar na VPS (Roxinha)

1. Copie a pasta `painel-checkout` para a VPS (ou `git clone` deste repositório).
2. Na pasta, rode: `bash instalar.sh` (pede a senha do admin e deixa rodando sozinho, inclusive depois de reiniciar).
3. No Caddy, dentro do bloco de `painel.zarktvqx.cfd`, antes do resto, coloque:

   ```
   handle /checkout* {
       reverse_proxy 127.0.0.1:3090
   }
   ```

   e rode `systemctl reload caddy`.
4. Abra `https://painel.zarktvqx.cfd/checkout/admin`, entre com a senha e configure:
   - **Gateway**: Mercado Pago ou InfinitePay.
   - **Mercado Pago**: Access Token de produção (Mercado Pago → Seu negócio → Configurações → Credenciais).
   - **InfinitePay**: sua InfiniteTag (o nome de usuário do app, sem o `$`).
   - **Planos** (Mensal R$ 30 já vem pronto), **servidores** e **WhatsApp** (Evolution: URL, API key, instância e seu número).
5. Para o botão **Pagar/Renovar do app** abrir o checkout, coloque no `config.json` do webplay
   (`tv.zarktvqx.cfd/config.json`) o campo:

   ```json
   "checkout": "https://painel.zarktvqx.cfd/checkout"
   ```

   O app manda junto o usuário e o servidor do cliente.

## Renovação automática

No admin há o campo **URL de renovação automática**. Quando um Pix é pago, o checkout envia
`{ usuario, servidor, dias, pedido, valor }` para essa URL — é onde o ZarkPlay Gestor vai renovar o
cliente sozinho no painel do servidor. Enquanto não estiver ligado, o aviso de "PIX PAGO" chega no
seu WhatsApp para você renovar.
