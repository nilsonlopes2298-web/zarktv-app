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
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
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
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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
 * Tela inicial ZarkTV no estilo Apple TV (tvOS): vitrine grande do app em foco,
 * fileira de cima com 4 favoritos + "Meu Servidor", grade de apps e uma tela
 * "Meu Servidor" no estilo dos Ajustes da Apple TV com tudo da VPS Roxinha.
 */
public class MainActivity extends Activity {

    // Paleta (modo escuro da Apple)
    private static final int FUNDO = Color.parseColor("#0B0B0D");
    private static final int CINZA_1 = Color.parseColor("#1C1C1E");
    private static final int CINZA_2 = Color.parseColor("#2C2C2E");
    private static final int CINZA_3 = Color.parseColor("#3A3A3C");
    private static final int TEXTO_2 = Color.parseColor("#98989F");
    private static final int TEXTO_3 = Color.parseColor("#636366");
    private static final int VERDE = Color.parseColor("#30D158");
    private static final int AMARELO = Color.parseColor("#FFD60A");
    private static final int VERMELHO = Color.parseColor("#FF453A");
    private static final int BRANCO = Color.WHITE;
    private static final int PRETO_TEXTO = Color.parseColor("#1C1C1E");

    private static final String LISTA_SISTEMAS =
            "https://raw.githubusercontent.com/nilsonlopes2298-web/zarktv-app/launcher/launcher/sistemas.json";
    private static final String TESTE_INTERNET = "http://connectivitycheck.gstatic.com/generate_204";
    private static final String FAVORITOS_PADRAO =
            "com.google.android.youtube.tv,com.amazon.firetv.youtube,com.netflix.ninja,"
                    + "cfd.zarktvqx.zarkplay,cfd.zarktvqx.tv,com.zark.play.tv";
    private static final int MAX_DOCK = 4;
    private static final String SERVIDOR = "__servidor__";
    private static final String NOME_SERVIDOR = "Meu Servidor";

    private static final Locale PT_BR = new Locale("pt", "BR");

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService fundo = Executors.newSingleThreadExecutor();
    private final ExecutorService rede = Executors.newSingleThreadExecutor();
    private final ExecutorService testes = Executors.newFixedThreadPool(6);

    private SharedPreferences prefs;
    private PackageManager pm;

    // Tela inicial
    private ImageView vitrineImagem;
    private TextView vitrineTitulo, vitrineSub, relogio;
    private LinearLayout dock;
    private GridLayout grade;
    private View primeiroFocavel, pontoServidor;
    private int larguraIcone, alturaIcone, espaco;

    // Tela "Meu Servidor"
    private FrameLayout painel;
    private ScrollView painelRolagem;
    private TextView pResumoGrande, pAtualizado, pInternet, pRede, pArmazenamento, pMemoria, pApps;
    private TextView pRoxinhaResumo, pVpsCpu, pVpsMemoria, pVpsDisco, pVpsLigada;
    private LinearLayout pSistemas;
    private View painelPrimeiro;

    // Tela de detalhe de um sistema
    private FrameLayout detalhe;
    private ScrollView detalheRolagem;
    private TextView dTitulo, dGrupo, dSituacao;
    private LinearLayout dLista;
    private Sistema sistemaAberto;

    private final Map<String, App> apps = new LinkedHashMap<>();
    private final List<Sistema> sistemas = new ArrayList<>();
    private long ultimaListaMs = 0;
    private String urlMetricas = null;
    private volatile boolean temInternet = true;

    static class App {
        String pacote, nome, versao = "";
        Drawable banner, icone;
        int cor;
    }

    static class Sistema {
        String grupo, nome, url, detalhes;
        TextView status;
        View linhaView;
        volatile int estado; // 0 verificando, 1 no ar, 2 lento, 3 fora do ar
        volatile long ms = -1;
    }

    // ================================================================ ciclo de vida

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
        ui.removeCallbacks(verificar);
        ui.post(verificar);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tique);
        ui.removeCallbacks(verificar);
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
        if (detalhe != null) detalhe.setVisibility(View.GONE);
        fecharPainel();
        if (primeiroFocavel != null) primeiroFocavel.requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (detalhe.getVisibility() == View.VISIBLE) {
            fecharDetalhe();
            return;
        }
        if (painel.getVisibility() == View.VISIBLE) {
            fecharPainel();
            return;
        }
        if (primeiroFocavel != null) primeiroFocavel.requestFocus();
    }

    private final BroadcastReceiver mudancaApps = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            carregarApps();
        }
    };

    // ================================================================ tela inicial

    private View montarTela() {
        int altura = getResources().getDisplayMetrics().heightPixels;
        int largura = getResources().getDisplayMetrics().widthPixels;
        int margemLado = dp(64);
        espaco = dp(26);
        // 5 ícones por linha, proporção 5:3 dos ícones da Apple TV
        larguraIcone = (largura - 2 * margemLado - 4 * espaco) / 5;
        alturaIcone = larguraIcone * 3 / 5;
        int alturaVitrine = (int) (altura * 0.70f);

        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(FUNDO);

        // Vitrine (top shelf): imagem grande e desfocada do app em foco
        vitrineImagem = new ImageView(this);
        vitrineImagem.setScaleType(ImageView.ScaleType.CENTER_CROP);
        vitrineImagem.setAlpha(0.5f);
        if (Build.VERSION.SDK_INT >= 31) {
            vitrineImagem.setRenderEffect(RenderEffect.createBlurEffect(28f, 28f, Shader.TileMode.CLAMP));
        }
        raiz.addView(vitrineImagem, new FrameLayout.LayoutParams(-1, alturaVitrine));

        View degradeBaixo = new View(this);
        degradeBaixo.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#400B0B0D"), Color.parseColor("#990B0B0D"), FUNDO}));
        raiz.addView(degradeBaixo, new FrameLayout.LayoutParams(-1, alturaVitrine));

        View degradeLado = new View(this);
        degradeLado.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#D90B0B0D"), Color.parseColor("#400B0B0D"), Color.parseColor("#000B0B0D")}));
        raiz.addView(degradeLado, new FrameLayout.LayoutParams(-1, alturaVitrine));

        // Relógio discreto no canto
        relogio = new TextView(this);
        relogio.setTextColor(BRANCO);
        relogio.setTextSize(20);
        relogio.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        FrameLayout.LayoutParams lpRel = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        lpRel.setMargins(0, dp(30), margemLado, 0);
        raiz.addView(relogio, lpRel);

        // Conteúdo rolável
        ScrollView rolagem = new ScrollView(this);
        rolagem.setVerticalScrollBarEnabled(false);
        rolagem.setClipChildren(false);
        rolagem.setClipToPadding(false);
        rolagem.setSmoothScrollingEnabled(true);
        raiz.addView(rolagem, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout coluna = new LinearLayout(this);
        coluna.setOrientation(LinearLayout.VERTICAL);
        coluna.setClipChildren(false);
        coluna.setClipToPadding(false);
        coluna.setPadding(margemLado, 0, margemLado, dp(56));
        rolagem.addView(coluna, new ScrollView.LayoutParams(-1, -2));

        // Nome e detalhe do item em foco, sobre a vitrine
        LinearLayout textoVitrine = new LinearLayout(this);
        textoVitrine.setOrientation(LinearLayout.VERTICAL);
        textoVitrine.setGravity(Gravity.BOTTOM);
        textoVitrine.setPadding(0, 0, 0, dp(26));
        vitrineTitulo = new TextView(this);
        vitrineTitulo.setTextColor(BRANCO);
        vitrineTitulo.setTextSize(44);
        vitrineTitulo.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        vitrineTitulo.setLetterSpacing(-0.01f);
        vitrineTitulo.setSingleLine(true);
        vitrineTitulo.setEllipsize(TextUtils.TruncateAt.END);
        vitrineTitulo.setShadowLayer(dp(12), 0, dp(2), Color.parseColor("#99000000"));
        textoVitrine.addView(vitrineTitulo);
        vitrineSub = new TextView(this);
        vitrineSub.setTextColor(Color.parseColor("#C7C7CC"));
        vitrineSub.setTextSize(16);
        textoVitrine.addView(vitrineSub, margem(-2, -2, 0, dp(6), 0, 0));
        coluna.addView(textoVitrine, new LinearLayout.LayoutParams(-1, (int) (altura * 0.46f)));

        // Fileira de cima (dock): 4 favoritos + Meu Servidor
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        coluna.addView(dock, new LinearLayout.LayoutParams(-1, -2));

        // Linha fina separando a fileira de cima da grade
        View linha = new View(this);
        linha.setBackgroundColor(Color.parseColor("#22FFFFFF"));
        coluna.addView(linha, margem(-1, Math.max(1, dp(1) / 2), 0, dp(4), 0, dp(30)));

        // Demais apps
        grade = new GridLayout(this);
        grade.setColumnCount(5);
        grade.setClipChildren(false);
        grade.setClipToPadding(false);
        coluna.addView(grade);

        raiz.addView(montarPainel(), new FrameLayout.LayoutParams(-1, -1));
        raiz.addView(montarDetalhe(), new FrameLayout.LayoutParams(-1, -1));
        return raiz;
    }

    /** Ícone no estilo Apple TV: cantos suaves, cresce, ganha sombra e brilho no foco. */
    private View icone(String chave, View conteudo, Drawable fundoIcone, String nome,
                       Runnable aoFocar, Runnable aoClicar, Runnable aoSegurar) {
        LinearLayout caixa = new LinearLayout(this);
        caixa.setOrientation(LinearLayout.VERTICAL);
        caixa.setGravity(Gravity.CENTER_HORIZONTAL);
        caixa.setClipChildren(false);
        caixa.setClipToPadding(false);

        FrameLayout cartao = new FrameLayout(this);
        cartao.setBackground(fundoIcone);
        cartao.setClipToOutline(true);
        cartao.addView(conteudo, new FrameLayout.LayoutParams(-1, -1));

        // Brilho que aparece no foco (efeito de luz da Apple TV)
        View brilho = new View(this);
        brilho.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#40FFFFFF"), Color.parseColor("#08FFFFFF"), Color.parseColor("#00FFFFFF")}));
        brilho.setAlpha(0f);
        cartao.addView(brilho, new FrameLayout.LayoutParams(-1, -1));
        caixa.addView(cartao, new LinearLayout.LayoutParams(larguraIcone, alturaIcone));

        TextView rotulo = new TextView(this);
        rotulo.setText(nome);
        rotulo.setTextColor(BRANCO);
        rotulo.setTextSize(14);
        rotulo.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        rotulo.setGravity(Gravity.CENTER);
        rotulo.setSingleLine(true);
        rotulo.setEllipsize(TextUtils.TruncateAt.END);
        rotulo.setAlpha(0f);
        LinearLayout.LayoutParams lpRotulo = new LinearLayout.LayoutParams(larguraIcone + dp(20), -2);
        lpRotulo.topMargin = dp(16);
        caixa.addView(rotulo, lpRotulo);

        caixa.setTag(chave);
        caixa.setFocusable(true);
        caixa.setClickable(true);
        caixa.setOnFocusChangeListener((v, foco) -> {
            float e = foco ? 1.14f : 1f;
            cartao.animate().scaleX(e).scaleY(e).translationY(foco ? -dp(6) : 0).setDuration(180).start();
            cartao.setElevation(foco ? dp(24) : dp(2));
            brilho.animate().alpha(foco ? 1f : 0f).setDuration(180).start();
            rotulo.animate().alpha(foco ? 1f : 0f).translationY(foco ? dp(4) : 0).setDuration(180).start();
            if (foco) {
                v.requestRectangleOnScreen(new Rect(0, -dp(40), v.getWidth(), v.getHeight() + dp(40)));
                if (aoFocar != null) aoFocar.run();
            }
        });
        caixa.setOnClickListener(v -> {
            cartao.animate().scaleX(1.06f).scaleY(1.06f).setDuration(80)
                    .withEndAction(() -> cartao.animate().scaleX(1.14f).scaleY(1.14f).setDuration(80).start()).start();
            aoClicar.run();
        });
        if (aoSegurar != null) {
            caixa.setOnLongClickListener(v -> {
                aoSegurar.run();
                return true;
            });
            caixa.setOnKeyListener((v, tecla, ev) -> {
                if (ev.getAction() == KeyEvent.ACTION_UP && tecla == KeyEvent.KEYCODE_MENU) {
                    aoSegurar.run();
                    return true;
                }
                return false;
            });
        }
        return caixa;
    }

    private GradientDrawable fundoArredondado(int[] cores) {
        GradientDrawable g = cores.length == 1
                ? new GradientDrawable()
                : new GradientDrawable(GradientDrawable.Orientation.TL_BR, cores);
        if (cores.length == 1) g.setColor(cores[0]);
        g.setCornerRadius(dp(12));
        return g;
    }

    private View iconeApp(App a) {
        View conteudo;
        Drawable fundoIcone;
        if (a.banner != null) {
            ImageView img = new ImageView(this);
            img.setImageDrawable(a.banner);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            conteudo = img;
            fundoIcone = fundoArredondado(new int[]{CINZA_2});
        } else {
            FrameLayout f = new FrameLayout(this);
            ImageView img = new ImageView(this);
            img.setImageDrawable(a.icone);
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int t = Math.min(alturaIcone * 3 / 5, dp(64));
            f.addView(img, new FrameLayout.LayoutParams(t, t, Gravity.CENTER));
            conteudo = f;
            fundoIcone = fundoArredondado(new int[]{clarear(a.cor, 0.12f), escurecer(a.cor, 0.35f)});
        }
        return icone(a.pacote, conteudo, fundoIcone, a.nome,
                () -> mostrarNaVitrine(a), () -> abrirApp(a.pacote), () -> mostrarOpcoes(a));
    }

    private View iconeServidor() {
        FrameLayout f = new FrameLayout(this);
        ImageView img = new ImageView(this);
        img.setImageResource(R.drawable.servidor);
        int t = Math.min(alturaIcone / 2, dp(48));
        f.addView(img, new FrameLayout.LayoutParams(t, t, Gravity.CENTER));
        pontoServidor = new View(this);
        GradientDrawable bola = new GradientDrawable();
        bola.setShape(GradientDrawable.OVAL);
        bola.setColor(TEXTO_2);
        bola.setStroke(dp(2), Color.parseColor("#55000000"));
        pontoServidor.setBackground(bola);
        FrameLayout.LayoutParams lpPonto = new FrameLayout.LayoutParams(dp(12), dp(12), Gravity.TOP | Gravity.END);
        lpPonto.setMargins(0, dp(10), dp(10), 0);
        f.addView(pontoServidor, lpPonto);
        return icone(SERVIDOR, f,
                fundoArredondado(new int[]{Color.parseColor("#C8102E"), Color.parseColor("#4A0710")}),
                NOME_SERVIDOR, this::mostrarServidorNaVitrine, this::abrirPainel, null);
    }

    private View iconeAjustes() {
        FrameLayout f = new FrameLayout(this);
        ImageView img = new ImageView(this);
        img.setImageResource(R.drawable.engrenagem);
        int t = Math.min(alturaIcone / 2, dp(46));
        f.addView(img, new FrameLayout.LayoutParams(t, t, Gravity.CENTER));
        return icone("__ajustes__", f,
                fundoArredondado(new int[]{Color.parseColor("#636366"), Color.parseColor("#2C2C2E")}),
                "Ajustes",
                () -> {
                    vitrineImagem.setImageDrawable(null);
                    vitrineTitulo.setText("Ajustes");
                    vitrineSub.setText("Configurações da TV e apps ocultos");
                },
                this::opcoesGerais, null);
    }

    private void mostrarNaVitrine(App a) {
        vitrineImagem.setImageDrawable(a.banner != null ? a.banner : a.icone);
        vitrineTitulo.setText(a.nome);
        boolean abre = intencaoAbrir(a.pacote) != null;
        vitrineSub.setText(abre
                ? (TextUtils.isEmpty(a.versao) ? "Pronto para abrir" : "Versão " + a.versao)
                : "Esse app não abre na TV");
    }

    private void mostrarServidorNaVitrine() {
        vitrineImagem.setImageDrawable(null);
        vitrineTitulo.setText(NOME_SERVIDOR);
        vitrineSub.setText(resumoGeral());
    }

    // ================================================================ apps

    private void carregarApps() {
        fundo.execute(() -> {
            Map<String, App> novos = new LinkedHashMap<>();
            String[] categorias = {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER};
            for (String cat : categorias) {
                List<ResolveInfo> lista;
                try {
                    lista = pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(cat), 0);
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
                    a.cor = corMedia(a.icone);
                    try {
                        PackageInfo info = pm.getPackageInfo(pacote, 0);
                        a.versao = info.versionName == null ? "" : info.versionName;
                        if (a.versao.length() > 14) a.versao = a.versao.substring(0, 14);
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
        String chaveFocada = focado != null && focado.getTag() instanceof String ? (String) focado.getTag() : null;

        LinkedHashSet<String> fav = favoritos();
        LinkedHashSet<String> ocultos = ocultos();

        dock.removeAllViews();
        grade.removeAllViews();
        primeiroFocavel = null;
        View focarDepois = null;

        // Fileira de cima: até 4 favoritos + Meu Servidor
        List<App> naGrade = new ArrayList<>();
        int noDock = 0;
        for (String p : fav) {
            App a = apps.get(p);
            if (a == null || ocultos.contains(p)) continue;
            if (noDock >= MAX_DOCK) {
                naGrade.add(a);
                continue;
            }
            View t = iconeApp(a);
            dock.addView(t, margem(-2, -2, 0, 0, espaco, 0));
            noDock++;
            if (primeiroFocavel == null) primeiroFocavel = t;
            if (p.equals(chaveFocada)) focarDepois = t;
        }
        View tServidor = iconeServidor();
        dock.addView(tServidor, margem(-2, -2, 0, 0, 0, 0));
        if (primeiroFocavel == null) primeiroFocavel = tServidor;
        if (SERVIDOR.equals(chaveFocada)) focarDepois = tServidor;

        // Grade: favoritos que sobraram + demais apps + Ajustes
        for (App a : apps.values()) {
            if (ocultos.contains(a.pacote) || fav.contains(a.pacote)) continue;
            naGrade.add(a);
        }
        int i = 0;
        for (App a : naGrade) {
            View t = iconeApp(a);
            grade.addView(t, itemGrade(i++));
            if (a.pacote.equals(chaveFocada)) focarDepois = t;
        }
        View tAjustes = iconeAjustes();
        grade.addView(tAjustes, itemGrade(i));
        if ("__ajustes__".equals(chaveFocada)) focarDepois = tAjustes;

        pApps.setText(apps.size() + " instalados");
        atualizarResumo();

        View alvo = focarDepois != null ? focarDepois : primeiroFocavel;
        if (alvo != null && painel.getVisibility() != View.VISIBLE) alvo.requestFocus();
    }

    private GridLayout.LayoutParams itemGrade(int indice) {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.setMargins(0, 0, (indice % 5 == 4) ? 0 : espaco, dp(8));
        return lp;
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

    private void mostrarOpcoes(App a) {
        LinkedHashSet<String> fav = favoritos();
        boolean ehFavorito = fav.contains(a.pacote);
        String[] itens = {
                ehFavorito ? "Tirar da fileira de cima" : "Colocar na fileira de cima",
                "Ocultar da tela inicial",
                "Informações do app",
                "Desinstalar"
        };
        new AlertDialog.Builder(this, R.style.Dialogo)
                .setTitle(a.nome)
                .setItems(itens, (d, qual) -> {
                    if (qual == 0) {
                        if (ehFavorito) {
                            fav.remove(a.pacote);
                        } else {
                            // entra no começo da fileira de cima
                            LinkedHashSet<String> novo = new LinkedHashSet<>();
                            novo.add(a.pacote);
                            novo.addAll(fav);
                            fav.clear();
                            fav.addAll(novo);
                        }
                        salvar("favoritos", fav);
                        desenharApps();
                    } else if (qual == 1) {
                        LinkedHashSet<String> oc = ocultos();
                        oc.add(a.pacote);
                        salvar("ocultos", oc);
                        desenharApps();
                        Toast.makeText(this, a.nome + " foi ocultado. Para voltar: Ajustes.", Toast.LENGTH_LONG).show();
                    } else if (qual == 2) {
                        abrirIntent(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.pacote)));
                    } else if (qual == 3) {
                        abrirIntent(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + a.pacote)));
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void opcoesGerais() {
        String[] itens = {"Configurações da TV", "Mostrar apps ocultos", "Chave do servidor"};
        new AlertDialog.Builder(this, R.style.Dialogo)
                .setTitle("Ajustes")
                .setItems(itens, (d, qual) -> {
                    if (qual == 0) abrirIntent(new Intent(Settings.ACTION_SETTINGS));
                    else if (qual == 1) mostrarOcultos();
                    else pedirChave();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void mostrarOcultos() {
        List<String> pacotes = new ArrayList<>();
        List<String> nomes = new ArrayList<>();
        for (String p : ocultos()) {
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
                .setTitle("Escolha para mostrar de novo")
                .setItems(nomes.toArray(new String[0]), (d, qual) -> {
                    LinkedHashSet<String> atual = ocultos();
                    atual.remove(pacotes.get(qual));
                    salvar("ocultos", atual);
                    desenharApps();
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void abrirIntent(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            Toast.makeText(this, "Não foi possível abrir.", Toast.LENGTH_SHORT).show();
        }
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

    // ================================================================ tela "Meu Servidor" (estilo Ajustes da Apple TV)

    private View montarPainel() {
        painel = new FrameLayout(this);
        painel.setBackgroundColor(FUNDO);
        painel.setVisibility(View.GONE);

        LinearLayout duas = new LinearLayout(this);
        duas.setOrientation(LinearLayout.HORIZONTAL);
        painel.addView(duas, new FrameLayout.LayoutParams(-1, -1));

        // Lado esquerdo: ícone grande e resumo
        LinearLayout esq = new LinearLayout(this);
        esq.setOrientation(LinearLayout.VERTICAL);
        esq.setGravity(Gravity.CENTER);
        esq.setPadding(dp(48), dp(40), dp(24), dp(40));

        FrameLayout grande = new FrameLayout(this);
        grande.setBackground(fundoArredondado(new int[]{Color.parseColor("#C8102E"), Color.parseColor("#4A0710")}));
        ((GradientDrawable) grande.getBackground()).setCornerRadius(dp(22));
        grande.setElevation(dp(16));
        ImageView sv = new ImageView(this);
        sv.setImageResource(R.drawable.servidor);
        grande.addView(sv, new FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER));
        esq.addView(grande, new LinearLayout.LayoutParams(dp(250), dp(150)));

        TextView t = new TextView(this);
        t.setText(NOME_SERVIDOR);
        t.setTextColor(BRANCO);
        t.setTextSize(30);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setGravity(Gravity.CENTER);
        esq.addView(t, margem(-2, -2, 0, dp(28), 0, 0));

        TextView sub = new TextView(this);
        sub.setText("VPS Roxinha · Hostinger");
        sub.setTextColor(TEXTO_2);
        sub.setTextSize(15);
        sub.setGravity(Gravity.CENTER);
        esq.addView(sub, margem(-2, -2, 0, dp(4), 0, 0));

        pResumoGrande = new TextView(this);
        pResumoGrande.setTextSize(17);
        pResumoGrande.setGravity(Gravity.CENTER);
        esq.addView(pResumoGrande, margem(-2, -2, 0, dp(22), 0, 0));

        pAtualizado = new TextView(this);
        pAtualizado.setTextColor(TEXTO_3);
        pAtualizado.setTextSize(13);
        pAtualizado.setGravity(Gravity.CENTER);
        esq.addView(pAtualizado, margem(-2, -2, 0, dp(6), 0, 0));
        duas.addView(esq, new LinearLayout.LayoutParams(0, -1, 0.42f));

        // Lado direito: lista rolável
        painelRolagem = new ScrollView(this);
        painelRolagem.setVerticalScrollBarEnabled(false);
        painelRolagem.setClipToPadding(false);
        painelRolagem.setPadding(0, dp(48), 0, dp(48));
        duas.addView(painelRolagem, new LinearLayout.LayoutParams(0, -1, 0.58f));

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(12), 0, dp(64), 0);
        painelRolagem.addView(c, new ScrollView.LayoutParams(-1, -2));

        c.addView(cabecalhoSecao("VPS ROXINHA", true));
        pRoxinhaResumo = linha(c, "Sistemas no ar", null, null);
        pVpsCpu = linha(c, "Processador", null, null);
        pVpsMemoria = linha(c, "Memória", null, null);
        pVpsDisco = linha(c, "Disco", null, null);
        pVpsLigada = linha(c, "Ligada há", null, null);
        for (TextView v : new TextView[]{pVpsCpu, pVpsMemoria, pVpsDisco, pVpsLigada}) {
            v.setText("aguardando o monitor");
            v.setTextColor(TEXTO_3);
        }

        pSistemas = new LinearLayout(this);
        pSistemas.setOrientation(LinearLayout.VERTICAL);
        c.addView(pSistemas);

        c.addView(cabecalhoSecao("ESTA TV", false));
        pInternet = linha(c, "Internet", null, null);
        pRede = linha(c, "Rede", null, null);
        pArmazenamento = linha(c, "Armazenamento livre", null, null);
        pMemoria = linha(c, "Memória livre", null, null);
        pApps = linha(c, "Apps instalados", null, null);

        TextView dica = new TextView(this);
        dica.setText("OK num sistema abre os detalhes e as métricas  ·  Voltar fecha");
        dica.setTextColor(TEXTO_3);
        dica.setTextSize(12);
        c.addView(dica, margem(-1, -2, dp(18), dp(28), 0, 0));
        return painel;
    }

    private TextView cabecalhoSecao(String texto, boolean primeiro) {
        TextView h = new TextView(this);
        h.setText(texto);
        h.setTextColor(TEXTO_2);
        h.setTextSize(13);
        h.setLetterSpacing(0.05f);
        h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        h.setPadding(dp(18), primeiro ? 0 : dp(28), 0, dp(8));
        return h;
    }

    /** Linha estilo Ajustes da Apple TV: no foco vira uma barra branca com texto escuro. */
    private TextView linha(LinearLayout lista, String rotulo, String detalhe, Runnable aoClicar) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(18), dp(12), dp(18), dp(12));
        GradientDrawable f = new GradientDrawable();
        f.setCornerRadius(dp(12));
        f.setColor(CINZA_1);
        l.setBackground(f);

        LinearLayout esq = new LinearLayout(this);
        esq.setOrientation(LinearLayout.VERTICAL);
        TextView r = new TextView(this);
        r.setText(rotulo);
        r.setTextColor(BRANCO);
        r.setTextSize(17);
        esq.addView(r);
        TextView d = null;
        if (detalhe != null) {
            d = new TextView(this);
            d.setText(detalhe);
            d.setTextColor(TEXTO_3);
            d.setTextSize(12);
            esq.addView(d);
        }
        l.addView(esq, new LinearLayout.LayoutParams(0, -2, 1));

        TextView v = new TextView(this);
        v.setTextColor(TEXTO_2);
        v.setTextSize(16);
        v.setGravity(Gravity.END);
        v.setText("—");
        l.addView(v);

        final TextView detalheFinal = d;
        l.setFocusable(true);
        l.setClickable(true);
        l.setOnFocusChangeListener((x, foco) -> {
            f.setColor(foco ? BRANCO : CINZA_1);
            r.setTextColor(foco ? PRETO_TEXTO : BRANCO);
            v.setTextColor(foco ? CINZA_3 : TEXTO_2);
            if (detalheFinal != null) detalheFinal.setTextColor(foco ? CINZA_3 : TEXTO_3);
            x.animate().scaleX(foco ? 1.03f : 1f).scaleY(foco ? 1.03f : 1f).setDuration(140).start();
            x.setElevation(foco ? dp(10) : 0);
            if (foco) x.requestRectangleOnScreen(new Rect(0, -dp(60), x.getWidth(), x.getHeight() + dp(60)));
        });
        if (aoClicar != null) l.setOnClickListener(x -> aoClicar.run());
        lista.addView(l, margem(-1, -2, 0, 0, 0, dp(6)));
        if (painelPrimeiro == null) painelPrimeiro = l;
        return v;
    }

    private void abrirPainel() {
        painel.setVisibility(View.VISIBLE);
        painel.setAlpha(0f);
        painel.animate().alpha(1f).setDuration(200).start();
        painelRolagem.scrollTo(0, 0);
        atualizarStatusLocal();
        if (painelPrimeiro != null) painelPrimeiro.requestFocus();
        ui.removeCallbacks(verificar);
        ui.post(verificar);
    }

    private void fecharPainel() {
        if (painel == null || painel.getVisibility() != View.VISIBLE) return;
        painel.setVisibility(View.GONE);
        View p = dock.findViewWithTag(SERVIDOR);
        if (p != null) p.requestFocus();
        else if (primeiroFocavel != null) primeiroFocavel.requestFocus();
    }

    // ================================================================ detalhe de um sistema

    private View montarDetalhe() {
        detalhe = new FrameLayout(this);
        detalhe.setBackgroundColor(FUNDO);
        detalhe.setVisibility(View.GONE);

        LinearLayout duas = new LinearLayout(this);
        duas.setOrientation(LinearLayout.HORIZONTAL);
        detalhe.addView(duas, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout esq = new LinearLayout(this);
        esq.setOrientation(LinearLayout.VERTICAL);
        esq.setGravity(Gravity.CENTER);
        esq.setPadding(dp(48), dp(40), dp(24), dp(40));

        FrameLayout grande = new FrameLayout(this);
        GradientDrawable fg = fundoArredondado(new int[]{Color.parseColor("#3A3A3C"), Color.parseColor("#1C1C1E")});
        fg.setCornerRadius(dp(22));
        grande.setBackground(fg);
        grande.setElevation(dp(16));
        ImageView sv = new ImageView(this);
        sv.setImageResource(R.drawable.servidor);
        grande.addView(sv, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));
        esq.addView(grande, new LinearLayout.LayoutParams(dp(250), dp(150)));

        dGrupo = new TextView(this);
        dGrupo.setTextColor(TEXTO_2);
        dGrupo.setTextSize(13);
        dGrupo.setLetterSpacing(0.06f);
        dGrupo.setGravity(Gravity.CENTER);
        esq.addView(dGrupo, margem(-2, -2, 0, dp(26), 0, 0));

        dTitulo = new TextView(this);
        dTitulo.setTextColor(BRANCO);
        dTitulo.setTextSize(28);
        dTitulo.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        dTitulo.setGravity(Gravity.CENTER);
        esq.addView(dTitulo, margem(-2, -2, 0, dp(4), 0, 0));

        dSituacao = new TextView(this);
        dSituacao.setTextSize(17);
        dSituacao.setGravity(Gravity.CENTER);
        esq.addView(dSituacao, margem(-2, -2, 0, dp(16), 0, 0));
        duas.addView(esq, new LinearLayout.LayoutParams(0, -1, 0.42f));

        detalheRolagem = new ScrollView(this);
        detalheRolagem.setVerticalScrollBarEnabled(false);
        detalheRolagem.setClipToPadding(false);
        detalheRolagem.setPadding(0, dp(48), 0, dp(48));
        duas.addView(detalheRolagem, new LinearLayout.LayoutParams(0, -1, 0.58f));

        dLista = new LinearLayout(this);
        dLista.setOrientation(LinearLayout.VERTICAL);
        dLista.setPadding(dp(12), 0, dp(64), 0);
        detalheRolagem.addView(dLista, new ScrollView.LayoutParams(-1, -2));
        return detalhe;
    }

    private void abrirDetalhe(Sistema s) {
        sistemaAberto = s;
        dGrupo.setText(s.grupo.toUpperCase(PT_BR));
        dTitulo.setText(s.nome);
        dLista.removeAllViews();

        dLista.addView(cabecalhoSecao("SITUAÇÃO", true));
        View primeira = null;
        TextView situacao = linha(dLista, "Agora", null, () -> {
            pintar(dSituacao, "testando…", AMARELO);
            dSituacao.setTextColor(BRANCO);
            testarUm(s);
        });
        situacao.setTag("situacao");
        primeira = (View) situacao.getParent();
        TextView endereco = linha(dLista, "Endereço", null, null);
        endereco.setText(Uri.parse(s.url).getHost());
        TextView resposta = linha(dLista, "Tempo de resposta", null, null);
        resposta.setTag("resposta");
        preencherSituacao(s);

        dLista.addView(cabecalhoSecao("MÉTRICAS", false));
        LinearLayout metricas = new LinearLayout(this);
        metricas.setOrientation(LinearLayout.VERTICAL);
        dLista.addView(metricas);
        final String urlDetalhes = comChave(s.detalhes);
        if (s.detalhes != null && urlDetalhes == null) {
            TextView v = linha(metricas, "Chave do servidor não configurada", "Ajustes → Chave do servidor", null);
            v.setText("");
        } else if (s.detalhes == null) {
            TextView v = linha(metricas, "Ainda não ligado", "Este sistema ainda não manda números para a TV", null);
            v.setText("");
        } else {
            TextView carregando = linha(metricas, "Carregando…", null, null);
            carregando.setText("");
            final String url = urlDetalhes;
            rede.execute(() -> {
                String json = baixar(url);
                ui.post(() -> {
                    if (sistemaAberto != s) return;
                    metricas.removeAllViews();
                    if (json == null) {
                        TextView v = linha(metricas, "Não consegui buscar as métricas", "O sistema não respondeu agora", null);
                        v.setText("");
                        return;
                    }
                    try {
                        JSONArray itens = new JSONObject(json).getJSONArray("itens");
                        for (int i = 0; i < itens.length(); i++) {
                            JSONObject it = itens.getJSONObject(i);
                            String det = it.optString("detalhe", "");
                            TextView v = linha(metricas, it.optString("rotulo", "—"), det.isEmpty() ? null : det, null);
                            v.setText(it.optString("valor", "—"));
                        }
                        if (itens.length() == 0) {
                            TextView v = linha(metricas, "Sem números por enquanto", null, null);
                            v.setText("");
                        }
                    } catch (Exception e) {
                        TextView v = linha(metricas, "Resposta inválida do sistema", null, null);
                        v.setText("");
                    }
                });
            });
        }

        detalhe.setVisibility(View.VISIBLE);
        detalhe.setAlpha(0f);
        detalhe.animate().alpha(1f).setDuration(200).start();
        detalheRolagem.scrollTo(0, 0);
        if (primeira != null) primeira.requestFocus();
    }

    private void preencherSituacao(Sistema s) {
        String texto;
        int cor;
        if (s.estado == 1) {
            texto = "No ar";
            cor = VERDE;
        } else if (s.estado == 2) {
            texto = "Lento";
            cor = AMARELO;
        } else if (s.estado == 3) {
            texto = "Fora do ar";
            cor = VERMELHO;
        } else {
            texto = "Verificando…";
            cor = TEXTO_2;
        }
        pintar(dSituacao, texto, cor);
        dSituacao.setTextColor(BRANCO);
        View sit = dLista.findViewWithTag("situacao");
        if (sit instanceof TextView) pintar((TextView) sit, texto + "  ·  OK testa de novo", cor);
        View resp = dLista.findViewWithTag("resposta");
        if (resp instanceof TextView) ((TextView) resp).setText(s.ms >= 0 ? s.ms + " ms" : "—");
    }

    private void fecharDetalhe() {
        detalhe.setVisibility(View.GONE);
        Sistema s = sistemaAberto;
        sistemaAberto = null;
        if (s != null && s.linhaView != null) s.linhaView.requestFocus();
    }

    // ================================================================ sistemas da Roxinha

    /** Chave do monitor: fica só na TV (preferências ou arquivo chave.txt na pasta do app). */
    private String chave() {
        String c = prefs.getString("chave", "");
        if (!c.isEmpty()) return c;
        try {
            java.io.File f = new java.io.File(getExternalFilesDir(null), "chave.txt");
            if (f.exists()) {
                c = ler(new java.io.FileInputStream(f)).trim();
                if (!c.isEmpty()) prefs.edit().putString("chave", c).apply();
                return c;
            }
        } catch (Exception ignorado) {
        }
        return "";
    }

    /** Troca {CHAVE} pela chave da TV; devolve null se a chave ainda não foi configurada. */
    private String comChave(String url) {
        if (url == null) return null;
        if (!url.contains("{CHAVE}")) return url;
        String c = chave();
        return c.isEmpty() ? null : url.replace("{CHAVE}", c);
    }

    private void pedirChave() {
        EditText campo = new EditText(this);
        campo.setSingleLine(true);
        campo.setHint("Chave do monitor");
        campo.setText(prefs.getString("chave", ""));
        new AlertDialog.Builder(this, R.style.Dialogo)
                .setTitle("Chave do servidor")
                .setView(campo)
                .setPositiveButton("Salvar", (d, w) -> {
                    prefs.edit().putString("chave", campo.getText().toString().trim()).apply();
                    ui.removeCallbacks(verificar);
                    ui.post(verificar);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private String listaInicial() {
        String salvo = prefs.getString("sistemas_json", null);
        if (salvo != null) return salvo;
        try {
            return ler(getAssets().open("sistemas.json"));
        } catch (Exception e) {
            return "{\"sistemas\":[]}";
        }
    }

    private void montarSistemas(String json) {
        List<Sistema> novos = new ArrayList<>();
        String metricas;
        try {
            JSONObject raiz = new JSONObject(json);
            metricas = raiz.optString("metricas", "");
            JSONArray arr = raiz.getJSONArray("sistemas");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Sistema s = new Sistema();
                s.grupo = o.optString("grupo", "Outros");
                s.nome = o.optString("nome", "Sistema");
                s.url = o.optString("url", "");
                s.detalhes = o.optString("detalhes", "");
                if (s.detalhes.isEmpty()) s.detalhes = null;
                if (!s.url.isEmpty()) novos.add(s);
            }
        } catch (Exception e) {
            return;
        }
        urlMetricas = metricas.isEmpty() ? null : metricas;
        sistemas.clear();
        sistemas.addAll(novos);

        pSistemas.removeAllViews();
        String grupoAtual = null;
        for (Sistema s : sistemas) {
            if (!s.grupo.equals(grupoAtual)) {
                pSistemas.addView(cabecalhoSecao(s.grupo.toUpperCase(PT_BR), false));
                grupoAtual = s.grupo;
            }
            String host = Uri.parse(s.url).getHost();
            s.status = linha(pSistemas, s.nome, host, () -> abrirDetalhe(s));
            s.linhaView = (View) s.status.getParent();
            pintar(s.status, "verificando", TEXTO_3);
        }
        atualizarResumo();
    }

    private final Runnable verificar = new Runnable() {
        @Override
        public void run() {
            atualizarStatusLocal();
            rede.execute(() -> {
                long ms = testar(TESTE_INTERNET, 204);
                temInternet = ms >= 0;
                ui.post(() -> {
                    if (ms >= 0) pintar(pInternet, "Online · " + ms + " ms", ms < 400 ? VERDE : AMARELO);
                    else pintar(pInternet, "Sem internet", VERMELHO);
                });
                if (ms >= 0 && System.currentTimeMillis() - ultimaListaMs > 10 * 60_000) {
                    String json = baixar(LISTA_SISTEMAS);
                    if (json != null) {
                        ultimaListaMs = System.currentTimeMillis();
                        prefs.edit().putString("sistemas_json", json).apply();
                        ui.post(() -> montarSistemas(json));
                    }
                }
                String urlM = comChave(urlMetricas);
                String m = (ms >= 0 && urlM != null) ? baixar(urlM) : null;
                ui.post(() -> {
                    if (m != null) mostrarMetricas(m);
                    testarSistemas(ms >= 0);
                });
            });
            ui.postDelayed(this, 30_000);
        }
    };

    private void testarSistemas(boolean internet) {
        if (!internet) {
            for (Sistema s : sistemas) pintar(s.status, "sem internet", TEXTO_3);
            atualizarResumo();
            return;
        }
        for (Sistema s : sistemas) testarUm(s);
    }

    private void testarUm(Sistema s) {
        try {
            testes.execute(() -> {
                long ms = testar(s.url, 0);
                ui.post(() -> {
                    s.ms = ms;
                    if (ms < 0) {
                        s.estado = 3;
                        pintar(s.status, "Fora do ar", VERMELHO);
                    } else if (ms > 2500) {
                        s.estado = 2;
                        pintar(s.status, "Lento · " + ms + " ms", AMARELO);
                    } else {
                        s.estado = 1;
                        pintar(s.status, "No ar · " + ms + " ms", VERDE);
                    }
                    atualizarResumo();
                    if (s == sistemaAberto && detalhe.getVisibility() == View.VISIBLE) preencherSituacao(s);
                });
            });
        } catch (Exception ignorado) {
        }
    }

    /** Monitor da VPS: {"cpu":12.5,"memoria_usada":3.1,"memoria_total":8,"disco_usado":40,"disco_total":100,"ligada":"3 dias"} */
    private void mostrarMetricas(String json) {
        try {
            JSONObject o = new JSONObject(json);
            double cpu = o.optDouble("cpu", -1);
            if (cpu >= 0) pintar(pVpsCpu, String.format(PT_BR, "%.0f%% em uso", cpu), cpu < 70 ? VERDE : cpu < 90 ? AMARELO : VERMELHO);
            double mu = o.optDouble("memoria_usada", -1), mt = o.optDouble("memoria_total", -1);
            if (mu >= 0 && mt > 0) pintar(pVpsMemoria, String.format(PT_BR, "%.1f de %.0f GB", mu, mt), mu / mt < 0.8 ? VERDE : mu / mt < 0.92 ? AMARELO : VERMELHO);
            double du = o.optDouble("disco_usado", -1), dt = o.optDouble("disco_total", -1);
            if (du >= 0 && dt > 0) pintar(pVpsDisco, String.format(PT_BR, "%.0f de %.0f GB", du, dt), du / dt < 0.8 ? VERDE : du / dt < 0.92 ? AMARELO : VERMELHO);
            String ligada = o.optString("ligada", "");
            if (!ligada.isEmpty()) {
                pVpsLigada.setText(ligada);
                pVpsLigada.setTextColor(TEXTO_2);
            }
        } catch (Exception ignorado) {
        }
    }

    private void atualizarResumo() {
        if (pRoxinhaResumo == null) return;
        int total = sistemas.size(), ok = 0, fora = 0, testados = 0;
        for (Sistema s : sistemas) {
            if (s.estado != 0) testados++;
            if (s.estado == 1 || s.estado == 2) ok++;
            if (s.estado == 3) fora++;
        }
        int cor;
        String texto;
        if (!temInternet) {
            cor = VERMELHO;
            texto = "Sem internet na TV";
        } else if (total == 0 || testados < total) {
            cor = TEXTO_2;
            texto = "Verificando…";
        } else if (fora == 0) {
            cor = VERDE;
            texto = "Tudo funcionando";
        } else {
            cor = fora == total ? VERMELHO : AMARELO;
            texto = fora == 1 ? "1 sistema fora do ar" : fora + " sistemas fora do ar";
        }
        if (total > 0 && testados > 0) {
            pintar(pRoxinhaResumo, ok + " de " + total, fora == 0 ? VERDE : ok == 0 ? VERMELHO : AMARELO);
        }
        pintar(pResumoGrande, texto, cor);
        pResumoGrande.setTextColor(BRANCO);
        if (pontoServidor != null && pontoServidor.getBackground() instanceof GradientDrawable) {
            ((GradientDrawable) pontoServidor.getBackground()).setColor(cor);
        }
        View p = dock == null ? null : dock.findViewWithTag(SERVIDOR);
        if (p != null && p.hasFocus()) vitrineSub.setText(resumoGeral());
    }

    private String resumoGeral() {
        int total = sistemas.size(), ok = 0;
        for (Sistema s : sistemas) if (s.estado == 1 || s.estado == 2) ok++;
        return "VPS Roxinha · " + ok + " de " + total + " sistemas no ar";
    }

    // ================================================================ esta TV

    @SuppressWarnings("deprecation")
    private void atualizarStatusLocal() {
        pAtualizado.setText("Atualizado às " + new SimpleDateFormat("HH:mm:ss", PT_BR).format(new Date()));
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            if (ni == null || !ni.isConnected()) {
                pintar(pRede, "Desconectado", VERMELHO);
            } else {
                String tipo = ni.getType() == ConnectivityManager.TYPE_ETHERNET ? "Cabo" : "Wi-Fi";
                String ip = ipLocal();
                pintar(pRede, tipo + (ip != null ? " · " + ip : ""), VERDE);
            }
        } catch (Exception e) {
            pRede.setText("—");
        }
        try {
            StatFs st = new StatFs(Environment.getDataDirectory().getPath());
            long livre = st.getAvailableBytes(), total = st.getTotalBytes();
            double pct = total > 0 ? (double) livre / total : 1;
            pintar(pArmazenamento, gb(livre) + " de " + gb(total), pct > 0.2 ? VERDE : pct > 0.08 ? AMARELO : VERMELHO);
        } catch (Exception e) {
            pArmazenamento.setText("—");
        }
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            double pct = mi.totalMem > 0 ? (double) mi.availMem / mi.totalMem : 1;
            pintar(pMemoria, gb(mi.availMem) + " de " + gb(mi.totalMem), mi.lowMemory ? VERMELHO : pct > 0.25 ? VERDE : AMARELO);
        } catch (Exception e) {
            pMemoria.setText("—");
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

    // ================================================================ relógio

    private final Runnable tique = new Runnable() {
        @Override
        public void run() {
            relogio.setText(new SimpleDateFormat("HH:mm", PT_BR).format(new Date()));
            ui.postDelayed(this, 10_000);
        }
    };

    // ================================================================ rede

    /** Tempo de resposta em ms, ou -1 se falhar. codigoEsperado 0 = qualquer resposta abaixo de 500. */
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
            return codigo == codigoEsperado ? ms : -1;
        } catch (Exception e) {
            return -1;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private String baixar(String endereco) {
        HttpURLConnection c = null;
        try {
            String sep = endereco.contains("?") ? "&" : "?";
            c = (HttpURLConnection) new URL(endereco + sep + "t=" + System.currentTimeMillis()).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(6000);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) return null;
            String s = ler(c.getInputStream());
            new JSONObject(s); // só aceita JSON válido
            return s;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String ler(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toString("UTF-8");
    }

    // ================================================================ utilidades

    /** Escreve "● texto" com a bolinha colorida; respeita a linha em foco (texto escuro). */
    private void pintar(TextView v, String texto, int corBolinha) {
        if (v == null) return;
        SpannableString s = new SpannableString("●  " + texto);
        s.setSpan(new ForegroundColorSpan(corBolinha), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        v.setText(s);
        ViewParent pai = v.getParent();
        boolean emFoco = pai instanceof View && ((View) pai).isFocused();
        v.setTextColor(emFoco ? CINZA_3 : TEXTO_2);
    }

    /** Cor média de um ícone (para o fundo dos ícones sem banner). */
    private int corMedia(Drawable d) {
        if (d == null) return CINZA_2;
        try {
            Drawable copia = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            Bitmap b = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            copia.setBounds(0, 0, 16, 16);
            copia.draw(c);
            long r = 0, g = 0, bl = 0, n = 0;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    int px = b.getPixel(x, y);
                    if (Color.alpha(px) < 160) continue;
                    int pr = Color.red(px), pg = Color.green(px), pb = Color.blue(px);
                    int max = Math.max(pr, Math.max(pg, pb)), min = Math.min(pr, Math.min(pg, pb));
                    if (max - min < 24) continue; // ignora branco, preto e cinza
                    r += pr;
                    g += pg;
                    bl += pb;
                    n++;
                }
            }
            b.recycle();
            if (n == 0) return CINZA_2;
            return Color.rgb((int) (r / n), (int) (g / n), (int) (bl / n));
        } catch (Exception e) {
            return CINZA_2;
        }
    }

    private static int escurecer(int cor, float quanto) {
        return Color.rgb((int) (Color.red(cor) * (1 - quanto)), (int) (Color.green(cor) * (1 - quanto)),
                (int) (Color.blue(cor) * (1 - quanto)));
    }

    private static int clarear(int cor, float quanto) {
        return Color.rgb((int) (Color.red(cor) + (255 - Color.red(cor)) * quanto),
                (int) (Color.green(cor) + (255 - Color.green(cor)) * quanto),
                (int) (Color.blue(cor) + (255 - Color.blue(cor)) * quanto));
    }

    private static String gb(long bytes) {
        double g = bytes / (1024.0 * 1024.0 * 1024.0);
        if (g < 1) return String.format(PT_BR, "%.0f MB", bytes / (1024.0 * 1024.0));
        return String.format(PT_BR, "%.1f GB", g);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams margem(int l, int a, int esq, int topo, int dir, int baixo) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(l, a);
        lp.setMargins(esq, topo, dir, baixo);
        return lp;
    }
}
