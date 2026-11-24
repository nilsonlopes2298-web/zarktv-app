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
const ARQ_ERROS = path.join(DADOS, 'erros.json');
const SEGREDO_SESSAO = crypto.createHash('sha256').update('zarktv:' + ADMIN_SENHA).digest();

if (!ADMIN_SENHA) {
  console.error('ERRO: defina a variável ADMIN_SENHA (senha do admin do checkout).');
  process.exit(1);
}
fs.mkdirSync(DADOS, { recursive: true, mode: 0o700 });

// ---------- armazenamento ----------------------------------------------------------------

const CONFIG_PADRAO = {
  gateway: 'chave', // 'chave' (Pix na sua chave, cliente manda o comprovante), 'mercadopago' ou 'infinitepay'
  pixChave: '',
  pixNome: '',
  pixCidade: 'QUIXERAMOBIM',
  urlPublica: process.env.URL_PUBLICA || 'https://painel.zarktvqx.cfd',
  mpToken: '',
  mpEmailPagador: 'cliente@zarktvqx.cfd',
  ipHandle: '',
  planos: [
    { id: 'mensal', nome: 'Mensal', dias: 30, valor: 35 },
    { id: 'anual', nome: 'Anual', dias: 365, valor: 350 },
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

// ---------- Erros dos aparelhos (relatório que os apps mandam) ---------------------------
// Cada app manda pacotes pequenos: aparelho + eventos (usuário, servidor, canal, tipo do erro).
// Guarda só os últimos 5000; nunca recebe senha nem endereço de canal.
const erros = () => ler(ARQ_ERROS, []);
const envioErros = new Map();
function limitarErros(ip) {
  const t = Date.now();
  const lista = (envioErros.get(ip) || []).filter((x) => t - x < 60_000);
  lista.push(t);
  envioErros.set(ip, lista);
  return lista.length > 20;
}
function guardarErros(corpo) {
  const ap = corpo && typeof corpo.aparelho === 'object' ? corpo.aparelho : {};
  const txt = (v, n = 80) => String(v ?? '').slice(0, n);
  const aparelho = { marca: txt(ap.marca, 40), modelo: txt(ap.modelo, 60), android: Number(ap.android) || 0, app: txt(ap.app, 20), versao: txt(ap.versao, 10) };
  const novos = (Array.isArray(corpo?.eventos) ? corpo.eventos : []).slice(0, 20).map((e) => ({
    quando: new Date(Number(e.quando) || Date.now()).toISOString(),
    tipo: txt(e.tipo), detalhe: txt(e.detalhe, 120), usuario: txt(e.usuario, 60), servidor: txt(e.servidor, 40), canal: txt(e.canal), leve: !!e.leve,
    ...aparelho,
  }));
  if (novos.length) gravar(ARQ_ERROS, [...novos.reverse(), ...erros()].slice(0, 5000));
}
function painelErros() {
  const todos = erros();
  const dia = Date.now() - 24 * 3600_000;
  const hoje = todos.filter((e) => Date.parse(e.quando) > dia);
  const contar = (f) => Object.entries(hoje.reduce((m, e) => { const k = f(e) || '—'; m[k] = (m[k] || 0) + 1; return m; }, {}))
    .sort((a, b) => b[1] - a[1]).slice(0, 8).map(([k, n]) => `<tr><td>${esc(k)}</td><td>${n}</td></tr>`).join('') || '<tr><td colspan="2">Nada nas últimas 24 horas.</td></tr>';
  const ultimos = todos.slice(0, 40).map((e) => `<tr><td>${esc(new Date(e.quando).toLocaleString('pt-BR', { timeZone: 'America/Fortaleza' }))}</td><td>${esc(e.usuario)}<br><small>${esc(e.servidor)}</small></td><td>${esc(e.canal)}</td><td>${esc(e.tipo)}<br><small>${esc(e.detalhe)}</small></td><td>${esc(e.marca)} ${esc(e.modelo)}<br><small>Android ${esc(e.android)} · app ${esc(e.app)} (${esc(e.versao)})${e.leve ? ' · fraco' : ''}</small></td></tr>`).join('');
  return `<div class="card"><h1>Erros dos aparelhos</h1>
<p>${hoje.length} erro(s) nas últimas 24 horas. Os apps mandam sozinhos quando um canal ou filme não abre ou demora mais de 10 segundos.</p>
<h2>Por tipo</h2><table>${contar((e) => e.tipo)}</table>
<h2>Por canal</h2><table>${contar((e) => e.canal)}</table>
<h2>Por servidor</h2><table>${contar((e) => e.servidor)}</table>
<h2>Por aparelho</h2><table>${contar((e) => `${e.marca} ${e.modelo}`.trim())}</table>
<h2>Últimos</h2><table><tr><th>Quando</th><th>Cliente</th><th>Canal</th><th>Erro</th><th>Aparelho</th></tr>${ultimos || '<tr><td colspan="5">Nenhum erro recebido ainda.</td></tr>'}</table>
<form method="post" action="/checkout/admin/erros/limpar"><button class="btn cinza">Limpar relatório</button></form></div>`;
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

// Pix na própria chave (padrão do Banco Central, "BR Code" estático com valor).
function campo(id, valor) { return id + String(valor.length).padStart(2, '0') + valor; }
function crc16(t) {
  let c = 0xffff;
  for (const b of Buffer.from(t, 'utf8')) {
    c ^= b << 8;
    for (let i = 0; i < 8; i++) c = c & 0x8000 ? ((c << 1) ^ 0x1021) & 0xffff : (c << 1) & 0xffff;
  }
  return c.toString(16).toUpperCase().padStart(4, '0');
}
const semAcento = (t, n) => String(t || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^A-Za-z0-9 ]/g, '').toUpperCase().trim().slice(0, n);
function brCode(chave, nome, cidade, valor, txid) {
  let chaveLimpa = String(chave).trim();
  // Telefone sem +55 vira +55…; CPF/CNPJ só números.
  if (/^\(?\d{2}\)?\s?9?\d{4}-?\d{4}$/.test(chaveLimpa)) chaveLimpa = '+55' + chaveLimpa.replace(/\D/g, '');
  else if (/^[\d.\-\/]+$/.test(chaveLimpa)) chaveLimpa = chaveLimpa.replace(/\D/g, '');
  const conta = campo('00', 'br.gov.bcb.pix') + campo('01', chaveLimpa);
  let t = campo('00', '01') + campo('26', conta) + campo('52', '0000') + campo('53', '986') +
    campo('54', Number(valor).toFixed(2)) + campo('58', 'BR') + campo('59', semAcento(nome, 25) || 'ZARKTV') +
    campo('60', semAcento(cidade, 15) || 'BRASIL') + campo('62', campo('05', String(txid).replace(/[^A-Za-z0-9]/g, '').slice(0, 25) || '***')) + '6304';
  return t + crc16(t);
}
let QR = null;
try { QR = require('qrcode'); } catch (e) { console.error('Pacote qrcode não instalado: a página do checkout mostra só o Copia e Cola.'); }
async function criarPixChave(cfg, p) {
  if (!cfg.pixChave) throw new Error('Chave Pix não cadastrada no admin.');
  const copia = brCode(cfg.pixChave, cfg.pixNome, cfg.pixCidade, p.valor, p.id);
  let qr = '';
  if (QR) qr = (await QR.toDataURL(copia, { margin: 1, width: 360 })).split(',')[1];
  return { pixCopiaCola: copia, pixQrBase64: qr, manual: true };
}

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
  <h1>Renovar meu acesso</h1><p>Escolha o plano e pague no Pix.${cfg.gateway === 'chave' ? ' Depois envie o comprovante no WhatsApp.' : ' A liberação é automática após o pagamento.'}</p>
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
${p.pixQrBase64 ? `<img class="qr" alt="QR Code Pix" src="data:image/png;base64,${esc(p.pixQrBase64)}">` : ''}
<label>Pix copia e cola</label><textarea id="cc" rows="4" readonly>${esc(p.pixCopiaCola)}</textarea>
<button type="button" onclick="navigator.clipboard.writeText(document.getElementById('cc').value).then(()=>this.textContent='Copiado! ✅')">Copiar código Pix</button>` :
    p.linkPagamento ? `<a class="btn" href="${esc(p.linkPagamento)}">Abrir pagamento Pix</a>` : '<p class="erro">Não foi possível gerar o Pix.</p>';
  return pagina('Pague com Pix', `<div class="card" style="text-align:center">
<h1>Pague com Pix</h1><p>${esc(p.planoNome)} · usuário <b>${esc(p.usuario)}</b> · servidor ${esc(p.servidor)}</p>
<div class="preco" style="font-size:28px">${brl(p.valor)}</div>${pix}
${p.manual
    ? `<p style="margin-top:14px">Depois de pagar, envie o comprovante para liberarmos o seu acesso.</p>
<a class="btn" href="${esc(cfg.suporte)}?text=${encodeURIComponent(`Olá! Paguei o Pix do ZarkTV.\nUsuário: ${p.usuario} (${p.servidor})\nPlano: ${p.planoNome} · ${brl(p.valor)}\nPedido: ${p.id}`)}">Enviar comprovante no WhatsApp</a>`
    : `<p id="st" class="pend" style="margin-top:14px">⏳ Aguardando pagamento…</p>`}</div>
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
<option value="chave" ${c.gateway === 'chave' ? 'selected' : ''}>Minha chave Pix (cliente manda o comprovante no WhatsApp)</option>
<option value="mercadopago" ${c.gateway === 'mercadopago' ? 'selected' : ''}>Mercado Pago (QR Code Pix na própria página)</option>
<option value="infinitepay" ${c.gateway === 'infinitepay' ? 'selected' : ''}>InfinitePay (página de pagamento da InfinitePay)</option></select>
<h2>Minha chave Pix</h2>
<label>Chave Pix (CPF, CNPJ, e-mail, telefone ou chave aleatória)</label><input name="pixChave" value="${esc(c.pixChave || '')}">
<label>Nome do recebedor (como está no banco)</label><input name="pixNome" maxlength="25" value="${esc(c.pixNome || '')}">
<label>Cidade</label><input name="pixCidade" maxlength="15" value="${esc(c.pixCidade || '')}">
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
${painelMonitor()}
${painelErros()}
<div class="card"><h1>Pedidos</h1><table><tr><th>Data</th><th>Cliente</th><th>Plano</th><th>Gateway</th><th>Status</th><th></th></tr>${linhas || '<tr><td colspan="6">Nenhum pedido ainda.</td></tr>'}</table></div>
<form method="post" action="/checkout/admin/sair"><button class="btn cinza">Sair</button></form>`, true);
}

// ---------- Monitor de servidores ------------------------------------------------------
// A cada 5 min testa cada servidor/DNS do Web Play (config.json) e, com a conta de teste, se o vídeo sai.
// Avisa no WhatsApp quando cai e quando volta. /checkout/api/status mostra o resultado para os apps.

const ARQ_MONITOR = path.join(DADOS, 'monitor.json');
const CONFIG_WEB = process.env.CONFIG_WEB || 'http://tv.zarktvqx.cfd/config.json';
const monitorPadrao = { contas: {}, status: {}, ultimo: '', avisoApps: false, avisos: {} }; // avisos: { Five: 'texto manual' } // contas: { Club: { usuario, senha } }
const monitor = () => ({ ...monitorPadrao, ...ler(ARQ_MONITOR, {}) });

async function tempo(fn) { const t = Date.now(); try { const r = await fn(); return { ok: r !== false, ms: Date.now() - t }; } catch (e) { return { ok: false, ms: Date.now() - t, erro: e.name === 'TimeoutError' ? 'não respondeu' : (e.message || 'erro') }; } }

async function testarDns(dns) {
  const base = dns.replace(/\/+$/, '');
  return tempo(async () => {
    const r = await fetch(base + '/player_api.php', { signal: AbortSignal.timeout(8000), redirect: 'manual' });
    await r.body?.cancel();
    return r.status < 500 || r.status === 512;
  });
}

// Com a conta de teste: login, pega um canal e baixa os primeiros bytes do vídeo.
async function testarVideo(dns, conta) {
  const base = dns.replace(/\/+$/, '');
  const q = `username=${encodeURIComponent(conta.usuario)}&password=${encodeURIComponent(conta.senha)}`;
  return tempo(async () => {
    const cats = await (await fetch(`${base}/player_api.php?${q}&action=get_live_categories`, { signal: AbortSignal.timeout(10000) })).json();
    const cat = Array.isArray(cats) && cats[0] ? cats[0].category_id : null;
    if (!cat) throw new Error('login da conta de teste não funcionou');
    const lista = await (await fetch(`${base}/player_api.php?${q}&action=get_live_streams&category_id=${encodeURIComponent(cat)}`, { signal: AbortSignal.timeout(10000) })).json();
    const canal = Array.isArray(lista) && lista[0];
    if (!canal) throw new Error('sem canais na conta de teste');
    const r = await fetch(`${base}/live/${encodeURIComponent(conta.usuario)}/${encodeURIComponent(conta.senha)}/${canal.stream_id}.ts`,
      { signal: AbortSignal.timeout(10000), headers: { 'User-Agent': 'VLC/3.0.20 LibVLC/3.0.20' } });
    if (!r.ok) { await r.body?.cancel(); throw new Error('vídeo recusado (HTTP ' + r.status + ')'); }
    const leitor = r.body.getReader();
    const { value } = await leitor.read();
    await leitor.cancel();
    if (!value || !value.length) throw new Error('vídeo sem dados');
    return true;
  });
}

let verificando = false;
async function verificarServidores() {
  if (verificando) return;
  verificando = true;
  try {
    const cfgWeb = await (await fetch(CONFIG_WEB, { signal: AbortSignal.timeout(10000) })).json();
    const lista = (cfgWeb.servidores || cfgWeb.servers || []).map((s) => ({ nome: s.nome || s.name || s.id, dns: (s.dns || s.urls || []).filter(Boolean) }));
    const m = monitor();
    const novo = {};
    for (const sv of lista) {
      const dnsRes = [];
      for (const d of sv.dns.slice(0, 6)) dnsRes.push({ dns: d, ...(await testarDns(d)) });
      const algumDns = dnsRes.some((x) => x.ok);
      const conta = m.contas[sv.nome];
      let video = null;
      if (conta && conta.usuario) {
        const dnsBom = dnsRes.find((x) => x.ok)?.dns || sv.dns[0];
        video = dnsBom ? { dns: dnsBom, ...(await testarVideo(dnsBom, conta)) } : { ok: false, erro: 'nenhum DNS respondeu' };
      }
      const ok = algumDns && (video ? video.ok : true);
      novo[sv.nome] = { ok, dns: dnsRes, video, em: agora(), desde: m.status[sv.nome]?.ok === ok ? (m.status[sv.nome]?.desde || agora()) : agora() };
      const antes = m.status[sv.nome];
      const cfg = config();
      if (antes && antes.ok && !ok) {
        const motivo = !algumDns ? 'nenhum DNS respondeu' : (video?.erro || 'o vídeo não sai');
        whats(cfg.whats.numeroAdmin, `🔴 *Servidor ${sv.nome} fora do ar* — ZarkTV\n${motivo}\n🕒 ${new Date().toLocaleString('pt-BR', { timeZone: 'America/Sao_Paulo' })}`);
      } else if (antes && !antes.ok && ok) {
        whats(cfg.whats.numeroAdmin, `🟢 *Servidor ${sv.nome} voltou* — ZarkTV\n🕒 ${new Date().toLocaleString('pt-BR', { timeZone: 'America/Sao_Paulo' })}`);
      }
    }
    gravar(ARQ_MONITOR, { ...m, status: novo, ultimo: agora() });
  } catch (e) {
    console.error('Monitor:', e.message);
  } finally {
    verificando = false;
  }
}
setTimeout(verificarServidores, 15_000);
setInterval(verificarServidores, 5 * 60_000);

function painelMonitor() {
  const m = monitor();
  const nomes = Object.keys(m.status).length ? Object.keys(m.status) : config().servidores;
  const linhas = nomes.map((n) => {
    const st = m.status[n];
    const dns = (st?.dns || []).map((d) => `<div class="dica">${d.ok ? '🟢' : '🔴'} ${esc(d.dns)} ${d.ok ? `· ${d.ms} ms` : `· ${esc(d.erro || 'falhou')}`}</div>`).join('');
    const video = st?.video ? `<div class="dica">${st.video.ok ? '🟢 Vídeo saindo' : '🔴 Vídeo: ' + esc(st.video.erro || 'não sai')}</div>` : '<div class="dica">⚪ Vídeo não testado (cadastre a conta de teste abaixo)</div>';
    return `<tr><td><b>${esc(n)}</b><br>${st ? (st.ok ? '<span class="ok">No ar</span>' : '<span class="erro">Fora do ar</span>') : 'Aguardando teste'}</td><td>${dns}${video}</td></tr>`;
  }).join('');
  const contas = nomes.map((n) => `<div class="linha"><div><label>${esc(n)} — usuário de teste</label><input name="u_${esc(n)}" value="${esc(m.contas[n]?.usuario || '')}"></div>
<div><label>Senha</label><input name="p_${esc(n)}" type="password" placeholder="${m.contas[n]?.senha ? '•••• (salva)' : ''}"></div><div></div><div></div></div>`).join('');
  return `<div class="card"><h1>Monitor de servidores</h1><p class="dica">Testa a cada 5 minutos${m.ultimo ? ' · último teste: ' + esc(m.ultimo) : ''}. Avisa no seu WhatsApp quando um servidor cai e quando volta.</p>
<table><tr><th>Servidor</th><th>Detalhes</th></tr>${linhas}</table>
<form method="post" action="/checkout/admin/monitor/aviso"><p>Aviso nos apps: <b>${m.avisoApps ? 'ligado' : 'desligado'}</b> — quando ligado, o cliente vê "Servidor em manutenção" no meio da tela se o servidor dele cair.</p>
<input type="hidden" name="ligar" value="${m.avisoApps ? '0' : '1'}"><button class="btn ${m.avisoApps ? 'cinza' : ''}">${m.avisoApps ? 'Desligar aviso nos apps' : 'Ligar aviso nos apps'}</button></form>
<form method="post" action="/checkout/admin/monitor/agora"><button class="btn cinza">Testar agora</button></form></div>
<form class="card" method="post" action="/checkout/admin/avisos"><h2>Avisos por servidor (manual)</h2>
<p class="dica">Escreva um aviso e todos os clientes daquele servidor veem no meio da tela do app. Para tirar, apague o texto e salve.</p>
${nomes.map((n) => `<label>${esc(n)}</label><input name="a_${esc(n)}" maxlength="200" placeholder="Ex.: Servidor em manutenção, volta às 20h" value="${esc(m.avisos?.[n] || '')}">`).join('')}
<button>Salvar avisos</button></form>
<form class="card" method="post" action="/checkout/admin/monitor"><h2>Contas de teste (para conferir se o vídeo sai)</h2>
<p class="dica">Use uma conta sua de cada servidor. Fica guardada só nesta VPS.</p>${contas}<button>Salvar contas de teste</button></form>`;
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
  if (req.method === 'GET' && rota === '/checkout/api/status') {
    const m = monitor();
    // Aviso desligado no admin: os apps não recebem nada (não mostram "manutenção").
    const st = m.avisoApps ? Object.fromEntries(Object.entries(m.status).map(([n, x]) => [n, { ok: x.ok, desde: x.desde }])) : {};
    // Aviso manual do admin vale sempre (com ou sem o automático).
    for (const [n, texto] of Object.entries(m.avisos || {})) st[n] = { ...(st[n] || {}), ok: false, msg: texto };
    return json(res, 200, { ultimo: m.ultimo, aviso: !!m.avisoApps, servidores: st });
  }
  if (req.method === 'POST' && rota === '/checkout/api/erros') {
    if (limitarErros(ipDe(req))) return json(res, 429, { ok: false });
    try { guardarErros(await lerCorpo(req)); } catch (e) { console.error('Relatório de erros inválido:', e.message); }
    return json(res, 200, { ok: true });
  }
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
      Object.assign(p, cfg.gateway === 'chave' ? await criarPixChave(cfg, p) : cfg.gateway === 'infinitepay' ? await criarLinkInfinitePay(cfg, p) : await criarPixMercadoPago(cfg, p));
    } catch (e) {
      console.error('Falha ao gerar Pix (app):', e.message);
      return json(res, 502, { erro: 'Não foi possível gerar o Pix agora. Tente de novo ou fale com o suporte.' });
    }
    salvarPedido(p);
    whats(cfg.whats.numeroAdmin, `🧾 *Novo pedido (app)* — ZarkTV\n👤 ${p.usuario} (${p.servidor})\n📦 ${p.planoNome} · ${brl(p.valor)}\n💳 ${p.gateway}`);
    return json(res, 200, {
      id: p.id, plano: p.planoNome, dias: p.dias, valor: Number(p.valor),
      pixCopiaCola: p.pixCopiaCola || null, link: p.linkPagamento || null,
      manual: !!p.manual, comprovante: cfg.suporte,
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
      Object.assign(p, cfg.gateway === 'chave' ? await criarPixChave(cfg, p) : cfg.gateway === 'infinitepay' ? await criarLinkInfinitePay(cfg, p) : await criarPixMercadoPago(cfg, p));
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
    if (req.method === 'POST' && rota === '/checkout/admin/monitor') {
      const b = await lerCorpo(req);
      const m = monitor();
      const contas = { ...m.contas };
      for (const k of Object.keys(b)) {
        if (!k.startsWith('u_')) continue;
        const n = k.slice(2);
        const usuario = String(b[k] || '').trim();
        const senha = String(b['p_' + n] || '').trim() || contas[n]?.senha || '';
        if (usuario) contas[n] = { usuario, senha }; else delete contas[n];
      }
      gravar(ARQ_MONITOR, { ...m, contas });
      verificarServidores();
      return enviar(res, 303, '', 'text/plain', { Location: '/checkout/admin?ok=1' });
    }
    if (req.method === 'POST' && rota === '/checkout/admin/avisos') {
      const b = await lerCorpo(req);
      const avisos = {};
      for (const k of Object.keys(b)) if (k.startsWith('a_') && String(b[k]).trim()) avisos[k.slice(2)] = String(b[k]).trim().slice(0, 200);
      gravar(ARQ_MONITOR, { ...monitor(), avisos });
      return enviar(res, 303, '', 'text/plain', { Location: '/checkout/admin?ok=1' });
    }
    if (req.method === 'POST' && rota === '/checkout/admin/monitor/aviso') {
      const b = await lerCorpo(req);
      gravar(ARQ_MONITOR, { ...monitor(), avisoApps: b.ligar === '1' });
      return enviar(res, 303, '', 'text/plain', { Location: '/checkout/admin' });
    }
    if (req.method === 'POST' && rota === '/checkout/admin/monitor/agora') {
      await verificarServidores();
      return enviar(res, 303, '', 'text/plain', { Location: '/checkout/admin' });
    }
    if (req.method === 'POST' && rota === '/checkout/admin/erros/limpar') {
      gravar(ARQ_ERROS, []);
      return enviar(res, 303, '', 'text/plain', { Location: '/checkout/admin' });
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
        gateway: ['chave', 'infinitepay', 'mercadopago'].includes(b.gateway) ? b.gateway : 'chave',
        pixChave: String(b.pixChave || '').trim(),
        pixNome: String(b.pixNome || '').trim(),
        pixCidade: String(b.pixCidade || '').trim(),
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
}).listen(PORTA, process.env.ENDERECO || '127.0.0.1', () => console.log(`Checkout ZarkTV rodando em http://${process.env.ENDERECO || '127.0.0.1'}:${PORTA}/checkout`));
