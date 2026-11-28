package cfd.zarktvqx.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Todos os apps que estão na box (os escondidos pelo painel não aparecem). */
public class AppsActivity extends Activity {
    private LinearLayout coluna;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
    }

    @Override
    protected void onResume() {
        super.onResume();
        LinearLayout[] saida = new LinearLayout[1];
        final PackageManager pm = getPackageManager();
        Map<String, ResolveInfo> apps = new LinkedHashMap<>();
        Intent i = new Intent(Intent.ACTION_MAIN);
        if (Build.VERSION.SDK_INT >= 21) {
            i.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            for (ResolveInfo r : pm.queryIntentActivities(i, 0)) apps.put(r.activityInfo.packageName, r);
        }
        Intent j = new Intent(Intent.ACTION_MAIN);
        j.addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo r : pm.queryIntentActivities(j, 0)) if (!apps.containsKey(r.activityInfo.packageName)) apps.put(r.activityInfo.packageName, r);
        apps.remove(getPackageName());
        final boolean escolher = getIntent().getBooleanExtra("escolher", false);
        if (escolher) for (String p : HomeActivity.fixados(this)) apps.remove(p);
        List<ResolveInfo> lista = new ArrayList<>(apps.values());
        Collections.sort(lista, new Comparator<ResolveInfo>() {
            @Override public int compare(ResolveInfo a, ResolveInfo b) {
                return String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm)));
            }
        });
        if (escolher) Grade.tela(this, "Adicionar à tela inicial", "Escolha o app que vai ficar no início · Voltar para sair", saida);
        else Grade.tela(this, "Todos os apps", lista.size() + " apps instalados · segure OK num app para pôr na tela inicial", saida);
        coluna = saida[0];
        List<View> itens = new ArrayList<>();
        for (final ResolveInfo r : lista) {
            final String pacote = r.activityInfo.packageName;
            LinearLayout it = Grade.item(this, String.valueOf(r.loadLabel(pm)), r.loadIcon(pm), "todos", null, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (escolher) {
                        HomeActivity.fixar(AppsActivity.this, pacote, true);
                        android.widget.Toast.makeText(AppsActivity.this, "Adicionado à tela inicial", android.widget.Toast.LENGTH_SHORT).show();
                        finish();
                    } else {
                        Ui.abrirApp(AppsActivity.this, pacote);
                    }
                }
            });
            if (!escolher) {
                it.getChildAt(0).setOnLongClickListener(new View.OnLongClickListener() {
                    @Override public boolean onLongClick(View v) {
                        HomeActivity.fixar(AppsActivity.this, pacote, true);
                        android.widget.Toast.makeText(AppsActivity.this, "Adicionado à tela inicial", android.widget.Toast.LENGTH_SHORT).show();
                        return true;
                    }
                });
            }
            itens.add(it);
        }
        Grade.distribuir(this, coluna, itens, 6);
    }
}
