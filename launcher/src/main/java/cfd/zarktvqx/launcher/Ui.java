package cfd.zarktvqx.launcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.security.MessageDigest;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Cores, medidas e peças visuais (tudo leve: sem sombra, sem blur). */
public final class Ui {
    public static final int FUNDO = Color.rgb(11, 11, 13);
    public static final int VERMELHO = Color.rgb(229, 9, 20);
    public static final int BRANCO = Color.rgb(250, 250, 250);
    public static final int CINZA = Color.rgb(176, 176, 184);
    public static final int CINZA_FRACO = Color.rgb(110, 110, 118);
    public static final int SUPERFICIE_1 = Color.rgb(26, 26, 30);
    public static final int SUPERFICIE_2 = Color.rgb(38, 38, 44);
    public static final int SUPERFICIE_3 = Color.rgb(52, 52, 60);

    private static float escala = 1f;
    private static final Handler principal = new Handler(Looper.getMainLooper());
    private static final ExecutorService fila = Executors.newSingleThreadExecutor();
    private static final LruCache<String, Bitmap> memoria = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getRowBytes() * b.getHeight(); }
    };

    private Ui() {}

    /** Desenho feito em 1920x1080: converte para o tamanho real da tela. */
    public static void iniciar(Activity a) {
        int w = a.getResources().getDisplayMetrics().widthPixels;
        int h = a.getResources().getDisplayMetrics().heightPixels;
        int larg = Math.max(w, h);
        escala = larg / 1920f;
    }

    public static int px(float v) { return Math.round(v * escala); }

    public static void rodarNaTela(Runnable r) { principal.post(r); }

    public static TextView texto(Context c, String s, float tamanho, int cor, boolean negrito) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextColor(cor);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px(tamanho));
        t.setIncludeFontPadding(false);
        if (negrito) t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    public static GradientDrawable caixa(int cor, float raio) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(cor);
        g.setCornerRadius(px(raio));
        return g;
    }

    /** Fundo com borda vermelha sólida quando focado (sem brilho espalhado). */
    public static Drawable focavel(int cor, float raio) {
        GradientDrawable foco = caixa(cor, raio);
        foco.setStroke(px(4), VERMELHO);
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, foco);
        s.addState(new int[]{android.R.attr.state_pressed}, foco);
        s.addState(new int[]{}, caixa(cor, raio));
        return s;
    }

    /** Leve aumento ao focar (150 ms). */
    public static void aumentarAoFocar(final View v, final View alvo) {
        v.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View x, boolean tem) {
                View a = alvo == null ? v : alvo;
                a.animate().scaleX(tem ? 1.06f : 1f).scaleY(tem ? 1.06f : 1f).setDuration(150).start();
            }
        });
    }

    public static TextView botao(Context c, String s, boolean principal) {
        TextView b = texto(c, s, 28, BRANCO, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(px(48), px(20), px(48), px(20));
        b.setFocusable(true);
        b.setClickable(true);
        b.setBackgroundDrawable(focavel(principal ? VERMELHO : SUPERFICIE_2, 60));
        if (principal) {
            // botão vermelho: no foco ganha borda branca para aparecer
            GradientDrawable foco = caixa(VERMELHO, 60);
            foco.setStroke(px(4), BRANCO);
            StateListDrawable st = new StateListDrawable();
            st.addState(new int[]{android.R.attr.state_focused}, foco);
            st.addState(new int[]{}, caixa(VERMELHO, 60));
            b.setBackgroundDrawable(st);
        }
        aumentarAoFocar(b, null);
        return b;
    }

    public static TextView marca(Context c, float tamanho) {
        TextView t = texto(c, "", tamanho, BRANCO, true);
        android.text.SpannableString s = new android.text.SpannableString("ZarkTV");
        s.setSpan(new android.text.style.ForegroundColorSpan(VERMELHO), 4, 6, 0);
        t.setText(s);
        return t;
    }

    // ---------- Abrir apps ----------

    public static boolean abrirApp(Context c, String pacote) {
        PackageManager pm = c.getPackageManager();
        Intent i = null;
        if (Build.VERSION.SDK_INT >= 21) i = pm.getLeanbackLaunchIntentForPackage(pacote);
        if (i == null) i = pm.getLaunchIntentForPackage(pacote);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static Drawable iconeDe(Context c, String pacote) {
        try {
            return c.getPackageManager().getApplicationIcon(pacote);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- Imagens (banners) ----------

    /** Baixa a imagem uma vez, guarda no aparelho e reduz para caber na largura (economiza memória). */
    public static void imagem(final Context c, final String url, final ImageView alvo, final int largura) {
        if (url == null || url.length() == 0) return;
        Bitmap m = memoria.get(url);
        if (m != null) { alvo.setImageBitmap(m); return; }
        alvo.setTag(url);
        fila.execute(new Runnable() {
            @Override public void run() {
                try {
                    Rede.preparar(c);
                    File dir = new File(c.getCacheDir(), "imagens");
                    dir.mkdirs();
                    MessageDigest md = MessageDigest.getInstance("SHA-1");
                    File f = new File(dir, Rede.hex(md.digest(url.getBytes("UTF-8"))));
                    if (!f.exists()) {
                        byte[] b = Rede.baixarBytes(url);
                        FileOutputStream o = new FileOutputStream(f);
                        o.write(b);
                        o.close();
                    }
                    BitmapFactory.Options op = new BitmapFactory.Options();
                    op.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(f.getAbsolutePath(), op);
                    int amostra = 1;
                    while (op.outWidth / (amostra * 2) >= largura) amostra *= 2;
                    op = new BitmapFactory.Options();
                    op.inSampleSize = amostra;
                    op.inPreferredConfig = Bitmap.Config.RGB_565;
                    final Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), op);
                    if (bmp == null) return;
                    memoria.put(url, bmp);
                    principal.post(new Runnable() {
                        @Override public void run() {
                            if (url.equals(alvo.getTag())) alvo.setImageBitmap(bmp);
                        }
                    });
                } catch (Exception ignored) {}
            }
        });
    }

    public static Bitmap qr(String conteudo, int tamanho) {
        try {
            BitMatrix m = new QRCodeWriter().encode(conteudo, BarcodeFormat.QR_CODE, tamanho, tamanho);
            Bitmap b = Bitmap.createBitmap(tamanho, tamanho, Bitmap.Config.RGB_565);
            int[] linha = new int[tamanho];
            for (int y = 0; y < tamanho; y++) {
                for (int x = 0; x < tamanho; x++) linha[x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
                b.setPixels(linha, 0, tamanho, 0, y, tamanho, 1);
            }
            return b;
        } catch (Exception e) {
            return null;
        }
    }
}
