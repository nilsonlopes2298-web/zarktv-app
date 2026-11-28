package cfd.zarktvqx.launcher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Conversa com o painel: registra a box, manda a ficha, recebe banners/avisos/bloqueio
 * e executa os comandos (instalar, atualizar, desinstalar, esconder...).
 */
public class SincronizarService extends Service {
    public static final String ATUALIZOU = "cfd.zarktvqx.launcher.ATUALIZOU";
    private static final AtomicBoolean rodando = new AtomicBoolean(false);

    // Progresso mostrado na tela de atualização obrigatória
    public static volatile String trabalhoTexto = null;
    public static volatile int trabalhoPorcento = -1;

    public static void agora(Context c) {
        Intent i = new Intent(c, SincronizarService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("zark", "ZarkTV", NotificationManager.IMPORTANCE_MIN));
            Notification n = new Notification.Builder(this, "zark")
                    .setContentTitle("ZarkTV")
                    .setContentText("Conferindo atualizações")
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .build();
            startForeground(7, n);
        }
        if (!rodando.compareAndSet(false, true)) return START_NOT_STICKY;
        new Thread(new Runnable() {
            @Override public void run() {
                PowerManager.WakeLock wl = null;
                try {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zark:sync");
                    wl.acquire(10 * 60 * 1000L);
                    sincronizar(SincronizarService.this);
                } catch (Throwable ignored) {
                } finally {
                    if (wl != null && wl.isHeld()) wl.release();
                    trabalhoTexto = null;
                    trabalhoPorcento = -1;
                    rodando.set(false);
                    avisarTela(SincronizarService.this);
                    stopSelf();
                }
            }
        }, "zark-sync").start();
        return START_NOT_STICKY;
    }

    static void avisarTela(Context c) {
        c.sendBroadcast(new Intent(ATUALIZOU).setPackage(c.getPackageName()));
    }

    public static JSONObject estado(Context c) {
        try {
            return new JSONObject(Box.prefs(c).getString("estado", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    static File pastaApks(Context c) {
        File base = (Build.VERSION.SDK_INT < 24 && c.getExternalCacheDir() != null) ? c.getExternalCacheDir() : c.getCacheDir();
        File d = new File(base, "apks");
        d.mkdirs();
        if (Build.VERSION.SDK_INT < 24) { d.setReadable(true, false); d.setExecutable(true, false); }
        return d;
    }

    // ---------------------------------------------------------------------------------------

    static void sincronizar(Context c) throws Exception {
        Rede.preparar(c);
        SharedPreferences sp = Box.prefs(c);

        // 1) Registra a box na primeira vez
        if (Box.token(c) == null) {
            JSONObject r = Rede.post("/registrar", Box.ficha(c));
            if (!r.has("token")) return;
            sp.edit().putString("token", r.getString("token")).putString("codigo", r.optString("codigo", "—")).apply();
        }

        // 2) Ficha + recebe o que o painel quer
        JSONObject r = Rede.post("/ping", Box.ficha(c));
        if (r.optBoolean("token_invalido")) {
            sp.edit().remove("token").apply();
            return;
        }
        if (r.has("erro") && !r.has("codigo")) return;
        sp.edit().putString("estado", r.toString())
                .putString("codigo", r.optString("codigo", Box.codigo(c)))
                .putString("cliente", r.optString("cliente", null))
                .putLong("ultimo_ok", System.currentTimeMillis())
                .apply();
        avisarTela(c);

        // 2b) Box minha com root: tenta virar "dona" sozinha (controle total sem o PC)
        if (r.optBoolean("controle_total")) Controle.virarDonaComRoot(c);

        // 3) Box minha: tela inicial fixa, nada de instalar por fora, sem atualização do fabricante.
        //    Box do cliente: libera tudo.
        Controle.proteger(c, r.optBoolean("controle_total"), r.optBoolean("bloquear_fora"));

        // 4) Esconde os apps que você marcou como lixo
        JSONArray lixo = r.optJSONArray("lixo");
        if (lixo != null && Controle.silencioso(c)) {
            for (int i = 0; i < lixo.length(); i++) {
                String p = lixo.optString(i);
                if (p.length() > 0 && Box.instalado(c, p)) Controle.esconder(c, p, true);
            }
        }

        // 5) Apps que toda box tem que ter (ZarkPlay, YouTube...)
        JSONArray iniciais = r.optJSONArray("iniciais");
        if (iniciais != null && Controle.silencioso(c)) {
            for (int i = 0; i < iniciais.length(); i++) {
                JSONObject a = iniciais.optJSONObject(i);
                if (a == null) continue;
                String p = a.optString("pacote");
                String url = a.optString("url");
                if (p.length() == 0 || url.length() == 0 || Box.instalado(c, p)) continue;
                long falhou = sp.getLong("falhou_" + p, 0);
                if (System.currentTimeMillis() - falhou < 6 * 3600 * 1000L) continue;
                String erro = baixarEInstalar(c, url, a.optString("sha256"), a.optString("nome", p), false);
                if (erro != null) sp.edit().putLong("falhou_" + p, System.currentTimeMillis()).apply();
            }
        }

        // 6) Comandos mandados pelo painel
        JSONArray cmds = r.optJSONArray("comandos");
        if (cmds != null) {
            for (int i = 0; i < cmds.length(); i++) {
                JSONObject cmd = cmds.optJSONObject(i);
                if (cmd != null) executar(c, cmd);
            }
        }

        // 6b) Box minha com limpeza ligada: só fica o que você quer (nossos apps, YouTube, permitidos)
        if (r.optBoolean("controle_total") && r.optBoolean("limpeza") && Controle.silencioso(c)) {
            java.util.Set<String> manter = new java.util.HashSet<>();
            manter.add(c.getPackageName());
            for (String p : HomeActivity.YOUTUBE) manter.add(p);
            for (String p : HomeActivity.ZARKPLAY) manter.add(p);
            manter.addAll(HomeActivity.fixados(c));
            String[] listas = {"iniciais", "loja"};
            for (String nome : listas) {
                JSONArray l = r.optJSONArray(nome);
                if (l != null) for (int i = 0; i < l.length(); i++) {
                    JSONObject a = l.optJSONObject(i);
                    if (a != null && a.optString("pacote").length() > 0) manter.add(a.optString("pacote"));
                }
            }
            JSONArray perm = r.optJSONArray("permitidos");
            if (perm != null) for (int i = 0; i < perm.length(); i++) manter.add(perm.optString(i));
            Controle.limpar(c, manter);
        }

        // 7) Alivia a memória
        java.util.Set<String> manter = new java.util.HashSet<>();
        Controle.aliviar(c, manter);
    }

    private static void responder(Context c, JSONObject cmd, String erro) {
        try {
            JSONObject j = new JSONObject();
            j.put("token", Box.token(c));
            j.put("id", cmd.optString("id"));
            j.put("ok", erro == null);
            if (erro != null) j.put("erro", erro);
            Rede.post("/comando", j);
        } catch (Exception ignored) {}
    }

    private static void executar(Context c, JSONObject cmd) {
        String tipo = cmd.optString("tipo");
        String pacote = cmd.optString("pacote");
        String erro;
        switch (tipo) {
            case "instalar": {
                boolean obrigatoria = cmd.optBoolean("obrigatoria");
                if (!Controle.silencioso(c) && !obrigatoria) {
                    // modo simples: guarda e a tela inicial pergunta "Atualizar agora / Depois"
                    Box.prefs(c).edit().putString("pendente", cmd.toString()).apply();
                    avisarTela(c);
                    responder(c, cmd, "Aguardando o cliente aceitar (box no modo simples)");
                    return;
                }
                boolean eAMinha = c.getPackageName().equals(pacote);
                if (eAMinha) responder(c, cmd, null); // a launcher fecha ao se atualizar
                erro = baixarEInstalar(c, cmd.optString("url"), cmd.optString("sha256"), cmd.optString("nome", "atualização"), obrigatoria);
                if (eAMinha) return;
                break;
            }
            case "desinstalar":
                erro = Controle.desinstalar(c, pacote);
                break;
            case "esconder":
                erro = Controle.esconder(c, pacote, true);
                break;
            case "mostrar":
                erro = Controle.esconder(c, pacote, false);
                break;
            case "limpar":
                Controle.aliviar(c, new java.util.HashSet<String>());
                erro = null;
                break;
            case "reiniciar":
                responder(c, cmd, Controle.reiniciar(c) ? null : "Box no modo simples: não dá para reiniciar");
                return;
            case "esquecer":
                Box.prefs(c).edit().remove("token").remove("estado").remove("cliente").apply();
                erro = null;
                break;
            default:
                erro = "Comando desconhecido: " + tipo;
        }
        responder(c, cmd, erro);
    }

    /** Baixa, confere e instala. obrigatoria = mostra a tela "Instalando atualização" por cima de tudo. */
    static String baixarEInstalar(Context c, String url, String sha256, String nome, boolean obrigatoria) {
        if (url == null || url.length() == 0) return "Sem endereço do APK";
        if (!url.startsWith("https://")) return "Só aceito APK por https";
        trabalhoTexto = "Instalando " + nome;
        trabalhoPorcento = 0;
        if (obrigatoria) {
            Intent i = new Intent(c, AtualizandoActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { c.startActivity(i); } catch (Exception ignored) {}
        }
        File apk = new File(pastaApks(c), "zark_" + Math.abs(url.hashCode()) + ".apk");
        try {
            String hash = Rede.baixarArquivo(url, apk, new Rede.Progresso() {
                @Override public void agora(int pc) { trabalhoPorcento = pc; }
            });
            if (sha256 != null && sha256.length() == 64 && !sha256.equalsIgnoreCase(hash)) {
                apk.delete();
                return "APK baixado diferente do enviado (conferência falhou)";
            }
            if (Build.VERSION.SDK_INT < 24) { apk.setReadable(true, false); }
            trabalhoPorcento = 100;
            String erro = Controle.instalar(c, apk);
            if (Controle.silencioso(c)) apk.delete();
            return erro;
        } catch (Exception e) {
            apk.delete();
            return "Erro ao baixar: " + e.getMessage();
        } finally {
            trabalhoTexto = null;
            trabalhoPorcento = -1;
        }
    }

    /** Usado pela tela inicial no modo simples ("Atualizar agora"). */
    static void instalarPendente(final Context c) {
        final String txt = Box.prefs(c).getString("pendente", null);
        if (txt == null) return;
        Box.prefs(c).edit().remove("pendente").apply();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Rede.preparar(c);
                    JSONObject cmd = new JSONObject(txt);
                    String erro = baixarEInstalar(c, cmd.optString("url"), cmd.optString("sha256"), cmd.optString("nome", "atualização"), true);
                    responder(c, cmd, erro);
                } catch (Exception ignored) {}
            }
        }).start();
    }

    /** Instalar um app escolhido na lojinha ou no cartão (YouTube/ZarkPlay que ainda não tem). */
    static void instalarApp(final Context c, final String url, final String sha, final String nome) {
        new Thread(new Runnable() {
            @Override public void run() {
                Rede.preparar(c);
                baixarEInstalar(c, url, sha, nome, true);
                avisarTela(c);
            }
        }).start();
    }
}
