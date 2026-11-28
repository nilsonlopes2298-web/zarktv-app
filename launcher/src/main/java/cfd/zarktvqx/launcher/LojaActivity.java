package cfd.zarktvqx.launcher;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Lojinha ZarkTV: os apps que você coloca no painel, com instalar/abrir num clique. */
public class LojaActivity extends Activity {
    @Override
    protected void onResume() {
        super.onResume();
        LinearLayout[] saida = new LinearLayout[1];
        JSONArray loja = SincronizarService.estado(this).optJSONArray("loja");
        int n = loja == null ? 0 : loja.length();
        Grade.tela(this, "Lojinha ZarkTV", n == 0 ? "Conectando ao painel… volte em instantes." : "Escolha um app para instalar ou abrir", saida);
        List<View> itens = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final JSONObject a = loja.optJSONObject(i);
            if (a == null) continue;
            final String pacote = a.optString("pacote");
            final boolean tem = pacote.length() > 0 && Box.instalado(this, pacote);
            itens.add(Grade.item(this, a.optString("nome"), tem ? Ui.iconeDe(this, pacote) : null, "loja",
                    tem ? "Instalado · abrir" : "Instalar", new View.OnClickListener() {
                        @Override public void onClick(View v) {
                            if (tem && Ui.abrirApp(LojaActivity.this, pacote)) return;
                            Toast.makeText(LojaActivity.this, "Instalando " + a.optString("nome") + "…", Toast.LENGTH_SHORT).show();
                            SincronizarService.instalarApp(LojaActivity.this, a.optString("url"), a.optString("sha256"), a.optString("nome"));
                        }
                    }));
        }
        Grade.distribuir(this, saida[0], itens, 6);
        if (n == 0) SincronizarService.agora(this);
    }
}
