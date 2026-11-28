package cfd.zarktvqx.launcher;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tela inicial da box. Feita em 1920x1080 e ajustada ao tamanho real da TV. */
public class HomeActivity extends Activity {
    static final String[] YOUTUBE = {
            "com.google.android.youtube.tv", "com.google.android.youtube.googletv",
            "com.liskovsoft.smarttubetv.beta", "com.liskovsoft.smarttubetv", "com.teamsmart.videomanager.tv",
            "com.google.android.youtube"};
    static final String[] ZARKPLAY = {"cfd.zarktvqx.zarkplay", "cfd.zarktvqx.tv", "io.github.axon07.channelbox"};

    private final Handler relogio = new Handler();
    private FrameLayout raiz;
    private TextView hora, data, rodape, chip;
    private SinalView sinal;
    private ImageView bannerImg;
    private TextView bannerSelo, bannerTitulo, bannerSub, bannerQrTexto;
    private ImageView bannerQr;
    private LinearLayout bannerQrCaixa;
    private View bannerSombra;
    private LinearLayout bolinhas, fileira;
    private View telaPorCima;
    private JSONArray banners = new JSONArray();
    private int bannerAtual = 0;
    private String avisoVisto;

    private final BroadcastReceiver mudou = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            atualizarRede();
            aplicarEstado();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.iniciar(this);
        avisoVisto = Box.prefs(this).getString("aviso_visto", "");
        montar();
        Despertador.agendar(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(SincronizarService.ATUALIZOU);
        f.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        registerReceiver(mudou, f);
        aplicarEstado();
        atualizarRede();
        relogio.removeCallbacksAndMessages(null);
        relogio.post(tique);
        // Conversa com o painel no máximo a cada 2 minutos ao voltar para o início
        long ultimo = Box.prefs(this).getLong("ultimo_tentou", 0);
        if (System.currentTimeMillis() - ultimo > 2 * 60 * 1000L) {
            Box.prefs(this).edit().putLong("ultimo_tentou", System.currentTimeMillis()).apply();
            SincronizarService.agora(this);
        }
        // Fecha o que ficou rodando escondido (box de 1 GB agradece)
        new Thread(new Runnable() {
            @Override public void run() { Controle.aliviar(HomeActivity.this, new java.util.HashSet<String>()); }
        }).start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try { unregisterReceiver(mudou); } catch (Exception ignored) {}
        relogio.removeCallbacksAndMessages(null);
    }

    @Override
    public void onBackPressed() {
        if (telaPorCima != null && !estado().optBoolean("bloqueada")) fecharPorCima();
        // na tela inicial o "voltar" não faz nada
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (telaPorCima == null && fileira.getChildCount() > 0) fileira.getChildAt(Math.min(1, fileira.getChildCount() - 1)).requestFocus();
    }

    private JSONObject estado() { return SincronizarService.estado(this); }

    // ------------------------------------------------------------------ montar

    private void montar() {
        raiz = new FrameLayout(this);
        raiz.setBackgroundColor(Ui.FUNDO);
        setContentView(raiz);

        // Barra de cima
        TextView marca = Ui.marca(this, 44);
        raiz.addView(marca, pos(Ui.px(72), Ui.px(36), -2, -2));

        LinearLayout direita = new LinearLayout(this);
        direita.setGravity(Gravity.CENTER_VERTICAL);
        sinal = new SinalView(this);
        direita.addView(sinal, new LinearLayout.LayoutParams(Ui.px(44), Ui.px(34)));
        LinearLayout hd = new LinearLayout(this);
        hd.setOrientation(LinearLayout.VERTICAL);
        hd.setGravity(Gravity.RIGHT);
        hora = Ui.texto(this, "", 46, Ui.BRANCO, true);
        data = Ui.texto(this, "", 22, Ui.CINZA, false);
        hd.addView(hora);
        hd.addView(data);
        LinearLayout.LayoutParams lhd = new LinearLayout.LayoutParams(-2, -2);
        lhd.leftMargin = Ui.px(32);
        direita.addView(hd, lhd);
        FrameLayout.LayoutParams ld = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.TOP);
        ld.rightMargin = Ui.px(72);
        ld.topMargin = Ui.px(26);
        raiz.addView(direita, ld);

        // Banner (anúncios do painel)
        FrameLayout banner = new FrameLayout(this);
        banner.setBackgroundDrawable(Ui.caixa(Ui.SUPERFICIE_1, 28));
        bannerImg = new ImageView(this);
        bannerImg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        banner.addView(bannerImg, new FrameLayout.LayoutParams(-1, -1));
        View sombra = bannerSombra = new View(this);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xE6000000, 0x40000000, 0x00000000});
        sombra.setBackgroundDrawable(g);
        banner.addView(sombra, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout textos = new LinearLayout(this);
        textos.setOrientation(LinearLayout.VERTICAL);
        bannerSelo = Ui.texto(this, "", 20, Ui.BRANCO, true);
        bannerSelo.setPadding(Ui.px(20), Ui.px(8), Ui.px(20), Ui.px(8));
        bannerSelo.setBackgroundDrawable(Ui.caixa(Ui.VERMELHO, 40));
        textos.addView(bannerSelo, new LinearLayout.LayoutParams(-2, -2));
        bannerTitulo = Ui.texto(this, "", 66, Ui.BRANCO, true);
        LinearLayout.LayoutParams lt = new LinearLayout.LayoutParams(Ui.px(1100), -2);
        lt.topMargin = Ui.px(20);
        textos.addView(bannerTitulo, lt);
        bannerSub = Ui.texto(this, "", 28, Ui.CINZA, false);
        LinearLayout.LayoutParams ls = new LinearLayout.LayoutParams(Ui.px(1000), -2);
        ls.topMargin = Ui.px(14);
        textos.addView(bannerSub, ls);
        FrameLayout.LayoutParams ltx = new FrameLayout.LayoutParams(-2, -2, Gravity.LEFT | Gravity.BOTTOM);
        ltx.leftMargin = Ui.px(64);
        ltx.bottomMargin = Ui.px(80);
        banner.addView(textos, ltx);
        // QR Code do anunciante (WhatsApp, cardápio, Instagram)
        bannerQrCaixa = new LinearLayout(this);
        bannerQrCaixa.setOrientation(LinearLayout.VERTICAL);
        bannerQrCaixa.setGravity(Gravity.CENTER_HORIZONTAL);
        bannerQrCaixa.setPadding(Ui.px(16), Ui.px(16), Ui.px(16), Ui.px(12));
        bannerQrCaixa.setBackgroundDrawable(Ui.caixa(Ui.BRANCO, 20));
        bannerQr = new ImageView(this);
        bannerQrCaixa.addView(bannerQr, new LinearLayout.LayoutParams(Ui.px(220), Ui.px(220)));
        bannerQrTexto = Ui.texto(this, "", 20, 0xFF111111, true);
        LinearLayout.LayoutParams lqt = new LinearLayout.LayoutParams(-2, -2);
        lqt.topMargin = Ui.px(8);
        bannerQrCaixa.addView(bannerQrTexto, lqt);
        FrameLayout.LayoutParams lq = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        lq.rightMargin = Ui.px(56);
        banner.addView(bannerQrCaixa, lq);
        bannerQrCaixa.setVisibility(View.GONE);
        bolinhas = new LinearLayout(this);
        FrameLayout.LayoutParams lb = new FrameLayout.LayoutParams(-2, -2, Gravity.LEFT | Gravity.BOTTOM);
        lb.leftMargin = Ui.px(64);
        lb.bottomMargin = Ui.px(36);
        banner.addView(bolinhas, lb);
        raiz.addView(banner, pos(Ui.px(72), Ui.px(116), Ui.px(1776), Ui.px(470)));

        // Meus apps
        TextView titulo = Ui.texto(this, "Meus apps", 28, Ui.CINZA, true);
        raiz.addView(titulo, pos(Ui.px(72), Ui.px(630), -2, -2));
        fileira = new LinearLayout(this);
        fileira.setClipChildren(false);
        fileira.setClipToPadding(false);
        raiz.setClipChildren(false);
        raiz.addView(fileira, pos(Ui.px(72), Ui.px(690), -2, -2));
        montarCartoes();

        // Aviso de vencimento e rodapé
        chip = Ui.texto(this, "", 22, Ui.BRANCO, false);
        chip.setPadding(Ui.px(28), Ui.px(14), Ui.px(28), Ui.px(14));
        chip.setBackgroundDrawable(Ui.caixa(Ui.SUPERFICIE_1, 40));
        chip.setVisibility(View.GONE);
        FrameLayout.LayoutParams lc = new FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT | Gravity.BOTTOM);
        lc.rightMargin = Ui.px(72);
        lc.bottomMargin = Ui.px(52);
        raiz.addView(chip, lc);
        rodape = Ui.texto(this, "", 17, Ui.CINZA_FRACO, false);
        FrameLayout.LayoutParams lr = new FrameLayout.LayoutParams(-2, -2, Gravity.LEFT | Gravity.BOTTOM);
        lr.leftMargin = Ui.px(72);
        lr.bottomMargin = Ui.px(26);
        raiz.addView(rodape, lr);
    }

    private static FrameLayout.LayoutParams pos(int x, int y, int w, int h) {
        FrameLayout.LayoutParams l = new FrameLayout.LayoutParams(w, h, Gravity.LEFT | Gravity.TOP);
        l.leftMargin = x;
        l.topMargin = y;
        return l;
    }

    private String assinaturaCartoes = "";

    private void montarCartoes() {
        StringBuilder sb = new StringBuilder();
        for (String p : YOUTUBE) if (Box.instalado(this, p)) sb.append(p).append(',');
        for (String p : ZARKPLAY) if (Box.instalado(this, p)) sb.append(p).append(',');
        String nova = sb.toString();
        if (fileira.getChildCount() > 0 && nova.equals(assinaturaCartoes)) return;
        assinaturaCartoes = nova;
        int focado = -1;
        for (int i = 0; i < fileira.getChildCount(); i++) if (fileira.getChildAt(i).hasFocus()) focado = i;
        fileira.removeAllViews();
        cartao("YouTube", "youtube", YOUTUBE, 0xFFCC0000);
        cartao("ZarkPlay", "zarkplay", ZARKPLAY, Ui.VERMELHO);
        cartao("Lojinha ZarkTV", "lojinha", null, Ui.SUPERFICIE_3);
        cartao("Configurações", "config", null, Ui.SUPERFICIE_3);
        cartao("Todos os apps", "todos", null, Ui.SUPERFICIE_2);
        fileira.getChildAt(focado >= 0 ? focado : 1).requestFocus();
    }

    private void cartao(String nome, final String chave, final String[] pacotes, int cor) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setClipChildren(false);

        final FrameLayout quadro = new FrameLayout(this);
        quadro.setFocusable(true);
        quadro.setClickable(true);
        quadro.setBackgroundDrawable(Ui.focavel(cor, 28));
        View icone = iconeDoCartao(chave, pacotes);
        int tam = Ui.px(96);
        quadro.addView(icone, new FrameLayout.LayoutParams(tam, tam, Gravity.CENTER));
        c.addView(quadro, new LinearLayout.LayoutParams(Ui.px(300), Ui.px(200)));

        final TextView rotulo = Ui.texto(this, nome, 25, Ui.CINZA, false);
        LinearLayout.LayoutParams lr = new LinearLayout.LayoutParams(-2, -2);
        lr.topMargin = Ui.px(18);
        c.addView(rotulo, lr);

        quadro.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean tem) {
                quadro.animate().scaleX(tem ? 1.06f : 1f).scaleY(tem ? 1.06f : 1f).setDuration(150).start();
                rotulo.setTextColor(tem ? Ui.BRANCO : Ui.CINZA);
            }
        });
        quadro.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { abrir(chave, pacotes); }
        });

        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
        l.rightMargin = Ui.px(42);
        fileira.addView(c, l);
    }

    private View iconeDoCartao(String chave, String[] pacotes) {
        if (pacotes != null) {
            for (String p : pacotes) {
                Drawable d = Ui.iconeDe(this, p);
                if (d != null) {
                    ImageView iv = new ImageView(this);
                    iv.setImageDrawable(d);
                    iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    return iv;
                }
            }
        }
        String tipo;
        switch (chave) {
            case "youtube": case "zarkplay": tipo = "play"; break;
            case "lojinha": tipo = "loja"; break;
            case "config": tipo = "config"; break;
            default: tipo = "todos";
        }
        return new IconeView(this, tipo);
    }

    private void abrir(String chave, String[] pacotes) {
        if (estado().optBoolean("bloqueada")) return;
        switch (chave) {
            case "lojinha":
                startActivity(new Intent(this, LojaActivity.class));
                return;
            case "todos":
                startActivity(new Intent(this, AppsActivity.class));
                return;
            case "config":
                try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) {}
                return;
        }
        if (pacotes != null) {
            for (String p : pacotes) if (Ui.abrirApp(this, p)) return;
        }
        // Ainda não tem: instala pelo painel (apps iniciais)
        JSONArray ini = estado().optJSONArray("iniciais");
        if (ini != null) {
            for (int i = 0; i < ini.length(); i++) {
                JSONObject a = ini.optJSONObject(i);
                if (a != null && chave.equals(a.optString("chave"))) {
                    Toast.makeText(this, "Instalando " + a.optString("nome") + "…", Toast.LENGTH_SHORT).show();
                    SincronizarService.instalarApp(this, a.optString("url"), a.optString("sha256"), a.optString("nome"));
                    return;
                }
            }
        }
        startActivity(new Intent(this, LojaActivity.class));
    }

    // ------------------------------------------------------------------ estado do painel

    private void aplicarEstado() {
        JSONObject e = estado();
        String cod = Box.codigo(this);
        String cli = Box.cliente(this);
        rodape.setText("Box " + cod + (cli != null && cli.length() > 0 && !"null".equals(cli) ? " · " + cli : "") + " · v" + BuildConfig.VERSION_NAME);

        banners = e.optJSONArray("banners");
        if (banners == null || banners.length() == 0) {
            banners = new JSONArray();
            try {
                banners.put(new JSONObject().put("selo", "Bem-vindo").put("titulo", "ZarkTV").put("sub", "Seus canais, filmes e séries num só lugar."));
            } catch (Exception ignored) {}
        }
        if (bannerAtual >= banners.length()) bannerAtual = 0;
        mostrarBanner();
        montarCartoes();

        // Vencimento
        int dias = e.optInt("vence_em_dias", 999);
        if (dias >= 0 && dias <= 5) {
            chip.setText(dias == 0 ? "Seu acesso vence hoje · renove agora ou fale com seu revendedor"
                    : "Seu acesso vence em " + dias + (dias == 1 ? " dia" : " dias") + " · renove agora ou fale com seu revendedor");
            piscarChip();
        }

        // Telas por cima: bloqueio > primeiro acesso > aviso > atualização pendente
        if (e.optBoolean("bloqueada")) { mostrarBloqueio(e); return; }
        if (telaPorCima != null && "bloqueio".equals(telaPorCima.getTag())) fecharPorCima();
        if (Box.token(this) != null && e.has("codigo") && !e.optBoolean("vinculada") && !Box.prefs(this).getBoolean("pulou_entrar", false)) {
            Box.prefs(this).edit().putBoolean("pulou_entrar", true).apply();
            startActivity(new Intent(this, EntrarActivity.class));
            return;
        }
        JSONObject aviso = e.optJSONObject("aviso");
        if (aviso != null && telaPorCima == null && !aviso.optString("id").equals(avisoVisto)) { mostrarAviso(aviso); return; }
        if (telaPorCima == null && Box.prefs(this).getString("pendente", null) != null) mostrarAtualizacaoDisponivel();
    }

    private void mostrarBanner() {
        JSONObject b = banners.optJSONObject(bannerAtual);
        if (b == null) return;
        bannerSelo.setText(b.optString("selo"));
        bannerSelo.setVisibility(b.optString("selo").length() > 0 ? View.VISIBLE : View.GONE);
        bannerTitulo.setText(b.optString("titulo"));
        bannerSub.setText(b.optString("sub"));
        // arte pronta (só imagem): tira o escurecido
        bannerSombra.setVisibility(b.optString("titulo").length() > 0 || b.optString("imagem").length() == 0 ? View.VISIBLE : View.GONE);
        String link = b.optString("link");
        android.graphics.Bitmap qr = link.length() > 0 ? Ui.qr(link, Ui.px(220)) : null;
        if (qr != null) {
            bannerQr.setImageBitmap(qr);
            String tx = b.optString("link_texto");
            bannerQrTexto.setText(tx);
            bannerQrTexto.setVisibility(tx.length() > 0 ? View.VISIBLE : View.GONE);
            bannerQrCaixa.setVisibility(View.VISIBLE);
        } else {
            bannerQrCaixa.setVisibility(View.GONE);
        }
        String img = b.optString("imagem");
        bannerImg.setImageBitmap(null);
        if (img.length() > 0) Ui.imagem(this, img, bannerImg, Ui.px(1776));
        bolinhas.removeAllViews();
        if (banners.length() > 1) {
            for (int i = 0; i < banners.length(); i++) {
                View d = new View(this);
                d.setBackgroundDrawable(Ui.caixa(i == bannerAtual ? Ui.BRANCO : Ui.SUPERFICIE_3, 10));
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(Ui.px(i == bannerAtual ? 36 : 10), Ui.px(10));
                l.rightMargin = Ui.px(12);
                bolinhas.addView(d, l);
            }
        }
    }

    private int segundos = 0;
    private final Runnable tique = new Runnable() {
        @Override public void run() {
            Date agora = new Date();
            Locale br = new Locale("pt", "BR");
            hora.setText(new SimpleDateFormat("HH:mm", br).format(agora));
            data.setText(new SimpleDateFormat("EEE, d MMM", br).format(agora).replace(".", ""));
            segundos++;
            if (segundos % 8 == 0 && banners.length() > 1 && telaPorCima == null) {
                bannerAtual = (bannerAtual + 1) % banners.length();
                mostrarBanner(); // troca seca, sem animação
            }
            if (segundos % 15 == 0) atualizarRede();
            relogio.postDelayed(this, 1000);
        }
    };

    private void atualizarRede() {
        String t = Box.redeTipo(this);
        sinal.mostrar(t, "wifi".equals(t) ? Math.max(1, Box.sinalWifi(this)) : 4);
    }

    private void piscarChip() {
        chip.setVisibility(View.VISIBLE);
        chip.removeCallbacks(esconderChip);
        chip.postDelayed(esconderChip, 8000);
    }

    private final Runnable esconderChip = new Runnable() {
        @Override public void run() { chip.setVisibility(View.GONE); }
    };

    // ------------------------------------------------------------------ telas por cima

    private FrameLayout novaPorCima(String tag) {
        fecharPorCima();
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(Ui.FUNDO);
        f.setClickable(true);
        f.setTag(tag);
        raiz.addView(f, new FrameLayout.LayoutParams(-1, -1));
        telaPorCima = f;
        fileira.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        return f;
    }

    private void fecharPorCima() {
        if (telaPorCima != null) {
            raiz.removeView(telaPorCima);
            telaPorCima = null;
            fileira.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
            if (fileira.getChildCount() > 1) ((ViewGroup) fileira.getChildAt(1)).getChildAt(0).requestFocus();
        }
    }

    private void mostrarBloqueio(JSONObject e) {
        if (telaPorCima != null && "bloqueio".equals(telaPorCima.getTag())) return;
        FrameLayout f = novaPorCima("bloqueio");
        LinearLayout linha = new LinearLayout(this);
        linha.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(Ui.texto(this, "ACESSO BLOQUEADO", 24, Ui.VERMELHO, true));
        TextView t = Ui.texto(this, "Seu acesso está vencido", 60, Ui.BRANCO, true);
        LinearLayout.LayoutParams lt = new LinearLayout.LayoutParams(-2, -2);
        lt.topMargin = Ui.px(28);
        col.addView(t, lt);
        TextView s = Ui.texto(this, "Renove pelo Pix ou fale com o suporte.", 30, Ui.CINZA, false);
        LinearLayout.LayoutParams ls = new LinearLayout.LayoutParams(-2, -2);
        ls.topMargin = Ui.px(18);
        col.addView(s, ls);
        TextView w = Ui.texto(this, "WhatsApp suporte: " + e.optString("suporte", "(85) 8789-0473"), 32, Ui.BRANCO, true);
        LinearLayout.LayoutParams lw = new LinearLayout.LayoutParams(-2, -2);
        lw.topMargin = Ui.px(48);
        col.addView(w, lw);
        TextView cod = Ui.texto(this, "Box " + Box.codigo(this), 20, Ui.CINZA_FRACO, false);
        LinearLayout.LayoutParams lcod = new LinearLayout.LayoutParams(-2, -2);
        lcod.topMargin = Ui.px(30);
        col.addView(cod, lcod);
        linha.addView(col);
        String link = e.optString("checkout_url");
        if (link.length() > 0) {
            LinearLayout q = new LinearLayout(this);
            q.setOrientation(LinearLayout.VERTICAL);
            q.setGravity(Gravity.CENTER_HORIZONTAL);
            ImageView iv = new ImageView(this);
            Bitmap bmp = Ui.qr(link, Ui.px(340));
            if (bmp != null) iv.setImageBitmap(bmp);
            iv.setPadding(Ui.px(24), Ui.px(24), Ui.px(24), Ui.px(24));
            iv.setBackgroundDrawable(Ui.caixa(Ui.BRANCO, 24));
            q.addView(iv, new LinearLayout.LayoutParams(Ui.px(388), Ui.px(388)));
            TextView qt = Ui.texto(this, "Aponte a câmera para pagar com Pix", 24, Ui.CINZA, false);
            LinearLayout.LayoutParams lq = new LinearLayout.LayoutParams(-2, -2);
            lq.topMargin = Ui.px(20);
            q.addView(qt, lq);
            LinearLayout.LayoutParams lql = new LinearLayout.LayoutParams(-2, -2);
            lql.leftMargin = Ui.px(120);
            linha.addView(q, lql);
        }
        f.addView(linha, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        // botão escondido para conferir de novo depois que pagar
        TextView conferir = Ui.botao(this, "Já paguei, conferir", false);
        FrameLayout.LayoutParams lc = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lc.bottomMargin = Ui.px(70);
        f.addView(conferir, lc);
        conferir.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Toast.makeText(HomeActivity.this, "Conferindo…", Toast.LENGTH_SHORT).show();
                SincronizarService.agora(HomeActivity.this);
            }
        });
        conferir.requestFocus();
    }

    private void mostrarAviso(final JSONObject a) {
        FrameLayout f = novaPorCima("aviso");
        LinearLayout linha = new LinearLayout(this);
        String img = a.optString("imagem");
        if (img.length() > 0) {
            ImageView iv = new ImageView(this);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            Ui.imagem(this, img, iv, Ui.px(960));
            linha.addView(iv, new LinearLayout.LayoutParams(Ui.px(960), -1));
        }
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_VERTICAL);
        col.setPadding(Ui.px(100), 0, Ui.px(100), 0);
        col.addView(Ui.texto(this, "AVISO", 22, Ui.VERMELHO, true));
        TextView t = Ui.texto(this, a.optString("titulo"), 58, Ui.BRANCO, true);
        LinearLayout.LayoutParams lt = new LinearLayout.LayoutParams(-2, -2);
        lt.topMargin = Ui.px(18);
        col.addView(t, lt);
        TextView s = Ui.texto(this, a.optString("texto"), 30, Ui.CINZA, false);
        s.setLineSpacing(0, 1.3f);
        LinearLayout.LayoutParams ls = new LinearLayout.LayoutParams(-2, -2);
        ls.topMargin = Ui.px(26);
        col.addView(s, ls);
        TextView ok = Ui.botao(this, "OK, entendi", true);
        LinearLayout.LayoutParams lo = new LinearLayout.LayoutParams(-2, -2);
        lo.topMargin = Ui.px(50);
        col.addView(ok, lo);
        linha.addView(col, new LinearLayout.LayoutParams(0, -1, 1));
        f.addView(linha, new FrameLayout.LayoutParams(-1, -1));
        ok.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                avisoVisto = a.optString("id");
                Box.prefs(HomeActivity.this).edit().putString("aviso_visto", avisoVisto).apply();
                fecharPorCima();
                aplicarEstado();
            }
        });
        ok.requestFocus();
    }

    private void mostrarAtualizacaoDisponivel() {
        String nome = "nova versão";
        try { nome = new JSONObject(Box.prefs(this).getString("pendente", "{}")).optString("nome", nome); } catch (Exception ignored) {}
        FrameLayout f = novaPorCima("atualizacao");
        f.setBackgroundColor(0xD90B0B0D);
        LinearLayout caixa = new LinearLayout(this);
        caixa.setOrientation(LinearLayout.VERTICAL);
        caixa.setGravity(Gravity.CENTER_HORIZONTAL);
        caixa.setPadding(Ui.px(70), Ui.px(60), Ui.px(70), Ui.px(60));
        caixa.setBackgroundDrawable(Ui.caixa(Ui.SUPERFICIE_1, 32));
        caixa.addView(Ui.texto(this, "Atualização disponível", 44, Ui.BRANCO, true));
        TextView s = Ui.texto(this, nome + " traz melhorias de velocidade e correções.", 26, Ui.CINZA, false);
        LinearLayout.LayoutParams ls = new LinearLayout.LayoutParams(-2, -2);
        ls.topMargin = Ui.px(16);
        caixa.addView(s, ls);
        LinearLayout bts = new LinearLayout(this);
        TextView sim = Ui.botao(this, "Atualizar agora", true);
        TextView depois = Ui.botao(this, "Depois", false);
        bts.addView(sim);
        LinearLayout.LayoutParams ld = new LinearLayout.LayoutParams(-2, -2);
        ld.leftMargin = Ui.px(24);
        bts.addView(depois, ld);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(-2, -2);
        lb.topMargin = Ui.px(44);
        caixa.addView(bts, lb);
        f.addView(caixa, new FrameLayout.LayoutParams(Ui.px(820), -2, Gravity.CENTER));
        sim.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                fecharPorCima();
                SincronizarService.instalarPendente(HomeActivity.this);
            }
        });
        depois.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { fecharPorCima(); }
        });
        sim.requestFocus();
    }
}
