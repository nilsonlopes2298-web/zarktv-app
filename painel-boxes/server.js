// ZarkTV Cloud — Boxes (painel da ZarkTV Launcher)
// Sem dependências: só Node 18+. Dados em ./data (boxes.json, config.json, apks.json e as pastas apks/ e imagens/).
//
//   Admin:  /boxes/admin            -> lista das boxes, cliente de cada uma, modelo, Android, modo, versão
//           /boxes/admin/box?t=...  -> detalhe: apps da box, instalar/desinstalar/esconder, bloquear, reiniciar
//           /boxes/admin/apks       -> biblioteca de APKs: sobe o APK e manda para uma box ou para todas
//           /boxes/admin/tela       -> banners, aviso, lojinha, apps que toda box tem e lixo para esconder
//   Box:    /boxes/api/registrar, /boxes/api/ping, /boxes/api/vincular, /boxes/api/comando
//   Arquivos públicos (a box baixa daqui): /boxes/arquivos/<id>.apk e /boxes/arquivos/img/<nome>
//
// Variáveis de ambiente: PORTA (padrão 3091), ADMIN_SENHA (obrigatória), URL_PUBLICA, CONFIG_WEB

'use strict';
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const zlib = require('zlib');

const PORTA = Number(process.env.PORTA || 3091);
const ADMIN_SENHA = process.env.ADMIN_SENHA || '';
const URL_PUBLICA = (process.env.URL_PUBLICA || 'https://painel.zarktvqx.cfd').replace(/\/+$/, '');
const CONFIG_WEB = process.env.CONFIG_WEB || 'http://tv.zarktvqx.cfd/config.json';
const VERSAO_LAUNCHER = 'https://github.com/nilsonlopes2298-web/zarktv-app/releases/download/launcher/version-launcher.json';
const PACOTE_LAUNCHER = 'cfd.zarktvqx.launcher';
const DADOS = process.env.DADOS || path.join(__dirname, 'data');
const PASTA_APKS = path.join(DADOS, 'apks');
const PASTA_IMG = path.join(DADOS, 'imagens');
const ARQ_BOXES = path.join(DADOS, 'boxes.json');
const ARQ_CONFIG = path.join(DADOS, 'config.json');
const ARQ_APKS = path.join(DADOS, 'apks.json');
const SEGREDO_SESSAO = crypto.createHash('sha256').update('zarktv-boxes:' + ADMIN_SENHA).digest();
const ONLINE_MS = 20 * 60 * 1000; // conversou nos últimos 20 min = online

if (!ADMIN_SENHA) {
  console.error('ERRO: defina a variável ADMIN_SENHA (senha do painel das boxes).');
  process.exit(1);
}
for (const p of [DADOS, PASTA_APKS, PASTA_IMG]) fs.mkdirSync(p, { recursive: true, mode: 0o700 });

// ---------- armazenamento ----------------------------------------------------------------

const ZARKPLAY_URL = 'https://github.com/nilsonlopes2298-web/zarktv-app/releases/download/latest/zarkplay.apk';
const CONFIG_PADRAO = {
  banners: [
    { id: 'b1', selo: 'Novidade', titulo: 'Bem-vindo ao ZarkTV', sub: 'Canais, filmes e séries num só lugar.', imagem: '', ativo: true },
  ],
  aviso: { id: '', titulo: '', texto: '', imagem: '', ativo: false },
  // Apps que toda box tem que ter. chave = qual cartão da tela inicial abre (youtube / zarkplay)
  iniciais: [
    { chave: 'zarkplay', nome: 'ZarkPlay', pacote: 'cfd.zarktvqx.zarkplay', url: ZARKPLAY_URL, sha256: '' },
  ],
  // Lojinha dentro da box
  loja: [
    { nome: 'ZarkPlay', pacote: 'cfd.zarktvqx.zarkplay', url: ZARKPLAY_URL, sha256: '' },
    { nome: 'ZarkTV TV', pacote: 'cfd.zarktvqx.tv', url: 'https://github.com/nilsonlopes2298-web/zarktv-app/releases/download/latest/zarktv-tv.apk', sha256: '' },
  ],
  lixo: [], // pacotes que a launcher esconde sozinha em toda box (modo dona/root)
  // As regras abaixo só valem para as boxes marcadas como "minha" (controle total).
  // Box do cliente fica livre: ele instala o que quiser e nada é bloqueado.
  bloquearFora: true, // não deixa instalar nada que não venha da launcher (modo dona)
  bloquearVencidos: false, // box minha com cliente vencido mostra a tela de bloqueio com o Pix
  limpeza: true, // box minha: apaga/esconde tudo que não é nosso nem permitido (deixa a box leve)
  permitidos: [], // pacotes extras que podem ficar nas boxes minhas
  launcherAuto: true, // atualiza a launcher sozinha quando sair versão nova no GitHub
  suporte: '(85) 8789-0473',
  checkout: 'https://painel.zarktvqx.cfd/checkout',
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
const salvarConfig = (c) => gravar(ARQ_CONFIG, c);

// As boxes ficam na memória e vão para o disco a cada mudança (poucas centenas de boxes = arquivo pequeno).
let BOXES = ler(ARQ_BOXES, {});
let gravarDepois = null;
function salvarBoxes() {
  if (gravarDepois) return;
  gravarDepois = setTimeout(() => { gravarDepois = null; gravar(ARQ_BOXES, BOXES); }, 500);
}
const apks = () => ler(ARQ_APKS, []);

// ---------- utilidades --------------------------------------------------------------------

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const agora = () => Date.now();
const id = (n = 8) => crypto.randomBytes(n).toString('hex');
const pacoteValido = (p) => /^[a-zA-Z][\w]*(\.[a-zA-Z_][\w]*)+$/.test(String(p || ''));

function quando(ms) {
  if (!ms) return 'nunca';
  const s = Math.round((agora() - ms) / 1000);
  if (s < 60) return 'agora';
  if (s < 3600) return `há ${Math.round(s / 60)} min`;
  if (s < 86400) return `há ${Math.round(s / 3600)} h`;
  return `há ${Math.round(s / 86400)} dias`;
}
function data(ms) {
  return ms ? new Date(ms).toLocaleString('pt-BR', { timeZone: 'America/Sao_Paulo' }) : '—';
}

function lerCorpo(req, limite = 2_000_000) {
  return new Promise((ok, erro) => {
    let txt = '';
    req.on('data', (c) => { txt += c; if (txt.length > limite) { erro(new Error('corpo grande demais')); req.destroy(); } });
    req.on('end', () => {
      const tipo = req.headers['content-type'] || '';
      if (!txt) return ok({});
      if (tipo.includes('application/json')) { try { return ok(JSON.parse(txt)); } catch { return ok({}); } }
      const p = new URLSearchParams(txt);
      const o = {};
      for (const [k, v] of p) { if (k in o) o[k] = [].concat(o[k], v); else o[k] = v; }
      ok(o);
    });
    req.on('error', erro);
  });
}
function enviar(res, status, corpo, tipo = 'text/html; charset=utf-8', extra = {}) {
  res.writeHead(status, { 'Content-Type': tipo, 'X-Content-Type-Options': 'nosniff', 'Referrer-Policy': 'same-origin', ...extra });
  res.end(corpo);
}
const json = (res, status, obj) => enviar(res, status, JSON.stringify(obj), 'application/json; charset=utf-8');
const voltar = (res, para) => enviar(res, 303, '', 'text/plain', { Location: para });

// Limite simples por IP (registrar/vincular)
const tentativas = new Map();
function limitar(ip, max = 20) {
  const t = agora();
  const l = (tentativas.get(ip) || []).filter((x) => t - x < 10 * 60 * 1000);
  l.push(t);
  tentativas.set(ip, l);
  return l.length > max;
}
const ipDe = (req) => String(req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').split(',')[0].trim();

// Sessão do admin: cookie assinado (HMAC), 12 h.
function criarSessao() {
  const exp = agora() + 12 * 3600 * 1000;
  return `${exp}.${crypto.createHmac('sha256', SEGREDO_SESSAO).update(String(exp)).digest('hex')}`;
}
function sessaoValida(req) {
  const m = /(?:^|;\s*)zk_box=([^;]+)/.exec(req.headers.cookie || '');
  if (!m) return false;
  const [exp, sig] = m[1].split('.');
  const certo = crypto.createHmac('sha256', SEGREDO_SESSAO).update(String(exp)).digest('hex');
  return Number(exp) > agora() && sig && sig.length === certo.length && crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(certo));
}

// ---------- leitura do APK (pacote e versão, sem dependências) ----------------------------

function lerManifestoApk(arquivo) {
  const b = fs.readFileSync(arquivo);
  // Zip: acha o diretório central
  let fimDir = -1;
  for (let i = b.length - 22; i >= Math.max(0, b.length - 70000); i--) if (b.readUInt32LE(i) === 0x06054b50) { fimDir = i; break; }
  if (fimDir < 0) throw new Error('não é um APK (zip) válido');
  const total = b.readUInt16LE(fimDir + 10);
  let p = b.readUInt32LE(fimDir + 16);
  let xml = null;
  for (let i = 0; i < total; i++) {
    if (b.readUInt32LE(p) !== 0x02014b50) break;
    const metodo = b.readUInt16LE(p + 10);
    const tamComp = b.readUInt32LE(p + 20);
    const nLen = b.readUInt16LE(p + 28), eLen = b.readUInt16LE(p + 30), cLen = b.readUInt16LE(p + 32);
    const local = b.readUInt32LE(p + 42);
    const nome = b.toString('utf8', p + 46, p + 46 + nLen);
    if (nome === 'AndroidManifest.xml') {
      const ini = local + 30 + b.readUInt16LE(local + 26) + b.readUInt16LE(local + 28);
      const dados = b.subarray(ini, ini + tamComp);
      xml = metodo === 8 ? zlib.inflateRawSync(dados) : dados;
      break;
    }
    p += 46 + nLen + eLen + cLen;
  }
  if (!xml) throw new Error('APK sem AndroidManifest.xml');
  // XML binário do Android: tabela de textos + primeira tag (<manifest>)
  let o = 8;
  let textos = [];
  while (o < xml.length) {
    const tipo = xml.readUInt16LE(o), cab = xml.readUInt16LE(o + 2), tam = xml.readUInt32LE(o + 4);
    if (tipo === 0x0001) {
      const qtd = xml.readUInt32LE(o + 8), flags = xml.readUInt32LE(o + 16), inicio = xml.readUInt32LE(o + 20);
      const utf8 = (flags & 0x100) !== 0;
      textos = [];
      for (let i = 0; i < qtd; i++) {
        let s = o + inicio + xml.readUInt32LE(o + cab + i * 4);
        if (utf8) {
          s += (xml[s] & 0x80) ? 2 : 1;
          let n = xml[s];
          if (n & 0x80) { n = ((n & 0x7f) << 8) | xml[s + 1]; s += 2; } else s += 1;
          textos.push(xml.toString('utf8', s, s + n));
        } else {
          let n = xml.readUInt16LE(s);
          if (n & 0x8000) { n = ((n & 0x7fff) << 16) | xml.readUInt16LE(s + 2); s += 4; } else s += 2;
          textos.push(xml.toString('utf16le', s, s + n * 2));
        }
      }
    } else if (tipo === 0x0102) {
      const attrIni = xml.readUInt16LE(o + 24), attrTam = xml.readUInt16LE(o + 26), attrQtd = xml.readUInt16LE(o + 28);
      const r = {};
      for (let i = 0; i < attrQtd; i++) {
        const a = o + 16 + attrIni + i * attrTam;
        const nome = textos[xml.readUInt32LE(a + 4)];
        const bruto = xml.readUInt32LE(a + 8);
        const tipoDado = xml[a + 15];
        const dado = xml.readUInt32LE(a + 16);
        r[nome] = bruto !== 0xffffffff ? textos[bruto] : (tipoDado === 0x10 ? dado : String(dado));
      }
      return { pacote: r.package || '', versao: r.versionName || '', codigo: Number(r.versionCode || 0) };
    }
    o += tam;
  }
  throw new Error('não achei o manifesto dentro do APK');
}

// ---------- clientes (login do ZarkTV nos servidores Xtream) ------------------------------

async function servidoresWeb() {
  try {
    const cfg = await (await fetch(CONFIG_WEB, { signal: AbortSignal.timeout(10000) })).json();
    return (cfg.servidores || cfg.servers || []).map((s) => ({ nome: s.nome || s.name || s.id, dns: (s.dns || s.urls || []).filter(Boolean) }));
  } catch { return []; }
}

/** Confere usuário/senha em cada servidor. Devolve { servidor, vence (ms), ativo } ou null. */
async function conferirLogin(usuario, senha, servidorPreferido) {
  const lista = await servidoresWeb();
  lista.sort((a, b) => (b.nome === servidorPreferido) - (a.nome === servidorPreferido));
  const q = `username=${encodeURIComponent(usuario)}&password=${encodeURIComponent(senha)}`;
  for (const sv of lista) {
    for (const dns of sv.dns.slice(0, 3)) {
      try {
        const r = await fetch(`${dns.replace(/\/+$/, '')}/player_api.php?${q}`, { signal: AbortSignal.timeout(8000) });
        const d = await r.json();
        const ui = d && d.user_info;
        if (ui && Number(ui.auth) === 1) {
          const exp = Number(ui.exp_date || 0) * 1000;
          return { servidor: sv.nome, vence: exp || null, ativo: String(ui.status || '').toLowerCase() === 'active' };
        }
        if (ui && Number(ui.auth) === 0) break; // servidor respondeu: não é deste servidor
      } catch { /* tenta o próximo DNS */ }
    }
  }
  return null;
}

async function reconferirCliente(box) {
  const c = box.cliente;
  if (!c || !c.senha || box.conferindo) return;
  box.conferindo = true;
  try {
    const r = await conferirLogin(c.usuario, c.senha, c.servidor);
    if (r) Object.assign(c, { servidor: r.servidor, vence: r.vence, ativo: r.ativo, conferido: agora() });
    else c.conferido = agora();
    salvarBoxes();
  } finally { box.conferindo = false; }
}

function diasParaVencer(box) {
  const v = box.cliente && box.cliente.vence;
  if (!v) return 999;
  return Math.floor((v - agora()) / 86400000);
}
const ehMinha = (box) => box.dono === 'minha';
function estaBloqueada(box, cfg) {
  if (!ehMinha(box)) return false; // box do cliente nunca é bloqueada
  if (box.bloqueadaManual) return true;
  if (cfg.bloquearVencidos && box.cliente && box.cliente.vence && box.cliente.vence < agora()) return true;
  return false;
}

// ---------- launcher: versão nova no GitHub -----------------------------------------------

let ultimaLauncher = null; // { codigo, version, url, sha256 }
async function conferirLauncherGithub() {
  try {
    const r = await fetch(VERSAO_LAUNCHER, { signal: AbortSignal.timeout(15000) });
    if (r.ok) ultimaLauncher = await r.json();
  } catch { /* tenta de novo mais tarde */ }
}
setInterval(conferirLauncherGithub, 30 * 60 * 1000);
conferirLauncherGithub();

// ---------- comandos ----------------------------------------------------------------------

function novoComando(box, cmd) {
  box.comandos = box.comandos || [];
  // não repete o mesmo comando ainda pendente
  const igual = box.comandos.find((c) => c.status === 'pendente' && c.tipo === cmd.tipo && c.pacote === cmd.pacote && c.url === cmd.url);
  if (igual) return igual;
  const c = { id: id(6), criado: agora(), status: 'pendente', tentativas: 0, ...cmd };
  box.comandos.unshift(c);
  box.comandos = box.comandos.slice(0, 60);
  salvarBoxes();
  return c;
}

function comandosParaEnviar(box) {
  const lista = [];
  for (const c of box.comandos || []) {
    const reenviar = c.status === 'enviado' && agora() - c.enviado > 20 * 60 * 1000 && c.tentativas < 5;
    if (c.status === 'pendente' || reenviar) {
      c.status = 'enviado';
      c.enviado = agora();
      c.tentativas++;
      const { id: cid, tipo, pacote, url, sha256, nome, obrigatoria } = c;
      lista.push({ id: cid, tipo, pacote, url, sha256, nome, obrigatoria });
    }
  }
  return lista.reverse(); // mais antigo primeiro
}

function comandoInstalarApk(apk, obrigatoria) {
  return {
    tipo: 'instalar', pacote: apk.pacote, nome: apk.nome, sha256: apk.sha256, obrigatoria: !!obrigatoria,
    url: `${URL_PUBLICA}/boxes/arquivos/${apk.id}.apk`, apk: apk.id,
  };
}

// ---------- API da box ----------------------------------------------------------------------

function fichaLimpa(f) {
  const campos = ['fabricante', 'modelo', 'placa', 'processador', 'android', 'sdk', 'modo', 'memoria_mb', 'launcher_versao', 'launcher_codigo', 'tela_inicial', 'rede', 'sinal'];
  const o = {};
  for (const k of campos) if (f[k] !== undefined) o[k] = typeof f[k] === 'string' ? f[k].slice(0, 120) : f[k];
  return o;
}
function appsLimpos(lista) {
  if (!Array.isArray(lista)) return [];
  return lista.slice(0, 600).map((a) => ({ p: String(a.p || '').slice(0, 150), n: String(a.n || '').slice(0, 80), v: String(a.v || '').slice(0, 40), c: Number(a.c || 0), s: !!a.s, on: a.on !== false }));
}

function novoCodigo() {
  const usados = new Set(Object.values(BOXES).map((b) => b.codigo));
  for (;;) {
    const c = 'ZK-' + String(1000 + crypto.randomInt(9000));
    if (!usados.has(c)) return c;
  }
}

// Banner no ar: ligado e dentro do período pago (início/fim). Fim conta o dia inteiro.
function bannersNoAr(cfg) {
  const hoje = new Date(new Date().toLocaleString('en-US', { timeZone: 'America/Sao_Paulo' }));
  const dia = hoje.toISOString().slice(0, 10);
  return cfg.banners.filter((b) => b.ativo !== false && (!b.inicio || b.inicio <= dia) && (!b.fim || b.fim >= dia));
}
function situacaoBanner(b) {
  const dia = new Date(new Date().toLocaleString('en-US', { timeZone: 'America/Sao_Paulo' })).toISOString().slice(0, 10);
  if (b.ativo === false) return '<span class="off">pausado</span>';
  if (b.inicio && b.inicio > dia) return '<span class="off">começa ' + esc(b.inicio.split('-').reverse().join('/')) + '</span>';
  if (b.fim && b.fim < dia) return '<span class="ver">venceu ' + esc(b.fim.split('-').reverse().join('/')) + '</span>';
  return '<span class="on">● no ar</span>' + (b.fim ? '<div class="dica">até ' + esc(b.fim.split('-').reverse().join('/')) + '</div>' : '');
}

// Atualização automática: o APK mais novo de cada pacote marcado "automático" vai para toda box
// que tem esse app numa versão mais velha (ex.: subiu o UniTV novo -> todas as boxes com UniTV atualizam).
function autoAtualizar(box) {
  const maisNovo = {};
  for (const a of apks()) {
    if (a.auto === false || !a.codigo) continue;
    if (!maisNovo[a.pacote] || a.codigo > maisNovo[a.pacote].codigo) maisNovo[a.pacote] = a;
  }
  for (const app of box.apps || []) {
    const a = maisNovo[app.p];
    if (a && app.c && app.c < a.codigo) {
      const ja = (box.comandos || []).find((c) => c.apk === a.id && ['pendente', 'enviado', 'ok'].includes(c.status));
      if (!ja) novoComando(box, { ...comandoInstalarApk(a, true), auto: true });
    }
  }
}

function respostaPing(box) {
  const cfg = config();
  const dias = diasParaVencer(box);
  const cli = box.cliente || {};
  const checkout = cfg.checkout ? `${cfg.checkout}?usuario=${encodeURIComponent(cli.usuario || '')}&servidor=${encodeURIComponent(cli.servidor || '')}` : '';
  const resp = {
    codigo: box.codigo,
    cliente: cli.usuario || '',
    vinculada: !!cli.usuario,
    bloqueada: estaBloqueada(box, cfg),
    vence_em_dias: dias,
    suporte: cfg.suporte,
    checkout_url: checkout,
    controle_total: ehMinha(box),
    bloquear_fora: ehMinha(box) && !!cfg.bloquearFora,
    banners: bannersNoAr(cfg).map((b) => ({ selo: b.selo, titulo: b.titulo, sub: b.sub, imagem: b.imagem, link: b.link || '', link_texto: b.linkTexto || '' })),
    aviso: cfg.aviso && cfg.aviso.ativo ? { id: cfg.aviso.id, titulo: cfg.aviso.titulo, texto: cfg.aviso.texto, imagem: cfg.aviso.imagem } : null,
    loja: cfg.loja,
    iniciais: cfg.iniciais,
    lixo: ehMinha(box) ? cfg.lixo : [],
    limpeza: ehMinha(box) && !!cfg.limpeza,
    permitidos: ehMinha(box) ? cfg.permitidos : [],
    comandos: comandosParaEnviar(box),
  };
  return resp;
}

async function apiBox(req, res, rota) {
  const ip = ipDe(req);
  const corpo = await lerCorpo(req, 600_000);

  if (rota === '/boxes/api/registrar') {
    if (limitar(ip, 30)) return json(res, 429, { erro: 'Muitas tentativas' });
    const idAp = String(corpo.id_aparelho || '').slice(0, 120);
    // mesma box reinstalando a launcher: devolve o mesmo cadastro
    let box = idAp ? Object.values(BOXES).find((b) => b.idAparelho === idAp) : null;
    if (!box) {
      const token = id(24);
      box = BOXES[token] = { token, codigo: novoCodigo(), idAparelho: idAp, criado: agora(), comandos: [] };
    }
    box.ficha = fichaLimpa(corpo);
    box.apps = appsLimpos(corpo.apps);
    box.visto = agora();
    box.ip = ip;
    salvarBoxes();
    return json(res, 200, { token: box.token, codigo: box.codigo });
  }

  const box = BOXES[String(corpo.token || '')];
  if (!box) return json(res, 200, { token_invalido: true });

  if (rota === '/boxes/api/ping') {
    box.ficha = fichaLimpa(corpo);
    box.apps = appsLimpos(corpo.apps);
    box.visto = agora();
    box.ip = ip;
    // Launcher nova no GitHub: atualização obrigatória automática
    const cfg = config();
    if (cfg.launcherAuto && ultimaLauncher && ultimaLauncher.codigo > Number(box.ficha.launcher_codigo || 0)) {
      novoComando(box, { tipo: 'instalar', pacote: PACOTE_LAUNCHER, nome: 'ZarkTV Launcher ' + ultimaLauncher.version, url: ultimaLauncher.url, sha256: ultimaLauncher.sha256, obrigatoria: true, auto: true });
    }
    autoAtualizar(box);
    if (box.cliente && box.cliente.senha && agora() - (box.cliente.conferido || 0) > 6 * 3600 * 1000) reconferirCliente(box);
    const r = respostaPing(box);
    salvarBoxes();
    return json(res, 200, r);
  }

  if (rota === '/boxes/api/vincular') {
    if (limitar(ip, 15)) return json(res, 429, { erro: 'Muitas tentativas. Espere alguns minutos.' });
    const usuario = String(corpo.usuario || '').trim().slice(0, 80);
    const senha = String(corpo.senha || '').slice(0, 80);
    if (!usuario || !senha) return json(res, 400, { erro: 'Preencha usuário e senha.' });
    const r = await conferirLogin(usuario, senha);
    if (!r) return json(res, 200, { ok: false, erro: 'Usuário ou senha não conferem em nenhum servidor.' });
    box.cliente = { usuario, senha, servidor: r.servidor, vence: r.vence, ativo: r.ativo, conferido: agora(), desde: agora() };
    salvarBoxes();
    return json(res, 200, { ok: true, cliente: usuario, servidor: r.servidor, vence_em_dias: diasParaVencer(box) });
  }

  if (rota === '/boxes/api/comando') {
    const c = (box.comandos || []).find((x) => x.id === String(corpo.id || ''));
    if (c) {
      c.status = corpo.ok ? 'ok' : 'erro';
      c.resposta = String(corpo.erro || '').slice(0, 300);
      c.respondido = agora();
      salvarBoxes();
    }
    return json(res, 200, { ok: true });
  }

  return json(res, 404, { erro: 'não encontrado' });
}

// ---------- páginas do admin --------------------------------------------------------------

const CSS = `*{box-sizing:border-box}body{margin:0;background:#0b0b0d;color:#f5f5f7;font:15px/1.45 system-ui,-apple-system,Segoe UI,Roboto,sans-serif}
a{color:#ff4d57}.wrap{max-width:1180px;margin:0 auto;padding:22px 16px 60px}
.topo{display:flex;align-items:center;gap:18px;flex-wrap:wrap;margin-bottom:18px}.logo{font-size:24px;font-weight:800}.logo b{color:#e50914}
nav a{color:#b0b0b8;text-decoration:none;margin-right:14px;font-weight:600}nav a.ativo{color:#fff;border-bottom:2px solid #e50914}
.card{background:#16161a;border:1px solid #26262c;border-radius:16px;padding:18px;margin-bottom:16px}
h1{font-size:22px;margin:0 0 12px}h2{font-size:17px;margin:0 0 10px}
.resumo{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:12px;margin-bottom:16px}
.num{background:#16161a;border:1px solid #26262c;border-radius:14px;padding:14px}.num b{display:block;font-size:26px}.num span{color:#9a9aa2;font-size:13px}
table{width:100%;border-collapse:collapse}th,td{text-align:left;padding:9px 8px;border-bottom:1px solid #222228;vertical-align:top}th{color:#9a9aa2;font-weight:600;font-size:13px}
tr.lnk{cursor:pointer}tr.lnk:hover{background:#1c1c21}
.on{color:#3ddc84}.off{color:#8a8a92}.ver{color:#ff4d57}
.tag{display:inline-block;padding:2px 8px;border-radius:20px;font-size:12px;font-weight:700;background:#26262c}
.tag.dona{background:#0f3d24;color:#3ddc84}.tag.root{background:#3d2f0f;color:#ffb020}.tag.simples{background:#26262c;color:#b0b0b8}
.tag.bloq{background:#4a0d10;color:#ff6b72}
input,select,textarea{width:100%;background:#0f0f12;color:#fff;border:1px solid #33333a;border-radius:10px;padding:10px;font:inherit;margin:4px 0 10px}
label{font-size:13px;color:#b0b0b8}.linha{display:flex;gap:10px;flex-wrap:wrap}.linha>*{flex:1;min-width:160px}
button,.btn{display:inline-block;background:#e50914;color:#fff;border:0;border-radius:10px;padding:10px 16px;font-weight:700;cursor:pointer;text-decoration:none;font:inherit;font-weight:700}
button.cinza,.btn.cinza{background:#2a2a30}button.pequeno{padding:5px 10px;font-size:13px}form.inline{display:inline}
.msg{background:#0f3d24;color:#3ddc84;padding:10px 14px;border-radius:10px;margin-bottom:14px}.erro{background:#4a0d10;color:#ff6b72}
.dica{color:#8a8a92;font-size:13px}.chk{display:flex;align-items:center;gap:8px;margin:6px 0}.chk input{width:auto;margin:0}
.apps td{padding:6px 8px;font-size:14px}`;

function pagina(titulo, corpo, aba = '') {
  const nav = [['/boxes/admin', 'Boxes', 'boxes'], ['/boxes/admin/apks', 'APKs', 'apks'], ['/boxes/admin/tela', 'Tela da box', 'tela']]
    .map(([h, n, k]) => `<a href="${h}" class="${aba === k ? 'ativo' : ''}">${n}</a>`).join('');
  return `<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>${esc(titulo)} · ZarkTV Cloud</title><style>${CSS}</style></head><body><div class="wrap">
<div class="topo"><div class="logo">▶ Zark<b>TV</b> · Boxes</div><nav>${nav}</nav>
<form method="post" action="/boxes/admin/sair" style="margin-left:auto"><button class="cinza pequeno">Sair</button></form></div>${corpo}</div></body></html>`;
}

function paginaLogin(erro) {
  return `<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Entrar · Boxes</title><style>${CSS}</style></head>
<body><div class="wrap" style="max-width:420px;padding-top:12vh"><div class="logo" style="text-align:center;margin-bottom:18px">▶ Zark<b>TV</b> · Boxes</div>
<form class="card" method="post" action="/boxes/admin/login">${erro ? `<div class="msg erro">${esc(erro)}</div>` : ''}
<label>Senha do painel</label><input type="password" name="senha" autofocus required><button style="width:100%">Entrar</button></form></div></body></html>`;
}

function online(box) { return box.visto && agora() - box.visto < ONLINE_MS; }

function paginaBoxes(msg) {
  const cfg = config();
  const lista = Object.values(BOXES).sort((a, b) => (b.visto || 0) - (a.visto || 0));
  const on = lista.filter(online).length;
  const modos = { dona: 0, root: 0, simples: 0 };
  for (const b of lista) if (b.ficha && modos[b.ficha.modo] !== undefined) modos[b.ficha.modo]++;
  const ult = ultimaLauncher ? Number(ultimaLauncher.codigo) : 0;
  const linhas = lista.map((b) => {
    const f = b.ficha || {};
    const cli = b.cliente || {};
    const dias = diasParaVencer(b);
    const desatualizada = ult && Number(f.launcher_codigo || 0) < ult;
    return `<tr class="lnk" onclick="location='/boxes/admin/box?t=${esc(b.token)}'">
<td><b>${esc(b.codigo)}</b> ${ehMinha(b) ? '<span class="tag dona">minha</span>' : '<span class="tag">do cliente</span>'}${estaBloqueada(b, cfg) ? ' <span class="tag bloq">bloqueada</span>' : ''}</td>
<td>${cli.usuario ? `${esc(cli.usuario)}<div class="dica">${esc(cli.servidor || '')}${cli.vence ? ' · vence ' + new Date(cli.vence).toLocaleDateString('pt-BR') + (dias <= 5 ? ` <span class="ver">(${dias < 0 ? 'vencido' : dias + ' dias'})</span>` : '') : ''}</div>` : '<span class="off">sem cliente</span>'}</td>
<td>${esc(f.fabricante || '')} ${esc(f.modelo || '')}<div class="dica">${esc(f.processador || '')} · ${f.memoria_mb ? Math.round(f.memoria_mb / 100) / 10 + ' GB' : ''}</div></td>
<td>Android ${esc(f.android || '?')}<div class="dica">API ${esc(f.sdk || '?')}</div></td>
<td><span class="tag ${esc(f.modo || 'simples')}">${esc(f.modo || '?')}</span></td>
<td>${esc(f.launcher_versao || '?')}${desatualizada ? ' <span class="ver">↑</span>' : ''}</td>
<td class="${online(b) ? 'on' : 'off'}">${online(b) ? '● online' : '○ ' + quando(b.visto)}<div class="dica">${esc(f.rede === 'wifi' ? 'Wi-Fi ' + (f.sinal ?? '') + '/4' : f.rede === 'cabo' ? 'cabo' : '')}</div></td></tr>`;
  }).join('');
  return pagina('Boxes', `${msg ? `<div class="msg">${esc(msg)}</div>` : ''}
<div class="resumo"><div class="num"><b>${lista.length}</b><span>boxes com a launcher</span></div>
<div class="num"><b class="on">${on}</b><span>online agora</span></div>
<div class="num"><b>${lista.filter(ehMinha).length}</b><span>boxes minhas (controle total)</span></div>
<div class="num"><b>${modos.dona}</b><span>modo dona (controle total)</span></div>
<div class="num"><b>${modos.root}</b><span>modo root</span></div>
<div class="num"><b>${modos.simples}</b><span>modo simples</span></div>
<div class="num"><b>${ultimaLauncher ? esc(ultimaLauncher.version) : '—'}</b><span>launcher mais nova (GitHub)</span></div></div>
<div class="card"><h1>Boxes</h1>
${lista.length ? `<table><tr><th>Box</th><th>Cliente</th><th>Aparelho</th><th>Sistema</th><th>Modo</th><th>Launcher</th><th>Situação</th></tr>${linhas}</table>`
    : '<p class="dica">Nenhuma box ainda. Assim que você instalar a ZarkTV Launcher numa box com internet, ela aparece aqui sozinha.</p>'}</div>
<div class="card"><h2>Box minha × box do cliente</h2><p class="dica">Toda box nova entra como <b>do cliente</b> (livre: nada é bloqueado e o aviso de vencimento aparece só dentro do app).
Abra a box e marque <b>minha</b> quando a box for sua: aí a launcher não sai, só entra o que você mandar e, se quiser, bloqueia quando vencer.</p></div>
<div class="card"><h2>O que é cada modo</h2><p class="dica"><b>dona</b>: a launcher manda na box (configurada uma vez pelo ADB). Instala e atualiza sem o cliente clicar, esconde apps e bloqueia instalações de fora.<br>
<b>root</b>: a box tem root. A launcher faz quase tudo o que a "dona" faz.<br>
<b>simples</b>: só tela inicial. Instalações e atualizações pedem um clique do cliente.</p></div>`, 'boxes');
}

function paginaBox(box, msg) {
  const cfg = config();
  const f = box.ficha || {};
  const cli = box.cliente || {};
  const lib = apks();
  const opcoesApk = lib.map((a) => `<option value="${esc(a.id)}">${esc(a.nome)} · ${esc(a.pacote)} ${esc(a.versao)}</option>`).join('');
  const lixo = new Set(cfg.lixo);
  const apps = (box.apps || []).slice().sort((a, b) => (a.s - b.s) || a.n.localeCompare(b.n));
  const linhasApps = apps.map((a) => `<tr><td>${esc(a.n)}<div class="dica">${esc(a.p)} ${esc(a.v)}</div></td>
<td>${a.s ? '<span class="tag">sistema</span>' : ''} ${a.on ? '' : '<span class="tag">escondido</span>'} ${lixo.has(a.p) ? '<span class="tag bloq">lixo</span>' : ''}</td>
<td style="white-space:nowrap">${a.p === PACOTE_LAUNCHER ? '' : `
<form class="inline" method="post" action="/boxes/admin/box/cmd"><input type="hidden" name="t" value="${esc(box.token)}"><input type="hidden" name="pacote" value="${esc(a.p)}">
${a.on ? `<button class="cinza pequeno" name="tipo" value="esconder">Esconder</button>` : `<button class="cinza pequeno" name="tipo" value="mostrar">Mostrar</button>`}
<button class="cinza pequeno" name="tipo" value="desinstalar" onclick="return confirm('Desinstalar ${esc(a.n)} desta box?')">Desinstalar</button>
${lixo.has(a.p) ? '' : `<button class="cinza pequeno" name="tipo" value="lixo" title="Esconde este app em todas as boxes minhas">Lixo nas minhas</button>`}</form>`}</td></tr>`).join('');
  const cmds = (box.comandos || []).slice(0, 25).map((c) => `<tr><td>${data(c.criado)}</td><td>${esc(c.tipo)} ${esc(c.nome || c.pacote || '')}${c.obrigatoria ? ' <span class="tag">obrigatória</span>' : ''}${c.auto ? ' <span class="tag">automática</span>' : ''}</td>
<td class="${c.status === 'ok' ? 'on' : c.status === 'erro' ? 'ver' : 'off'}">${esc({ pendente: 'esperando a box', enviado: 'enviado', ok: 'feito', erro: 'erro' }[c.status] || c.status)}<div class="dica">${esc(c.resposta || '')}</div></td></tr>`).join('');
  const bloq = estaBloqueada(box, cfg);
  return pagina('Box ' + box.codigo, `${msg ? `<div class="msg">${esc(msg)}</div>` : ''}
<div class="card"><h1>Box ${esc(box.codigo)} ${bloq ? '<span class="tag bloq">bloqueada</span>' : ''} <span class="${online(box) ? 'on' : 'off'}" style="font-size:15px">${online(box) ? '● online' : '○ visto ' + quando(box.visto)}</span></h1>
<div class="linha"><div><label>Aparelho</label><div>${esc(f.fabricante || '')} ${esc(f.modelo || '')}<div class="dica">${esc(f.processador || '')} · placa ${esc(f.placa || '')}</div></div></div>
<div><label>Sistema</label><div>Android ${esc(f.android || '?')} (API ${esc(f.sdk || '?')})<div class="dica">${f.memoria_mb ? Math.round(f.memoria_mb) + ' MB de memória' : ''}</div></div></div>
<div><label>Modo</label><div><span class="tag ${esc(f.modo || 'simples')}">${esc(f.modo || '?')}</span><div class="dica">${f.tela_inicial ? 'é a tela inicial' : 'ainda não é a tela inicial'}</div></div></div>
<div><label>Launcher</label><div>${esc(f.launcher_versao || '?')}<div class="dica">${esc(f.rede === 'wifi' ? 'Wi-Fi ' + (f.sinal ?? '') + '/4' : f.rede || '')} · IP ${esc(box.ip || '')}</div></div></div></div></div>

<form class="card" method="post" action="/boxes/admin/box/acao"><input type="hidden" name="t" value="${esc(box.token)}"><h2>De quem é esta box?</h2>
${ehMinha(box) ? `<p><span class="tag dona">Box minha · controle total</span> A launcher fica fixa e não sai, só instala o que vem do painel, esconde o lixo${cfg.bloquearVencidos ? ' e bloqueia com o Pix quando o cliente vencer' : ''}.</p><button class="cinza" name="acao" value="dono_cliente">Mudar para box do cliente (liberar)</button>`
    : `<p><span class="tag">Box do cliente · livre</span> O cliente usa a box como quiser: instala outros apps, troca de tela inicial e nada é bloqueado. Quando vencer, o aviso aparece só dentro do app (ZarkPlay).</p><button name="acao" value="dono_minha">Mudar para box minha (controle total)</button>`}</form>

<div class="card"><h2>Cliente</h2>${cli.usuario ? `<p><b>${esc(cli.usuario)}</b> · servidor ${esc(cli.servidor || '?')} · ${cli.vence ? 'vence em ' + new Date(cli.vence).toLocaleDateString('pt-BR') + ` (${diasParaVencer(box)} dias)` : 'sem data de vencimento'}<br><span class="dica">conferido ${quando(cli.conferido)}</span></p>` : '<p class="dica">Ainda não entrou com o login do ZarkTV nesta box.</p>'}
<form class="inline" method="post" action="/boxes/admin/box/acao"><input type="hidden" name="t" value="${esc(box.token)}">
${!ehMinha(box) ? '' : box.bloqueadaManual ? '<button name="acao" value="desbloquear">Desbloquear box</button>' : '<button class="cinza" name="acao" value="bloquear">Bloquear box</button>'}
${cli.usuario ? '<button class="cinza" name="acao" value="conferir">Conferir vencimento agora</button> <button class="cinza" name="acao" value="desvincular" onclick="return confirm(\'Tirar o cliente desta box?\')">Tirar cliente</button>' : ''}
<button class="cinza" name="acao" value="limpar">Limpar memória</button>
<button class="cinza" name="acao" value="reiniciar" onclick="return confirm('Reiniciar a box?')">Reiniciar</button>
<button class="cinza" name="acao" value="apagar" onclick="return confirm('Apagar esta box do painel? Ela se cadastra de novo quando conectar.')">Apagar do painel</button></form></div>

<div class="card"><h2>Instalar ou atualizar app nesta box</h2>
${lib.length ? `<form method="post" action="/boxes/admin/box/instalar"><input type="hidden" name="t" value="${esc(box.token)}"><div class="linha"><div><select name="apk">${opcoesApk}</select></div></div>
<label class="chk"><input type="checkbox" name="obrigatoria" value="1" checked> Atualização obrigatória (instala na hora, mostra "Instalando atualização")</label><button>Mandar para esta box</button></form>`
    : '<p class="dica">Suba um APK na aba <a href="/boxes/admin/apks">APKs</a> primeiro.</p>'}</div>

<div class="card"><h2>Histórico de comandos</h2>${cmds ? `<table>${cmds}</table>` : '<p class="dica">Nenhum comando ainda.</p>'}</div>
<div class="card"><h2>Apps desta box (${apps.length})</h2><p class="dica">"Esconder" e "Desinstalar" só funcionam sozinhos nos modos dona e root. "Lixo nas minhas" esconde o app em todas as boxes marcadas como suas.</p>
<table class="apps">${linhasApps}</table></div>`, 'boxes');
}

function paginaApks(msg) {
  const lib = apks();
  const linhas = lib.map((a) => `<tr><td><b>${esc(a.nome)}</b><div class="dica">${esc(a.pacote)} · versão ${esc(a.versao)} (${esc(a.codigo)})</div>
<div class="dica">${Object.values(BOXES).filter((b) => (b.apps || []).some((x) => x.p === a.pacote)).length} boxes têm este app</div>
<form class="inline" method="post" action="/boxes/admin/apks/auto"><input type="hidden" name="apk" value="${esc(a.id)}">
${a.auto === false ? '<button class="cinza pequeno">Ligar atualização automática</button>' : '<span class="tag dona">atualiza sozinho</span> <button class="cinza pequeno">Desligar</button>'}</form></td>
<td>${(a.tamanho / 1048576).toFixed(1)} MB<div class="dica">${data(a.criado)}</div></td>
<td style="white-space:nowrap"><form class="inline" method="post" action="/boxes/admin/apks/todas"><input type="hidden" name="apk" value="${esc(a.id)}">
<label class="chk" style="display:inline-flex"><input type="checkbox" name="obrigatoria" value="1" checked> obrigatória</label>
<button class="pequeno" onclick="return confirm('Mandar ${esc(a.nome)} para TODAS as boxes?')">Mandar para todas</button></form>
<form class="inline" method="post" action="/boxes/admin/apks/apagar"><input type="hidden" name="apk" value="${esc(a.id)}"><button class="cinza pequeno" onclick="return confirm('Apagar este APK?')">Apagar</button></form>
<div class="dica"><a href="${esc(URL_PUBLICA)}/boxes/arquivos/${esc(a.id)}.apk">link do APK</a></div></td></tr>`).join('');
  return pagina('APKs', `${msg ? `<div class="msg">${esc(msg)}</div>` : ''}
<div class="card"><h1>Subir APK</h1><p class="dica">Escolha o arquivo .apk. O painel lê sozinho o nome do pacote e a versão.
<b>Atualização automática:</b> subiu a versão nova (ex.: UniTV), toda box que tem esse app numa versão mais velha atualiza sozinha, sem o cliente fazer nada (no modo simples aparece "Atualizar agora").
Para atualizar a própria launcher, suba o APK dela aqui (ou deixe ligada a atualização automática pelo GitHub na aba "Tela da box").</p>
<label>Nome que aparece</label><input id="nome" placeholder="Ex.: ZarkPlay">
<input type="file" id="arq" accept=".apk"><button id="subir" onclick="subir()">Subir APK</button> <span id="st" class="dica"></span></div>
<div class="card"><h1>Biblioteca</h1>${lib.length ? `<table>${linhas}</table>` : '<p class="dica">Nenhum APK ainda.</p>'}</div>
<script>
async function subir(){const f=document.getElementById('arq').files[0];if(!f){alert('Escolha o APK');return}
const st=document.getElementById('st');const b=document.getElementById('subir');b.disabled=true;st.textContent='Enviando… (não feche a página)';
try{const r=await fetch('/boxes/admin/apks/subir?nome='+encodeURIComponent(document.getElementById('nome').value||f.name),{method:'POST',body:f,headers:{'Content-Type':'application/vnd.android.package-archive'}});
const d=await r.json();if(d.erro){st.textContent='Erro: '+d.erro;b.disabled=false;return}location='/boxes/admin/apks?ok='+encodeURIComponent('APK '+d.pacote+' '+d.versao+' salvo.')}
catch(e){st.textContent='Falhou: '+e.message;b.disabled=false}}
</script>`, 'apks');
}

function linhaApp(a, i, grupo, comChave) {
  return `<tr><td>${comChave ? `<span class="tag">${esc(a.chave || 'outro')}</span> ` : ''}<b>${esc(a.nome)}</b><div class="dica">${esc(a.pacote)}</div></td><td class="dica" style="word-break:break-all">${esc(a.url)}</td>
<td><form class="inline" method="post" action="/boxes/admin/tela/remover"><input type="hidden" name="grupo" value="${grupo}"><input type="hidden" name="i" value="${i}"><button class="cinza pequeno">Tirar</button></form></td></tr>`;
}

function formApp(grupo, comChave) {
  const lib = apks();
  return `<form method="post" action="/boxes/admin/tela/app"><input type="hidden" name="grupo" value="${grupo}"><div class="linha">
${comChave ? `<div><label>Cartão da tela inicial</label><select name="chave"><option value="youtube">YouTube</option><option value="zarkplay">ZarkPlay</option><option value="outro">Ícone próprio na tela inicial (ex.: UniTV)</option></select></div>` : ''}
<div><label>Nome</label><input name="nome" required></div>
<div><label>APK da biblioteca</label><select name="apk"><option value="">— usar o endereço abaixo —</option>${lib.map((a) => `<option value="${esc(a.id)}">${esc(a.nome)} · ${esc(a.pacote)}</option>`).join('')}</select></div></div>
<div class="linha"><div><label>ou endereço https do APK</label><input name="url" placeholder="https://..."></div><div><label>Pacote (se usar endereço)</label><input name="pacote" placeholder="com.exemplo.app"></div></div>
<button class="cinza">Adicionar</button></form>`;
}

function paginaTela(msg) {
  const cfg = config();
  const banners = cfg.banners.map((b, i) => `<tr><td>${b.imagem ? `<img src="${esc(b.imagem)}" style="width:150px;height:60px;object-fit:cover;border-radius:8px">` : '<span class="dica">sem imagem</span>'}</td>
<td>${b.selo ? `<span class="tag">${esc(b.selo)}</span> ` : ''}<b>${esc(b.titulo)}</b><div class="dica">${esc(b.sub)}</div>
${b.anunciante ? `<div class="dica">Anunciante: <b>${esc(b.anunciante)}</b>${b.valor ? ' · ' + esc(b.valor) : ''}${b.contato ? ' · ' + esc(b.contato) : ''}</div>` : '<div class="dica">Banner do ZarkTV</div>'}
${b.link ? `<div class="dica">QR na tela: ${esc(b.link)}</div>` : ''}</td>
<td>${situacaoBanner(b)}</td>
<td style="white-space:nowrap"><form class="inline" method="post" action="/boxes/admin/tela/banner/pausar"><input type="hidden" name="i" value="${i}"><button class="cinza pequeno">${b.ativo === false ? 'Ligar' : 'Pausar'}</button></form>
<form class="inline" method="post" action="/boxes/admin/tela/remover"><input type="hidden" name="grupo" value="banners"><input type="hidden" name="i" value="${i}"><button class="cinza pequeno" onclick="return confirm('Tirar este banner?')">Tirar</button></form></td></tr>`).join('');
  const noAr = bannersNoAr(cfg).length;
  const totalBoxes = Object.keys(BOXES).length;
  const av = cfg.aviso || {};
  return pagina('Tela da box', `${msg ? `<div class="msg">${esc(msg)}</div>` : ''}
<div class="card"><h1>Banners e anúncios</h1><p class="dica">${noAr} no ar agora, aparecendo em ${totalBoxes} boxes. Trocam a cada 8 segundos.
Para anúncio pago (ex.: promoção do Point do Açaí ou de outra loja de Quixeramobim), preencha o anunciante e o período: o banner entra e sai do ar sozinho nas datas.</p>
${banners ? `<table>${banners}</table>` : ''}
<form method="post" action="/boxes/admin/tela/banner" style="margin-top:18px" onsubmit="return mandarImg(this)"><h2>Novo banner</h2>
<label>Imagem deitada (ideal 1920×600). Se a arte já tiver os textos, deixe título e texto em branco.</label><input type="file" accept="image/*" data-img="1"><input type="hidden" name="imagem">
<div class="linha"><div><label>Selo</label><input name="selo" placeholder="Promoção, Jogo hoje, Patrocinado…"></div><div><label>Título</label><input name="titulo" placeholder="Açaí 500 ml por R$ 15"></div></div>
<label>Texto menor</label><input name="sub" placeholder="Só hoje no Point do Açaí · Quixeramobim">
<div class="linha"><div><label>QR Code na tela (link do WhatsApp, cardápio ou Instagram)</label><input name="link" placeholder="https://wa.me/5588..."></div>
<div><label>Texto do QR</label><input name="linkTexto" placeholder="Peça pelo WhatsApp"></div></div>
<h2 style="margin-top:8px">Anúncio pago (opcional)</h2>
<div class="linha"><div><label>Anunciante</label><input name="anunciante" placeholder="Point do Açaí"></div><div><label>Contato do anunciante</label><input name="contato" placeholder="(88) 9...."></div><div><label>Valor combinado</label><input name="valor" placeholder="R$ 50/mês"></div></div>
<div class="linha"><div><label>Começa em</label><input type="date" name="inicio"></div><div><label>Termina em</label><input type="date" name="fim"></div></div>
<button>Adicionar banner</button></form></div>

<form class="card" method="post" action="/boxes/admin/tela/aviso" onsubmit="return mandarImg(this)"><h1>Aviso em tela cheia</h1><p class="dica">Aparece uma vez em cada box (até o cliente apertar "OK, entendi"). Mudou o texto, aparece de novo.</p>
<label class="chk"><input type="checkbox" name="ativo" value="1" ${av.ativo ? 'checked' : ''}> Aviso ligado</label>
<label>Título</label><input name="titulo" value="${esc(av.titulo)}"><label>Texto</label><textarea name="texto" rows="3">${esc(av.texto)}</textarea>
<label>Imagem (opcional)</label><input type="file" accept="image/*" data-img="1"><input type="hidden" name="imagem" value="${esc(av.imagem)}"><button>Salvar aviso</button></form>

<div class="card"><h1>Apps que toda box tem</h1><p class="dica">A launcher instala sozinha (modos dona e root). No modo simples, instala quando o cliente abrir o cartão.
Para o YouTube em box antiga (Android 4.4 a 7), use um APK compatível com TV Box, porque o YouTube oficial não instala nelas.</p>
<table>${cfg.iniciais.map((a, i) => linhaApp(a, i, 'iniciais', true)).join('')}</table>${formApp('iniciais', true)}</div>

<div class="card"><h1>Lojinha da box</h1><table>${cfg.loja.map((a, i) => linhaApp(a, i, 'loja', false)).join('')}</table>${formApp('loja', false)}</div>

<form class="card" method="post" action="/boxes/admin/tela/regras"><h1>Regras</h1>
<p class="dica">Estas duas regras valem só para as boxes marcadas como <b>minha</b>. Box do cliente fica sempre livre.</p>
<label class="chk"><input type="checkbox" name="bloquearFora" value="1" ${cfg.bloquearFora ? 'checked' : ''}> Não deixar instalar nada que não venha da launcher (modo dona)</label>
<label class="chk"><input type="checkbox" name="bloquearVencidos" value="1" ${cfg.bloquearVencidos ? 'checked' : ''}> Bloquear a box quando o cliente vencer (mostra o Pix)</label>
<label class="chk"><input type="checkbox" name="limpeza" value="1" ${cfg.limpeza ? 'checked' : ''}> Limpeza total: deixar só os nossos apps, o YouTube e os permitidos (apaga o resto e esconde os apps de fábrica)</label>
<label>Apps permitidos nas boxes minhas, além dos nossos (um pacote por linha)</label><textarea name="permitidos" rows="3">${esc((cfg.permitidos || []).join('\n'))}</textarea>
<label class="chk"><input type="checkbox" name="launcherAuto" value="1" ${cfg.launcherAuto ? 'checked' : ''}> Atualizar a launcher sozinha quando sair versão nova no GitHub</label>
<div class="linha"><div><label>WhatsApp de suporte (aparece no bloqueio)</label><input name="suporte" value="${esc(cfg.suporte)}"></div>
<div><label>Link do checkout Pix</label><input name="checkout" value="${esc(cfg.checkout)}"></div></div>
<label>Lixo: pacotes para esconder nas boxes minhas (um por linha)</label><textarea name="lixo" rows="5">${esc(cfg.lixo.join('\n'))}</textarea>
<button>Salvar regras</button></form>
<script>
async function mandarImg(form){const f=form.querySelector('input[type=file][data-img]');if(!f||!f.files[0])return true;
const r=await fetch('/boxes/admin/imagem?nome='+encodeURIComponent(f.files[0].name),{method:'POST',body:f.files[0]});const d=await r.json();
if(d.erro){alert(d.erro);return false}form.querySelector('input[name=imagem]').value=d.url;f.value='';form.submit();return false}
</script>`, 'tela');
}

// ---------- upload em fluxo (APK e imagens) -----------------------------------------------

function receberArquivo(req, destino, limite) {
  return new Promise((ok, erro) => {
    const hash = crypto.createHash('sha256');
    const out = fs.createWriteStream(destino, { mode: 0o600 });
    let tam = 0;
    req.on('data', (c) => {
      tam += c.length;
      if (tam > limite) { req.destroy(); out.destroy(); fs.rm(destino, () => {}); erro(new Error('arquivo grande demais')); return; }
      hash.update(c);
    });
    req.pipe(out);
    out.on('finish', () => ok({ tamanho: tam, sha256: hash.digest('hex') }));
    out.on('error', erro);
    req.on('error', erro);
  });
}

// ---------- rotas do admin ----------------------------------------------------------------

async function admin(req, res, rota, url) {
  if (rota === '/boxes/admin/login' && req.method === 'POST') {
    const c = await lerCorpo(req);
    const certo = crypto.createHash('sha256').update(ADMIN_SENHA).digest();
    const dado = crypto.createHash('sha256').update(String(c.senha || '')).digest();
    if (limitar(ipDe(req), 10) || !crypto.timingSafeEqual(certo, dado)) return enviar(res, 200, paginaLogin('Senha errada.'));
    return enviar(res, 303, '', 'text/plain', { Location: '/boxes/admin', 'Set-Cookie': `zk_box=${criarSessao()}; Path=/boxes; HttpOnly; Secure; SameSite=Strict; Max-Age=43200` });
  }
  if (!sessaoValida(req)) return enviar(res, 200, paginaLogin());
  const ok = url.searchParams.get('ok') || '';

  if (req.method === 'POST' && rota === '/boxes/admin/sair') {
    return enviar(res, 303, '', 'text/plain', { Location: '/boxes/admin', 'Set-Cookie': 'zk_box=; Path=/boxes; Max-Age=0' });
  }
  if (req.method === 'GET' && rota === '/boxes/admin') return enviar(res, 200, paginaBoxes(ok));
  if (req.method === 'GET' && rota === '/boxes/admin/box') {
    const box = BOXES[url.searchParams.get('t') || ''];
    return box ? enviar(res, 200, paginaBox(box, ok)) : voltar(res, '/boxes/admin');
  }
  if (req.method === 'GET' && rota === '/boxes/admin/apks') return enviar(res, 200, paginaApks(ok));
  if (req.method === 'GET' && rota === '/boxes/admin/tela') return enviar(res, 200, paginaTela(ok));

  // --- boxes ---
  if (req.method === 'POST' && rota === '/boxes/admin/box/cmd') {
    const c = await lerCorpo(req);
    const box = BOXES[c.t];
    if (!box) return voltar(res, '/boxes/admin');
    const pacote = String(c.pacote || '');
    if (!pacoteValido(pacote)) return voltar(res, `/boxes/admin/box?t=${box.token}&ok=Pacote+inválido`);
    if (c.tipo === 'lixo') {
      const cfg = config();
      if (!cfg.lixo.includes(pacote)) cfg.lixo.push(pacote);
      salvarConfig(cfg);
      return voltar(res, `/boxes/admin/box?t=${box.token}&ok=${encodeURIComponent(pacote + ' vai ser escondido em todas as boxes.')}`);
    }
    if (!['esconder', 'mostrar', 'desinstalar'].includes(c.tipo)) return voltar(res, `/boxes/admin/box?t=${box.token}`);
    novoComando(box, { tipo: c.tipo, pacote });
    return voltar(res, `/boxes/admin/box?t=${box.token}&ok=${encodeURIComponent('Comando enviado. A box faz na próxima conversa (até 15 min, ou na hora quando o cliente volta para o início).')}`);
  }
  if (req.method === 'POST' && rota === '/boxes/admin/box/acao') {
    const c = await lerCorpo(req);
    const box = BOXES[c.t];
    if (!box) return voltar(res, '/boxes/admin');
    let m = 'Feito.';
    if (c.acao === 'dono_minha') { box.dono = 'minha'; m = 'Box marcada como sua: controle total na próxima conversa.'; }
    else if (c.acao === 'dono_cliente') { box.dono = 'cliente'; box.bloqueadaManual = false; m = 'Box liberada para o cliente na próxima conversa.'; }
    else if (c.acao === 'bloquear') { if (ehMinha(box)) box.bloqueadaManual = true; m = 'Box bloqueada.'; }
    else if (c.acao === 'desbloquear') { box.bloqueadaManual = false; m = 'Box desbloqueada.'; }
    else if (c.acao === 'desvincular') { delete box.cliente; m = 'Cliente tirado da box.'; }
    else if (c.acao === 'conferir') { if (box.cliente) box.cliente.conferido = 0; await reconferirCliente(box); m = 'Vencimento conferido.'; }
    else if (c.acao === 'limpar' || c.acao === 'reiniciar') { novoComando(box, { tipo: c.acao }); m = 'Comando enviado.'; }
    else if (c.acao === 'apagar') { delete BOXES[box.token]; salvarBoxes(); return voltar(res, '/boxes/admin?ok=Box+apagada.'); }
    salvarBoxes();
    return voltar(res, `/boxes/admin/box?t=${box.token}&ok=${encodeURIComponent(m)}`);
  }
  if (req.method === 'POST' && rota === '/boxes/admin/box/instalar') {
    const c = await lerCorpo(req);
    const box = BOXES[c.t];
    const apk = apks().find((a) => a.id === c.apk);
    if (!box || !apk) return voltar(res, '/boxes/admin');
    novoComando(box, comandoInstalarApk(apk, c.obrigatoria === '1'));
    return voltar(res, `/boxes/admin/box?t=${box.token}&ok=${encodeURIComponent(apk.nome + ' enviado para a box.')}`);
  }

  // --- APKs ---
  if (req.method === 'POST' && rota === '/boxes/admin/apks/subir') {
    const novoId = id(8);
    const destino = path.join(PASTA_APKS, novoId + '.apk');
    try {
      const r = await receberArquivo(req, destino, 300 * 1024 * 1024);
      const m = lerManifestoApk(destino);
      if (!pacoteValido(m.pacote)) throw new Error('não consegui ler o pacote do APK');
      const lib = apks();
      const apk = { id: novoId, nome: String(url.searchParams.get('nome') || m.pacote).replace(/\.apk$/i, '').slice(0, 60), pacote: m.pacote, versao: m.versao, codigo: m.codigo, tamanho: r.tamanho, sha256: r.sha256, criado: agora(), auto: true };
      lib.unshift(apk);
      gravar(ARQ_APKS, lib);
      return json(res, 200, apk);
    } catch (e) {
      fs.rm(destino, () => {});
      return json(res, 200, { erro: e.message });
    }
  }
  if (req.method === 'POST' && rota === '/boxes/admin/apks/todas') {
    const c = await lerCorpo(req);
    const apk = apks().find((a) => a.id === c.apk);
    if (!apk) return voltar(res, '/boxes/admin/apks');
    let n = 0;
    for (const box of Object.values(BOXES)) { novoComando(box, comandoInstalarApk(apk, c.obrigatoria === '1')); n++; }
    return voltar(res, `/boxes/admin/apks?ok=${encodeURIComponent(`${apk.nome} enviado para ${n} boxes.`)}`);
  }
  if (req.method === 'POST' && rota === '/boxes/admin/apks/auto') {
    const c = await lerCorpo(req);
    const lib = apks();
    const a = lib.find((x) => x.id === c.apk);
    if (a) { a.auto = a.auto === false; gravar(ARQ_APKS, lib); }
    return voltar(res, '/boxes/admin/apks?ok=' + encodeURIComponent(a && a.auto ? 'Atualização automática ligada.' : 'Atualização automática desligada.'));
  }
  if (req.method === 'POST' && rota === '/boxes/admin/apks/apagar') {
    const c = await lerCorpo(req);
    const lib = apks();
    const a = lib.find((x) => x.id === c.apk);
    if (a) { fs.rm(path.join(PASTA_APKS, a.id + '.apk'), () => {}); gravar(ARQ_APKS, lib.filter((x) => x.id !== a.id)); }
    return voltar(res, '/boxes/admin/apks?ok=APK+apagado.');
  }

  // --- tela da box ---
  if (req.method === 'POST' && rota === '/boxes/admin/imagem') {
    const ext = (/\.(png|jpe?g|webp)$/i.exec(url.searchParams.get('nome') || '') || ['.jpg'])[0].toLowerCase();
    const nome = id(8) + ext;
    try {
      await receberArquivo(req, path.join(PASTA_IMG, nome), 8 * 1024 * 1024);
      return json(res, 200, { url: `${URL_PUBLICA}/boxes/arquivos/img/${nome}` });
    } catch (e) { return json(res, 200, { erro: e.message }); }
  }
  if (req.method === 'POST' && rota === '/boxes/admin/tela/banner') {
    const c = await lerCorpo(req);
    const cfg = config();
    const dataOk = (d) => (/^\d{4}-\d{2}-\d{2}$/.test(String(d || '')) ? String(d) : '');
    const link = /^https?:\/\//.test(String(c.link || '')) ? String(c.link).slice(0, 300) : '';
    if (!c.imagem && !c.titulo) return voltar(res, '/boxes/admin/tela?ok=' + encodeURIComponent('Coloque uma imagem ou um título.'));
    cfg.banners.push({
      id: id(4), selo: String(c.selo || '').slice(0, 30), titulo: String(c.titulo || '').slice(0, 80), sub: String(c.sub || '').slice(0, 140),
      imagem: String(c.imagem || '').slice(0, 300), link, linkTexto: String(c.linkTexto || '').slice(0, 40),
      anunciante: String(c.anunciante || '').slice(0, 60), contato: String(c.contato || '').slice(0, 40), valor: String(c.valor || '').slice(0, 30),
      inicio: dataOk(c.inicio), fim: dataOk(c.fim), ativo: true, criado: agora(),
    });
    salvarConfig(cfg);
    return voltar(res, '/boxes/admin/tela?ok=Banner+adicionado.');
  }
  if (req.method === 'POST' && rota === '/boxes/admin/tela/banner/pausar') {
    const c = await lerCorpo(req);
    const cfg = config();
    const b = cfg.banners[Number(c.i)];
    if (b) { b.ativo = b.ativo === false; salvarConfig(cfg); }
    return voltar(res, '/boxes/admin/tela?ok=Banner+atualizado.');
  }
  if (req.method === 'POST' && rota === '/boxes/admin/tela/aviso') {
    const c = await lerCorpo(req);
    const cfg = config();
    const titulo = String(c.titulo || '').slice(0, 100), texto = String(c.texto || '').slice(0, 600), imagem = String(c.imagem || '').slice(0, 300);
    const mudou = titulo !== cfg.aviso.titulo || texto !== cfg.aviso.texto || imagem !== cfg.aviso.imagem;
    cfg.aviso = { id: mudou || !cfg.aviso.id ? id(4) : cfg.aviso.id, titulo, texto, imagem, ativo: c.ativo === '1' };
    salvarConfig(cfg);
    return voltar(res, '/boxes/admin/tela?ok=Aviso+salvo.');
  }
  if (req.method === 'POST' && rota === '/boxes/admin/tela/app') {
    const c = await lerCorpo(req);
    const cfg = config();
    const grupo = c.grupo === 'iniciais' ? 'iniciais' : 'loja';
    let item;
    const apk = c.apk ? apks().find((a) => a.id === c.apk) : null;
    if (apk) item = { nome: String(c.nome || apk.nome), pacote: apk.pacote, url: `${URL_PUBLICA}/boxes/arquivos/${apk.id}.apk`, sha256: apk.sha256 };
    else {
      const u = String(c.url || '');
      if (!/^https:\/\//.test(u) || !pacoteValido(c.pacote)) return voltar(res, '/boxes/admin/tela?ok=' + encodeURIComponent('Escolha um APK da biblioteca ou informe um endereço https e o pacote.'));
      item = { nome: String(c.nome || c.pacote), pacote: c.pacote, url: u, sha256: '' };
    }
    if (grupo === 'iniciais') item.chave = ['youtube', 'zarkplay'].includes(c.chave) ? c.chave : 'outro';
    cfg[grupo].push(item);
    salvarConfig(cfg);
    return voltar(res, '/boxes/admin/tela?ok=App+adicionado.');
  }
  if (req.method === 'POST' && rota === '/boxes/admin/tela/remover') {
    const c = await lerCorpo(req);
    const cfg = config();
    if (['banners', 'loja', 'iniciais'].includes(c.grupo)) { cfg[c.grupo].splice(Number(c.i), 1); salvarConfig(cfg); }
    return voltar(res, '/boxes/admin/tela?ok=Removido.');
  }
  if (req.method === 'POST' && rota === '/boxes/admin/tela/regras') {
    const c = await lerCorpo(req);
    const cfg = config();
    cfg.bloquearFora = c.bloquearFora === '1';
    cfg.bloquearVencidos = c.bloquearVencidos === '1';
    cfg.launcherAuto = c.launcherAuto === '1';
    cfg.limpeza = c.limpeza === '1';
    cfg.permitidos = String(c.permitidos || '').split(/\s+/).filter(pacoteValido);
    cfg.suporte = String(c.suporte || '').slice(0, 40);
    cfg.checkout = String(c.checkout || '').slice(0, 200);
    cfg.lixo = String(c.lixo || '').split(/\s+/).filter(pacoteValido);
    salvarConfig(cfg);
    return voltar(res, '/boxes/admin/tela?ok=Regras+salvas.');
  }
  return voltar(res, '/boxes/admin');
}

// ---------- arquivos públicos -------------------------------------------------------------

function servirArquivo(req, res, rota) {
  let arq, tipo;
  let m = /^\/boxes\/arquivos\/([a-f0-9]{16})\.apk$/.exec(rota);
  if (m) { arq = path.join(PASTA_APKS, m[1] + '.apk'); tipo = 'application/vnd.android.package-archive'; }
  m = /^\/boxes\/arquivos\/img\/([a-f0-9]{16}\.(png|jpe?g|webp))$/.exec(rota);
  if (m) { arq = path.join(PASTA_IMG, m[1]); tipo = m[2] === 'png' ? 'image/png' : m[2] === 'webp' ? 'image/webp' : 'image/jpeg'; }
  if (!arq || !fs.existsSync(arq)) return enviar(res, 404, 'não encontrado', 'text/plain');
  const st = fs.statSync(arq);
  res.writeHead(200, { 'Content-Type': tipo, 'Content-Length': st.size, 'Cache-Control': 'public, max-age=86400', 'X-Content-Type-Options': 'nosniff' });
  fs.createReadStream(arq).pipe(res);
}

// ---------- servidor ----------------------------------------------------------------------

http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, 'http://x');
    const rota = url.pathname.replace(/\/+$/, '') || '/';
    if (rota.startsWith('/boxes/api/') && req.method === 'POST') return await apiBox(req, res, rota);
    if (rota.startsWith('/boxes/arquivos/') && req.method === 'GET') return servirArquivo(req, res, rota);
    if (rota === '/boxes' || rota.startsWith('/boxes/admin')) return await admin(req, res, rota === '/boxes' ? '/boxes/admin' : rota, url);
    enviar(res, 404, 'não encontrado', 'text/plain');
  } catch (e) {
    console.error(e);
    if (!res.headersSent) enviar(res, 500, 'erro interno', 'text/plain');
  }
}).listen(PORTA, process.env.ENDERECO || '127.0.0.1', () => console.log(`Painel das boxes ZarkTV rodando em http://${process.env.ENDERECO || '127.0.0.1'}:${PORTA}/boxes/admin`));

process.on('SIGTERM', () => { gravar(ARQ_BOXES, BOXES); process.exit(0); });
