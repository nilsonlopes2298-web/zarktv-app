package cfd.zarktvqx.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Ícone de conexão desenhado na mão: barras do Wi-Fi (0 a 4), cabo ou sem internet. */
public class SinalView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private String tipo = "wifi";
    private int nivel = 4;

    public SinalView(Context c) { super(c); }

    public void mostrar(String tipo, int nivel) {
        this.tipo = tipo;
        this.nivel = nivel;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        if ("cabo".equals(tipo)) {
            // tomada de rede: quadrado com 3 pinos
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(h * 0.09f);
            p.setColor(Ui.BRANCO);
            r.set(w * 0.15f, h * 0.2f, w * 0.85f, h * 0.85f);
            c.drawRoundRect(r, h * 0.08f, h * 0.08f, p);
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < 3; i++) {
                float x = w * (0.32f + i * 0.18f);
                c.drawRect(x - h * 0.04f, h * 0.35f, x + h * 0.04f, h * 0.55f, p);
            }
            return;
        }
        p.setStyle(Paint.Style.FILL);
        float larg = w / 5.5f;
        for (int i = 0; i < 4; i++) {
            float alt = h * (0.25f + i * 0.25f);
            float x = i * (larg * 1.45f);
            boolean aceso = !"sem".equals(tipo) && i < nivel;
            p.setColor(aceso ? Ui.BRANCO : Ui.SUPERFICIE_3);
            r.set(x, h - alt, x + larg, h);
            c.drawRoundRect(r, larg * 0.3f, larg * 0.3f, p);
        }
        if ("sem".equals(tipo)) {
            p.setColor(Ui.VERMELHO);
            p.setStrokeWidth(h * 0.12f);
            c.drawLine(0, h * 0.1f, w * 0.95f, h * 0.95f, p);
        }
    }
}
