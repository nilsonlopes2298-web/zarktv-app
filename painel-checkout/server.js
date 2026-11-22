// ZarkTV Cloud — Checkout Pix (Mercado Pago ou InfinitePay)
// Sem dependências: só Node 18+. Dados em ./data (config.json e pedidos.json).
//
//   Cliente:  /checkout?usuario=joao&servidor=Club   -> escolhe o plano e paga no Pix
//   Admin:    /checkout/admin                         -> configura gateway, planos e WhatsApp; vê pedidos
//   Webhooks: /checkout/webhook/mercadopago  e  /checkout/webhook/infinitepay
//
// Variáveis de ambiente:
//   PORTA (padrão 3090), ADMIN_SENHA (obrigatória), URL_PUBLICA (ex.: https://painel.zarktvqx.cfd)

'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const PORTA = Number(process.env.PORTA || 3090);
const ADMIN_SENHA = process.env.ADMIN_SENHA || '';
const DADOS = path.join(__dirname, 'data');
const ARQ_CONFIG = path.join(DADOS, 'config.json');
const ARQ_PEDIDOS = path.join(DADOS, 'pedidos.json');
const SEGREDO_SESSAO = crypto.createHash('sha256').update('zarktv:' + ADMIN_SENHA).digest();

if (!ADMIN_SENHA) {
  console.error('ERRO: defina a variável ADMIN_SENHA (senha do admin do checkout).');
  process.exit(1);
}
fs.mkdirSync(DADOS, { recursive: true, mode: 0o700 });

// ---------- armazenamento ----------------------------------------------------------------

const CONFIG_PADRAO = {
  gateway: 'mercadopago', // 'mercadopago' ou 'infinitepay'
  urlPublica: process.env.URL_PUBLICA || 'https://painel.zarktvqx.cfd',
  mpToken: '',
  mpEmailPagador: 'cliente@zarktvqx.cfd',
  ipHandle: '',
  planos: [
    { id: 'mensal', nome: 'Mensal', dias: 30, valor: 30 },
    { id: 'trimestral', nome: 'Trimestral', dias: 90, valor: 80 },
  ],
  servidores: ['Club', 'Uniplay', 'Five', 'Live'],
  whats: { url: '', apikey: '', instancia: '', numeroAdmin: '558587890473', avisarCliente: true },
  suporte: 'https://wa.me/558587890473',
  renovacaoUrl: '', // opcional: POST para o ZarkPlay Gestor renovar sozinho quando pagar
};

function ler(arq, padrao) {
  try { return JSON.parse(fs.readFileSync(arq, 'utf8')); } catch { return padrao; }
}
function gravar(arq, dados) {
  const tmp = arq + '.tmp';
  fs.writeFileSync(tmp, JSON.stringify(dados, null, 2), { mode: 0o600 });
  fs.renameSync(tmp, arq);
}
const config = () => ({ ...CONFIG_PADRAO, ...ler(ARQ_CONFIG, {}) });
const pedidos = () => ler(ARQ_PEDIDOS, []);
function salvarPedido(p) {
  const lista = pedidos();
  const i = lista.findIndex((x) => x.id === p.id);
  if (i >= 0) lista[i] = p; else lista.unshift(p);
  gravar(ARQ_PEDIDOS, lista.slice(0, 5000));
}
const acharPedido = (id) => pedidos().find((p) => p.id === id);

// ---------- utilidades --------------------------------------------------------------------

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const brl = (v) => Number(v).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
const agora = () => new Date().toISOString();
const soDigitos = (s) => String(s || '').replace(/\D/g, '');

function lerCorpo(req, limite = 100_000) {
  return new Promise((ok, erro) => {
    let txt = '';
    req.on('data', (c) => { txt += c; if (txt.length > limite) { erro(new Error('corpo grande demais')); req.destroy(); } });
    req.on('end', () => {
      const tipo = req.headers['content-type'] || '';
      if (!txt) return ok({});
      if (tipo.includes('application/json')) { try { return ok(JSON.parse(txt)); } catch { return ok({}); } }
      ok(Object.fromEntries(new URLSearchParams(txt)));
    });
    req.on('error', erro);
  });
}
function enviar(res, status, corpo, tipo = 'text/html; charset=utf-8', extra = {}) {
  res.writeHead(status, {
    'Content-Type': tipo,
    'X-Content-Type-Options': 'nosniff',
    'Referrer-Policy': 'same-origin',
    ...extra,
  });
  res.end(corpo);
}
const json = (res, status, obj) => enviar(res, status, JSON.stringify(obj), 'application/json; charset=utf-8');

// Sessão do admin: cookie assinado (HMAC) com validade de 12 h.
function criarSessao() {
  const exp = Date.now() + 12 * 3600 * 1000;
  const sig = crypto.createHmac('sha256', SEGREDO_SESSAO).update(String(exp)).digest('hex');
  return `${exp}.${sig}`;
}
function sessaoValida(req) {
  const m = /(?:^|;\s*)zk_adm=([^;]+)/.exec(req.headers.cookie || '');
  if (!m) return false;
  const [exp, sig] = m[1].split('.');
  if (!exp || !sig || Number(exp) < Date.now()) return false;
  const certo = crypto.createHmac('sha256', SEGREDO_SESSAO).update(exp).digest('hex');
  return sig.length === certo.length && crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(certo));
}
function senhaConfere(s) {
  const a = crypto.createHash('sha256').update(String(s || '')).digest();
  const b = crypto.createHash('sha256').update(ADMIN_SENHA).digest();
  return crypto.timingSafeEqual(a, b);
}

// Limite simples: no máximo 10 pedidos por IP a cada 10 minutos.
const tentativas = new Map();
function limitar(ip) {
  const t = Date.now();
  const lista = (tentativas.get(ip) || []).filter((x) => t - x < 600_000);
  lista.push(t);
  tentativas.set(ip, lista);
  return lista.length > 10;
}
const ipDe = (req) => (req.headers['x-forwarded-for'] || '').split(',')[0].trim() || req.socket.remoteAddress;

// ---------- WhatsApp (Evolution API) ----------------------------------------------------

async function whats(numero, texto) {
  const w = config().whats;
  if (!w.url || !w.apikey || !w.instancia || !numero) return;
  try {
    await fetch(`${w.url.replace(/\/$/, '')}/message/sendText/${encodeURIComponent(w.instancia)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', apikey: w.apikey },
      body: JSON.stringify({ number: soDigitos(numero), text: texto }),
      signal: AbortSignal.timeout(10_000),
    });
  } catch (e) {
    console.error('WhatsApp não enviado:', e.message);
  }
}

// ---------- gateways -------------------------------------------------------------------

async function criarPixMercadoPago(cfg, p) {
  if (!cfg.mpToken) throw new Error('Mercado Pago sem token configurado no admin.');
  const r = await fetch('https://api.mercadopago.com/v1/payments', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${cfg.mpToken}`,
      'Content-Type': 'application/json',
      'X-Idempotency-Key': p.id,
    },
    body: JSON.stringify({
      transaction_amount: Number(p.valor),
      description: `ZarkTV ${p.planoNome} - ${p.usuario}`,
      payment_method_id: 'pix',
      external_reference: p.id,
      notification_url: `${cfg.urlPublica}/checkout/webhook/mercadopago`,
      date_of_expiration: new Date(Date.now() + 30 * 60 * 1000).toISOString().replace('Z', '-00:00'),
      payer: { email: cfg.mpEmailPagador || 'cliente@zarktvqx.cfd' },
    }),
    signal: AbortSignal.timeout(20_000),
  });
  const d = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error('Mercado Pago recusou: ' + (d.message || r.status));
  const t = d.point_of_interaction?.transaction_data || {};
  return { gatewayId: String(d.id), pixCopiaCola: t.qr_code, pixQrBase64: t.qr_code_base64, linkPagamento: t.ticket_url };
}

async function statusMercadoPago(cfg, gatewayId) {
  const r = await fetch(`https://api.mercadopago.com/v1/payments/${encodeURIComponent(gatewayId)}`, {
    headers: { Authorization: `Bearer ${cfg.mpToken}` },
    signal: AbortSignal.timeout(15_000),
  });
  const d = await r.json().catch(() => ({}));
  return { pago: d.status === 'approved', status: d.status, ref: d.external_reference };
}

async function criarLinkInfinitePay(cfg, p) {
  if (!cfg.ipHandle) throw new Error('InfinitePay sem InfiniteTag configurada no admin.');
  const r = await fetch('https://api.checkout.infinitepay.io/links', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      handle: cfg.ipHandle.replace(/^\$/, ''),
      order_nsu: p.id,
      redirect_url: `${cfg.urlPublica}/checkout/pedido/${p.id}`,
      webhook_url: `${cfg.urlPublica}/checkout/webhook/infinitepay`,
      items: [{ quantity: 1, price: Math.round(Number(p.valor) * 100), description: `ZarkTV ${p.planoNome} - ${p.usuario}` }],
    }),
    signal: AbortSignal.timeout(20_000),
  });
  const d = await r.json().catch(() => ({}));
  if (!r.ok || !d.url) throw new Error('InfinitePay recusou: ' + (d.message || r.status));
  return { linkPagamento: d.url };
}

async function conferirInfinitePay(cfg, dados) {
  try {
    const r = await fetch('https://api.checkout.infinitepay.io/payment_check', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        handle: cfg.ipHandle.replace(/^\$/, ''),
        order_nsu: dados.order_nsu,
        transaction_nsu: dados.transaction_nsu,
        slug: dados.slug || dados.invoice_slug,
      }),
      signal: AbortSignal.timeout(15_000),
    });
    const d = await r.json().catch(() => ({}));
    return d.paid === true;
  } catch {
    return null; // não deu para conferir
  }
}

// ---------- pedido pago ----------------------------------------------------------------

async function marcarPago(p, origem) {
  if (p.status === 'pago') return;
  p.status = 'pago';
  p.pagoEm = agora();
  p.origemPagamento = origem;
  salvarPedido(p);
  const cfg = config();
  await whats(cfg.whats.numeroAdmin,
    `✅ *PIX PAGO* — ZarkTV\n👤 Usuário: ${p.usuario}\n🖥️ Servidor: ${p.servidor}\n📦 Plano: ${p.planoNome} (${p.dias} dias)\n💰 ${brl(p.valor)}\n🧾 Pedido: ${p.id}\n${cfg.renovacaoUrl ? '🔄 Renovação automática enviada.' : '👉 Renove o cliente no painel.'}`);
  if (cfg.whats.avisarCliente && p.whatsapp) {
    await whats(p.whatsapp, `✅ Recebemos seu pagamento do ZarkTV (${p.planoNome}). Obrigado! Seu acesso será renovado em instantes.`);
  }
  if (cfg.renovacaoUrl) {
    try {
      await fetch(cfg.renovacaoUrl, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ usuario: p.usuario, servidor: p.servidor, dias: p.dias, pedido: p.id, valor: p.valor }),
        signal: AbortSignal.timeout(15_000),
      });
    } catch (e) {
      console.error('Renovação automática falhou:', e.message);
    }
  }
}

// ---------- páginas ------------------------------------------------------------------

const CSS = `
*{box-sizing:border-box}body{margin:0;font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#0b0b0b;color:#f2f2f2;min-height:100vh;
background-image:radial-gradient(ellipse at top,#3a0609 0,#0b0b0b 60%)}
.wrap{max-width:460px;margin:0 auto;padding:24px 16px}.wrap.larga{max-width:1000px}
.logo{font-weight:800;font-size:28px;text-align:center;margin:8px 0 18px}.logo b{color:#e50914}
.card{background:#151515cc;border:1px solid #ffffff1f;border-radius:16px;padding:20px;margin-bottom:14px}
h1{font-size:20px;margin:0 0 6px}h2{font-size:16px;margin:18px 0 8px}p{color:#b9b9b9;margin:4px 0 12px;line-height:1.45}
label{display:block;font-size:13px;color:#b9b9b9;margin:10px 0 4px}
input,select,textarea{width:100%;padding:12px;border-radius:10px;border:1px solid #333;background:#0f0f0f;color:#fff;font-size:15px}
.planos{display:grid;gap:10px}.plano{display:flex;align-items:center;justify-content:space-between;padding:14px;border-radius:12px;border:2px solid #2a2a2a;background:#111;cursor:pointer}
.plano input{width:auto;margin-right:10px}.plano:has(input:checked){border-color:#e50914;background:#1d0a0b}.preco{font-weight:800;font-size:18px}
button,.btn{display:inline-block;width:100%;padding:14px;border:0;border-radius:12px;background:#e50914;color:#fff;font-weight:700;font-size:16px;cursor:pointer;text-align:center;text-decoration:none;margin-top:14px}
.btn.cinza{background:#2a2a2a}.pequeno{width:auto;padding:8px 12px;font-size:13px;margin:0}
.qr{background:#fff;border-radius:12px;padding:12px;display:block;margin:10px auto;width:240px;height:240px}
.ok{color:#3ddc84;font-weight:700}.pend{color:#ffb020;font-weight:700}.erro{color:#ff6b6b}
table{width:100%;border-collapse:collapse;font-size:13px}th,td{padding:8px;border-bottom:1px solid #222;text-align:left;vertical-align:top}
.linha{display:grid;grid-template-columns:2fr 1fr 1fr auto;gap:8px;align-items:end}
.dica{font-size:12px;color:#888}`;

function pagina(titulo, corpo, larga = false) {
  return `<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(titulo)}</title><style>${CSS}</style></head><body><div class="wrap${larga ? ' larga' : ''}">
<div class="logo">▶ Zark<b>TV</b></div>${corpo}</div></body></html>`;
}

function paginaCheckout(q) {
  const cfg = config();
  const servidores = cfg.servidores.map((s) => `<option ${s === q.servidor ? 'selected' : ''}>${esc(s)}</option>`).join('');
  const planos = cfg.planos.map((p, i) => `<label class="plano"><span><input type="radio" name="plano" value="${esc(p.id)}" ${i === 0 ? 'checked' : ''}>${esc(p.nome)} <span class="dica">· ${p.dias} dias</span></span><span class="preco">${brl(p.valor)}</span></label>`).join('');
  return pagina('Renovar ZarkTV', `
<form class="card" method="post" action="/checkout/pedido">
  <h1>Renovar meu acesso</h1><p>Escolha o plano e pague no Pix. A liberação é automática após o pagamento.</p>
  <label>Seu usuário do ZarkTV</label><input name="usuario" required maxlength="60" value="${esc(q.usuario)}" autocomplete="username">
  <label>Servidor</label><select name="servidor">${servidores}</select>
  <label>Seu WhatsApp (opcional, para receber a confirmação)</label><input name="whatsapp" inputmode="tel" maxlength="20" placeholder="(85) 9 9999-9999">
  <h2>Plano</h2><div class="planos">${planos}</div>
  <button type="submit">Pagar com Pix</button>
</form>
<a class="btn cinza" href="${esc(cfg.suporte)}">Falar com o suporte</a>`);
}

function paginaPedido(p) {
  const cfg = config();
  if (p.status === 'pago') {
    return pagina('Pagamento confirmado', `<div class="card" style="text-align:center"><h1 class="ok">✅ Pagamento confirmado!</h1>
<p>Recebemos ${brl(p.valor)} do plano ${esc(p.planoNome)} para o usuário <b>${esc(p.usuario)}</b>. Seu acesso será renovado em instantes.</p>
<a class="btn cinza" href="${esc(cfg.suporte)}">Falar com o suporte</a></div>`);
  }
  const pix = p.pixCopiaCola ? `
<img class="qr" alt="QR Code Pix" src="data:image/png;base64,${esc(p.pixQrBase64)}">
<label>Pix copia e cola</label><textarea id="cc" rows="4" readonly>${esc(p.pixCopiaCola)}</textarea>
<button type="button" onclick="navigator.clipboard.writeText(document.getElementById('cc').value).then(()=>this.textContent='Copiado! ✅')">Copiar código Pix</button>` :
    p.linkPagamento ? `<a class="btn" href="${esc(p.linkPagamento)}">Abrir pagamento Pix</a>` : '<p class="erro">Não foi possível gerar o Pix.</p>';
  return pagina('Pague com Pix', `<div class="card" style="text-align:center">
<h1>Pague com Pix</h1><p>${esc(p.planoNome)} · usuário <b>${esc(p.usuario)}</b> · servidor ${esc(p.servidor)}</p>
<div class="preco" style="font-size:28px">${brl(p.valor)}</div>${pix}
<p id="st" class="pend" style="margin-top:14px">⏳ Aguardando pagamento…</p></div>
<a class="btn cinza" href="${esc(cfg.suporte)}">Falar com o suporte</a>
<script>
setInterval(async()=>{try{const r=await fetch('/checkout/api/pedido/${encodeURIComponent(p.id)}');const d=await r.json();
if(d.status==='pago'){location.reload()}}catch(e){}},5000);
</script>`);
}

function paginaLogin(erro) {
  return pagina('Admin do checkout', `<form class="card" method="post" action="/checkout/admin/login">
<h1>Admin do checkout</h1>${erro ? `<p class="erro">${esc(erro)}</p>` : ''}
<label>Senha</label><input type="password" name="senha" required autofocus><button>Entrar</button></form>`);
}

function paginaAdmin(msg) {
  const c = config();
  const lista = pedidos().slice(0, 100);
  const planos = c.planos.map((p, i) => `<div class="linha">
<div><label>Nome</label><input name="plano_nome_${i}" value="${esc(p.nome)}"></div>
<div><label>Dias</label><input name="plano_dias_${i}" type="number" value="${p.dias}"></div>
<div><label>Valor (R$)</label><input name="plano_valor_${i}" type="number" step="0.01" value="${p.valor}"></div><div></div></div>`).join('');
  const linhas = lista.map((p) => `<tr><td>${esc(new Date(p.criadoEm).toLocaleString('pt-BR', { timeZone: 'America/Fortaleza' }))}</td>
<td><b>${esc(p.usuario)}</b><br><span class="dica">${esc(p.servidor)} · ${esc(p.whatsapp || '')}</span></td><td>${esc(p.planoNome)}<br>${brl(p.valor)}</td>
<td>${esc(p.gateway)}</td><td class="${p.status === 'pago' ? 'ok' : 'pend'}">${esc(p.status)}</td>
<td>${p.status !== 'pago' ? `<form method="post" action="/checkout/admin/pago"><input type="hidden" name="id" value="${esc(p.id)}"><button class="pequeno">Marcar pago</button></form>` : ''}</td></tr>`).join('');
  return pagina('Admin do checkout', `
${msg ? `<div class="card ok">${esc(msg)}</div>` : ''}
<form class="card" method="post" action="/checkout/admin/config">
<h1>Checkout Pix — configuração</h1>
<p>Link para os clientes: <b>${esc(c.urlPublica)}/checkout?usuario=NOME&amp;servidor=Club</b></p>
<label>Gateway de pagamento</label><select name="gateway">
<option value="mercadopago" ${c.gateway === 'mercadopago' ? 'selected' : ''}>Mercado Pago (QR Code Pix na própria página)</option>
<option value="infinitepay" ${c.gateway === 'infinitepay' ? 'selected' : ''}>InfinitePay (página de pagamento da InfinitePay)</option></select>
<h2>Mercado Pago</h2>
<label>Access Token de produção (APP_USR-…)</label><input name="mpToken" type="password" placeholder="${c.mpToken ? '•••••••• (salvo — deixe vazio para manter)' : 'APP_USR-...'}">
<label>E-mail do pagador padrão</label><input name="mpEmailPagador" value="${esc(c.mpEmailPagador)}">
<h2>InfinitePay</h2>
<label>InfiniteTag (sem o $)</label><input name="ipHandle" value="${esc(c.ipHandle)}">
<h2>Planos</h2>${planos}
<p class="dica">Deixe o nome vazio para apagar um plano. Linha extra para criar um novo:</p>
<div class="linha"><div><input name="plano_nome_${c.planos.length}" placeholder="Novo plano"></div>
<div><input name="plano_dias_${c.planos.length}" type="number" placeholder="30"></div>
<div><input name="plano_valor_${c.planos.length}" type="number" step="0.01" placeholder="30.00"></div><div></div></div>
<label>Servidores (separados por vírgula)</label><input name="servidores" value="${esc(c.servidores.join(', '))}">
<h2>Avisos no WhatsApp (Evolution API)</h2>
<label>URL da Evolution</label><input name="w_url" value="${esc(c.whats.url)}" placeholder="https://api.rstvquix.com">
<label>API key</label><input name="w_apikey" type="password" placeholder="${c.whats.apikey ? '•••••••• (salva — deixe vazio para manter)' : ''}">
<label>Instância</label><input name="w_instancia" value="${esc(c.whats.instancia)}">
<label>Seu número (recebe os avisos)</label><input name="w_numero" value="${esc(c.whats.numeroAdmin)}">
<label><input type="checkbox" name="w_cliente" style="width:auto" ${c.whats.avisarCliente ? 'checked' : ''}> Avisar o cliente quando pagar</label>
<h2>Outros</h2>
<label>Endereço público do painel</label><input name="urlPublica" value="${esc(c.urlPublica)}">
<label>Link do suporte</label><input name="suporte" value="${esc(c.suporte)}">
<label>URL de renovação automática (opcional — ZarkPlay Gestor)</label><input name="renovacaoUrl" value="${esc(c.renovacaoUrl)}">
<button>Salvar configuração</button></form>
<div class="card"><h1>Pedidos</h1><table><tr><th>Data</th><th>Cliente</th><th>Plano</th><th>Gateway</th><th>Status</th><th></th></tr>${linhas || '<tr><td colspan="6">Nenhum pedido ainda.</td></tr>'}</table></div>
<form method="post" action="/checkout/admin/sair"><button class="btn cinza">Sair</button></form>`, true);
}

// ---------- rotas -------------------------------------------------------------------

async function rotas(req, res) {
  const url = new URL(req.url, 'http://x');
  const rota = url.pathname.replace(/\/+$/, '') || '/';
  const cfg = config();

  if (req.method === 'GET' && (rota === '/checkout' || rota === '/')) {
    return enviar(res, 200, paginaCheckout({ usuario: url.searchParams.get('usuario') || '', servidor: url.searchParams.get('servidor') || '' }));
  }

  // ---- API do app ZarkTV (Perfil → Pagar agora): planos, gerar Pix e conferir pagamento ----
  if (req.method === 'GET' && rota === '/checkout/api/planos') {
    return json(res, 200, { planos: cfg.planos.map((p) => ({ id: p.id, nome: p.nome, dias: p.dias, valor: Number(p.valor) })), gateway: cfg.gateway });
  }
  if (req.method === 'POST' && rota === '/checkout/api/pix') {
    if (limitar(ipDe(req))) return json(res, 429, { erro: 'Muitas tentativas. Aguarde alguns minutos.' });
    const b = await lerCorpo(req);
    const plano = cfg.planos.find((p) => p.id === b.plano) || cfg.planos[0];
    const usuario = String(b.usuario || '').trim().slice(0, 60);
    if (!usuario || !plano) return json(res, 400, { erro: 'Informe o usuário e o plano.' });
    const p = {
      id: 'ZK' + Date.now().toString(36).toUpperCase() + crypto.randomBytes(3).toString('hex').toUpperCase(),
      criadoEm: agora(), status: 'pendente', gateway: cfg.gateway, origem: 'app',
      usuario, servidor: cfg.servidores.includes(b.servidor) ? b.servidor : (cfg.servidores[0] || ''),
      whatsapp: '', planoId: plano.id, planoNome: plano.nome, dias: plano.dias, valor: plano.valor,
    };
    try {
      Object.assign(p, cfg.gateway === 'infinitepay' ? await criarLinkInfinitePay(cfg, p) : await criarPixMercadoPago(cfg, p));
    } catch (e) {
      console.error('Falha ao gerar Pix (app):', e.message);
      return json(res, 502, { erro: 'Não foi possível gerar o Pix agora. Tente de novo ou fale com o suporte.' });
    }
    salvarPedido(p);
    whats(cfg.whats.numeroAdmin, `🧾 *Novo pedido (app)* — ZarkTV\n👤 ${p.usuario} (${p.servidor})\n📦 ${p.planoNome} · ${brl(p.valor)}\n💳 ${p.gateway}`);
    return json(res, 200, {
      id: p.id, plano: p.planoNome, dias: p.dias, valor: Number(p.valor),
      pixCopiaCola: p.pixCopiaCola || null, link: p.linkPagamento || null,
    });
  }

  if (req.method === 'POST' && rota === '/checkout/pedido') {
    if (limitar(ipDe(req))) return enviar(res, 429, pagina('Aguarde', '<div class="card"><p class="erro">Muitas tentativas. Aguarde alguns minutos.</p></div>'));
    const b = await lerCorpo(req);
    const plano = cfg.planos.find((p) => p.id === b.plano) || cfg.planos[0];
    const usuario = String(b.usuario || '').trim().slice(0, 60);
    if (!usuario || !plano) return enviar(res, 400, pagina('Erro', '<div class="card"><p class="erro">Informe o usuário e o plano.</p></div>'));
    const p = {
      id: 'ZK' + Date.now().toString(36).toUpperCase() + crypto.randomBytes(3).toString('hex').toUpperCase(),
      criadoEm: agora(), status: 'pendente', gateway: cfg.gateway,
      usuario, servidor: cfg.servidores.includes(b.servidor) ? b.servidor : (cfg.servidores[0] || ''),
      whatsapp: soDigitos(b.whatsapp).slice(0, 13), planoId: plano.id, planoNome: plano.nome, dias: plano.dias, valor: plano.valor,
    };
    try {
      Object.assign(p, cfg.gateway === 'infinitepay' ? await criarLinkInfinitePay(cfg, p) : await criarPixMercadoPago(cfg, p));
    } catch (e) {
      console.error('Falha ao gerar Pix:', e.message);
      return enviar(res, 502, pagina('Erro', `<div class="card"><p class="erro">Não foi possível gerar o Pix agora. Tente de novo ou fale com o suporte.</p><a class="btn cinza" href="${esc(cfg.suporte)}">Suporte</a></div>`));
    }
    salvarPedido(p);
    whats(cfg.whats.numeroAdmin, `🧾 *Novo pedido* — ZarkTV\n👤 ${p.usuario} (${p.servidor})\n📦 ${p.planoNome} · ${brl(p.valor)}\n💳 ${p.gateway}\n🔗 ${cfg.urlPublica}/checkout/pedido/${p.id}`);
    if (cfg.gateway === 'infinitepay' && p.linkPagamento) return enviar(res, 303, '', 'text/plain', { Location: p.linkPagamento });
    return enviar(res, 303, '', 'text/plain', { Location: `/checkout/pedido/${p.id}` });
  }

  let m;
  if (req.method === 'GET' && (m = /^\/checkout\/pedido\/([A-Z0-9]+)$/.exec(rota))) {
    const p = acharPedido(m[1]);
    if (!p) return enviar(res, 404, pagina('Não encontrado', '<div class="card"><p>Pedido não encontrado.</p></div>'));
    return enviar(res, 200, paginaPedido(p));
  }

  if (req.method === 'GET' && (m = /^\/checkout\/api\/pedido\/([A-Z0-9]+)$/.exec(rota))) {
    const p = acharPedido(m[1]);
    if (!p) return json(res, 404, { erro: 'não encontrado' });
    // Mercado Pago: confere direto na API (caso o webhook atrase).
    if (p.status !== 'pago' && p.gateway === 'mercadopago' && p.gatewayId && cfg.mpToken) {
      const s = await statusMercadoPago(cfg, p.gatewayId).catch(() => null);
      if (s?.pago && s.ref === p.id) await marcarPago(p, 'consulta');
    }
    return json(res, 200, { status: acharPedido(p.id).status });
  }

  if (req.method === 'POST' && rota === '/checkout/webhook/mercadopago') {
    const b = await lerCorpo(req);
    const id = b?.data?.id || url.searchParams.get('data.id') || url.searchParams.get('id');
    if (id && cfg.mpToken) {
      // Nunca confia no corpo do aviso: confere o pagamento na API do Mercado Pago.
      const s = await statusMercadoPago(cfg, id).catch(() => null);
      const p = s?.ref && acharPedido(s.ref);
      if (p && s.pago) await marcarPago(p, 'webhook-mp');
    }
    return json(res, 200, { ok: true });
  }

  if (req.method === 'POST' && rota === '/checkout/webhook/infinitepay') {
    const b = await lerCorpo(req);
    const p = b.order_nsu && acharPedido(String(b.order_nsu));
    if (p && p.status !== 'pago') {
      const conferido = await conferirInfinitePay(cfg, b);
      if (conferido === true) await marcarPago(p, 'webhook-infinitepay');
      else if (conferido === null) {
        p.status = 'a conferir';
        salvarPedido(p);
        whats(cfg.whats.numeroAdmin, `⚠️ InfinitePay avisou pagamento do pedido ${p.id} (${p.usuario}), mas não consegui conferir. Veja no app da InfinitePay e marque como pago no admin.`);
      }
    }
    return json(res, 200, { ok: true });
  }

  // ----- admin -----
  if (rota === '/checkout/admin/login' && req.method === 'POST') {
    const b = await lerCorpo(req);
    if (limitar('login:' + ipDe(req)) || !senhaConfere(b.senha)) return enviar(res, 401, paginaLogin('Senha incorreta.'));
    return enviar(res, 303, '', 'text/plain', {
      Location: '/checkout/admin',
      'Set-Cookie': `zk_adm=${criarSessao()}; Path=/checkout; HttpOnly; Secure; SameSite=Strict; Max-Age=43200`,
    });
  }
  if (rota.startsWith('/checkout/admin')) {
    if (!sessaoValida(req)) return enviar(res, 200, paginaLogin());
    if (req.method === 'GET' && rota === '/checkout/admin') return enviar(res, 200, paginaAdmin(url.searchParams.get('ok') ? 'Configuração salva.' : ''));
    if (req.method === 'POST' && rota === '/checkout/admin/sair') {
      return enviar(res, 303, '', 'text/plain', { Location: '/checkout/admin', 'Set-Cookie': 'zk_adm=; Path=/checkout; Max-Age=0' });
    }
    if (req.method === 'POST' && rota === '/checkout/admin/pago') {
      const b = await lerCorpo(req);
      const p = acharPedido(String(b.id || ''));
      if (p) await marcarPago(p, 'manual');
      return enviar(res, 303, '', 'text/plain', { Location: '/checkout/admin' });
    }
    if (req.method === 'POST' && rota === '/checkout/admin/config') {
      const b = await lerCorpo(req);
      const atual = config();
      const planos = [];
      for (let i = 0; i < 30; i++) {
        const nome = String(b[`plano_nome_${i}`] || '').trim();
        if (!nome) continue;
        const id = nome.toLowerCase().normalize('NFD').replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '') || `plano${i}`;
        planos.push({ id, nome, dias: Math.max(1, Number(b[`plano_dias_${i}`]) || 30), valor: Math.max(1, Number(b[`plano_valor_${i}`]) || 30) });
      }
      gravar(ARQ_CONFIG, {
        ...atual,
        gateway: b.gateway === 'infinitepay' ? 'infinitepay' : 'mercadopago',
        mpToken: String(b.mpToken || '').trim() || atual.mpToken,
        mpEmailPagador: String(b.mpEmailPagador || '').trim() || atual.mpEmailPagador,
        ipHandle: String(b.ipHandle || '').trim().replace(/^\$/, ''),
        planos: planos.length ? planos : atual.planos,
        servidores: String(b.servidores || '').split(',').map((s) => s.trim()).filter(Boolean),
        whats: {
          url: String(b.w_url || '').trim(),
          apikey: String(b.w_apikey || '').trim() || atual.whats.apikey,
          instancia: String(b.w_instancia || '').trim(),
          numeroAdmin: soDigitos(b.w_numero),
          avisarCliente: b.w_cliente === 'on',
        },
        urlPublica: String(b.urlPublica || '').trim().replace(/\/$/, '') || atual.urlPublica,
        suporte: String(b.suporte || '').trim() || atual.suporte,
        renovacaoUrl: String(b.renovacaoUrl || '').trim(),
      });
      return enviar(res, 303, '', 'text/plain', { Location: '/checkout/admin?ok=1' });
    }
  }

  enviar(res, 404, pagina('Não encontrado', '<div class="card"><p>Página não encontrada.</p></div>'));
}

http.createServer((req, res) => {
  rotas(req, res).catch((e) => {
    console.error('Erro:', e);
    if (!res.headersSent) enviar(res, 500, pagina('Erro', '<div class="card"><p class="erro">Erro interno. Tente de novo.</p></div>'));
  });
}).listen(PORTA, '127.0.0.1', () => console.log(`Checkout ZarkTV rodando em http://127.0.0.1:${PORTA}/checkout`));
