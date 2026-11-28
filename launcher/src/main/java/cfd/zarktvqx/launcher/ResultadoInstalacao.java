package cfd.zarktvqx.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import java.util.HashMap;
import java.util.Map;

/** Resultado das instalações/desinstalações silenciosas (modo "dona"). */
public class ResultadoInstalacao extends BroadcastReceiver {
    private static final Map<Integer, String> resultados = new HashMap<>();
    private static final String OK = "\u0000ok";

    static synchronized void esperar(int id) { resultados.remove(id); ultimoEspera = id; }

    /** Espera o Android responder. null = deu certo. */
    static String aguardar(int id, long tempoMs) {
        long fim = System.currentTimeMillis() + tempoMs;
        synchronized (ResultadoInstalacao.class) {
            while (!resultados.containsKey(id)) {
                long falta = fim - System.currentTimeMillis();
                if (falta <= 0) return "O Android não respondeu a tempo";
                try { ResultadoInstalacao.class.wait(falta); } catch (InterruptedException e) { return "Interrompido"; }
            }
            String r = resultados.remove(id);
            return OK.equals(r) ? null : r;
        }
    }

    @Override
    public void onReceive(Context c, Intent i) {
        int id = i.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1);
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // o Android quer confirmação: mostra a tela para o cliente
            Intent conf = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (conf != null) {
                conf.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(conf); } catch (Exception ignored) {}
            }
            return;
        }
        String msg = st == PackageInstaller.STATUS_SUCCESS ? OK
                : ("Falhou (" + st + "): " + i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
        synchronized (ResultadoInstalacao.class) {
            if (id == -1) {
                // desinstalação não manda o id da sessão: responde a quem estiver esperando
                resultados.put(Integer.valueOf(ultimoEspera), msg);
            } else {
                resultados.put(id, msg);
            }
            ResultadoInstalacao.class.notifyAll();
        }
    }

    private static volatile int ultimoEspera = -1;
}
