package cfd.zarktvqx.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

/** Ícones simples desenhados na mão (funcionam em qualquer Android, sem fonte especial). */
public class IconeView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Path caminho = new Path();
    private final String tipo;

    public IconeView(Context c, String tipo) {
        super(c);
        this.tipo = tipo;
        p.setColor(Ui.BRANCO);
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), m = Math.min(w, h);
        float cx = w / 2, cy = h / 2;
        p.setStrokeWidth(m * 0.08f);
        p.setStrokeCap(Paint.Cap.ROUND);
        switch (tipo) {
            case "play": {
                p.setStyle(Paint.Style.FILL);
                caminho.reset();
                caminho.moveTo(cx - m * 0.22f, cy - m * 0.3f);
                caminho.lineTo(cx + m * 0.32f, cy);
                caminho.lineTo(cx - m * 0.22f, cy + m * 0.3f);
                caminho.close();
                c.drawPath(caminho, p);
                break;
            }
            case "loja": {
                p.setStyle(Paint.Style.STROKE);
                r.set(cx - m * 0.34f, cy - m * 0.12f, cx + m * 0.34f, cy + m * 0.38f);
                c.drawRoundRect(r, m * 0.06f, m * 0.06f, p);
                r.set(cx - m * 0.17f, cy - m * 0.4f, cx + m * 0.17f, cy + m * 0.06f);
                c.drawArc(r, 180, 180, false, p);
                break;
            }
            case "config": {
                p.setStyle(Paint.Style.STROKE);
                c.drawCircle(cx, cy, m * 0.24f, p);
                c.drawCircle(cx, cy, m * 0.08f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI * i / 4;
                    float x1 = (float) (cx + Math.cos(a) * m * 0.30f), y1 = (float) (cy + Math.sin(a) * m * 0.30f);
                    float x2 = (float) (cx + Math.cos(a) * m * 0.40f), y2 = (float) (cy + Math.sin(a) * m * 0.40f);
                    c.drawLine(x1, y1, x2, y2, p);
                }
                break;
            }
            default: { // todos os apps: grade 3x3
                p.setStyle(Paint.Style.FILL);
                float t = m * 0.16f, g = m * 0.1f, ini = cx - (t * 3 + g * 2) / 2;
                for (int i = 0; i < 3; i++)
                    for (int j = 0; j < 3; j++) {
                        float x = ini + i * (t + g), y = cy - (t * 3 + g * 2) / 2 + j * (t + g);
                        r.set(x, y, x + t, y + t);
                        c.drawRoundRect(r, t * 0.25f, t * 0.25f, p);
                    }
            }
        }
    }
}
