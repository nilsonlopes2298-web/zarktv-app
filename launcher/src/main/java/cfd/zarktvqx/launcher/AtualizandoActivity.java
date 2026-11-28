package cfd.zarktvqx.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** "Instalando atualização · Não desligue a box". Sem botão de cancelar. */
public class AtualizandoActivity extends Activity {
    private final Handler h = new Handler();
    private TextView texto, pc;
    private View barra;
    private int semTrabalho = 0;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.iniciar(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(Ui.FUNDO);
        col.addView(Ui.marca(this, 96));
        texto = Ui.texto(this, "Instalando atualização", 34, Ui.BRANCO, false);
        LinearLayout.LayoutParams lt = new LinearLayout.LayoutParams(-2, -2);
        lt.topMargin = Ui.px(64);
        col.addView(texto, lt);
        FrameLayout trilho = new FrameLayout(this);
        trilho.setBackgroundDrawable(Ui.caixa(Ui.SUPERFICIE_2, 8));
        barra = new View(this);
        barra.setBackgroundDrawable(Ui.caixa(Ui.VERMELHO, 8));
        trilho.addView(barra, new FrameLayout.LayoutParams(0, -1));
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(Ui.px(760), Ui.px(12));
        lb.topMargin = Ui.px(40);
        col.addView(trilho, lb);
        pc = Ui.texto(this, "", 24, Ui.CINZA, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = Ui.px(16);
        col.addView(pc, lp);
        TextView aviso = Ui.texto(this, "Não desligue a box", 26, Ui.VERMELHO, true);
        LinearLayout.LayoutParams la = new LinearLayout.LayoutParams(-2, -2);
        la.topMargin = Ui.px(64);
        col.addView(aviso, la);
        setContentView(col);
    }

    @Override
    protected void onResume() {
        super.onResume();
        h.post(passo);
    }

    @Override
    protected void onPause() {
        super.onPause();
        h.removeCallbacks(passo);
    }

    private final Runnable passo = new Runnable() {
        @Override public void run() {
            String t = SincronizarService.trabalhoTexto;
            int p = SincronizarService.trabalhoPorcento;
            if (t == null) {
                if (++semTrabalho > 4) { finish(); return; }
            } else {
                semTrabalho = 0;
                texto.setText(t);
            }
            if (p >= 0) {
                pc.setText(p + "%");
                FrameLayout.LayoutParams l = (FrameLayout.LayoutParams) barra.getLayoutParams();
                l.width = Ui.px(760) * p / 100;
                barra.setLayoutParams(l);
            }
            h.postDelayed(this, 400);
        }
    };

    @Override
    public void onBackPressed() { /* não deixa sair no meio */ }
}
