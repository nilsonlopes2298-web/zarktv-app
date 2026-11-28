package cfd.zarktvqx.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

/** Liga a box à conta do cliente (mesmo usuário e senha do app ZarkTV). */
public class EntrarActivity extends Activity {
    private EditText usuario, senha;
    private TextView msg, entrar;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.iniciar(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        col.setBackgroundColor(Ui.FUNDO);
        col.addView(Ui.marca(this, 72));
        TextView sub = Ui.texto(this, "Entre com o mesmo usuário e senha do app ZarkTV", 28, Ui.CINZA, false);
        LinearLayout.LayoutParams ls = new LinearLayout.LayoutParams(-2, -2);
        ls.topMargin = Ui.px(16);
        col.addView(sub, ls);

        usuario = campo("Usuário", false);
        senha = campo("Senha", true);
        LinearLayout.LayoutParams lc = new LinearLayout.LayoutParams(Ui.px(760), Ui.px(88));
        lc.topMargin = Ui.px(48);
        col.addView(usuario, lc);
        LinearLayout.LayoutParams lc2 = new LinearLayout.LayoutParams(Ui.px(760), Ui.px(88));
        lc2.topMargin = Ui.px(24);
        col.addView(senha, lc2);

        msg = Ui.texto(this, "", 24, Ui.VERMELHO, false);
        LinearLayout.LayoutParams lm = new LinearLayout.LayoutParams(-2, -2);
        lm.topMargin = Ui.px(20);
        col.addView(msg, lm);

        LinearLayout bts = new LinearLayout(this);
        entrar = Ui.botao(this, "Entrar", true);
        TextView pular = Ui.botao(this, "Agora não", false);
        bts.addView(entrar);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.leftMargin = Ui.px(24);
        bts.addView(pular, lp);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(-2, -2);
        lb.topMargin = Ui.px(30);
        col.addView(bts, lb);

        TextView cod = Ui.texto(this, "Box " + Box.codigo(this), 18, Ui.CINZA_FRACO, false);
        LinearLayout.LayoutParams lcod = new LinearLayout.LayoutParams(-2, -2);
        lcod.topMargin = Ui.px(60);
        col.addView(cod, lcod);
        setContentView(col);

        entrar.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { enviar(); }
        });
        pular.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        usuario.requestFocus();
    }

    private EditText campo(String dica, boolean secreto) {
        EditText e = new EditText(this);
        e.setHint(dica);
        e.setHintTextColor(Ui.CINZA_FRACO);
        e.setTextColor(Ui.BRANCO);
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(32));
        e.setSingleLine(true);
        e.setPadding(Ui.px(32), 0, Ui.px(32), 0);
        e.setBackgroundDrawable(Ui.focavel(Ui.SUPERFICIE_1, 20));
        e.setInputType(secreto ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setImeOptions(secreto ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
        return e;
    }

    private void enviar() {
        final String u = usuario.getText().toString().trim();
        final String s = senha.getText().toString();
        if (u.length() == 0 || s.length() == 0) { msg.setText("Preencha usuário e senha."); return; }
        msg.setTextColor(Ui.CINZA);
        msg.setText("Conferindo…");
        entrar.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                String erro;
                try {
                    Rede.preparar(EntrarActivity.this);
                    if (Box.token(EntrarActivity.this) == null) SincronizarService.sincronizar(EntrarActivity.this);
                    JSONObject j = new JSONObject();
                    j.put("token", Box.token(EntrarActivity.this));
                    j.put("usuario", u);
                    j.put("senha", s);
                    JSONObject r = Rede.post("/vincular", j);
                    erro = r.optBoolean("ok") ? null : r.optString("erro", "Não deu certo. Confira usuário e senha.");
                } catch (Exception e) {
                    erro = "Sem conexão com o painel. Confira a internet.";
                }
                final String fim = erro;
                Ui.rodarNaTela(new Runnable() {
                    @Override public void run() {
                        entrar.setEnabled(true);
                        if (fim == null) {
                            SincronizarService.agora(EntrarActivity.this);
                            finish();
                        } else {
                            msg.setTextColor(Ui.VERMELHO);
                            msg.setText(fim);
                        }
                    }
                });
            }
        }).start();
    }
}
