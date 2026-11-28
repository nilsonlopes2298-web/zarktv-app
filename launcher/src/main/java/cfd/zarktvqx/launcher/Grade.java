package cfd.zarktvqx.launcher;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Peça "ícone + nome" usada na lojinha e em Todos os apps. */
final class Grade {
    private Grade() {}

    static LinearLayout item(Context c, String nome, Drawable icone, String tipoIcone, String detalhe, View.OnClickListener clique) {
        LinearLayout it = new LinearLayout(c);
        it.setOrientation(LinearLayout.VERTICAL);
        it.setGravity(Gravity.CENTER_HORIZONTAL);
        final FrameLayout quadro = new FrameLayout(c);
        quadro.setFocusable(true);
        quadro.setClickable(true);
        quadro.setBackgroundDrawable(Ui.focavel(Ui.SUPERFICIE_2, 24));
        View ic;
        if (icone != null) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(icone);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            ic = iv;
        } else {
            ic = new IconeView(c, tipoIcone);
        }
        quadro.addView(ic, new FrameLayout.LayoutParams(Ui.px(84), Ui.px(84), Gravity.CENTER));
        it.addView(quadro, new LinearLayout.LayoutParams(Ui.px(220), Ui.px(150)));
        final TextView t = Ui.texto(c, nome, 22, Ui.CINZA, false);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lt = new LinearLayout.LayoutParams(Ui.px(220), -2);
        lt.topMargin = Ui.px(14);
        it.addView(t, lt);
        if (detalhe != null) {
            TextView d = Ui.texto(c, detalhe, 18, Ui.CINZA_FRACO, false);
            d.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams ld = new LinearLayout.LayoutParams(Ui.px(220), -2);
            ld.topMargin = Ui.px(6);
            it.addView(d, ld);
        }
        quadro.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean tem) {
                quadro.animate().scaleX(tem ? 1.06f : 1f).scaleY(tem ? 1.06f : 1f).setDuration(150).start();
                t.setTextColor(tem ? Ui.BRANCO : Ui.CINZA);
            }
        });
        quadro.setOnClickListener(clique);
        return it;
    }

    /** Coloca os itens em linhas de N colunas dentro de uma coluna rolável. */
    static void distribuir(Context c, LinearLayout coluna, java.util.List<View> itens, int colunas) {
        coluna.removeAllViews();
        LinearLayout linha = null;
        for (int i = 0; i < itens.size(); i++) {
            if (i % colunas == 0) {
                linha = new LinearLayout(c);
                linha.setClipChildren(false);
                LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(-2, -2);
                l.bottomMargin = Ui.px(40);
                coluna.addView(linha, l);
            }
            LinearLayout.LayoutParams li = new LinearLayout.LayoutParams(-2, -2);
            li.rightMargin = Ui.px(48);
            linha.addView(itens.get(i), li);
        }
    }

    static LinearLayout tela(android.app.Activity a, String titulo, String sub, LinearLayout[] colunaSaida) {
        Ui.iniciar(a);
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.setBackgroundColor(Ui.FUNDO);
        sv.setClipChildren(false);
        LinearLayout raiz = new LinearLayout(a);
        raiz.setOrientation(LinearLayout.VERTICAL);
        raiz.setClipChildren(false);
        raiz.setPadding(Ui.px(120), Ui.px(90), Ui.px(120), Ui.px(90));
        raiz.addView(Ui.texto(a, titulo, 48, Ui.BRANCO, true));
        TextView s = Ui.texto(a, sub, 22, Ui.CINZA_FRACO, false);
        LinearLayout.LayoutParams ls = new LinearLayout.LayoutParams(-2, -2);
        ls.topMargin = Ui.px(10);
        ls.bottomMargin = Ui.px(48);
        raiz.addView(s, ls);
        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setClipChildren(false);
        raiz.addView(col);
        sv.addView(raiz);
        a.setContentView(sv);
        colunaSaida[0] = col;
        return raiz;
    }
}
