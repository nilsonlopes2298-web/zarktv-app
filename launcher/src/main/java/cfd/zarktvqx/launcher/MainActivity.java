package cfd.zarktvqx.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URL;
import java.text.Collator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tela inicial ZarkTV: relógio, painel de status (internet, servidor ZarkTV, rede,
 * armazenamento, memória), favoritos em destaque e todos os apps instalados.
 * Segurar OK (ou apertar Menu) em um app abre as opções: favoritar, ocultar,
 * informações e desinstalar.
 */
public class MainActivity extends Activity {

    private static final int PRETO = Color.parseColor("#0B0B0B");
    private static final int CARTAO = Color.parseColor("#181818");
    private static final int CARTAO_FOCO = Color.parseColor("#262626");
    private static final int VERMELHO = Color.parseColor("#E50914");
    private static final int VERDE = Color.parseColor("#22C55E");
    private static final int AMARELO = Color.parseColor("#F59E0B");
    private static final int CINZA = Color.parseColor("#9CA3AF");
    private static final int BRANCO = Color.WHITE;

    private static final String LISTA_SISTEMAS =
            "https://raw.githubusercontent.com/nilsonlopes2298-web/zarktv-app/launcher/launcher/sistemas.json";
    private static final String TESTE_INTERNET = "http://connectivitycheck.gstatic.com/generate_204";
    private static final String FAVORITOS_PADRAO =
            "com.google.android.youtube.tv,com.amazon.firetv.youtube,com.netflix.ninja,"
                    + "cfd.zarktvqx.zarkplay,cfd.zarktvqx.tv,com.zark.play.tv";

    private static final Locale PT_BR = new Locale("pt", "BR");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService fundo = Executors.newSingleThreadExecutor();
    private final ExecutorService rede = Executors.newSingleThreadExecutor();
    private final ExecutorService testes = Executors.newFixedThreadPool(6);

    private SharedPreferences prefs;
    private PackageManager pm;

    private TextView relogio, data;
    private TextView chipInternet, chipRoxinha, chipRede, chipArmazenamento, chipMemoria, chipApps;
    private LinearLayout linhaFavoritos;
    private GridLayout grade;
    private TextView tituloFavoritos, botaoOcultos, resumoSistemas;
    private GridLayout gradeSistemas;
    private final List<Sistema> sistemas = new ArrayList<>();
    private long ultimaListaMs = 0;
    private View primeiroFocavel;

    private final Map<String, App> apps = new LinkedHashMap<>();

    static class Sistema {
        String grupo, nome, url;
        TextView status;
        GradientDrawable fundo;
        volatile int estado; // 0 verificando, 1 no ar, 2 lento, 3 fora do ar
    }

    static class App {
        String pacote;
        String nome;
        Drawable banner;
        Drawable icone;
        String versao = "";
        long atualizado;
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    protected void onCreate(Bundle salvo) {
        super.onCreate(salvo);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        pm = getPackageManager();
        setContentView(montarTela());

        IntentFilter filtro = new IntentFilter();
        filtro.addAction(Intent.ACTION_PACKAGE_ADDED);
        filtro.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filtro.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filtro.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filtro.addDataScheme("package");
        registerReceiver(mudancaApps, filtro);

        montarSistemas(listaInicial());
        carregarApps();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tique);
        ui.post(tique);
        ui.removeCallbacks(verificarStatus);
        ui.post(verificarStatus);
        atualizarStatusLocal();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tique);
        ui.removeCallbacks(verificarStatus);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(mudancaApps);
        } catch (Exception ignorado) {
        }
        fundo.shutdownNow();
        rede.shutdownNow();
        testes.shutdownNow();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Apertou o botão Início estando já na tela inicial: volta o foco para o começo.
        if (primeiroFocavel != null) primeiroFocavel.requestFocus();
    }

    @Override
    public void onBackPressed() {
        // Tela inicial: o botão Voltar não fecha nada.
        if (primeiroFocavel != null) primeiroFocavel.requestFocus();
    }

    private final BroadcastReceiver mudancaApps = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            carregarApps();
        }
    };

    // ------------------------------------------------------------------ montagem da tela

    private View montarTela() {
        FrameLayout raiz = new FrameLayout(this);
        GradientDrawable fundoTela = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#2A0306"), PRETO, PRETO});
        raiz.setBackground(fundoTela);

        ScrollView rolagem = new ScrollView(this);
        rolagem.setFillViewport(true);
        rolagem.setVerticalScrollBarEnabled(false);
        raiz.addView(rolagem, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout coluna = new LinearLayout(this);
        coluna.setOrientation(LinearLayout.VERTICAL);
        coluna.setPadding(dp(40), dp(24), dp(40), dp(32));
        rolagem.addView(coluna, new ScrollView.LayoutParams(-1, -2));

        coluna.addView(montarCabecalho());
        coluna.addView(montarPainelStatus(), margem(-1, -2, 0, dp(18), 0, 0));

        tituloFavoritos = titulo("Favoritos");
        coluna.addView(tituloFavoritos, margem(-1, -2, 0, dp(22), 0, dp(10)));
        HorizontalScrollView rolagemFav = new HorizontalScrollView(this);
        rolagemFav.setHorizontalScrollBarEnabled(false);
        rolagemFav.setClipToPadding(false);
        rolagemFav.setPadding(dp(6), dp(6), dp(6), dp(6));
        linhaFavoritos = new LinearLayout(this);
        linhaFavoritos.setOrientation(LinearLayout.HORIZONTAL);
        rolagemFav.addView(linhaFavoritos);
        coluna.addView(rolagemFav);

        LinearLayout cabRoxinha = new LinearLayout(this);
        cabRoxinha.setOrientation(LinearLayout.HORIZONTAL);
        cabRoxinha.setGravity(Gravity.BOTTOM);
        cabRoxinha.addView(titulo("Minha Roxinha"), new LinearLayout.LayoutParams(0, -2, 1));
        resumoSistemas = new TextView(this);
        resumoSistemas.setTextColor(CINZA);
        resumoSistemas.setTextSize(13);
        cabRoxinha.addView(resumoSistemas);
        coluna.addView(cabRoxinha, margem(-1, -2, 0, dp(20), 0, dp(10)));
        gradeSistemas = new GridLayout(this);
        gradeSistemas.setColumnCount(4);
        gradeSistemas.setClipChildren(false);
        gradeSistemas.setPadding(dp(6), dp(6), dp(6), dp(6));
        coluna.addView(gradeSistemas);

        coluna.addView(titulo("Meus apps"), margem(-1, -2, 0, dp(20), 0, dp(10)));
        grade = new GridLayout(this);
        grade.setColumnCount(6);
        grade.setUseDefaultMargins(false);
        grade.setClipChildren(false);
        grade.setPadding(dp(6), dp(6), dp(6), dp(6));
        coluna.addView(grade);

        LinearLayout rodape = new LinearLayout(this);
        rodape.setOrientation(LinearLayout.HORIZONTAL);
        rodape.setGravity(Gravity.CENTER_VERTICAL);
        rodape.addView(botaoTexto("⚙  Configurações da TV", v -> abrirConfiguracoes()));
        botaoOcultos = botaoTexto("Apps ocultos", v -> mostrarOcultos());
        rodape.addView(botaoOcultos, margem(-2, -2, dp(14), 0, 0, 0));
        coluna.addView(rodape, margem(-1, -2, 0, dp(22), 0, 0));

        return raiz;
    }

    private View montarCabecalho() {
        LinearLayout linha = new LinearLayout(this);
        linha.setOrientation(LinearLayout.HORIZONTAL);
        linha.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout marca = new LinearLayout(this);
        marca.setOrientation(LinearLayout.VERTICAL);
        TextView logo = new TextView(this);
        SpannableString s = new SpannableString("ZarkTV");
        s.setSpan(new ForegroundColorSpan(VERMELHO), 4, 6, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        logo.setText(s);
        logo.setTextColor(BRANCO);
        logo.setTextSize(34);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setLetterSpacing(0.02f);
        marca.addView(logo);
        TextView sub = new TextView(this);
        sub.setText("Sua central de entretenimento");
        sub.setTextColor(CINZA);
        sub.setTextSize(13);
        marca.addView(sub);
        linha.addView(marca, new LinearLayout.LayoutParams(0, -2, 1));

        LinearLayout hora = new LinearLayout(this);
        hora.setOrientation(LinearLayout.VERTICAL);
        hora.setGravity(Gravity.END);
        relogio = new TextView(this);
        relogio.setTextColor(BRANCO);
        relogio.setTextSize(38);
        relogio.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        relogio.setGravity(Gravity.END);
        hora.addView(relogio);
        data = new TextView(this);
        data.setTextColor(CINZA);
        data.setTextSize(13);
        data.setGravity(Gravity.END);
        hora.addView(data);
        linha.addView(hora);
        return linha;
    }

    private View montarPainelStatus() {
        LinearLayout painel = new LinearLayout(this);
        painel.setOrientation(LinearLayout.HORIZONTAL);
        chipInternet = chip(painel, "Internet");
        chipRoxinha = chip(painel, "VPS Roxinha");
        chipRede = chip(painel, "Rede");
        chipArmazenamento = chip(painel, "Armazenamento");
        chipMemoria = chip(painel, "Memória livre");
        chipApps = chip(painel, "Apps");
        definirChip(chipInternet, "verificando…", AMARELO);
        definirChip(chipRoxinha, "verificando…", AMARELO);
        return painel;
    }

    /** Cria um cartãozinho de status e devolve o texto do valor. */
    private TextView chip(LinearLayout painel, String rotulo) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(10), dp(14), dp(10));
        c.setBackground(fundoArredondado(Color.parseColor("#141414"), 12, Color.parseColor("#262626")));
        TextView r = new TextView(this);
        r.setText(rotulo.toUpperCase(PT_BR));
        r.setTextColor(CINZA);
        r.setTextSize(10);
        r.setLetterSpacing(0.08f);
        c.addView(r);
        TextView v = new TextView(this);
        v.setTextColor(BRANCO);
        v.setTextSize(14);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        v.setText("—");
        c.addView(v, margem(-2, -2, 0, dp(3), 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        if (painel.getChildCount() > 0) lp.leftMargin = dp(10);
        painel.addView(c, lp);
        return v;
    }

    private void definirChip(TextView v, String texto, int corBolinha) {
        SpannableString s = new SpannableString("● " + texto);
        s.setSpan(new ForegroundColorSpan(corBolinha), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        v.setText(s);
    }

    private TextView titulo(String texto) {
        TextView t = new TextView(this);
        t.setText(texto);
        t.setTextColor(BRANCO);
        t.setTextSize(18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView botaoTexto(String texto, View.OnClickListener acao) {
        TextView b = new TextView(this);
        b.setText(texto);
        b.setTextColor(BRANCO);
        b.setTextSize(14);
        b.setPadding(dp(18), dp(10), dp(18), dp(10));
        b.setFocusable(true);
        b.setClickable(true);
        GradientDrawable f = fundoArredondado(CARTAO, 22, Color.parseColor("#2A2A2A"));
        b.setBackground(f);
        b.setOnFocusChangeListener((v, foco) -> {
            f.setColor(foco ? VERMELHO : CARTAO);
            f.setStroke(dp(2), foco ? VERMELHO : Color.parseColor("#2A2A2A"));
        });
        b.setOnClickListener(acao);
        return b;
    }

    // ------------------------------------------------------------------ cartões de apps

    private View cartaoFavorito(App a) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(6), dp(6), dp(6), dp(10));
        GradientDrawable f = fundoArredondado(CARTAO, 14, Color.TRANSPARENT);
        c.setBackground(f);

        FrameLayout imagem = new FrameLayout(this);
        imagem.setBackground(fundoArredondado(Color.parseColor("#0F0F0F"), 10, Color.TRANSPARENT));
        ImageView img = new ImageView(this);
        if (a.banner != null) {
            img.setImageDrawable(a.banner);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imagem.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setImageDrawable(a.icone);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            imagem.addView(img, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));
        }
        c.addView(imagem, new LinearLayout.LayoutParams(dp(232), dp(130)));

        TextView nome = new TextView(this);
        nome.setText(a.nome);
        nome.setTextColor(BRANCO);
        nome.setTextSize(15);
        nome.setTypeface(Typeface.DEFAULT_BOLD);
        nome.setSingleLine(true);
        nome.setEllipsize(TextUtils.TruncateAt.END);
        nome.setPadding(dp(6), dp(8), dp(6), 0);
        c.addView(nome);

        TextView st = new TextView(this);
        st.setTextSize(11);
        st.setTextColor(CINZA);
        st.setPadding(dp(6), dp(2), dp(6), 0);
        st.setSingleLine(true);
        definirStatusApp(st, a);
        c.addView(st);

        configurarFoco(c, f, a, 1.06f);
        return c;
    }

    private View cartaoApp(App a) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(dp(5), dp(5), dp(5), dp(8));
        GradientDrawable f = fundoArredondado(CARTAO, 12, Color.TRANSPARENT);
        c.setBackground(f);

        FrameLayout imagem = new FrameLayout(this);
        imagem.setBackground(fundoArredondado(Color.parseColor("#0F0F0F"), 8, Color.TRANSPARENT));
        ImageView img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (a.banner != null) {
            img.setImageDrawable(a.banner);
            imagem.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setImageDrawable(a.icone);
            imagem.addView(img, new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.CENTER));
        }
        c.addView(imagem, new LinearLayout.LayoutParams(dp(118), dp(66)));

        TextView nome = new TextView(this);
        nome.setText(a.nome);
        nome.setTextColor(BRANCO);
        nome.setTextSize(12);
        nome.setSingleLine(true);
        nome.setEllipsize(TextUtils.TruncateAt.END);
        nome.setGravity(Gravity.CENTER);
        nome.setPadding(dp(4), dp(6), dp(4), 0);
        c.addView(nome, new LinearLayout.LayoutParams(dp(118), -2));

        TextView st = new TextView(this);
        st.setTextSize(10);
        st.setTextColor(CINZA);
        st.setGravity(Gravity.CENTER);
        st.setSingleLine(true);
        definirStatusApp(st, a);
        c.addView(st, new LinearLayout.LayoutParams(dp(118), -2));

        configurarFoco(c, f, a, 1.08f);
        return c;
    }

    private void definirStatusApp(TextView t, App a) {
        boolean abre = intencaoAbrir(a.pacote) != null;
        String texto = abre ? "Pronto" : "Não abre";
        if (abre && !TextUtils.isEmpty(a.versao)) texto += " · v" + a.versao;
        SpannableString s = new SpannableString("● " + texto);
        s.setSpan(new ForegroundColorSpan(abre ? VERDE : VERMELHO), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setText(s);
    }

    private void configurarFoco(View c, GradientDrawable f, App a, float escala) {
        c.setFocusable(true);
        c.setClickable(true);
        c.setOnFocusChangeListener((v, foco) -> {
            f.setColor(foco ? CARTAO_FOCO : CARTAO);
            f.setStroke(dp(3), foco ? VERMELHO : Color.TRANSPARENT);
            v.animate().scaleX(foco ? escala : 1f).scaleY(foco ? escala : 1f).setDuration(120).start();
            v.setElevation(foco ? dp(8) : 0);
        });
        c.setOnClickListener(v -> abrirApp(a.pacote));
        c.setOnLongClickListener(v -> {
            mostrarOpcoes(a);
            return true;
        });
        c.setOnKeyListener((v, tecla, ev) -> {
            if (ev.getAction() == KeyEvent.ACTION_UP && tecla == KeyEvent.KEYCODE_MENU) {
                mostrarOpcoes(a);
                return true;
            }
            return false;
        });
    }

    // ------------------------------------------------------------------ apps instalados

    private void carregarApps() {
        fundo.execute(() -> {
            Map<String, App> novos = new LinkedHashMap<>();
            String[] categorias = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
            for (String cat : categorias) {
                Intent i = new Intent(Intent.ACTION_MAIN).addCategory(cat);
                List<ResolveInfo> lista;
                try {
                    lista = pm.queryIntentActivities(i, 0);
                } catch (Exception e) {
                    continue;
                }
                for (ResolveInfo ri : lista) {
                    String pacote = ri.activityInfo.packageName;
                    if (pacote.equals(getPackageName()) || novos.containsKey(pacote)) continue;
                    App a = new App();
                    a.pacote = pacote;
                    try {
                        a.nome = String.valueOf(ri.loadLabel(pm));
                    } catch (Exception e) {
                        a.nome = pacote;
                    }
                    try {
                        a.banner = ri.activityInfo.loadBanner(pm);
                        if (a.banner == null) a.banner = ri.activityInfo.applicationInfo.loadBanner(pm);
                    } catch (Exception ignorado) {
                    }
                    try {
                        a.icone = ri.loadIcon(pm);
                    } catch (Exception ignorado) {
                    }
                    try {
                        PackageInfo info = pm.getPackageInfo(pacote, 0);
                        a.versao = info.versionName == null ? "" : info.versionName;
                        if (a.versao.length() > 12) a.versao = a.versao.substring(0, 12);
                        a.atualizado = info.lastUpdateTime;
                    } catch (Exception ignorado) {
                    }
                    novos.put(pacote, a);
                }
            }
            List<App> ordenados = new ArrayList<>(novos.values());
            Collator col = Collator.getInstance(PT_BR);
            Collections.sort(ordenados, (x, y) -> col.compare(x.nome, y.nome));
            ui.post(() -> {
                apps.clear();
                for (App a : ordenados) apps.put(a.pacote, a);
                desenharApps();
            });
        });
    }

    private void desenharApps() {
        View focado = getCurrentFocus();
        String pacoteFocado = focado != null && focado.getTag() instanceof String ? (String) focado.getTag() : null;

        LinkedHashSet<String> fav = favoritos();
        LinkedHashSet<String> ocultos = ocultos();

        linhaFavoritos.removeAllViews();
        grade.removeAllViews();
        primeiroFocavel = null;
        View focarDepois = null;

        for (String p : fav) {
            App a = apps.get(p);
            if (a == null || ocultos.contains(p)) continue;
            View c = cartaoFavorito(a);
            c.setTag(p);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(18);
            linhaFavoritos.addView(c, lp);
            if (primeiroFocavel == null) primeiroFocavel = c;
            if (p.equals(pacoteFocado)) focarDepois = c;
        }
        tituloFavoritos.setVisibility(linhaFavoritos.getChildCount() > 0 ? View.VISIBLE : View.GONE);

        int visiveis = 0;
        for (App a : apps.values()) {
            if (ocultos.contains(a.pacote) || fav.contains(a.pacote)) continue;
            View c = cartaoApp(a);
            c.setTag(a.pacote);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.setMargins(0, 0, dp(14), dp(14));
            grade.addView(c, lp);
            visiveis++;
            if (primeiroFocavel == null) primeiroFocavel = c;
            if (a.pacote.equals(pacoteFocado)) focarDepois = c;
        }

        botaoOcultos.setText(ocultos.isEmpty() ? "Apps ocultos" : "Apps ocultos (" + contarOcultosInstalados(ocultos) + ")");
        definirChip(chipApps, apps.size() + " instalados", VERDE);

        View alvo = focarDepois != null ? focarDepois : primeiroFocavel;
        if (alvo != null) alvo.requestFocus();
    }

    private int contarOcultosInstalados(LinkedHashSet<String> ocultos) {
        int n = 0;
        for (String p : ocultos) if (apps.containsKey(p)) n++;
        return n;
    }

    private Intent intencaoAbrir(String pacote) {
        Intent i = null;
        try {
            i = pm.getLeanbackLaunchIntentForPackage(pacote);
        } catch (Exception ignorado) {
        }
        if (i == null) {
            try {
                i = pm.getLaunchIntentForPackage(pacote);
            } catch (Exception ignorado) {
            }
        }
        return i;
    }

    private void abrirApp(String pacote) {
        Intent i = intencaoAbrir(pacote);
        if (i == null) {
            Toast.makeText(this, "Esse app não pode ser aberto.", Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Não consegui abrir esse app.", Toast.LENGTH_SHORT).show();
        }
    }

    private void abrirConfiguracoes() {
        try {
            startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Não consegui abrir as configurações.", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ opções do app

    private void mostrarOpcoes(App a) {
        LinkedHashSet<String> fav = favoritos();
        boolean ehFavorito = fav.contains(a.pacote);
        String[] itens = {
                ehFavorito ? "Tirar dos favoritos" : "Colocar nos favoritos",
                "Ocultar da tela inicial",
                "Informações do app",
                "Desinstalar"
        };
        new AlertDialog.Builder(this, R.style.Dialogo)
                .setTitle(a.nome)
                .setItems(itens, (d, qual) -> {
                    switch (qual) {
                        case 0:
                            if (ehFavorito) fav.remove(a.pacote);
                            else fav.add(a.pacote);
                            salvar("favoritos", fav);
                            desenharApps();
                            break;
                        case 1:
                            LinkedHashSet<String> oc = ocultos();
                            oc.add(a.pacote);
                            salvar("ocultos", oc);
                            desenharApps();
                            Toast.makeText(this, a.nome + " foi ocultado. Para voltar: Apps ocultos.", Toast.LENGTH_LONG).show();
                            break;
                        case 2:
                            try {
                                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:" + a.pacote)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            } catch (Exception e) {
                                Toast.makeText(this, "Não consegui abrir.", Toast.LENGTH_SHORT).show();
                            }
                            break;
                        case 3:
                            try {
                                startActivity(new Intent(Intent.ACTION_DELETE,
                                        Uri.parse("package:" + a.pacote)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                            } catch (Exception e) {
                                Toast.makeText(this, "Esse app não pode ser desinstalado.", Toast.LENGTH_SHORT).show();
                            }
                            break;
                        default:
                            break;
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void mostrarOcultos() {
        LinkedHashSet<String> oc = ocultos();
        List<String> pacotes = new ArrayList<>();
        List<String> nomes = new ArrayList<>();
        for (String p : oc) {
            App a = apps.get(p);
            if (a == null) continue;
            pacotes.add(p);
            nomes.add(a.nome);
        }
        if (pacotes.isEmpty()) {
            Toast.makeText(this, "Nenhum app oculto.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this, R.style.Dialogo)
                .setTitle("Toque para mostrar de novo")
                .setItems(nomes.toArray(new String[0]), (d, qual) -> {
                    LinkedHashSet<String> atual = ocultos();
                    atual.remove(pacotes.get(qual));
                    salvar("ocultos", atual);
                    desenharApps();
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private LinkedHashSet<String> favoritos() {
        return lerLista("favoritos", FAVORITOS_PADRAO);
    }

    private LinkedHashSet<String> ocultos() {
        return lerLista("ocultos", "");
    }

    private LinkedHashSet<String> lerLista(String chave, String padrao) {
        String s = prefs.getString(chave, padrao);
        LinkedHashSet<String> r = new LinkedHashSet<>();
        if (s == null || s.isEmpty()) return r;
        r.addAll(Arrays.asList(s.split(",")));
        r.remove("");
        return r;
    }

    private void salvar(String chave, LinkedHashSet<String> valores) {
        prefs.edit().putString(chave, TextUtils.join(",", valores)).apply();
    }

    // ------------------------------------------------------------------ relógio e status

    private final Runnable tique = new Runnable() {
        @Override
        public void run() {
            Date agora = new Date();
            relogio.setText(new SimpleDateFormat("HH:mm", PT_BR).format(agora));
            String d = new SimpleDateFormat("EEEE, d 'de' MMMM", PT_BR).format(agora);
            data.setText(d.substring(0, 1).toUpperCase(PT_BR) + d.substring(1));
            ui.postDelayed(this, 15_000);
        }
    };

    private final Runnable verificarStatus = new Runnable() {
        @Override
        public void run() {
            atualizarStatusLocal();
            rede.execute(() -> {
                long ms = testar(TESTE_INTERNET, 204);
                ui.post(() -> {
                    if (ms >= 0) definirChip(chipInternet, "Online · " + ms + " ms", ms < 400 ? VERDE : AMARELO);
                    else definirChip(chipInternet, "Sem internet", VERMELHO);
                });
                if (ms >= 0 && System.currentTimeMillis() - ultimaListaMs > 10 * 60_000) {
                    String json = baixar(LISTA_SISTEMAS);
                    if (json != null) {
                        ultimaListaMs = System.currentTimeMillis();
                        prefs.edit().putString("sistemas_json", json).apply();
                        ui.post(() -> montarSistemas(json));
                    }
                }
                ui.post(() -> testarSistemas(ms >= 0));
            });
            ui.postDelayed(this, 30_000);
        }
    };

    /** Devolve o tempo de resposta em ms, ou -1 se falhar. */
    private long testar(String endereco, int codigoEsperado) {
        HttpURLConnection c = null;
        try {
            long inicio = System.currentTimeMillis();
            c = (HttpURLConnection) new URL(endereco).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(true);
            c.setUseCaches(false);
            int codigo = c.getResponseCode();
            long ms = System.currentTimeMillis() - inicio;
            if (codigoEsperado == 0) return codigo > 0 && codigo < 500 ? ms : -1;
            return (codigo == codigoEsperado || (codigoEsperado == 200 && codigo >= 200 && codigo < 400)) ? ms : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    // ------------------------------------------------------------------ sistemas da Roxinha

    private String baixar(String endereco) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(endereco + "?t=" + System.currentTimeMillis()).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) return null;
            java.io.InputStream in = c.getInputStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            String s = out.toString("UTF-8");
            new org.json.JSONObject(s); // valida antes de usar
            return s;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String listaInicial() {
        String salvo = prefs.getString("sistemas_json", null);
        if (salvo != null) return salvo;
        try {
            java.io.InputStream in = getAssets().open("sistemas.json");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "{\"sistemas\":[]}";
        }
    }

    private void montarSistemas(String json) {
        List<Sistema> novos = new ArrayList<>();
        try {
            org.json.JSONArray arr = new org.json.JSONObject(json).getJSONArray("sistemas");
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                Sistema s = new Sistema();
                s.grupo = o.optString("grupo", "");
                s.nome = o.optString("nome", "Sistema");
                s.url = o.optString("url", "");
                if (!s.url.isEmpty()) novos.add(s);
            }
        } catch (Exception e) {
            return;
        }
        sistemas.clear();
        sistemas.addAll(novos);
        gradeSistemas.removeAllViews();
        for (Sistema s : sistemas) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.setMargins(0, 0, dp(12), dp(12));
            gradeSistemas.addView(cartaoSistema(s), lp);
        }
        atualizarResumo();
    }

    private View cartaoSistema(Sistema s) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        s.fundo = fundoArredondado(CARTAO, 12, Color.TRANSPARENT);
        c.setBackground(s.fundo);

        TextView g = new TextView(this);
        g.setText(s.grupo.toUpperCase(PT_BR));
        g.setTextColor(VERMELHO);
        g.setTextSize(9);
        g.setLetterSpacing(0.1f);
        c.addView(g);

        TextView n = new TextView(this);
        n.setText(s.nome);
        n.setTextColor(BRANCO);
        n.setTextSize(13);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setSingleLine(true);
        n.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(n, margem(-1, -2, 0, dp(2), 0, 0));

        TextView h = new TextView(this);
        h.setText(Uri.parse(s.url).getHost());
        h.setTextColor(Color.parseColor("#6B7280"));
        h.setTextSize(10);
        h.setSingleLine(true);
        h.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(h);

        s.status = new TextView(this);
        s.status.setTextSize(11);
        s.status.setTextColor(CINZA);
        s.status.setSingleLine(true);
        definirChip(s.status, "verificando…", AMARELO);
        c.addView(s.status, margem(-1, -2, 0, dp(4), 0, 0));

        c.setFocusable(true);
        c.setClickable(true);
        c.setOnFocusChangeListener((v, foco) -> {
            s.fundo.setStroke(dp(2), foco ? VERMELHO : corBorda(s.estado));
            v.animate().scaleX(foco ? 1.05f : 1f).scaleY(foco ? 1.05f : 1f).setDuration(120).start();
        });
        c.setOnClickListener(v -> {
            definirChip(s.status, "testando agora…", AMARELO);
            testarUm(s);
        });
        return c;
    }

    private int corBorda(int estado) {
        return estado == 3 ? Color.parseColor("#7F1D1D") : Color.TRANSPARENT;
    }

    private void testarSistemas(boolean temInternet) {
        if (sistemas.isEmpty()) montarSistemas(listaInicial());
        if (!temInternet) {
            for (Sistema s : sistemas) definirChip(s.status, "sem internet na TV", CINZA);
            definirChip(chipRoxinha, "sem internet", CINZA);
            return;
        }
        for (Sistema s : sistemas) testarUm(s);
    }

    private void testarUm(Sistema s) {
        try {
            testes.execute(() -> {
                long ms = testar(s.url, 0);
                ui.post(() -> {
                    if (ms < 0) {
                        s.estado = 3;
                        definirChip(s.status, "Fora do ar", VERMELHO);
                    } else if (ms > 2500) {
                        s.estado = 2;
                        definirChip(s.status, "Lento · " + ms + " ms", AMARELO);
                    } else {
                        s.estado = 1;
                        definirChip(s.status, "No ar · " + ms + " ms", VERDE);
                    }
                    if (s.fundo != null) s.fundo.setStroke(dp(2), corBorda(s.estado));
                    atualizarResumo();
                });
            });
        } catch (Exception ignorado) {
        }
    }

    private void atualizarResumo() {
        int total = sistemas.size(), ok = 0, fora = 0, testados = 0;
        for (Sistema s : sistemas) {
            if (s.estado != 0) testados++;
            if (s.estado == 1 || s.estado == 2) ok++;
            if (s.estado == 3) fora++;
        }
        if (total == 0) {
            resumoSistemas.setText("");
            return;
        }
        if (testados < total) {
            resumoSistemas.setText("verificando " + testados + " de " + total + "…");
        } else {
            resumoSistemas.setText(ok + " de " + total + " no ar" + (fora > 0 ? " · " + fora + " fora do ar" : " · tudo certo"));
        }
        if (testados > 0) {
            definirChip(chipRoxinha, ok + " de " + total + " no ar",
                    fora == 0 ? VERDE : ok == 0 ? VERMELHO : AMARELO);
        }
    }

    @SuppressWarnings("deprecation")
    private void atualizarStatusLocal() {
        // Rede
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) {
                definirChip(chipRede, "Desconectado", VERMELHO);
            } else {
                String tipo = ni.getType() == ConnectivityManager.TYPE_ETHERNET ? "Cabo" : "Wi-Fi";
                String ip = ipLocal();
                definirChip(chipRede, tipo + (ip != null ? " · " + ip : ""), VERDE);
            }
        } catch (Exception e) {
            definirChip(chipRede, "—", CINZA);
        }
        // Armazenamento
        try {
            StatFs st = new StatFs(Environment.getDataDirectory().getPath());
            long livre = st.getAvailableBytes();
            long total = st.getTotalBytes();
            double pct = total > 0 ? (double) livre / total : 1;
            definirChip(chipArmazenamento, gb(livre) + " de " + gb(total),
                    pct > 0.2 ? VERDE : pct > 0.08 ? AMARELO : VERMELHO);
        } catch (Exception e) {
            definirChip(chipArmazenamento, "—", CINZA);
        }
        // Memória
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            double pct = mi.totalMem > 0 ? (double) mi.availMem / mi.totalMem : 1;
            definirChip(chipMemoria, gb(mi.availMem) + " de " + gb(mi.totalMem),
                    mi.lowMemory ? VERMELHO : pct > 0.25 ? VERDE : AMARELO);
        } catch (Exception e) {
            definirChip(chipMemoria, "—", CINZA);
        }
    }

    private String ipLocal() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> ends = ni.getInetAddresses();
                while (ends.hasMoreElements()) {
                    InetAddress e = ends.nextElement();
                    if (e instanceof Inet4Address && !e.isLoopbackAddress()) return e.getHostAddress();
                }
            }
        } catch (Exception ignorado) {
        }
        return null;
    }

    private static String gb(long bytes) {
        double g = bytes / (1024.0 * 1024.0 * 1024.0);
        if (g < 1) return String.format(PT_BR, "%.0f MB", bytes / (1024.0 * 1024.0));
        return String.format(PT_BR, "%.1f GB", g);
    }

    // ------------------------------------------------------------------ utilidades

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable fundoArredondado(int cor, int raioDp, int borda) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(cor);
        g.setCornerRadius(dp(raioDp));
        if (borda != Color.TRANSPARENT) g.setStroke(dp(1), borda);
        return g;
    }

    private LinearLayout.LayoutParams margem(int l, int a, int esq, int topo, int dir, int baixo) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(l, a);
        lp.setMargins(esq, topo, dir, baixo);
        return lp;
    }
}
