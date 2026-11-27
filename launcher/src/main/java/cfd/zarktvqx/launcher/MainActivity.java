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
import android.graphics.Rect;
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
 * Tela inicial ZarkTV no estilo Apple TV: o app em foco aparece grande no topo,
 * fileira de favoritos, grade de apps e o ícone "Meu Servidor", que abre o status
 * desta TV, da VPS Roxinha e de cada sistema. Segurar OK (ou Menu) num app abre as opções.
 */
public class MainActivity extends Activity {

    // Paleta (modo escuro da Apple)
    private static final int FUNDO = Color.parseColor("#0E0E10");
    private static final int CINZA_1 = Color.parseColor("#1C1C1E");
    private static final int CINZA_2 = Color.parseColor("#2C2C2E");
    private static final int CINZA_3 = Color.parseColor("#3A3A3C");
    private static final int TEXTO_2 = Color.parseColor("#98989F");
    private static final int TEXTO_3 = Color.parseColor("#636366");
    private static final int VERDE = Color.parseColor("#30D158");
    private static final int AMARELO = Color.parseColor("#FFD60A");
    private static final int VERMELHO = Color.parseColor("#FF453A");
    private static final int BRANCO = Color.WHITE;

    private static final String LISTA_SISTEMAS =
            "https://raw.githubusercontent.com/nilsonlopes2298-web/zarktv-app/launcher/launcher/sistemas.json";
    private static final String TESTE_INTERNET = "http://connectivitycheck.gstatic.com/generate_204";
    private static final String FAVORITOS_PADRAO =
            "com.google.android.youtube.tv,com.amazon.firetv.youtube,com.netflix.ninja,"
                    + "cfd.zarktvqx.zarkplay,cfd.zarktvqx.tv,com.zark.play.tv";
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
    private TextView vitrineTitulo, vitrineSub, relogio, statusTopo;
    private LinearLayout linhaFavoritos;
    private GridLayout grade;
    private View primeiroFocavel;

    // Tela "Meu Servidor"
    private FrameLayout painel;
    private ScrollView painelRolagem;
    private TextView pAtualizado, pInternet, pRede, pArmazenamento, pMemoria, pApps;
    private TextView pRoxinhaResumo, pVpsCpu, pVpsMemoria, pVpsDisco, pVpsLigada;
    private LinearLayout pSistemas;
    private View painelPrimeiro;

    private final Map<String, App> apps = new LinkedHashMap<>();
    private final List<Sistema> sistemas = new ArrayList<>();
    private long ultimaListaMs = 0;
    private String urlMetricas = null;
    private volatile boolean temInternet = true;

    static class App {
        String pacote, nome, versao = "";
        Drawable banner, icone;
    }

    static class Sistema {
        String grupo, nome, url;
        TextView status;
        volatile int estado; // 0 verificando, 1 no ar, 2 lento, 3 fora do ar
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
        fecharPainel();
        if (primeiroFocavel != null) primeiroFocavel.requestFocus();
    }

    @Override
    public void onBackPressed() {
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
        int alturaVitrine = (int) (altura * 0.66f);

        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(FUNDO);

        // Vitrine (top shelf): imagem grande do app em foco
        vitrineImagem = new ImageView(this);
        vitrineImagem.setScaleType(ImageView.ScaleType.CENTER_CROP);
        vitrineImagem.setAlpha(0.55f);
        raiz.addView(vitrineImagem, new FrameLayout.LayoutParams(-1, alturaVitrine));

        View degradeBaixo = new View(this);
        degradeBaixo.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.parseColor("#000E0E10"), Color.parseColor("#990E0E10"), FUNDO}));
        raiz.addView(degradeBaixo, new FrameLayout.LayoutParams(-1, alturaVitrine));

        View degradeLado = new View(this);
        degradeLado.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.parseColor("#E60E0E10"), Color.parseColor("#660E0E10"), Color.parseColor("#000E0E10")}));
        raiz.addView(degradeLado, new FrameLayout.LayoutParams(-1, alturaVitrine));

        // Barra de cima: status discreto à esquerda, relógio à direita
        LinearLayout topo = new LinearLayout(this);
        topo.setOrientation(LinearLayout.HORIZONTAL);
        topo.setGravity(Gravity.CENTER_VERTICAL);
        topo.setPadding(dp(56), dp(28), dp(56), 0);
        statusTopo = new TextView(this);
        statusTopo.setTextColor(TEXTO_2);
        statusTopo.setTextSize(13);
        topo.addView(statusTopo, new LinearLayout.LayoutParams(0, -2, 1));
        relogio = new TextView(this);
        relogio.setTextColor(BRANCO);
        relogio.setTextSize(20);
        relogio.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        topo.addView(relogio);
        raiz.addView(topo, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        // Conteúdo rolável
        ScrollView rolagem = new ScrollView(this);
        rolagem.setVerticalScrollBarEnabled(false);
        rolagem.setClipChildren(false);
        rolagem.setClipToPadding(false);
        raiz.addView(rolagem, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout coluna = new LinearLayout(this);
        coluna.setOrientation(LinearLayout.VERTICAL);
        coluna.setClipChildren(false);
        coluna.setClipToPadding(false);
        coluna.setPadding(0, 0, 0, dp(48));
        rolagem.addView(coluna, new ScrollView.LayoutParams(-1, -2));

        // Nome e detalhe do app em foco, sobre a vitrine
        LinearLayout textoVitrine = new LinearLayout(this);
        textoVitrine.setOrientation(LinearLayout.VERTICAL);
        textoVitrine.setGravity(Gravity.BOTTOM);
        textoVitrine.setPadding(dp(56), 0, dp(56), dp(14));
        vitrineTitulo = new TextView(this);
        vitrineTitulo.setTextColor(BRANCO);
        vitrineTitulo.setTextSize(40);
        vitrineTitulo.setTypeface(Typeface.DEFAULT_BOLD);
        vitrineTitulo.setSingleLine(true);
        vitrineTitulo.setEllipsize(TextUtils.TruncateAt.END);
        textoVitrine.addView(vitrineTitulo);
        vitrineSub = new TextView(this);
        vitrineSub.setTextColor(TEXTO_2);
        vitrineSub.setTextSize(15);
        textoVitrine.addView(vitrineSub, margem(-2, -2, 0, dp(4), 0, 0));
        coluna.addView(textoVitrine, new LinearLayout.LayoutParams(-1, (int) (altura * 0.42f)));

        // Favoritos (fileira de cima, maiores)
        HorizontalScrollView rolagemFav = new HorizontalScrollView(this);
        rolagemFav.setHorizontalScrollBarEnabled(false);
        rolagemFav.setClipChildren(false);
        rolagemFav.setClipToPadding(false);
        rolagemFav.setPadding(dp(56), dp(18), dp(56), dp(10));
        linhaFavoritos = new LinearLayout(this);
        linhaFavoritos.setOrientation(LinearLayout.HORIZONTAL);
        linhaFavoritos.setClipChildren(false);
        rolagemFav.addView(linhaFavoritos);
        coluna.addView(rolagemFav);

        // Demais apps
        grade = new GridLayout(this);
        grade.setColumnCount(5);
        grade.setClipChildren(false);
        grade.setClipToPadding(false);
        grade.setPadding(dp(56), dp(18), dp(40), 0);
        coluna.addView(grade);

        raiz.addView(montarPainel(), new FrameLayout.LayoutParams(-1, -1));
        return raiz;
    }

    /** Ladrilho no estilo Apple TV: cantos arredondados, cresce e "flutua" no foco. */
    private View ladrilho(String chave, int largura, int altura, View conteudo, String nome,
                          Runnable aoFocar, Runnable aoClicar, Runnable aoSegurar) {
        LinearLayout caixa = new LinearLayout(this);
        caixa.setOrientation(LinearLayout.VERTICAL);
        caixa.setGravity(Gravity.CENTER_HORIZONTAL);
        caixa.setClipChildren(false);

        FrameLayout cartao = new FrameLayout(this);
        GradientDrawable fundoCartao = new GradientDrawable();
        fundoCartao.setColor(CINZA_2);
        fundoCartao.setCornerRadius(dp(14));
        cartao.setBackground(fundoCartao);
        cartao.setClipToOutline(true);
        cartao.addView(conteudo, new FrameLayout.LayoutParams(-1, -1));
        caixa.addView(cartao, new LinearLayout.LayoutParams(largura, altura));

        TextView rotulo = new TextView(this);
        rotulo.setText(nome);
        rotulo.setTextColor(BRANCO);
        rotulo.setTextSize(13);
        rotulo.setGravity(Gravity.CENTER);
        rotulo.setSingleLine(true);
        rotulo.setEllipsize(TextUtils.TruncateAt.END);
        rotulo.setAlpha(0f);
        LinearLayout.LayoutParams lpRotulo = new LinearLayout.LayoutParams(largura, -2);
        lpRotulo.topMargin = dp(12);
        caixa.addView(rotulo, lpRotulo);

        caixa.setTag(chave);
        caixa.setFocusable(true);
        caixa.setClickable(true);
        caixa.setOnFocusChangeListener((v, foco) -> {
            float e = foco ? 1.12f : 1f;
            cartao.animate().scaleX(e).scaleY(e).translationY(foco ? -dp(4) : 0).setDuration(160).start();
            cartao.setElevation(foco ? dp(18) : 0);
            rotulo.animate().alpha(foco ? 1f : 0f).setDuration(160).start();
            if (foco && aoFocar != null) aoFocar.run();
        });
        caixa.setOnClickListener(v -> aoClicar.run());
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

    private View conteudoApp(App a) {
        ImageView img = new ImageView(this);
        if (a.banner != null) {
            img.setImageDrawable(a.banner);
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            return img;
        }
        FrameLayout f = new FrameLayout(this);
        img.setImageDrawable(a.icone);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        f.addView(img, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER));
        return f;
    }

    private View conteudoServidor() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{Color.parseColor("#3A0A0D"), CINZA_1}));
        TextView simbolo = new TextView(this);
        simbolo.setText("●  ●  ●");
        simbolo.setTextColor(TEXTO_2);
        simbolo.setTextSize(14);
        simbolo.setTag("bolinhas");
        c.addView(simbolo);
        TextView t = new TextView(this);
        t.setText(NOME_SERVIDOR);
        t.setTextColor(BRANCO);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t, margem(-2, -2, 0, dp(6), 0, 0));
        return c;
    }

    private void mostrarNaVitrine(App a) {
        vitrineImagem.setImageDrawable(a.banner);
        vitrineTitulo.setText(a.nome);
        boolean abre = intencaoAbrir(a.pacote) != null;
        vitrineSub.setText(abre
                ? (TextUtils.isEmpty(a.versao) ? "Pronto para abrir" : "Pronto para abrir · versão " + a.versao)
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

        linhaFavoritos.removeAllViews();
        grade.removeAllViews();
        primeiroFocavel = null;
        View focarDepois = null;

        int lf = dp(212), af = dp(119); // favoritos 16:9
        for (String p : fav) {
            App a = apps.get(p);
            if (a == null || ocultos.contains(p)) continue;
            View t = ladrilho(p, lf, af, conteudoApp(a), a.nome,
                    () -> mostrarNaVitrine(a), () -> abrirApp(a.pacote), () -> mostrarOpcoes(a));
            linhaFavoritos.addView(t, margem(-2, -2, 0, 0, dp(28), 0));
            if (primeiroFocavel == null) primeiroFocavel = t;
            if (p.equals(chaveFocada)) focarDepois = t;
        }
        View tServidor = ladrilho(SERVIDOR, lf, af, conteudoServidor(), NOME_SERVIDOR,
                this::mostrarServidorNaVitrine, this::abrirPainel, null);
        linhaFavoritos.addView(tServidor, margem(-2, -2, 0, 0, dp(28), 0));
        if (primeiroFocavel == null) primeiroFocavel = tServidor;
        if (SERVIDOR.equals(chaveFocada)) focarDepois = tServidor;

        int lg = dp(150), ag = dp(84);
        for (App a : apps.values()) {
            if (ocultos.contains(a.pacote) || fav.contains(a.pacote)) continue;
            View t = ladrilho(a.pacote, lg, ag, conteudoApp(a), a.nome,
                    () -> mostrarNaVitrine(a), () -> abrirApp(a.pacote), () -> mostrarOpcoes(a));
            grade.addView(t, itemGrade());
            if (a.pacote.equals(chaveFocada)) focarDepois = t;
        }

        // Configurações (e apps ocultos) no fim da grade
        TextView engrenagem = new TextView(this);
        engrenagem.setText("⚙");
        engrenagem.setTextColor(BRANCO);
        engrenagem.setTextSize(30);
        engrenagem.setGravity(Gravity.CENTER);
        View tConfig = ladrilho("__config__", lg, ag, engrenagem, "Configurações",
                () -> {
                    vitrineImagem.setImageDrawable(null);
                    vitrineTitulo.setText("Configurações");
                    vitrineSub.setText("Configurações da TV e apps ocultos");
                },
                this::opcoesGerais, null);
        grade.addView(tConfig, itemGrade());

        pApps.setText(apps.size() + " instalados");
        atualizarResumo();

        View alvo = focarDepois != null ? focarDepois : primeiroFocavel;
        if (alvo != null && painel.getVisibility() != View.VISIBLE) alvo.requestFocus();
    }

    private GridLayout.LayoutParams itemGrade() {
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.setMargins(0, 0, dp(26), dp(6));
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
                ehFavorito ? "Tirar dos favoritos" : "Colocar nos favoritos",
                "Ocultar da tela inicial",
                "Informações do app",
                "Desinstalar"
        };
        new AlertDialog.Builder(this, R.style.Dialogo)
                .setTitle(a.nome)
                .setItems(itens, (d, qual) -> {
                    if (qual == 0) {
                        if (ehFavorito) fav.remove(a.pacote);
                        else fav.add(a.pacote);
                        salvar("favoritos", fav);
                        desenharApps();
                    } else if (qual == 1) {
                        LinkedHashSet<String> oc = ocultos();
                        oc.add(a.pacote);
                        salvar("ocultos", oc);
                        desenharApps();
                        Toast.makeText(this, a.nome + " foi ocultado. Para voltar: Configurações ⚙", Toast.LENGTH_LONG).show();
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
        String[] itens = {"Configurações da TV", "Mostrar apps ocultos"};
        new AlertDialog.Builder(this, R.style.Dialogo)
                .setTitle("Configurações")
                .setItems(itens, (d, qual) -> {
                    if (qual == 0) abrirIntent(new Intent(Settings.ACTION_SETTINGS));
                    else mostrarOcultos();
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

    // ================================================================ tela "Meu Servidor"

    private View montarPainel() {
        painel = new FrameLayout(this);
        painel.setBackgroundColor(FUNDO);
        painel.setVisibility(View.GONE);

        painelRolagem = new ScrollView(this);
        painelRolagem.setVerticalScrollBarEnabled(false);
        painel.addView(painelRolagem, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(120), dp(40), dp(120), dp(48));
        painelRolagem.addView(c, new ScrollView.LayoutParams(-1, -2));

        TextView t = new TextView(this);
        t.setText(NOME_SERVIDOR);
        t.setTextColor(BRANCO);
        t.setTextSize(34);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t);
        pAtualizado = new TextView(this);
        pAtualizado.setTextColor(TEXTO_2);
        pAtualizado.setTextSize(14);
        c.addView(pAtualizado, margem(-2, -2, 0, dp(2), 0, dp(8)));

        // VPS Roxinha
        c.addView(cabecalhoSecao("VPS ROXINHA"));
        LinearLayout vps = grupo();
        pRoxinhaResumo = linha(vps, "Sistemas no ar");
        pVpsCpu = linha(vps, "Processador");
        pVpsMemoria = linha(vps, "Memória");
        pVpsDisco = linha(vps, "Disco");
        pVpsLigada = linha(vps, "Ligada há");
        for (TextView v : new TextView[]{pVpsCpu, pVpsMemoria, pVpsDisco, pVpsLigada}) {
            v.setText("aguardando monitor");
            v.setTextColor(TEXTO_3);
        }
        c.addView(vps);

        // Sistemas (preenchido por grupo)
        pSistemas = new LinearLayout(this);
        pSistemas.setOrientation(LinearLayout.VERTICAL);
        c.addView(pSistemas);

        // Esta TV
        c.addView(cabecalhoSecao("ESTA TV"));
        LinearLayout tv = grupo();
        pInternet = linha(tv, "Internet");
        pRede = linha(tv, "Rede");
        pArmazenamento = linha(tv, "Armazenamento livre");
        pMemoria = linha(tv, "Memória livre");
        pApps = linha(tv, "Apps instalados");
        c.addView(tv);

        TextView dica = new TextView(this);
        dica.setText("OK num sistema testa de novo na hora · Voltar fecha");
        dica.setTextColor(TEXTO_3);
        dica.setTextSize(12);
        dica.setGravity(Gravity.CENTER);
        c.addView(dica, margem(-1, -2, 0, dp(24), 0, 0));
        return painel;
    }

    private TextView cabecalhoSecao(String texto) {
        TextView h = new TextView(this);
        h.setText(texto);
        h.setTextColor(TEXTO_2);
        h.setTextSize(12);
        h.setLetterSpacing(0.06f);
        h.setPadding(dp(16), dp(26), 0, dp(8));
        return h;
    }

    private LinearLayout grupo() {
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable f = new GradientDrawable();
        f.setColor(CINZA_1);
        f.setCornerRadius(dp(14));
        g.setBackground(f);
        g.setClipToOutline(true);
        return g;
    }

    /** Linha de um grupo (rótulo à esquerda, valor à direita). Devolve o texto do valor. */
    private TextView linha(LinearLayout grupo, String rotulo) {
        return linha(grupo, rotulo, null, null);
    }

    private TextView linha(LinearLayout grupo, String rotulo, String detalhe, Runnable aoClicar) {
        if (grupo.getChildCount() > 0) {
            View sep = new View(this);
            sep.setBackgroundColor(CINZA_2);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
            lp.leftMargin = dp(18);
            grupo.addView(sep, lp);
        }
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setPadding(dp(18), dp(13), dp(18), dp(13));

        LinearLayout esq = new LinearLayout(this);
        esq.setOrientation(LinearLayout.VERTICAL);
        TextView r = new TextView(this);
        r.setText(rotulo);
        r.setTextColor(BRANCO);
        r.setTextSize(16);
        esq.addView(r);
        if (detalhe != null) {
            TextView d = new TextView(this);
            d.setText(detalhe);
            d.setTextColor(TEXTO_3);
            d.setTextSize(12);
            esq.addView(d);
        }
        l.addView(esq, new LinearLayout.LayoutParams(0, -2, 1));

        TextView v = new TextView(this);
        v.setTextColor(TEXTO_2);
        v.setTextSize(15);
        v.setGravity(Gravity.END);
        v.setText("—");
        l.addView(v);

        l.setFocusable(true);
        l.setClickable(true);
        l.setOnFocusChangeListener((x, foco) -> {
            x.setBackgroundColor(foco ? CINZA_3 : Color.TRANSPARENT);
            if (foco) x.requestRectangleOnScreen(new Rect(0, 0, x.getWidth(), x.getHeight()));
        });
        if (aoClicar != null) l.setOnClickListener(x -> aoClicar.run());
        grupo.addView(l);
        if (painelPrimeiro == null) painelPrimeiro = l;
        return v;
    }

    private void abrirPainel() {
        painel.setVisibility(View.VISIBLE);
        painel.setAlpha(0f);
        painel.animate().alpha(1f).setDuration(180).start();
        painelRolagem.scrollTo(0, 0);
        atualizarStatusLocal();
        if (painelPrimeiro != null) painelPrimeiro.requestFocus();
        ui.removeCallbacks(verificar);
        ui.post(verificar);
    }

    private void fecharPainel() {
        if (painel == null || painel.getVisibility() != View.VISIBLE) return;
        painel.setVisibility(View.GONE);
        View p = linhaFavoritos.findViewWithTag(SERVIDOR);
        if (p != null) p.requestFocus();
        else if (primeiroFocavel != null) primeiroFocavel.requestFocus();
    }

    // ================================================================ sistemas da Roxinha

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
                if (!s.url.isEmpty()) novos.add(s);
            }
        } catch (Exception e) {
            return;
        }
        urlMetricas = metricas.isEmpty() ? null : metricas;
        sistemas.clear();
        sistemas.addAll(novos);

        pSistemas.removeAllViews();
        Map<String, LinearLayout> grupos = new LinkedHashMap<>();
        for (Sistema s : sistemas) {
            LinearLayout g = grupos.get(s.grupo);
            if (g == null) {
                pSistemas.addView(cabecalhoSecao(s.grupo.toUpperCase(PT_BR)));
                g = grupo();
                pSistemas.addView(g);
                grupos.put(s.grupo, g);
            }
            String host = Uri.parse(s.url).getHost();
            s.status = linha(g, s.nome, host, () -> {
                pintar(s.status, "testando…", AMARELO);
                testarUm(s);
            });
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
                String m = (ms >= 0 && urlMetricas != null) ? baixar(urlMetricas) : null;
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
        if (statusTopo == null || pRoxinhaResumo == null) return;
        int total = sistemas.size(), ok = 0, fora = 0, testados = 0;
        for (Sistema s : sistemas) {
            if (s.estado != 0) testados++;
            if (s.estado == 1 || s.estado == 2) ok++;
            if (s.estado == 3) fora++;
        }
        if (total > 0 && testados > 0) {
            pintar(pRoxinhaResumo, ok + " de " + total, fora == 0 ? VERDE : ok == 0 ? VERMELHO : AMARELO);
        }
        // Status discreto na tela inicial
        String texto;
        int cor;
        if (!temInternet) {
            texto = "Sem internet";
            cor = VERMELHO;
        } else if (total == 0 || testados < total) {
            texto = "Verificando o servidor…";
            cor = TEXTO_3;
        } else if (fora == 0) {
            texto = "Servidor: tudo funcionando";
            cor = VERDE;
        } else {
            texto = "Servidor: " + (fora == 1 ? "1 sistema fora do ar" : fora + " sistemas fora do ar");
            cor = fora == total ? VERMELHO : AMARELO;
        }
        pintar(statusTopo, texto, cor);
        View p = linhaFavoritos == null ? null : linhaFavoritos.findViewWithTag(SERVIDOR);
        if (p != null) {
            View b = p.findViewWithTag("bolinhas");
            if (b instanceof TextView) ((TextView) b).setTextColor(cor == TEXTO_3 ? TEXTO_2 : cor);
            if (p.hasFocus()) vitrineSub.setText(resumoGeral());
        }
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

    private void pintar(TextView v, String texto, int corBolinha) {
        if (v == null) return;
        SpannableString s = new SpannableString("●  " + texto);
        s.setSpan(new ForegroundColorSpan(corBolinha), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        v.setText(s);
        v.setTextColor(TEXTO_2);
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
